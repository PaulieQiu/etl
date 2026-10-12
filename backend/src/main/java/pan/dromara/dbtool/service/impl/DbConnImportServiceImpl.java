package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbConnCrypto;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.service.IDbConnImportService;
import pan.dromara.dbtool.service.IDbConnService;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 连接导入实现: DBeaver connections.xml + 通用 JSON
 *
 * @author doubao
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbConnImportServiceImpl implements IDbConnImportService {

    /** 支持的数据库类型(导入白名单) */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
        "MYSQL", "MARIADB", "POSTGRESQL", "ORACLE", "SQLSERVER", "REDIS", "ELASTICSEARCH", "DM");

    private final IDbConnService connService;

    @Override
    public Map<String, Object> preview(String content, String fileName) {
        if (StrUtil.isBlank(content)) {
            throw new ServiceException("导入文件内容为空");
        }
        String trimmed = content.trim();
        String format = trimmed.startsWith("{") || trimmed.startsWith("[")
            ? "json" : (trimmed.startsWith("<") ? "dbeaver-xml" : "unknown");
        List<Map<String, Object>> candidates = switch (format) {
            case "json" -> parseJson(trimmed);
            case "dbeaver-xml" -> parseDbeaverXml(trimmed);
            default -> throw new ServiceException("无法识别的连接配置文件格式(支持 DBeaver connections.xml 与 JSON)");
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", format);
        result.put("candidates", candidates);
        return result;
    }

    @Override
    public Map<String, Object> apply(List<Map<String, Object>> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new ServiceException("未选择任何连接");
        }
        List<String> imported = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String connName = str(candidate.get("connName"));
            String dbType = str(candidate.get("dbType")).toUpperCase(Locale.ROOT);
            String host = str(candidate.get("host"));
            Integer port = intOrNull(candidate.get("port"));
            String databaseName = str(candidate.get("databaseName"));
            String username = str(candidate.get("username"));
            String password = str(candidate.get("password"));
            String reason = null;
            if (StrUtil.isBlank(connName)) {
                reason = "连接名称为空";
            } else if (!SUPPORTED_TYPES.contains(dbType)) {
                reason = "不支持的数据库类型: " + dbType;
            } else if (StrUtil.isBlank(host)) {
                reason = "主机地址为空";
            } else if (connService.listAll().stream().anyMatch(c -> c.getConnName().equals(connName))) {
                reason = "连接名称已存在: " + connName;
            }
            if (reason != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("connName", connName);
                item.put("reason", reason);
                skipped.add(item);
                continue;
            }
            try {
                DbConn conn = new DbConn();
                conn.setConnName(connName);
                conn.setDbType(dbType);
                conn.setDbVersion("");
                conn.setAccessMode("READ_WRITE");
                conn.setHost(host);
                conn.setPort(port == null ? defaultPort(dbType) : port);
                conn.setDatabaseName(databaseName);
                conn.setUsername(username);
                // 密码默认不导入(安全策略), 导入后由用户在连接编辑页手动填写
                conn.setPassword(StrUtil.isNotBlank(password) ? DbConnCrypto.encrypt(password) : null);
                conn.setStatus("0");
                connService.add(conn);
                imported.add(connName);
            } catch (Exception e) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("connName", connName);
                item.put("reason", "导入失败: " + e.getMessage());
                skipped.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("skipped", skipped);
        return result;
    }

    // ================= 解析 =================

    /** 通用 JSON 格式: [{"connName","dbType","host","port","databaseName","username","password"}] */
    private List<Map<String, Object>> parseJson(String content) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        Object parsed = JSONUtil.parse(content);
        JSONArray array = parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray().set(parsed);
        for (Object item : array) {
            if (!(item instanceof JSONObject json)) {
                continue;
            }
            String connName = json.getStr("connName", json.getStr("name", ""));
            String dbType = normalizeType(json.getStr("dbType", json.getStr("type", "")));
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("connName", connName);
            candidate.put("dbType", dbType);
            candidate.put("host", json.getStr("host", ""));
            candidate.put("port", json.getInt("port"));
            candidate.put("databaseName", json.getStr("databaseName", json.getStr("database", "")));
            candidate.put("username", json.getStr("username", json.getStr("user", "")));
            candidate.put("password", json.getStr("password", ""));
            candidate.put("passwordPresent", StrUtil.isNotBlank(json.getStr("password", "")));
            candidate.put("issues", checkIssues(dbType, candidate));
            candidates.add(candidate);
        }
        return candidates;
    }

    /** DBeaver connections.xml: data-source 元素 + connection 子元素 object 键值对 */
    private List<Map<String, Object>> parseDbeaverXml(String content) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 禁用外部实体, 防 XXE
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            NodeList dataSources = doc.getElementsByTagName("data-source");
            for (int i = 0; i < dataSources.getLength(); i++) {
                Element source = (Element) dataSources.item(i);
                String connName = source.getAttribute("name");
                String provider = source.getAttribute("provider").toLowerCase(Locale.ROOT);
                String driver = source.getAttribute("driver").toLowerCase(Locale.ROOT);
                Map<String, String> values = new LinkedHashMap<>();
                NodeList connections = source.getElementsByTagName("connection");
                Element connection = connections.getLength() > 0 ? (Element) connections.item(0) : null;
                if (connection != null) {
                    NodeList objects = connection.getElementsByTagName("object");
                    for (int j = 0; j < objects.getLength(); j++) {
                        Element object = (Element) objects.item(j);
                        values.put(object.getAttribute("name"), object.getAttribute("value"));
                    }
                }
                String dbType = mapProvider(provider, driver);
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("connName", StrUtil.blankToDefault(connName, ""));
                candidate.put("dbType", dbType);
                candidate.put("host", values.getOrDefault("host", ""));
                candidate.put("port", StrUtil.isBlank(values.get("port")) ? null : parseIntSafe(values.get("port")));
                candidate.put("databaseName", values.getOrDefault("database", ""));
                candidate.put("username", values.getOrDefault("user", ""));
                candidate.put("password", values.getOrDefault("password", ""));
                candidate.put("passwordPresent", StrUtil.isNotBlank(values.get("password")));
                candidate.put("issues", checkIssues(dbType, candidate));
                candidates.add(candidate);
            }
        } catch (Exception e) {
            log.warn("解析 DBeaver connections.xml 失败: {}", e.getMessage());
            throw new ServiceException("解析 DBeaver connections.xml 失败: " + e.getMessage());
        }
        return candidates;
    }

    private List<String> checkIssues(String dbType, Map<String, Object> candidate) {
        List<String> issues = new ArrayList<>();
        if (!SUPPORTED_TYPES.contains(dbType)) {
            issues.add("不支持的数据库类型(未映射到系统白名单)");
        }
        if (StrUtil.isBlank(str(candidate.get("host")))) {
            issues.add("缺少主机地址");
        }
        if (Boolean.TRUE.equals(candidate.get("passwordPresent"))) {
            issues.add("已检测到密码: 出于安全考虑默认不导入, 请导入后手动填写");
        }
        return issues;
    }

    private String mapProvider(String provider, String driver) {
        if (provider.contains("mysql") || provider.contains("mariadb") || driver.contains("mariadb")) {
            return provider.contains("mariadb") || driver.contains("mariadb") ? "MARIADB" : "MYSQL";
        }
        if (provider.contains("postgresql")) {
            return "POSTGRESQL";
        }
        if (provider.contains("oracle")) {
            return "ORACLE";
        }
        if (provider.contains("sqlserver") || provider.contains("sql_server") || provider.contains("mssql")) {
            return "SQLSERVER";
        }
        if (provider.contains("redis") || driver.contains("redis")) {
            return "REDIS";
        }
        if (provider.contains("elastic") || driver.contains("elastic")) {
            return "ELASTICSEARCH";
        }
        if (provider.contains("dm") || driver.contains("dm")) {
            return "DM";
        }
        return provider.toUpperCase(Locale.ROOT);
    }

    private String normalizeType(String raw) {
        String type = StrUtil.nullToEmpty(raw).trim().toUpperCase(Locale.ROOT);
        if (type.contains("MYSQL") || type.contains("MARIADB")) {
            return type.contains("MARIADB") ? "MARIADB" : "MYSQL";
        }
        if (type.contains("POSTGRES")) {
            return "POSTGRESQL";
        }
        if (type.contains("ORACLE")) {
            return "ORACLE";
        }
        if (type.contains("SQLSERVER") || type.contains("MSSQL")) {
            return "SQLSERVER";
        }
        if (type.contains("REDIS")) {
            return "REDIS";
        }
        if (type.contains("ELASTIC")) {
            return "ELASTICSEARCH";
        }
        if (type.equals("DM")) {
            return "DM";
        }
        return type;
    }

    private Integer defaultPort(String dbType) {
        return switch (dbType) {
            case "MYSQL", "MARIADB" -> 3306;
            case "POSTGRESQL" -> 5432;
            case "ORACLE" -> 1521;
            case "SQLSERVER" -> 1433;
            case "REDIS" -> 6379;
            case "ELASTICSEARCH" -> 9200;
            case "DM" -> 5236;
            default -> null;
        };
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntSafe(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
