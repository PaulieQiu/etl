package pan.dromara.dbtool.core;

import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.ssh.SshTunnelService;

import cn.hutool.core.util.StrUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库连接池管理器
 * <p>按连接配置ID缓存 Hikari 数据源, 配置变更/删除时销毁</p>
 * <p>支持通过 DbTypeRegistry 扩展数据库类型, 外部驱动自动加载</p>
 *
 * @author doubao
 */
@Slf4j
@Component
public class DbConnectionManager {

    private final DbDriverManager driverManager;
    private final SshTunnelService sshTunnelService;

    public DbConnectionManager(DbDriverManager driverManager, SshTunnelService sshTunnelService) {
        this.driverManager = driverManager;
        this.sshTunnelService = sshTunnelService;
    }

    /**
     * 连接池缓存 (connId -> DataSource)
     */
    private final Map<Long, HikariDataSource> poolCache = new ConcurrentHashMap<>();

    /**
     * 获取数据源(缓存命中或新建)
     */
    public HikariDataSource getDataSource(DbConn conn) {
        Long id = conn.getId();
        HikariDataSource ds = poolCache.get(id);
        if (ds == null || ds.isClosed()) {
            synchronized (this) {
                ds = poolCache.get(id);
                if (ds == null || ds.isClosed()) {
                    ds = createDataSource(conn);
                    poolCache.put(id, ds);
                    log.info("数据库连接池创建: connId={}, connName={}", id, conn.getConnName());
                }
            }
        }
        return ds;
    }

    /**
     * 创建数据源
     */
    public HikariDataSource createDataSource(DbConn conn) {
        DbTypeInfo info = resolveType(conn);
        // 非内置 JDBC 类型驱动未就绪时提前抛出中文提示(避免 Hikari 报英文 Failed to load driver class)
        if (info != null && StrUtil.isNotBlank(info.getDriverClass())
            && !Boolean.TRUE.equals(info.getBuiltin())
            && !driverManager.isDriverReady(info.getCode())) {
            throw new ServiceException("未找到合适的驱动（类型: " + info.getName() + "），请先在「驱动管理」中下载或上传对应驱动 jar");
        }
        Endpoint endpoint = resolveEndpoint(conn);
        String jdbcUrl = info.buildUrl(endpoint.host, endpoint.port, conn.getDatabaseName(), conn.getParams());
        // MySQL 系 JDBC 驱动默认 connectTimeout/socketTimeout 为 0(无限等待),
        // 目标主机不可达(网络黑洞/防火墙丢弃)时连接建立可能长时间挂起, 导致工作台执行无响应且无法取消。
        // 统一追加连接/读超时, 使建连失败在 10s 内有界返回。
        if ("MYSQL".equalsIgnoreCase(conn.getDbType()) || "MARIADB".equalsIgnoreCase(conn.getDbType())) {
            String sep = jdbcUrl.contains("?") ? "&" : "?";
            jdbcUrl += sep + "connectTimeout=10000&socketTimeout=60000";
        }
        String username = conn.getUsername();
        String password = DbConnCrypto.decrypt(conn.getPassword());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        // 内置类型: 驱动在应用 classpath, 显式指定 driverClassName 由 Hikari Class.forName 加载
        // 非内置类型: 驱动 jar 由 DbDriverManager 通过 URLClassLoader 加载并已注册到 DriverManager,
        //   Hikari 若再 setDriverClassName 会因外部 jar 不在系统 classpath 而报 Failed to load driver class,
        //   因此不显式指定, 让 Hikari 通过 DriverManager.getDriver(jdbcUrl) 解析已注册的外部驱动(与 DBeaver 同机制)
        if (info != null && Boolean.TRUE.equals(info.getBuiltin())) {
            config.setDriverClassName(info.getDriverClass());
        }
        config.setPoolName("dbtool-" + conn.getId());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        // 懒初始化: 首次 getConnection 时才建连, 避免启动/首次查询时同步阻塞在不可达目标上
        config.setInitializationFailTimeout(0);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setValidationTimeout(5000);
        config.setConnectionTestQuery("SELECT 1");
        // Elasticsearch SQL JDBC 驱动仅支持只读连接(isReadOnly() 恒为 true),
        // 若不与 Hikari 默认配置(读 false)一致, Hikari 7 会调用 setReadOnly(false),
        // 驱动将抛出 "Only read-only mode is supported" 导致连接池初始化失败
        if ("ELASTICSEARCH".equalsIgnoreCase(conn.getDbType()) || DbAccessPolicy.isReadOnly(conn)) {
            config.setReadOnly(true);
        }
        return new HikariDataSource(config);
    }

    /**
     * 解析连接使用的数据库类型信息, 并确保驱动可用
     */
    private DbTypeInfo resolveType(DbConn conn) {
        DbTypeInfo info = DbTypeRegistry.of(conn.getDbType());
        if (info == null) {
            info = DbTypeRegistry.of("MYSQL");
        }
        if (!Boolean.TRUE.equals(info.getBuiltin())) {
            driverManager.ensureDriver(info.getCode());
        }
        return info;
    }

    /**
     * 测试连接(用 DriverManager 快速连接, 不进入连接池; Redis/Elasticsearch 走原生协议)
     *
     * @param conn 连接配置
     * @return 成功返回 null, 失败返回错误信息
     */
    public String testConnection(DbConn conn) {
        try {
            // Redis 为 NoSQL, 无 JDBC 驱动, 使用 RESP 协议(AUTH + PING)验证
            if ("REDIS".equalsIgnoreCase(conn.getDbType())) {
                return testRedisConnection(conn);
            }
            // Elasticsearch: 使用 REST API + REST SQL 验证(ES 7.x JDBC 需白金许可, REST 在 Basic 许可可用)
            if ("ELASTICSEARCH".equalsIgnoreCase(conn.getDbType())) {
                return testElasticsearchConnection(conn);
            }
            DbTypeInfo info = resolveType(conn);
            // 非内置 JDBC 类型驱动未就绪时提前返回中文提示(避免进入 JDBC 后报英文 No suitable driver / 未知错误)
            if (info != null && StrUtil.isNotBlank(info.getDriverClass())
                && !Boolean.TRUE.equals(info.getBuiltin())
                && !driverManager.isDriverReady(info.getCode())) {
                return "未找到合适的驱动（类型: " + info.getName() + "），请先在「驱动管理」中下载或上传对应驱动 jar";
            }
            Endpoint endpoint = resolveEndpoint(conn);
            String jdbcUrl = info.buildUrl(endpoint.host, endpoint.port, conn.getDatabaseName(), conn.getParams());
            String password = DbConnCrypto.decrypt(conn.getPassword());
            Properties props = new Properties();
            props.setProperty("user", conn.getUsername());
            props.setProperty("password", password);
            props.setProperty("connectTimeout", "5000");
            props.setProperty("socketTimeout", "5000");
            props.setProperty("loginTimeout", "5");
            try (Connection connection = DriverManager.getConnection(jdbcUrl, props)) {
                return null;
            }
        } catch (Exception e) {
            log.warn("数据库连接测试失败: {}", e.getMessage());
            return friendlyError(conn.getDbType(), e.getMessage());
        }
    }

    /**
     * Elasticsearch 连接测试(REST 方式): 访问根路径 + REST SQL SELECT 1 验证
     * <p>ES 7.x SQL/JDBC 需要白金许可(JDBC 受限), 而 REST API 与 REST SQL 在 Basic 许可下可用,
     * 故 ES 接入与 DBeaver 一致走 REST, 不使用 JDBC 驱动</p>
     */
    private String testElasticsearchConnection(DbConn conn) {
        try {
            esRest(conn, "GET", "/", null);
            esRest(conn, "POST", "/_sql", Map.of("query", "SELECT 1", "fetch_size", 1));
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * Elasticsearch REST 请求(带 Basic 认证), 返回 JSONObject 或 JSONArray
     *
     * @param conn   ES 连接配置
     * @param method HTTP 方法 GET/POST
     * @param path   请求路径(含 query string), 如 /_cat/indices?format=json、/{index}/_mapping
     * @param body   请求体(可空)
     */
    public Object esRest(DbConn conn, String method, String path, Object body) {
        Endpoint endpoint = resolveEndpoint(conn);
        int port = endpoint.port == null ? 9200 : endpoint.port;
        String password = DbConnCrypto.decrypt(conn.getPassword());
        String url = "http://" + endpoint.host + ":" + port + path;
        try {
            HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
            http.setRequestMethod(method);
            http.setConnectTimeout(8000);
            http.setReadTimeout(60000);
            http.setRequestProperty("Content-Type", "application/json");
            http.setRequestProperty("Accept", "application/json");
            if (StrUtil.isNotBlank(conn.getUsername())) {
                String token = java.util.Base64.getEncoder()
                    .encodeToString((conn.getUsername() + ":" + StrUtil.nullToEmpty(password)).getBytes(StandardCharsets.UTF_8));
                http.setRequestProperty("Authorization", "Basic " + token);
            }
            if (body != null) {
                http.setDoOutput(true);
                http.getOutputStream().write(cn.hutool.json.JSONUtil.toJsonStr(body).getBytes(StandardCharsets.UTF_8));
            }
            int code = http.getResponseCode();
            String resp;
            try (java.io.InputStream is = code >= 200 && code < 300 ? http.getInputStream() : http.getErrorStream()) {
                resp = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (code < 200 || code >= 300) {
                throw new ServiceException("Elasticsearch 请求失败(HTTP " + code + "): " + truncate(resp, 300));
            }
            if (resp == null || resp.isBlank()) {
                return null;
            }
            return cn.hutool.json.JSONUtil.parse(resp);
        } catch (ServiceException e) {
            throw e;
        } catch (java.net.ConnectException e) {
            throw new ServiceException("无法连接到 Elasticsearch(主机:端口不可达), 请检查地址、端口及网络");
        } catch (java.net.SocketTimeoutException e) {
            throw new ServiceException("连接 Elasticsearch 超时, 请检查网络与防火墙");
        } catch (java.net.UnknownHostException e) {
            throw new ServiceException("无法解析主机地址: " + conn.getHost());
        } catch (Exception e) {
            throw new ServiceException("Elasticsearch 请求失败: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 常见 JDBC/网络错误信息中文化(保留原始信息便于排查)
     */
    private String friendlyError(String dbType, String raw) {
        if (StrUtil.isBlank(raw)) {
            return "无法连接到数据库，请检查主机地址、端口、驱动及网络";
        }
        String msg = raw;
        if (msg.contains("No suitable driver found")) {
            DbTypeInfo info = DbTypeRegistry.of(dbType);
            String name = info != null ? info.getName() : (StrUtil.isNotBlank(dbType) ? dbType : "未知");
            return "未找到合适的驱动（类型: " + name + "），请先在「驱动管理」中下载或上传对应驱动 jar";
        }
        if (msg.contains("Access denied for user")) {
            return "用户名或密码错误，认证失败（Access denied for user）";
        }
        if (msg.contains("Communications link failure") || msg.contains("Connection refused")
            || msg.contains("connect timed out") || msg.contains("Connect timed out")) {
            return "无法连接到数据库服务器（连接被拒绝或超时），请检查主机地址、端口及网络/防火墙";
        }
        if (msg.contains("UnknownHostException") || msg.contains("Unknown host")) {
            return "无法解析主机地址（UnknownHostException），请检查主机名/IP 是否正确";
        }
        if (msg.contains("Unknown database")) {
            return "数据库不存在（Unknown database），请检查数据库名是否正确";
        }
        if (msg.contains("Could not create connection to database server")) {
            return "无法创建数据库连接，请检查数据库服务是否正常启动";
        }
        return msg;
    }

    /**
     * Redis 连接测试: 建立 TCP 连接后按 RESP 协议发送 AUTH(如有密码) 与 PING, 期望 +PONG
     */
    private String testRedisConnection(DbConn conn) {
        Endpoint endpoint = resolveEndpoint(conn);
        int port = endpoint.port == null ? 6379 : endpoint.port;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host, port), 5000);
            socket.setSoTimeout(5000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            String password = DbConnCrypto.decrypt(conn.getPassword());
            if (StrUtil.isNotBlank(password)) {
                writeRedisCommand(out, "AUTH", password);
                String authReply = readRedisReply(in);
                if (authReply == null) {
                    return "AUTH 命令无响应";
                }
                if (authReply.startsWith("-")) {
                    return "认证失败: " + authReply.substring(1).trim();
                }
            }
            writeRedisCommand(out, "PING");
            String reply = readRedisReply(in);
            if (reply != null && reply.startsWith("+PONG")) {
                return null;
            }
            return reply == null ? "PING 命令无响应" : reply;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private void writeRedisCommand(OutputStream out, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] b = arg.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n");
            sb.append(arg).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String readRedisReply(InputStream in) throws IOException {
        int c = in.read();
        if (c == -1) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        // 简单字符串(+)/错误(-)/整数(:): 读到行尾
        if (c == '+' || c == '-' || c == ':') {
            int ch;
            while ((ch = in.read()) != -1 && ch != '\n') {
                if (ch != '\r') {
                    sb.append((char) ch);
                }
            }
            return ((char) c) + sb.toString();
        }
        // Bulk 字符串($len): 读取指定长度内容
        if (c == '$') {
            while (true) {
                int ch = in.read();
                if (ch == -1 || ch == '\n') {
                    break;
                }
                if (ch != '\r') {
                    sb.append((char) ch);
                }
            }
            int len;
            try {
                len = Integer.parseInt(sb.toString().trim());
            } catch (NumberFormatException e) {
                return "$";
            }
            if (len == -1) {
                return "$-1";
            }
            byte[] data = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(data, off, len - off);
                if (r == -1) {
                    break;
                }
                off += r;
            }
            in.read();
            in.read();
            return new String(data, StandardCharsets.UTF_8);
        }
        return String.valueOf((char) c);
    }

    /**
     * 执行 Redis 命令(数据库工作台/元数据查询用), 返回解析后的 RESP 值列表(数组展开)
     *
     * @param conn 连接配置
     * @param db   Redis 逻辑库编号(0-15, 可空)
     * @param args 命令及参数, 如 SCAN/0/COUNT/500、TYPE/key
     */
    public List<String> redisCommand(DbConn conn, String db, String... args) {
        Endpoint endpoint = resolveEndpoint(conn);
        int port = endpoint.port == null ? 6379 : endpoint.port;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host, port), 5000);
            socket.setSoTimeout(8000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            String password = DbConnCrypto.decrypt(conn.getPassword());
            if (StrUtil.isNotBlank(password)) {
                writeRedisCommand(out, "AUTH", password);
                readRedisReply(in);
            }
            if (StrUtil.isNotBlank(db)) {
                writeRedisCommand(out, "SELECT", db);
                readRedisReply(in);
            }
            writeRedisCommand(out, args);
            return parseRedisReply(in);
        } catch (IOException e) {
            throw new RuntimeException("Redis 命令执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 RESP 协议回复(支持简单字符串/错误/整数/Bulk 字符串/数组, 数组递归展开为值列表)
     */
    private List<String> parseRedisReply(InputStream in) throws IOException {
        int c = in.read();
        if (c == -1) {
            return List.of();
        }
        // 数组: *N 递归展开
        if (c == '*') {
            int len = readIntLine(in);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < len && i < 100000; i++) {
                result.addAll(parseRedisReply(in));
            }
            return result;
        }
        // 简单字符串/错误/整数: 读到行尾
        if (c == '+' || c == '-' || c == ':') {
            return List.of(((char) c) + readLine(in));
        }
        // Bulk 字符串: $len + 内容
        if (c == '$') {
            int len = readIntLine(in);
            if (len == -1) {
                return List.of("(nil)");
            }
            byte[] data = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(data, off, len - off);
                if (r == -1) {
                    break;
                }
                off += r;
            }
            in.read();
            in.read();
            return List.of(new String(data, StandardCharsets.UTF_8));
        }
        return List.of(String.valueOf((char) c));
    }

    private int readIntLine(InputStream in) throws IOException {
        String line = readLine(in);
        try {
            return Integer.parseInt(line.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1 && ch != '\n') {
            if (ch != '\r') {
                sb.append((char) ch);
            }
        }
        return sb.toString();
    }

    /**
     * 关闭并移除连接池
     */
    public void close(Long connId) {
        HikariDataSource ds = poolCache.remove(connId);
        if (ds != null && !ds.isClosed()) {
            ds.close();
            log.info("数据库连接池关闭: connId={}", connId);
        }
        // 级联关闭 SSH 隧道(连接编辑/删除/池关闭路径)
        sshTunnelService.closeTunnel(connId);
    }

    /**
     * 读取 Redis 原始字节值(GET, 保留任意二进制, 用于二进制内容查看/编辑)
     */
    public byte[] redisBytes(DbConn conn, String db, String key) {
        try (Socket socket = openRedisSocket(conn, db)) {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            writeRedisCommand(out, "GET", key);
            out.flush();
            return readBulkBytes(in);
        } catch (IOException e) {
            throw new RuntimeException("Redis 读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入 Redis 原始字节值(SET, 保留任意二进制)
     */
    public void redisSetBytes(DbConn conn, String db, String key, byte[] value) {
        try (Socket socket = openRedisSocket(conn, db)) {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            byte[] kb = key.getBytes(StandardCharsets.UTF_8);
            StringBuilder header = new StringBuilder();
            header.append('*').append(3).append("\r\n");
            header.append('$').append(3).append("\r\nSET\r\n");
            header.append('$').append(kb.length).append("\r\n");
            out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(kb);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(('$' + String.valueOf(value.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(value);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            readRedisReply(in);
        } catch (IOException e) {
            throw new RuntimeException("Redis 写入失败: " + e.getMessage(), e);
        }
    }

    private Socket openRedisSocket(DbConn conn, String db) throws IOException {
        Endpoint endpoint = resolveEndpoint(conn);
        int port = endpoint.port == null ? 6379 : endpoint.port;
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(endpoint.host, port), 5000);
        socket.setSoTimeout(8000);
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String password = DbConnCrypto.decrypt(conn.getPassword());
        if (StrUtil.isNotBlank(password)) {
            writeRedisCommand(out, "AUTH", password);
            out.flush();
            readRedisReply(in);
        }
        if (StrUtil.isNotBlank(db)) {
            writeRedisCommand(out, "SELECT", db);
            out.flush();
            readRedisReply(in);
        }
        return socket;
    }

    private byte[] readBulkBytes(InputStream in) throws IOException {
        int c = in.read();
        if (c == -1) {
            return new byte[0];
        }
        if (c == '$') {
            int len = readIntLine(in);
            if (len == -1) {
                return null;
            }
            byte[] data = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(data, off, len - off);
                if (r == -1) {
                    break;
                }
                off += r;
            }
            in.read();
            in.read();
            return data;
        }
        if (c == '-') {
            throw new RuntimeException("Redis 读取失败: " + readLine(in));
        }
        return new byte[0];
    }

    /**
     * 获取指定连接
     */
    public Connection getConnection(DbConn conn) {
        try {
            return getDataSource(conn).getConnection();
        } catch (Exception e) {
            throw new RuntimeException("获取数据库连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析连接实际端点: 启用 SSH 隧道时返回本地转发端点(127.0.0.1:localPort),
     * 否则返回配置的 host/port(可能为 null, 由调用方默认值兜底)
     */
    private Endpoint resolveEndpoint(DbConn conn) {
        if (conn != null && "1".equals(conn.getSshUseTunnel())) {
            Integer localPort = sshTunnelService.getLocalPort(conn.getId());
            if (localPort == null) {
                localPort = sshTunnelService.createTunnel(conn.getId());
            }
            if (localPort == null) {
                throw new ServiceException("SSH 隧道未就绪, 无法建立数据库连接");
            }
            return new Endpoint("127.0.0.1", localPort);
        }
        return new Endpoint(conn == null ? null : conn.getHost(), conn == null ? null : conn.getPort());
    }

    /** 端点(host/port) */
    private static final class Endpoint {
        private final String host;
        private final Integer port;

        private Endpoint(String host, Integer port) {
            this.host = host;
            this.port = port;
        }
    }
}
