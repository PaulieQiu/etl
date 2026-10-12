package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbConnCrypto;
import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.core.DbGridChangePlanner;
import pan.dromara.dbtool.core.DbOperationConfirmationService;
import pan.dromara.dbtool.core.DbQueryExecutionRegistry;
import pan.dromara.dbtool.core.DbStructureChangeConfirmation;
import pan.dromara.dbtool.core.dialect.ClickHouseDialectAdapter;
import pan.dromara.dbtool.core.dialect.DbDialectAdapter;
import pan.dromara.dbtool.core.dialect.Db2DialectAdapter;
import pan.dromara.dbtool.core.dialect.HsqlDialectAdapter;
import pan.dromara.dbtool.core.dialect.NullDialectAdapter;
import pan.dromara.dbtool.core.dialect.DmDialectAdapter;
import pan.dromara.dbtool.core.dialect.DuckDbDialectAdapter;
import pan.dromara.dbtool.core.dialect.ExasolDialectAdapter;
import pan.dromara.dbtool.core.dialect.FirebirdDialectAdapter;
import pan.dromara.dbtool.core.dialect.H2DialectAdapter;
import pan.dromara.dbtool.core.dialect.MySqlDialectAdapter;
import pan.dromara.dbtool.core.dialect.OracleDialectAdapter;
import pan.dromara.dbtool.core.dialect.PostgreSqlDialectAdapter;
import pan.dromara.dbtool.core.dialect.SqlServerDialectAdapter;
import pan.dromara.dbtool.core.dialect.SqliteDialectAdapter;
import pan.dromara.dbtool.core.dialect.VerticaDialectAdapter;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.bo.DbGridChangeBo;
import pan.dromara.dbtool.domain.bo.DbStructureChangeBo;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbConstraintVo;
import pan.dromara.dbtool.domain.vo.DbForeignKeyVo;
import pan.dromara.dbtool.domain.vo.DbGridChangePreviewVo;
import pan.dromara.dbtool.domain.vo.DbIndexVo;
import pan.dromara.dbtool.domain.vo.DbReferenceVo;
import pan.dromara.dbtool.domain.vo.DbResultVo;
import pan.dromara.dbtool.domain.vo.DbTableVo;
import pan.dromara.dbtool.domain.vo.DbTriggerVo;
import pan.dromara.dbtool.mapper.DbConnMapper;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据库元数据 服务实现
 * <p>MySQL 完整支持; 其他类型元数据走 DatabaseMetaData 基础兼容</p>
 *
 * @author doubao
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DbMetaServiceImpl implements IDbMetaService {

    private final DbConnMapper dbConnMapper;
    private final DbConnectionManager connectionManager;
    private final DbOperationConfirmationService confirmationService;
    private final DbQueryExecutionRegistry queryExecutionRegistry;

    private static final int QUERY_TIMEOUT_SECONDS = 120;

    /**
     * 系统库过滤
     */
    private static final Set<String> SYS_SCHEMAS = Set.of(
        "information_schema", "performance_schema", "mysql", "sys"
    );

    /**
     * 查询语句判定
     */
    private static final Pattern SELECT_PATTERN = Pattern.compile("^\\s*(select|show|describe|desc|explain|with)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 高危SQL判定: DROP / TRUNCATE / 无条件DELETE / 无条件UPDATE
     */
    private static final Pattern DANGEROUS_DROP_PATTERN = Pattern.compile("^\\s*drop\\s+(database|schema|table|index|view|trigger|procedure|function|event)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_TRUNCATE_PATTERN = Pattern.compile("^\\s*truncate\\s+(table\\s+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_ALTER_PATTERN = Pattern.compile("^\\s*alter\\s+(table|database|schema|view)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_PATTERN = Pattern.compile("^\\s*delete\\s+from\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_PATTERN = Pattern.compile("^\\s*update\\s+\\S+\\s+set\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE_PATTERN = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_TABLE_SELECT = Pattern.compile(
        "(?is)^\\s*select\\s+\\*\\s+from\\s+((?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|\\[[^\\]]+\\]|[\\w$-]+)(?:\\s*\\.\\s*(?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|\\[[^\\]]+\\]|[\\w$-]+))?)(.*)$"
    );

    /**
     * 最大查询返回行数保护
     */
    private static final int MAX_ROWS = 1000;

    /**
     * Redis 逻辑库数量(0-15)
     */
    private static final int REDIS_DB_COUNT = 16;

    /**
     * 方言适配器注册表(优先匹配, 默认 MySQL)
     */
    private static final List<DbDialectAdapter> DIALECTS = List.of(
        new DmDialectAdapter(),
        new SqlServerDialectAdapter(),
        new OracleDialectAdapter(),
        new ClickHouseDialectAdapter(),
        new SqliteDialectAdapter(),
        new H2DialectAdapter(),
        new DuckDbDialectAdapter(),
        new VerticaDialectAdapter(),
        new ExasolDialectAdapter(),
        new FirebirdDialectAdapter(),
        new Db2DialectAdapter(),
        new HsqlDialectAdapter(),
        new PostgreSqlDialectAdapter(),
        new MySqlDialectAdapter()
    );

    /** 未适配方言的统一降级实例 */
    private static final DbDialectAdapter NULL_DIALECT = new NullDialectAdapter();

    /** 支持切换当前库(catalog)的类型: MySQL 协议族 + SQLServer; 其余方言暂不切库(与既有行为一致) */
    private static final Set<String> CATALOG_SWITCH_TYPES = Set.of(
        "MYSQL", "MARIADB", "TIDB", "DORIS", "STARROCKS", "OCEANBASE", "GBASE", "SQLSERVER");

    /**
     * 界面选择了目标库(dbName)时切换 JDBC catalog;
     * 空值或非支持方言保持连接默认库, 与既有行为一致
     */
    private void applyDbName(Connection connection, DbConn conn, String dbName) {
        if (conn.getDbType() == null
                || !CATALOG_SWITCH_TYPES.contains(conn.getDbType().trim().toUpperCase(Locale.ROOT))) {
            return;
        }
        // 未选择库时回退连接配置的默认库, 防止连接池复用上一次切换造成串库。
        // 注意: MySQL Connector/J 的 setCatalog 只更新客户端缓存, 不会真正改变服务器默认库,
        // 因此统一通过执行 USE 语句切换。
        String target = StrUtil.isNotBlank(dbName) ? dbName : conn.getDatabaseName();
        if (StrUtil.isNotBlank(target)) {
            try (Statement st = connection.createStatement()) {
                st.execute("USE `" + target.replace("`", "``") + "`");
            } catch (SQLException e) {
                throw new ServiceException("切换数据库失败: " + translateSqlError(e.getMessage()));
            }
        }
    }

    /**
     * 按连接数据库类型选择方言适配器(忽略大小写, 未命中回退 MySQL 保持历史行为)
     */
    private DbDialectAdapter dialect(DbConn conn) {
        String t = conn == null ? null : conn.getDbType();
        for (DbDialectAdapter a : DIALECTS) {
            if (a.supports(t)) {
                return a;
            }
        }
        // 未适配方言的类型不再回退 MySQL(避免错误方言 SQL), 由上层对 null 做安全降级/明确报错
        return NULL_DIALECT;
    }

    /**
     * 判断是否为 Redis 连接
     */
    private boolean isRedis(DbConn conn) {
        return "REDIS".equalsIgnoreCase(conn.getDbType());
    }

    /**
     * 判断是否为 Elasticsearch 连接(ES 走 REST API, 见 DbConnectionManager.esRest)
     */
    private boolean isEs(DbConn conn) {
        return "ELASTICSEARCH".equalsIgnoreCase(conn.getDbType());
    }

    private String redisError() {
        return "Redis 为 NoSQL 数据库, 不支持该操作(可查看库/键及类型/TTL)";
    }

    private String esError() {
        return "Elasticsearch 为全文搜索引擎, 不支持该操作(可查看索引、字段及数据浏览)";
    }

    /**
     * URL 编码(路径段), 索引名可能包含特殊字符
     */
    private static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 递归收集 ES 字段(object/nested 类型展开为 父.子)
     */
    private void collectEsFields(cn.hutool.json.JSONObject properties, String prefix, List<DbColumnVo> list) {
        for (String field : properties.keySet()) {
            cn.hutool.json.JSONObject prop = properties.getJSONObject(field);
            if (prop == null) {
                continue;
            }
            String name = prefix.isEmpty() ? field : prefix + "." + field;
            String type = prop.getStr("type", "object");
            DbColumnVo vo = new DbColumnVo();
            vo.setColumnName(name);
            vo.setDataType(type);
            vo.setColumnType(type);
            cn.hutool.json.JSONObject subFields = prop.getJSONObject("fields");
            vo.setColumnComment(subFields != null && !subFields.isEmpty() ? "多字段:" + subFields.keySet() : "");
            list.add(vo);
            if ("object".equals(type) || "nested".equals(type)) {
                cn.hutool.json.JSONObject sub = prop.getJSONObject("properties");
                if (sub != null) {
                    collectEsFields(sub, name, list);
                }
            }
        }
    }

    @Override
    public DbConn getConn(Long connId) {
        DbConn conn = dbConnMapper.selectById(connId);
        if (conn == null) {
            throw new ServiceException("数据库连接配置不存在: " + connId);
        }
        return conn;
    }

    @Override
    public List<String> databases(Long connId) {
        DbConn conn = getConn(connId);
        // Redis: 返回 0-15 逻辑库
        if (isRedis(conn)) {
            List<String> dbs = new ArrayList<>();
            for (int i = 0; i < REDIS_DB_COUNT; i++) {
                dbs.add(String.valueOf(i));
            }
            return dbs;
        }
        // Elasticsearch: 无 schema 概念, 返回虚拟库(表=索引)
        if ("ELASTICSEARCH".equalsIgnoreCase(conn.getDbType())) {
            return List.of("default");
        }
        String dbSql = dialect(conn).databasesSql();
        if (dbSql == null) {
            throw new ServiceException("当前数据库类型(" + conn.getDbType() + ")未适配元数据浏览, 仅支持连接与基础 SQL 查询");
        }
        List<String> list = new ArrayList<>();
        try (Connection connection = connectionManager.getConnection(conn);
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(dbSql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (!SYS_SCHEMAS.contains(name)) {
                    list.add(name);
                }
            }
        } catch (Exception e) {
            throw new ServiceException("查询数据库列表失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<DbTableVo> tables(Long connId, String schema) {
        DbConn conn = getConn(connId);
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        List<DbTableVo> list = new ArrayList<>();
        // Redis: SCAN 增量迭代获取 key(上限 5000)
        if (isRedis(conn)) {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            String cursor = "0";
            int guard = 0;
            do {
                List<String> repl = connectionManager.redisCommand(conn, db, "SCAN", cursor, "COUNT", "500");
                if (repl.isEmpty()) {
                    break;
                }
                cursor = repl.get(0);
                for (int i = 1; i < repl.size() && keys.size() < 5000; i++) {
                    keys.add(repl.get(i));
                }
                guard++;
            } while (!"0".equals(cursor) && keys.size() < 5000 && guard < 50);
            for (String k : keys) {
                DbTableVo vo = new DbTableVo();
                vo.setTableName(k);
                vo.setTableType("KEY");
                vo.setTableComment("Redis Key");
                list.add(vo);
            }
            return list;
        }
        String sql = dialect(conn).tablesSql();
        if (sql == null) {
            throw new ServiceException("当前数据库类型(" + conn.getDbType() + ")未适配表元数据查询, 仅支持连接与基础 SQL 查询");
        }
        // Elasticsearch: 索引列表(表=索引), 通过 REST _cat/indices 获取(与 DBeaver 一致)
        if (isEs(conn)) {
            Object obj = connectionManager.esRest(conn, "GET", "/_cat/indices?format=json", null);
            if (obj instanceof cn.hutool.json.JSONArray arr) {
                for (Object o : arr) {
                    cn.hutool.json.JSONObject row = (cn.hutool.json.JSONObject) o;
                    DbTableVo vo = new DbTableVo();
                    vo.setTableName(row.getStr("index"));
                    vo.setTableComment("文档数=" + row.getStr("docs.count", "0") + ", 状态=" + row.getStr("status", ""));
                    vo.setTableType("INDEX");
                    list.add(vo);
                }
            }
            return list;
        }
        try (Connection connection = connectionManager.getConnection(conn);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DbTableVo vo = new DbTableVo();
                    vo.setTableName(rs.getString("TABLE_NAME"));
                    vo.setTableComment(rs.getString("TABLE_COMMENT"));
                    vo.setTableType(rs.getString("TABLE_TYPE"));
                    vo.setEngine(rs.getString("ENGINE"));
                    vo.setTableRows(rs.getLong("TABLE_ROWS"));
                    vo.setCreateTime(rs.getString("CREATE_TIME"));
                    list.add(vo);
                }
            }
        } catch (Exception e) {
            throw new ServiceException("查询表列表失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<DbColumnVo> columns(Long connId, String schema, String table) {
        DbConn conn = getConn(connId);
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        List<DbColumnVo> list = new ArrayList<>();
        // Redis: 返回 key 的类型与 TTL 两个虚拟列
        if (isRedis(conn)) {
            List<String> typeRepl = connectionManager.redisCommand(conn, db, "TYPE", table);
            String typeVal = typeRepl.isEmpty() ? "-" : typeRepl.get(0).replace("+", "");
            List<String> ttlRepl = connectionManager.redisCommand(conn, db, "TTL", table);
            String ttlVal = ttlRepl.isEmpty() ? "-" : ttlRepl.get(0).replace(":", "");
            DbColumnVo c1 = new DbColumnVo();
            c1.setColumnName("type");
            c1.setDataType(typeVal);
            c1.setColumnComment("Redis 数据类型(string/hash/list/set/zset)");
            list.add(c1);
            DbColumnVo c2 = new DbColumnVo();
            c2.setColumnName("ttl");
            c2.setDataType(ttlVal);
            c2.setColumnComment("TTL 秒(-1 永久, -2 不存在)");
            list.add(c2);
            return list;
        }
        // Elasticsearch: 索引字段通过 REST _mapping 获取
        if (isEs(conn)) {
            Object obj = connectionManager.esRest(conn, "GET", "/" + urlEncode(table) + "/_mapping", null);
            if (obj instanceof cn.hutool.json.JSONObject root) {
                for (String idx : root.keySet()) {
                    cn.hutool.json.JSONObject mappings = root.getJSONObject(idx).getJSONObject("mappings");
                    if (mappings == null) {
                        continue;
                    }
                    cn.hutool.json.JSONObject properties = mappings.getJSONObject("properties");
                    if (properties == null) {
                        continue;
                    }
                    collectEsFields(properties, "", list);
                }
            }
            return list;
        }
        String sql = dialect(conn).columnsSql();
        if (sql == null) {
            throw new ServiceException("当前数据库类型(" + conn.getDbType() + ")未适配列元数据查询, 仅支持连接与基础 SQL 查询");
        }
        try (Connection connection = connectionManager.getConnection(conn);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DbColumnVo vo = new DbColumnVo();
                    vo.setColumnName(rs.getString("COLUMN_NAME"));
                    vo.setDataType(rs.getString("DATA_TYPE"));
                    vo.setColumnType(rs.getString("COLUMN_TYPE"));
                    vo.setNullable(rs.getString("IS_NULLABLE"));
                    vo.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                    vo.setColumnComment(rs.getString("COLUMN_COMMENT"));
                    vo.setColumnKey(rs.getString("COLUMN_KEY"));
                    vo.setPrimary("PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY")));
                    vo.setExtra(rs.getString("EXTRA"));
                    vo.setCharacterSet(rs.getString("CHARACTER_SET_NAME"));
                    vo.setCollation(rs.getString("COLLATION_NAME"));
                    list.add(vo);
                }
            }
        } catch (Exception e) {
            throw new ServiceException("查询列结构失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<DbIndexVo> indexes(Long connId, String schema, String table) {
        DbConn conn = getConn(connId);
        // Redis 无索引概念

        if (isRedis(conn)) {

            return new ArrayList<>();

        }

        // Elasticsearch 无此概念

        if (isEs(conn)) {

            return new ArrayList<>();

        }
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        List<DbIndexVo> list = new ArrayList<>();
        String sql = dialect(conn).indexesSql();
        if (sql == null) {
            throw new ServiceException("当前数据库类型(" + conn.getDbType() + ")未适配索引元数据查询, 仅支持连接与基础 SQL 查询");
        }
        try (Connection connection = connectionManager.getConnection(conn);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DbIndexVo vo = new DbIndexVo();
                    vo.setIndexName(rs.getString("INDEX_NAME"));
                    vo.setColumnName(rs.getString("COLUMN_NAME"));
                    vo.setSeqInIndex(rs.getInt("SEQ_IN_INDEX"));
                    vo.setNonUnique(rs.getInt("NON_UNIQUE"));
                    vo.setIndexType(rs.getString("INDEX_TYPE"));
                    vo.setIndexComment(rs.getString("INDEX_COMMENT"));
                    list.add(vo);
                }
            }
        } catch (Exception e) {
            throw new ServiceException("查询索引失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<DbConstraintVo> constraints(Long connId, String schema, String table) {
        DbConn conn = getConn(connId);
        if (isRedis(conn)) {
            return new ArrayList<>();
        }
        // Elasticsearch 无此概念
        if (isEs(conn)) {
            return new ArrayList<>();
        }
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        List<DbConstraintVo> list = new ArrayList<>();
        String sql = dialect(conn).constraintsSql();
        if (sql == null) {
            throw new ServiceException("当前数据库类型(" + conn.getDbType() + ")未适配约束元数据查询, 仅支持连接与基础 SQL 查询");
        }
        try (Connection connection = connectionManager.getConnection(conn);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DbConstraintVo vo = new DbConstraintVo();
                    vo.setConstraintName(rs.getString("CONSTRAINT_NAME"));
                    vo.setConstraintType(rs.getString("CONSTRAINT_TYPE"));
                    vo.setColumnName(rs.getString("COLUMN_NAME"));
                    vo.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                    list.add(vo);
                }
            }
        } catch (Exception e) {
            throw new ServiceException("查询约束失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<DbForeignKeyVo> foreignKeys(Long connId, String schema, String table) {
        DbConn conn = getConn(connId);
        if (isRedis(conn)) {

            return new ArrayList<>();

        }

        // Elasticsearch 无此概念

        if (isEs(conn)) {

            return new ArrayList<>();

        }
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        List<DbForeignKeyVo> list = new ArrayList<>();
        String sql = dialect(conn).foreignKeysSql();
        if (sql == null) {
            throw new ServiceException("当前数据库类型(" + conn.getDbType() + ")未适配外键元数据查询, 仅支持连接与基础 SQL 查询");
        }
        try (Connection connection = connectionManager.getConnection(conn);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DbForeignKeyVo vo = new DbForeignKeyVo();
                    vo.setFkName(rs.getString("CONSTRAINT_NAME"));
                    vo.setTableName(rs.getString("TABLE_NAME"));
                    vo.setColumnName(rs.getString("COLUMN_NAME"));
                    vo.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                    vo.setReferencedTableName(rs.getString("REFERENCED_TABLE_NAME"));
                    vo.setReferencedColumnName(rs.getString("REFERENCED_COLUMN_NAME"));
                    vo.setUpdateRule(rs.getString("UPDATE_RULE"));
                    vo.setDeleteRule(rs.getString("DELETE_RULE"));
                    list.add(vo);
                }
            }
        } catch (Exception e) {
            throw new ServiceException("查询外键失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<DbReferenceVo> references(Long connId, String schema, String table) {
        DbConn conn = getConn(connId);
        if (isRedis(conn)) {

            return new ArrayList<>();

        }

        // Elasticsearch 无此概念

        if (isEs(conn)) {

            return new ArrayList<>();

        }
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        List<DbReferenceVo> list = new ArrayList<>();
        String sql = dialect(conn).referencesSql();
        if (sql == null) {
            throw new ServiceException("当前数据库类型(" + conn.getDbType() + ")未适配引用元数据查询, 仅支持连接与基础 SQL 查询");
        }
        try (Connection connection = connectionManager.getConnection(conn);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DbReferenceVo vo = new DbReferenceVo();
                    vo.setFkName(rs.getString("CONSTRAINT_NAME"));
                    vo.setTableName(rs.getString("TABLE_NAME"));
                    vo.setColumnName(rs.getString("COLUMN_NAME"));
                    vo.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                    vo.setReferencedTableName(rs.getString("REFERENCED_TABLE_NAME"));
                    vo.setReferencedColumnName(rs.getString("REFERENCED_COLUMN_NAME"));
                    vo.setUpdateRule(rs.getString("UPDATE_RULE"));
                    vo.setDeleteRule(rs.getString("DELETE_RULE"));
                    list.add(vo);
                }
            }
        } catch (Exception e) {
            throw new ServiceException("查询引用失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<DbTriggerVo> triggers(Long connId, String schema, String table) {
        DbConn conn = getConn(connId);
        if (isRedis(conn)) {

            return new ArrayList<>();

        }

        // Elasticsearch 无此概念

        if (isEs(conn)) {

            return new ArrayList<>();

        }
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        List<DbTriggerVo> list = new ArrayList<>();
        String sql = dialect(conn).triggersSql();
        if (sql == null) {
            throw new ServiceException("当前数据库类型(" + conn.getDbType() + ")未适配触发器元数据查询, 仅支持连接与基础 SQL 查询");
        }
        try (Connection connection = connectionManager.getConnection(conn);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DbTriggerVo vo = new DbTriggerVo();
                    vo.setTriggerName(rs.getString("TRIGGER_NAME"));
                    vo.setTiming(rs.getString("ACTION_TIMING"));
                    vo.setEvent(rs.getString("EVENT_MANIPULATION"));
                    vo.setTableName(rs.getString("EVENT_OBJECT_TABLE"));
                    vo.setStatement(rs.getString("ACTION_STATEMENT"));
                    vo.setCreated(rs.getString("CREATED"));
                    vo.setStatus("ENABLED");
                    list.add(vo);
                }
            }
        } catch (Exception e) {
            throw new ServiceException("查询触发器失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public String ddl(Long connId, String schema, String table) {
        DbConn conn = getConn(connId);
        // Redis 无 DDL
        if (isRedis(conn)) {
            return "-- Redis 为 NoSQL 数据库, 无 DDL 定义. 键类型与 TTL 请查看「列结构」";
        }
        // Elasticsearch 无建表 DDL(SQL 工作台支持 SELECT 查询索引)
        if ("ELASTICSEARCH".equalsIgnoreCase(conn.getDbType())) {
            return "-- Elasticsearch 索引无建表 DDL.\n-- 索引字段结构请查看「列结构」, 数据查询请使用: SELECT * FROM \"" + table + "\" LIMIT 100";
        }
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        DbDialectAdapter ad = dialect(conn);
        String sql = ad.showCreateTableSql(db, table);
        if (sql == null) {
            // 无原生 SHOW CREATE TABLE(如 PostgreSQL): 按列元数据重建建表语句
            List<DbColumnVo> cols = columns(connId, schema, table);
            return buildCreateTable(conn.getDbType() == null ? "mysql" : conn.getDbType().toLowerCase(), db, table, cols);
        }
        try (Connection connection = connectionManager.getConnection(conn);
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(2);
            }
        } catch (Exception e) {
            log.error("查询建表DDL失败, table={}", table, e);
            throw new ServiceException("查询建表DDL失败: " + e.getMessage());
        }
        return "";
    }

    @Override
    public void createDatabase(Long connId, String dbName, String charset) {
        DbConn conn = getConn(connId);
        if (isRedis(conn)) {

            throw new ServiceException(redisError());

        }

        // Elasticsearch 不支持写操作

        if (isEs(conn)) {

            throw new ServiceException(esError());

        }
        String sql = dialect(conn).createDatabaseSql(dbName, charset);
        if (sql == null) {
            throw new ServiceException("当前数据库类型不支持通过工作台创建数据库(如 Oracle 无实例级 CREATE DATABASE)");
        }
        executeByConn(conn, sql);
    }

    @Override
    public void dropTable(Long connId, String schema, String table, String confirmationToken, String userId) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        if (isRedis(conn)) {

            throw new ServiceException(redisError());

        }

        // Elasticsearch 不支持写操作

        if (isEs(conn)) {

            throw new ServiceException(esError());

        }
        DbStructureChangeBo confirmation = new DbStructureChangeBo();
        confirmation.setConnId(connId);
        confirmation.setOperation("DROP_TABLE");
        confirmation.setSchema(schema);
        confirmation.setTable(table);
        requireStructureChangeConfirmation(confirmationToken, confirmation, connId, userId);
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        executeByConn(conn, dialect(conn).dropTableSql(db, table));
    }

    @Override
    public void renameTable(Long connId, String schema, String oldName, String newName) {
        DbConn conn = getConn(connId);
        if (isRedis(conn)) {

            throw new ServiceException(redisError());

        }

        // Elasticsearch 不支持写操作

        if (isEs(conn)) {

            throw new ServiceException(esError());

        }
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        executeByConn(conn, dialect(conn).renameTableSql(db, oldName, newName));
    }

    @Override
    public void addColumn(Long connId, String schema, String table, DbColumnVo column) {
        DbConn conn = getConn(connId);
        if (isRedis(conn)) {

            throw new ServiceException(redisError());

        }

        // Elasticsearch 不支持写操作

        if (isEs(conn)) {

            throw new ServiceException(esError());

        }
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        DbDialectAdapter ad = dialect(conn);
        String sql = ad.addColumnSql(db, table, ad.columnDdl(column));
        executeByConn(conn, sql);
    }

    @Override
    public void dropColumn(Long connId, String schema, String table, String columnName, String confirmationToken, String userId) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        if (isRedis(conn)) {

            throw new ServiceException(redisError());

        }

        // Elasticsearch 不支持写操作

        if (isEs(conn)) {

            throw new ServiceException(esError());

        }
        DbStructureChangeBo confirmation = new DbStructureChangeBo();
        confirmation.setConnId(connId);
        confirmation.setOperation("DROP_COLUMN");
        confirmation.setSchema(schema);
        confirmation.setTable(table);
        confirmation.setColumnName(columnName);
        requireStructureChangeConfirmation(confirmationToken, confirmation, connId, userId);
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        executeByConn(conn, dialect(conn).dropColumnSql(db, table, columnName));
    }

    @Override
    public void modifyColumn(Long connId, String schema, String table, String oldColumnName, DbColumnVo column) {
        DbConn conn = getConn(connId);
        if (isRedis(conn)) {

            throw new ServiceException(redisError());

        }

        // Elasticsearch 不支持写操作

        if (isEs(conn)) {

            throw new ServiceException(esError());

        }
        String db = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        String sql = dialect(conn).modifyColumnSql(db, table, oldColumnName, column);
        if (sql == null) {
            throw new ServiceException("当前数据库类型不支持该列修改操作(如 PostgreSQL 重命名列), 请使用 SQL 工作台手动执行");
        }
        executeByConn(conn, sql);
    }

    @Override
    public void executeDdl(Long connId, String sql) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        if (isRedis(conn)) {

            throw new ServiceException(redisError());

        }

        // Elasticsearch 不支持写操作

        if (isEs(conn)) {

            throw new ServiceException(esError());

        }
        executeByConn(conn, sql);
    }

    @Override
    public DbResultVo query(Long connId, String sql, Integer pageNum, Integer pageSize) {
        return query(connId, sql, pageNum, pageSize, null, null, null);
    }

    private DbResultVo query(Long connId, String sql, Integer pageNum, Integer pageSize, String userId, String executionId, String dbName) {
        DbConn conn = getConn(connId);
        if (isRedis(conn)) {
            throw new ServiceException("Redis 不支持 SQL 查询, 请在左侧浏览键及类型/TTL");
        }
        String trimSql = DbAccessPolicy.normalizeLeadingComments(sql);
        if (DbAccessPolicy.isReadOnly(conn)) {
            DbAccessPolicy.requireReadOnlyStatement(trimSql);
        }
        if (isEs(conn)) {
            return esQuery(conn, trimSql, pageNum, pageSize);
        }
        if (trimSql.endsWith(";")) {
            trimSql = trimSql.substring(0, trimSql.length() - 1).trim();
        }
        if (trimSql.contains(";")) {
            throw new ServiceException("仅支持单条 SQL");
        }
        if (!SELECT_PATTERN.matcher(trimSql).find()) {
            throw new ServiceException("仅支持查询语句(select/show/describe)");
        }
        long start = System.currentTimeMillis();
        DbResultVo result = new DbResultVo();
        result.setSqlType("select");
        List<Map<String, Object>> rows = new ArrayList<>();
        DbQueryExecutionRegistry.Execution execution = beginQueryExecution(executionId, userId);
        if (execution != null && execution.isCancelled()) {
            throw new ServiceException("SQL 执行已取消");
        }
        try (Connection connection = connectionManager.getConnection(conn);
             Statement stmt = connection.createStatement()) {
            applyDbName(connection, conn, dbName);
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            attachQueryExecution(executionId, execution, stmt);
            // 分页: MySQL 追加 LIMIT (SQL 已含 LIMIT 时不追加)
            String finalSql = trimSql;
            boolean hasLimit = Pattern.compile("\\blimit\\b", Pattern.CASE_INSENSITIVE).matcher(trimSql).find();
            boolean isMetaStatement = Pattern.compile("^\\s*(show|describe|desc)\\b", Pattern.CASE_INSENSITIVE).matcher(trimSql).find();
            if (pageNum != null && pageSize != null && pageSize > 0 && !hasLimit && !isMetaStatement) {
                int offset = Math.max(pageNum - 1, 0) * pageSize;
                String pageSql = dialect(conn).paginate(trimSql, offset, pageSize);
                if (pageSql != null) {
                    finalSql = pageSql;
                } else {
                    // 该方言不支持分页包装(未适配类型): 安全降级为不追加 LIMIT, 仅限制返回行数
                    stmt.setMaxRows(pageSize);
                }
            } else if (pageNum != null && pageSize != null && pageSize > 0) {
                stmt.setMaxRows(MAX_ROWS);
            } else {
                stmt.setMaxRows(MAX_ROWS);
            }
            try (ResultSet rs = stmt.executeQuery(finalSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>();
                List<String> columnTypes = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                    columnTypes.add(meta.getColumnTypeName(i));
                }
                result.setColumns(columns);
                result.setColumnTypes(columnTypes);
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object v = rs.getObject(i);
                        row.put(meta.getColumnLabel(i), v == null ? null : String.valueOf(v));
                    }
                    rows.add(row);
                    if (rows.size() >= (pageSize != null && pageSize > 0 ? pageSize : MAX_ROWS)) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (execution != null && execution.isCancelled()) {
                throw new ServiceException("查询已取消");
            }
            log.error("数据查询失败, sql={}", trimSql, e);
            if (isQueryTimeout(e)) {
                throw new ServiceException("查询超过 120 秒，已由服务器终止");
            }
            throw new ServiceException(translateSqlError(e.getMessage()));
        } finally {
            finishQueryExecution(executionId, execution);
        }
        result.setSuccess(true);
        result.setRows(rows);
        result.setTotal((long) rows.size());
        result.setCostMs(System.currentTimeMillis() - start);
        return result;
    }




    private String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (Exception e) {
            return "<error:" + e.getMessage() + ">";
        }
    }

    private void fillResultSet(DbResultVo result, ResultSet rs) throws SQLException {
        if (rs == null) {
            result.setAffectedRows(0);
            return;
        }
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>();
        List<String> columnTypes = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnLabel(i));
            columnTypes.add(meta.getColumnTypeName(i));
        }
        result.setColumns(columns);
        result.setColumnTypes(columnTypes);
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                Object v = rs.getObject(i);
                row.put(meta.getColumnLabel(i), v == null ? null : String.valueOf(v));
            }
            rows.add(row);
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        result.setRows(rows);
        result.setTotal((long) rows.size());
        result.setSqlType("select");
    }

    @Override
    public DbResultVo execute(Long connId, String sql, String confirmationToken, String userId) {
        return execute(connId, sql, confirmationToken, userId, null, null);
    }

    @Override
    public DbResultVo execute(Long connId, String sql, String confirmationToken, String userId, String executionId) {
        return execute(connId, sql, confirmationToken, userId, executionId, null);
    }

    @Override
    public DbResultVo execute(Long connId, String sql, String confirmationToken, String userId, String executionId, String dbName) {
        DbConn conn = getConn(connId);
        if (isRedis(conn)) {
            throw new ServiceException("Redis 不支持 SQL 执行, 请在左侧浏览键及类型/TTL");
        }
        String trimSql = DbAccessPolicy.normalizeLeadingComments(sql);
        if (DbAccessPolicy.isReadOnly(conn)) {
            DbAccessPolicy.requireReadOnlyStatement(trimSql);
        }
        if (isEs(conn)) {
            return esQuery(conn, trimSql, 1, 200);
        }
        if (trimSql.endsWith(";")) {
            trimSql = trimSql.substring(0, trimSql.length() - 1).trim();
        }
        if (trimSql.contains(";")) {
            throw new ServiceException("仅支持单条 SQL");
        }
        // 高危SQL检测
        String dangerWarning = checkDangerousSql(trimSql);
        if (dangerWarning != null && !confirmationService.consume(confirmationToken, connId, trimSql, userId)) {
            DbResultVo warn = new DbResultVo();
            warn.setSuccess(false);
            warn.setHighRisk(true);
            warn.setWarning(dangerWarning);
            warn.setMessage("高危SQL, 需确认后执行");
            warn.setSqlType("dangerous");
            warn.setConfirmationToken(confirmationService.issue(connId, trimSql, userId));
            return warn;
        }
        if (SELECT_PATTERN.matcher(trimSql).find()) {
            return query(connId, trimSql, 1, 200, userId, executionId, dbName);
        }
        long start = System.currentTimeMillis();
        DbResultVo result = new DbResultVo();
        result.setSqlType("update");
        DbQueryExecutionRegistry.Execution execution = beginQueryExecution(executionId, userId);
        if (execution != null && execution.isCancelled()) {
            throw new ServiceException("SQL 执行已取消");
        }
        try (Connection connection = connectionManager.getConnection(conn);
             Statement stmt = connection.createStatement()) {
            applyDbName(connection, conn, dbName);
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            attachQueryExecution(executionId, execution, stmt);
            boolean hasResultSet = stmt.execute(trimSql);
            int affected = stmt.getUpdateCount();
            if (!hasResultSet && affected >= 0) {
                result.setAffectedRows(affected);
            } else if (hasResultSet) {
                // show tables / describe 等语句: 读取结果集返回, 避免结果丢失
                fillResultSet(result, stmt.getResultSet());
            } else {
                result.setAffectedRows(0);
            }
        } catch (Exception e) {
            if (execution != null && execution.isCancelled()) {
                throw new ServiceException("SQL 执行已取消");
            }
            if (isQueryTimeout(e)) {
                throw new ServiceException("SQL 执行超过 120 秒，已由服务器终止");
            }
            throw new ServiceException(translateSqlError(e.getMessage()));
        } finally {
            finishQueryExecution(executionId, execution);
        }
        result.setSuccess(true);
        result.setMessage("执行成功");
        result.setCostMs(System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public boolean cancelQuery(String executionId, String userId) {
        return queryExecutionRegistry.cancel(executionId, userId);
    }

    private DbQueryExecutionRegistry.Execution beginQueryExecution(String executionId, String userId) {
        return executionId == null ? null : queryExecutionRegistry.begin(executionId, userId);
    }

    private void attachQueryExecution(
        String executionId, DbQueryExecutionRegistry.Execution execution, Statement statement) {
        if (execution != null) {
            queryExecutionRegistry.attach(executionId, execution, statement);
        }
    }

    private void finishQueryExecution(String executionId, DbQueryExecutionRegistry.Execution execution) {
        if (execution != null) {
            queryExecutionRegistry.finish(executionId, execution);
        }
    }

    private boolean isQueryTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLTimeoutException) {
                return true;
            }
            if (current instanceof java.sql.SQLException sqlException
                && ("57014".equals(sqlException.getSQLState()) || "70100".equals(sqlException.getSQLState()))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public DbGridChangePreviewVo previewGridChanges(DbGridChangeBo request, String userId) {
        DbConn conn = validateGridChangeRequest(request);
        DbAccessPolicy.requireWritable(conn);
        List<DbGridChangePlanner.PlannedGridChange> plan = buildGridChangePlan(request, conn);
        String operationDigest = DbGridChangePlanner.digest(plan);
        DbGridChangePreviewVo preview = new DbGridChangePreviewVo();
        preview.setConfirmationToken(confirmationService.issue(conn.getId(), "GRID:" + operationDigest, userId));
        preview.setChangeCount(plan.size());
        preview.setStatements(plan.stream().map(change -> change.operation() + ": " + change.sql()).toList());
        return preview;
    }

    @Override
    public Integer commitGridChanges(DbGridChangeBo request, String confirmationToken, String userId) {
        DbConn conn = validateGridChangeRequest(request);
        DbAccessPolicy.requireWritable(conn);
        List<DbGridChangePlanner.PlannedGridChange> plan = buildGridChangePlan(request, conn);
        if (!confirmationService.consume(confirmationToken, conn.getId(), "GRID:" + DbGridChangePlanner.digest(plan), userId)) {
            throw new ServiceException("数据变更确认已过期、已使用或与当前变更不匹配，请重新预览");
        }
        int affected = 0;
        try (Connection connection = connectionManager.getConnection(conn)) {
            connection.setAutoCommit(false);
            try {
                for (DbGridChangePlanner.PlannedGridChange change : plan) {
                    try (PreparedStatement statement = connection.prepareStatement(change.sql())) {
                        for (int i = 0; i < change.parameters().size(); i++) {
                            statement.setObject(i + 1, change.parameters().get(i));
                        }
                        int count = statement.executeUpdate();
                        if (count != 1) {
                            throw new ServiceException("数据已被其他用户修改或删除，变更已回滚，请刷新后重试");
                        }
                        affected += count;
                    }
                }
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (Exception rollbackError) {
                    e.addSuppressed(rollbackError);
                    log.error("结果网格变更事务回滚失败", rollbackError);
                    throw new ServiceException("保存数据变更失败，且事务回滚失败，请立即检查数据库状态", e);
                }
                if (e instanceof ServiceException serviceException) {
                    throw serviceException;
                }
                throw new ServiceException("保存数据变更失败，事务已回滚: " + e.getMessage());
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("保存数据变更失败: " + translateSqlError(e.getMessage()));
        }
        return affected;
    }

    private DbConn validateGridChangeRequest(DbGridChangeBo request) {
        if (request == null || request.getConnId() == null) {
            throw new ServiceException("数据变更请求不完整");
        }
        DbConn conn = getConn(request.getConnId());
        DbGridChangePlanner.validateRequest(request, conn);
        return conn;
    }

    private List<DbGridChangePlanner.PlannedGridChange> buildGridChangePlan(DbGridChangeBo request, DbConn conn) {
        String schema = StrUtil.blankToDefault(request.getSchema(), conn.getDatabaseName());
        List<DbColumnVo> metadata = columns(conn.getId(), schema, request.getTable());
        return DbGridChangePlanner.buildPlan(request, conn, metadata);
    }

    private void requireStructureChangeConfirmation(
        String confirmationToken, DbStructureChangeBo operation, Long connId, String userId) {
        String payload = DbStructureChangeConfirmation.canonicalPayload(operation);
        if (!confirmationService.consume(confirmationToken, connId, payload, userId)) {
            throw new ServiceException("结构变更确认已过期、已使用或与当前操作不匹配，请重新确认");
        }
    }

    /**
     * Elasticsearch REST SQL 查询(REST /_sql, Basic 许可可用; ES 7.x JDBC 需白金许可故走 REST)
     */
    private DbResultVo esQuery(DbConn conn, String sql, Integer pageNum, Integer pageSize) {
        long start = System.currentTimeMillis();
        DbResultVo result = new DbResultVo();
        result.setSqlType("select");
        String trimSql = sql.trim();
        if (trimSql.endsWith(";")) {
            trimSql = trimSql.substring(0, trimSql.length() - 1).trim();
        }
        int fetch = pageSize == null || pageSize <= 0 ? 200 : Math.min(pageSize, 1000);
        int offset = pageNum != null && pageNum > 0 ? Math.max(pageNum - 1, 0) * (pageSize == null || pageSize <= 0 ? 200 : pageSize) : 0;
        // 分页: ES REST SQL 仅支持 LIMIT count(单值), 不支持 MySQL 的 LIMIT offset,size
        // 故查询 offset+fetch 条后在内存中截取 offset 之后的 fetch 条
        String finalSql = trimSql;
        boolean hasLimit = Pattern.compile("\blimit\b", Pattern.CASE_INSENSITIVE).matcher(trimSql).find();
        if (!hasLimit) {
            finalSql = trimSql + " LIMIT " + (offset + fetch);
        }
        cn.hutool.json.JSONObject body = new cn.hutool.json.JSONObject();
        body.set("query", finalSql);
        body.set("fetch_size", fetch);
        Object obj = connectionManager.esRest(conn, "POST", "/_sql", body);
        if (obj instanceof cn.hutool.json.JSONObject resp) {
            cn.hutool.json.JSONArray cols = resp.getJSONArray("columns");
            cn.hutool.json.JSONArray rows = resp.getJSONArray("rows");
            List<String> columns = new ArrayList<>();
            List<String> columnTypes = new ArrayList<>();
            if (cols != null) {
                for (Object c : cols) {
                    cn.hutool.json.JSONObject col = (cn.hutool.json.JSONObject) c;
                    columns.add(col.getStr("name"));
                    columnTypes.add(col.getStr("type"));
                }
            }
            result.setColumns(columns);
            result.setColumnTypes(columnTypes);
            List<Map<String, Object>> rowsList = new ArrayList<>();
            if (rows != null) {
                for (Object r : rows) {
                    cn.hutool.json.JSONArray row = (cn.hutool.json.JSONArray) r;
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 0; i < columns.size() && i < row.size(); i++) {
                        m.put(columns.get(i), row.get(i));
                    }
                    rowsList.add(m);
                }
            }
            // 内存分页截取(ES 不支持 LIMIT offset,size)
            if (offset > 0 && rowsList.size() > offset) {
                rowsList = new ArrayList<>(rowsList.subList(offset, Math.min(rowsList.size(), offset + fetch)));
            } else if (offset > 0) {
                rowsList = new ArrayList<>();
            }
            result.setRows(rowsList);
            // 关闭服务端 cursor(释放资源)
            String cursor = resp.getStr("cursor");
            if (cursor != null && !cursor.isEmpty()) {
                cn.hutool.json.JSONObject close = new cn.hutool.json.JSONObject();
                close.set("cursor", cursor);
                try {
                    connectionManager.esRest(conn, "POST", "/_sql/close", close);
                } catch (Exception ignored) {
                }
            }
            result.setSuccess(true);
            result.setCostMs(System.currentTimeMillis() - start);
            return result;
        }
        throw new ServiceException("Elasticsearch 查询返回格式异常");
    }

    /**
     * 高危SQL检测, 返回警告信息; 非高危返回null
     */
    private String checkDangerousSql(String sql) {
        // 统一走 DbAccessPolicy 公共检测(事务会话执行也复用同一套规则)
        return DbAccessPolicy.dangerWarning(sql);
    }

    /**
     * 执行 SQL
     */
    private void executeByConn(DbConn conn, String sql) {
        DbAccessPolicy.requireWritable(conn);
        try (Connection connection = connectionManager.getConnection(conn);
             Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new ServiceException(translateSqlError(e.getMessage()));
        }
    }

    /**
     * 反引号转义标识符
     */
    private static String q(String name) {
        return "`" + (name == null ? "" : name.replace("`", "``")) + "`";
    }

    /**
     * 生成列定义 DDL
     */
    private String columnDdl(DbColumnVo c) {
        StringBuilder sb = new StringBuilder(q(c.getColumnName()));
        String type = StrUtil.isNotBlank(c.getColumnType()) ? c.getColumnType() : c.getDataType();
        if (StrUtil.isBlank(type)) {
            throw new ServiceException("列类型不能为空");
        }
        sb.append(' ').append(type);
        boolean notNull = c.getNullable() != null && "NO".equalsIgnoreCase(c.getNullable());
        sb.append(notNull ? " NOT NULL" : " NULL");
        String def = c.getDefaultValue();
        if (def != null && !def.isEmpty() && !"NULL".equalsIgnoreCase(def)) {
            if (isNumericDefault(c.getDataType(), def)) {
                sb.append(" DEFAULT ").append(def);
            } else {
                sb.append(" DEFAULT '").append(def.replace("'", "''")).append("'");
            }
        }
        if (c.getExtra() != null && c.getExtra().toLowerCase(Locale.ROOT).contains("auto_increment")) {
            sb.append(" AUTO_INCREMENT");
        }
        if (StrUtil.isNotBlank(c.getColumnComment())) {
            sb.append(" COMMENT '").append(c.getColumnComment().replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    private static final Set<String> NUMERIC_TYPES = Set.of(
        "int", "tinyint", "smallint", "mediumint", "bigint", "float", "double", "decimal", "bit", "year"
    );

    private boolean isNumericDefault(String dataType, String def) {
        if (dataType == null) {
            return false;
        }
        String dt = dataType.toLowerCase(Locale.ROOT);
        if (NUMERIC_TYPES.contains(dt)) {
            return true;
        }
        return dt.contains("int") || dt.contains("decimal") || dt.contains("double") || dt.contains("float");
    }

    /**
     * 将 JDBC/数据库异常信息翻译为简洁可读的中文提示
     */
    private static String translateSqlError(String msg) {
        if (msg == null || msg.isBlank()) {
            return "SQL 执行失败";
        }
        String m = msg.trim();
        // 去掉多余的前缀包装
        if (m.startsWith("执行失败: ")) {
            m = m.substring("执行失败: ".length());
        }
        // 常见错误码/关键字翻译
        String lower = m.toLowerCase(Locale.ROOT);
        if (lower.contains("syntax error") || lower.contains("sql syntax")) {
            // 提取 near 'xxx' 部分
            java.util.regex.Matcher near = Pattern.compile("near '(.*?)'", Pattern.CASE_INSENSITIVE).matcher(m);
            String nearPart = "";
            if (near.find()) {
                String raw = near.group(1);
                nearPart = "，错误位置: " + (raw.length() > 40 ? raw.substring(0, 40) + "..." : raw);
            }
            java.util.regex.Matcher line = Pattern.compile("at line (\\d+)", Pattern.CASE_INSENSITIVE).matcher(m);
            String linePart = line.find() ? "（第 " + line.group(1) + " 行）" : "";
            return "SQL 语法错误" + (nearPart.isEmpty() ? "" : nearPart) + linePart;
        }
        if (lower.contains("unknown database")) {
            java.util.regex.Matcher d = Pattern.compile("Unknown database '(.*?)'", Pattern.CASE_INSENSITIVE).matcher(m);
            if (d.find()) {
                return "数据库不存在: " + d.group(1) + "，请检查数据库名是否正确";
            }
            return "数据库不存在，请检查数据库名是否正确";
        }
        if (lower.contains("unknown column")) {
            java.util.regex.Matcher c = Pattern.compile("Unknown column '(.*?)'", Pattern.CASE_INSENSITIVE).matcher(m);
            if (c.find()) {
                return "未知字段: " + c.group(1) + "，请检查字段名是否正确";
            }
            return "SQL 引用了不存在的字段";
        }
        if (lower.contains("unknown table")) {
            java.util.regex.Matcher t = Pattern.compile("Unknown table '(.*?)'", Pattern.CASE_INSENSITIVE).matcher(m);
            if (t.find()) {
                return "未知表: " + t.group(1) + "，请检查表名是否正确";
            }
            return "SQL 引用了不存在的表";
        }
        if (lower.contains("doesn't exist") || lower.contains("does not exist")) {
            java.util.regex.Matcher t = Pattern.compile("Table '(.*?)' doesn't exist", Pattern.CASE_INSENSITIVE).matcher(m);
            if (t.find()) {
                return "表不存在: " + t.group(1);
            }
            return "对象不存在，请检查表名/字段名";
        }
        if (lower.contains("duplicate entry")) {
            java.util.regex.Matcher d = Pattern.compile("Duplicate entry '(.*?)'", Pattern.CASE_INSENSITIVE).matcher(m);
            if (d.find()) {
                return "数据重复: 值 '" + d.group(1) + "' 已存在（主键或唯一约束冲突）";
            }
            return "数据重复，违反唯一约束";
        }
        if (lower.contains("duplicate column")) {
            return "字段重复: 表中已存在同名字段";
        }
        if (lower.contains("cannot be null")) {
            java.util.regex.Matcher c = Pattern.compile("Column '(.*?)' cannot be null", Pattern.CASE_INSENSITIVE).matcher(m);
            if (c.find()) {
                return "字段 " + c.group(1) + " 不能为空";
            }
            return "存在不能为空的字段";
        }
        if (lower.contains("data too long")) {
            java.util.regex.Matcher c = Pattern.compile("Data too long for column '(.*?)'", Pattern.CASE_INSENSITIVE).matcher(m);
            if (c.find()) {
                return "字段 " + c.group(1) + " 数据超长，请检查内容长度";
            }
            return "数据超长，超出字段长度限制";
        }
        if (lower.contains("out of range")) {
            return "数值超出字段取值范围";
        }
        if (lower.contains("incorrect value") || lower.contains("invalid value")) {
            java.util.regex.Matcher c = Pattern.compile("Incorrect (?:integer|double|date|time|value|string) value: '(.*?)'", Pattern.CASE_INSENSITIVE).matcher(m);
            if (c.find()) {
                return "字段值格式错误: '" + c.group(1) + "'";
            }
            return "字段值格式错误";
        }
        if (lower.contains("foreign key") && lower.contains("fail")) {
            return "外键约束失败: 关联数据不存在或正在被引用";
        }
        if (lower.contains("cannot delete or update a parent row")) {
            return "删除/更新失败: 该记录存在关联子数据（外键约束）";
        }
        if (lower.contains("lock wait timeout")) {
            return "执行超时: 表被其他事务锁定，请稍后重试";
        }
        if (lower.contains("deadlock")) {
            return "死锁: 检测到事务死锁，请重试";
        }
        if (lower.contains("access denied") || lower.contains("denied to user")) {
            return "权限不足: 当前账号无权执行该操作";
        }
        if (lower.contains("connection refused") || lower.contains("connection timed out")) {
            return "连接失败: 无法连接到数据库，请检查连接配置";
        }
        if (lower.contains("table is full")) {
            return "表空间已满";
        }
        if (lower.contains("too many connections")) {
            return "连接数超限: 数据库连接池已满，请稍后重试";
        }
        // 默认: 截断过长的原始信息
        if (m.length() > 200) {
            return m.substring(0, 200) + "...";
        }
        return m;
    }

    // ==================== Redis 可视化管理 ====================

    private static final Pattern DANGEROUS_REDIS_CMD = Pattern.compile("^(flushall|flushdb|shutdown|debug|monitor|config\\s+set|slaveof|replicaof|keys)\\b", Pattern.CASE_INSENSITIVE);

    private String redisOne(DbConn conn, String db, String... args) {
        List<String> repl = connectionManager.redisCommand(conn, db, args);
        return repl.isEmpty() ? "" : repl.get(0);
    }

    private String redisClean(String v) {
        if (v == null) {
            return "";
        }
        if (v.startsWith("+") || v.startsWith(":") || v.startsWith("-")) {
            return v.substring(1);
        }
        return v;
    }

    private void ensureRedis(DbConn conn) {
        if (!isRedis(conn)) {
            throw new ServiceException("该连接不是 Redis 类型");
        }
    }

    /**
     * Redis 键列表(SCAN 增量迭代, 支持 * 通配符)
     */
    public Map<String, Object> redisKeys(Long connId, String db, String pattern, String cursor, int count) {
        DbConn conn = getConn(connId);
        ensureRedis(conn);
        String cur = StrUtil.blankToDefault(cursor, "0");
        int cnt = count <= 0 ? 200 : Math.min(count, 1000);
        List<String> repl = connectionManager.redisCommand(conn, db, "SCAN", cur, "MATCH", StrUtil.blankToDefault(pattern, "*"), "COUNT", String.valueOf(cnt));
        Map<String, Object> result = new LinkedHashMap<>();
        String next = repl.isEmpty() ? "0" : redisClean(repl.get(0));
        List<String> keys = repl.size() > 1 ? new ArrayList<>(repl.subList(1, repl.size())) : List.of();
        result.put("cursor", next);
        result.put("keys", keys);
        return result;
    }

    /**
     * Redis 键信息(TYPE/TTL/内存占用/元素数量)
     */
    public Map<String, Object> redisKeyInfo(Long connId, String db, String key) {
        DbConn conn = getConn(connId);
        ensureRedis(conn);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("key", key);
        info.put("type", redisClean(redisOne(conn, db, "TYPE", key)));
        String ttl = redisClean(redisOne(conn, db, "TTL", key));
        info.put("ttl", "-2".equals(ttl) || "-1".equals(ttl) ? ttl : ttl);
        String size = redisClean(redisOne(conn, db, "MEMORY", "USAGE", key));
        if (size.startsWith("ERR") || size.isBlank()) {
            size = "";
        }
        info.put("size", size);
        return info;
    }

    /**
     * Redis 键值读取(按类型结构化返回)
     */
    public Map<String, Object> redisValue(Long connId, String db, String key, String type) {
        DbConn conn = getConn(connId);
        ensureRedis(conn);
        String t = StrUtil.blankToDefault(type, redisClean(redisOne(conn, db, "TYPE", key)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("type", t);
        switch (t) {
            case "string" -> {
                byte[] bytes = connectionManager.redisBytes(conn, db, key);
                if (bytes == null) {
                    result.put("value", null);
                    break;
                }
                String utf8 = new String(bytes, StandardCharsets.UTF_8);
                if (isUtf8Text(bytes, utf8)) {
                    result.put("value", utf8);
                } else {
                    // 二进制内容: 返回 Hex 供前端查看/编辑
                    result.put("binary", true);
                    result.put("hex", toHex(bytes));
                }
            }
            case "hash" -> {
                List<String> fv = connectionManager.redisCommand(conn, db, "HGETALL", key);
                List<Map<String, String>> fields = new ArrayList<>();
                for (int i = 0; i + 1 < fv.size(); i += 2) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("field", fv.get(i));
                    m.put("value", fv.get(i + 1));
                    fields.add(m);
                }
                result.put("fields", fields);
            }
            case "list" -> {
                List<String> items = connectionManager.redisCommand(conn, db, "LRANGE", key, "0", "-1");
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index", i);
                    m.put("value", items.get(i));
                    rows.add(m);
                }
                result.put("items", rows);
            }
            case "set" -> result.put("members", connectionManager.redisCommand(conn, db, "SMEMBERS", key));
            case "zset" -> {
                List<String> ms = connectionManager.redisCommand(conn, db, "ZRANGE", key, "0", "-1", "WITHSCORES");
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i + 1 < ms.size(); i += 2) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("member", ms.get(i));
                    m.put("score", ms.get(i + 1));
                    rows.add(m);
                }
                result.put("items", rows);
            }
            case "stream" -> result.put("note", "Stream 类型暂不支持在线编辑, 请使用 CLI");
            default -> {
                String v = redisOne(conn, db, "GET", key);
                result.put("value", v);
            }
        }
        return result;
    }

    /**
     * Redis 键写入(新增/更新, 按类型; ttl 为 null 不动, >=0 设置, <0 持久化)
     */
    public void redisSet(Long connId, String db, String key, String type, String value, Long ttl, List<Map<String, String>> fields) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        ensureRedis(conn);
        String t = StrUtil.blankToDefault(type, "string");
        switch (t) {
            case "string" -> {
                if (value == null) {
                    throw new ServiceException("值不能为空");
                }
                if (value.startsWith("__HEX__")) {
                    // 二进制内容: Hex 解码后原样写回
                    connectionManager.redisSetBytes(conn, db, key, fromHex(value.substring(7)));
                } else {
                    connectionManager.redisCommand(conn, db, "SET", key, value);
                }
            }
            case "hash" -> {
                connectionManager.redisCommand(conn, db, "DEL", key);
                if (fields != null && !fields.isEmpty()) {
                    for (Map<String, String> f : fields) {
                        connectionManager.redisCommand(conn, db, "HSET", key,
                            StrUtil.blankToDefault(f.get("field"), ""),
                            StrUtil.blankToDefault(f.get("value"), ""));
                    }
                }
            }
            case "list" -> {
                connectionManager.redisCommand(conn, db, "DEL", key);
                if (fields != null && !fields.isEmpty()) {
                    List<String> args = new ArrayList<>();
                    args.add("RPUSH");
                    args.add(key);
                    for (Map<String, String> f : fields) {
                        args.add(StrUtil.blankToDefault(f.get("value"), ""));
                    }
                    connectionManager.redisCommand(conn, db, args.toArray(new String[0]));
                }
            }
            case "set" -> {
                connectionManager.redisCommand(conn, db, "DEL", key);
                if (fields != null && !fields.isEmpty()) {
                    List<String> args = new ArrayList<>();
                    args.add("SADD");
                    args.add(key);
                    for (Map<String, String> f : fields) {
                        args.add(StrUtil.blankToDefault(f.get("value"), ""));
                    }
                    connectionManager.redisCommand(conn, db, args.toArray(new String[0]));
                }
            }
            case "zset" -> {
                connectionManager.redisCommand(conn, db, "DEL", key);
                if (fields != null && !fields.isEmpty()) {
                    for (Map<String, String> f : fields) {
                        connectionManager.redisCommand(conn, db, "ZADD", key,
                            StrUtil.blankToDefault(f.get("score"), "0"),
                            StrUtil.blankToDefault(f.get("value"), ""));
                    }
                }
            }
            default -> throw new ServiceException("不支持的键类型: " + t);
        }
        if (ttl != null && ttl >= 0) {
            connectionManager.redisCommand(conn, db, "EXPIRE", key, String.valueOf(ttl));
        } else if (ttl != null && ttl < 0) {
            connectionManager.redisCommand(conn, db, "PERSIST", key);
        }
    }

    /**
     * Redis 键删除
     */
    public void redisDel(Long connId, String db, List<String> keys) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        ensureRedis(conn);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>();
        args.add("DEL");
        args.addAll(keys);
        connectionManager.redisCommand(conn, db, args.toArray(new String[0]));
    }

    /**
     * Redis TTL 更新(>=0 设置过期秒, <0 持久化)
     */
    public void redisExpire(Long connId, String db, String key, Long ttl) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        ensureRedis(conn);
        if (ttl != null && ttl >= 0) {
            connectionManager.redisCommand(conn, db, "EXPIRE", key, String.valueOf(ttl));
        } else {
            connectionManager.redisCommand(conn, db, "PERSIST", key);
        }
    }

    /**
     * Redis INFO 信息(解析为 section -> k/v)
     */
    public Map<String, Object> redisInfo(Long connId, String db) {
        DbConn conn = getConn(connId);
        ensureRedis(conn);
        String raw = redisOne(conn, db, "INFO");
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> current = new LinkedHashMap<>();
        for (String line : raw.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#")) {
                current = new LinkedHashMap<>();
                result.put(line.substring(1).trim(), current);
                continue;
            }
            int idx = line.indexOf(':');
            if (idx > 0) {
                current.put(line.substring(0, idx), line.substring(idx + 1));
            }
        }
        return result;
    }

    /**
     * Redis CLI 通用命令执行(返回平铺结果)
     */
    public Object redisCli(Long connId, String db, String command) {
        DbConn conn = getConn(connId);
        ensureRedis(conn);
        String cmd = StrUtil.trim(command);
        if (cmd.isBlank()) {
            throw new ServiceException("命令不能为空");
        }
        String[] parts = cmd.split("\\s+");
        String first = parts[0].toLowerCase();
        if (DbAccessPolicy.isReadOnly(conn) && !DbAccessPolicy.isReadOnlyRedisCommand(cmd)) {
            DbAccessPolicy.requireWritable(conn);
        }
        // KEYS 限量执行(用 SCAN 替代, 最多 1000 条), 其余危险命令拦截
        if ("keys".equals(first) || first.startsWith("keys*")) {
            String pattern = parts.length > 1 ? parts[1] : (first.equals("keys") ? "*" : first.substring(4));
            List<String> out = new ArrayList<>();
            String cur = "0";
            int guard = 0;
            while (guard++ < 200) {
                List<String> batch = connectionManager.redisCommand(conn, db, "SCAN", cur, "MATCH", pattern, "COUNT", "500");
                if (batch.isEmpty()) {
                    break;
                }
                String nextCur = redisClean(batch.get(0));
                for (int i = 1; i < batch.size(); i++) {
                    out.add(batch.get(i));
                }
                cur = nextCur;
                if ("0".equals(cur) || out.size() >= 1000) {
                    break;
                }
            }
            out.add(0, "(KEYS 已限量执行, 共 " + out.size() + " 条; 大库请优先使用 SCAN 避免阻塞)");
            return out;
        }
        if (DANGEROUS_REDIS_CMD.matcher(cmd).find()) {
            throw new ServiceException("危险命令已拦截: " + first + " (FLUSHALL/FLUSHDB/SHUTDOWN/DEBUG/MONITOR/CONFIG SET 等禁止在工作台执行)");
        }
        List<String> repl = connectionManager.redisCommand(conn, db, parts);
        List<String> cleaned = new ArrayList<>(repl.size());
        for (String v : repl) {
            cleaned.add(redisClean(v));
        }
        return cleaned;
    }

    // ==================== Redis 二进制值处理 ====================

    private boolean isUtf8Text(byte[] bytes, String decoded) {
        if (decoded.indexOf('\uFFFD') >= 0) {
            return false;
        }
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c == 0) {
                return false;
            }
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private byte[] fromHex(String s) {
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ==================== 数据迁移 & Excel 导入 ====================

    @Override
    public Map<String, Object> migrateTable(Long srcConnId, String srcSchema, String srcTable,
                                            Long dstConnId, String dstSchema, String dstTable,
                                            Boolean withStructure, Boolean withData, Boolean dropTarget,
                                            List<Map<String, String>> fieldMappings) {
        DbConn src = getConn(srcConnId);
        DbConn dst = getConn(dstConnId);
        if (src == null || dst == null) {
            throw new ServiceException("源/目标连接不存在");
        }
        if (StrUtil.isBlank(srcTable) || StrUtil.isBlank(dstTable)) {
            throw new ServiceException("源表/目标表不能为空");
        }
        boolean structure = withStructure == null || withStructure;
        if (structure || Boolean.TRUE.equals(withData) || Boolean.TRUE.equals(dropTarget)) {
            DbAccessPolicy.requireWritable(dst);
        }
        List<DbColumnVo> cols = columns(srcConnId, srcSchema, srcTable);
        if (cols == null || cols.isEmpty()) {
            throw new ServiceException("源表无列信息(表不存在或无权限): " + srcTable);
        }
        String dstType = StrUtil.nullToDefault(dst.getDbType(), "mysql").toLowerCase();
        // 字段映射: 目标列定义(列名已按映射替换), 源列名列表保持原样
        List<DbColumnVo> mappedCols = applyMappings(cols, fieldMappings);
        String ddl = buildCreateTable(dstType, dstSchema, dstTable, mappedCols);
        int moved = 0;
        try {
            if (structure) {
                try (Connection c = connectionManager.getConnection(dst);
                     Statement st = c.createStatement()) {
                    if (dropTarget != null && dropTarget) {
                        try {
                            st.execute("DROP TABLE " + qname(dstSchema, dstTable, dstType));
                        } catch (Exception ignore) {
                            // 表不存在忽略
                        }
                    }
                    st.execute(ddl);
                }
            }
            if (withData != null && withData) {
                moved = copyData(src, srcSchema, srcTable, dst, dstSchema, dstTable, cols, mappedCols, dstType);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("迁移失败: " + e.getMessage());
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ddl", ddl);
        r.put("moved", moved);
        r.put("targetTable", dstTable);
        if (fieldMappings != null && !fieldMappings.isEmpty()) {
            List<Map<String, String>> applied = new ArrayList<>();
            for (int i = 0; i < cols.size(); i++) {
                String srcN = cols.get(i).getColumnName();
                String dstN = mappedCols.get(i).getColumnName();
                if (!srcN.equalsIgnoreCase(dstN)) {
                    Map<String, String> mm = new LinkedHashMap<>();
                    mm.put("src", srcN);
                    mm.put("dst", dstN);
                    applied.add(mm);
                }
            }
            r.put("mappings", applied);
        }
        return r;
    }

    /** 按字段映射生成目标列定义(列名替换, 类型/属性沿用源列) */
    private List<DbColumnVo> applyMappings(List<DbColumnVo> cols, List<Map<String, String>> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return cols;
        }
        List<DbColumnVo> out = new ArrayList<>();
        for (DbColumnVo c : cols) {
            DbColumnVo nc = new DbColumnVo();
            nc.setColumnName(c.getColumnName());
            nc.setDataType(c.getDataType());
            nc.setColumnType(c.getColumnType());
            nc.setNullable(c.getNullable());
            nc.setDefaultValue(c.getDefaultValue());
            nc.setColumnComment(c.getColumnComment());
            nc.setColumnKey(c.getColumnKey());
            nc.setPrimary(c.getPrimary());
            nc.setExtra(c.getExtra());
            nc.setCharacterSet(c.getCharacterSet());
            nc.setCollation(c.getCollation());
            for (Map<String, String> m : mappings) {
                String s = m.get("src");
                String d = m.get("dst");
                if (StrUtil.isNotBlank(s) && StrUtil.isNotBlank(d) && s.equalsIgnoreCase(c.getColumnName())) {
                    nc.setColumnName(d);
                    break;
                }
            }
            out.add(nc);
        }
        return out;
    }

    /** 构造目标库建表 DDL(跨库类型映射) */
    private String buildCreateTable(String dbType, String schema, String table, List<DbColumnVo> cols) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(qname(schema, table, dbType)).append(" (\n");
        List<String> pks = new ArrayList<>();
        int n = 0;
        for (DbColumnVo c : cols) {
            sb.append("  ").append(qcol(c.getColumnName(), dbType)).append(' ').append(mapType(dbType, c));
            if (c.getNullable() == null || !"YES".equalsIgnoreCase(c.getNullable())) {
                sb.append(" NOT NULL");
            }
            String dv = c.getDefaultValue();
            if (StrUtil.isNotBlank(dv) && !"NULL".equalsIgnoreCase(dv)) {
                sb.append(" DEFAULT ").append(defaultLiteral(dv, c.getDataType()));
            }
            if (Boolean.TRUE.equals(c.getPrimary())) {
                pks.add(qcol(c.getColumnName(), dbType));
            }
            sb.append(",\n");
            n++;
        }
        if (!pks.isEmpty()) {
            sb.append("  PRIMARY KEY (").append(String.join(", ", pks)).append(")\n");
        } else {
            sb.setLength(sb.length() - 2);
            sb.append("\n");
        }
        sb.append(")");
        if ("mysql".equals(dbType)) {
            sb.append(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        return sb.toString();
    }

    /** 跨库类型映射 */
    private String mapType(String dbType, DbColumnVo c) {
        String t = c.getDataType() == null ? "" : c.getDataType().toLowerCase();
        String full = c.getColumnType() == null ? "" : c.getColumnType().toLowerCase();
        int[] len = parseLen(full);
        int p = len[0], s = len[1];
        boolean unsigned = full.contains("unsigned");
        switch (dbType) {
            case "postgresql":
                if (t.contains("int") && !t.contains("bigint") && !t.contains("smallint") && !t.contains("tinyint")) return "INTEGER";
                if (t.contains("bigint")) return "BIGINT";
                if (t.contains("smallint")) return "SMALLINT";
                if (t.contains("tinyint")) return "SMALLINT";
                if (t.contains("char") || t.contains("text") || t.contains("json") || t.contains("enum") || t.contains("set")) {
                    if (t.contains("text") && !t.contains("char")) return "TEXT";
                    return "VARCHAR(" + (p > 0 ? p : 255) + ")";
                }
                if (t.contains("date") && !t.contains("time")) return "DATE";
                if (t.contains("datetime") || t.contains("timestamp")) return "TIMESTAMP";
                if (t.contains("time")) return "TIME";
                if (t.contains("decimal") || t.contains("numeric")) return "NUMERIC(" + (p > 0 ? p : 18) + "," + (s >= 0 ? s : 2) + ")";
                if (t.contains("float")) return "REAL";
                if (t.contains("double")) return "DOUBLE PRECISION";
                if (t.contains("blob") || t.contains("binary")) return "BYTEA";
                return "TEXT";
            case "oracle":
                if (t.contains("int") && !t.contains("bigint")) return "NUMBER(10)";
                if (t.contains("bigint")) return "NUMBER(19)";
                if (t.contains("smallint")) return "NUMBER(5)";
                if (t.contains("tinyint")) return "NUMBER(3)";
                if (t.contains("char") || t.contains("text") || t.contains("json") || t.contains("enum") || t.contains("set")) {
                    if (t.contains("text") && !t.contains("char")) return "CLOB";
                    return "VARCHAR2(" + (p > 0 ? p : 255) + ")";
                }
                if (t.contains("date") && !t.contains("time")) return "DATE";
                if (t.contains("datetime") || t.contains("timestamp")) return "TIMESTAMP";
                if (t.contains("decimal") || t.contains("numeric") || t.contains("float") || t.contains("double")) {
                    return "NUMBER(" + (p > 0 ? p : 18) + "," + (s >= 0 ? s : 2) + ")";
                }
                if (t.contains("blob") || t.contains("binary")) return "BLOB";
                return "CLOB";
            case "dm":
                if (t.contains("int") && !t.contains("bigint") && !t.contains("smallint") && !t.contains("tinyint")) return "INT";
                if (t.contains("bigint")) return "BIGINT";
                if (t.contains("smallint")) return "SMALLINT";
                if (t.contains("tinyint")) return "TINYINT";
                if (t.contains("char") || t.contains("text") || t.contains("json") || t.contains("enum") || t.contains("set")) {
                    if (t.contains("text") && !t.contains("char")) return "TEXT";
                    return "VARCHAR(" + (p > 0 ? p : 255) + ")";
                }
                if (t.contains("date") && !t.contains("time")) return "DATE";
                if (t.contains("datetime") || t.contains("timestamp")) return "TIMESTAMP";
                if (t.contains("decimal") || t.contains("numeric")) return "DECIMAL(" + (p > 0 ? p : 18) + "," + (s >= 0 ? s : 2) + ")";
                if (t.contains("float")) return "FLOAT";
                if (t.contains("double")) return "DOUBLE";
                if (t.contains("blob") || t.contains("binary")) return "BLOB";
                return "TEXT";
            case "sqlserver":
                if (t.contains("int") && !t.contains("bigint") && !t.contains("smallint") && !t.contains("tinyint")) return "INT";
                if (t.contains("bigint")) return "BIGINT";
                if (t.contains("smallint")) return "SMALLINT";
                if (t.contains("tinyint")) return "TINYINT";
                if (t.contains("char") || t.contains("text") || t.contains("json") || t.contains("enum") || t.contains("set")) {
                    if (t.contains("text") && !t.contains("char")) return "NVARCHAR(MAX)";
                    return "NVARCHAR(" + (p > 0 ? p : 255) + ")";
                }
                if (t.contains("date") && !t.contains("time")) return "DATE";
                if (t.contains("datetime") || t.contains("timestamp")) return "DATETIME2";
                if (t.contains("decimal") || t.contains("numeric")) return "DECIMAL(" + (p > 0 ? p : 18) + "," + (s >= 0 ? s : 2) + ")";
                if (t.contains("float")) return "FLOAT";
                if (t.contains("double")) return "FLOAT(53)";
                if (t.contains("blob") || t.contains("binary")) return "VARBINARY(MAX)";
                return "NVARCHAR(MAX)";
            default: // mysql 及其他
                if (t.contains("int") && !t.contains("bigint") && !t.contains("smallint") && !t.contains("tinyint")) return unsigned ? "INT UNSIGNED" : "INT";
                if (t.contains("bigint")) return unsigned ? "BIGINT UNSIGNED" : "BIGINT";
                if (t.contains("smallint")) return "SMALLINT";
                if (t.contains("tinyint")) return "TINYINT";
                if (t.contains("char") || t.contains("text") || t.contains("json") || t.contains("enum") || t.contains("set")) {
                    if (t.contains("text") && !t.contains("char")) return "TEXT";
                    if (t.contains("long")) return "LONGTEXT";
                    return "VARCHAR(" + (p > 0 ? p : 255) + ")";
                }
                if (t.contains("date") && !t.contains("time")) return "DATE";
                if (t.contains("datetime")) return "DATETIME";
                if (t.contains("timestamp")) return "TIMESTAMP";
                if (t.contains("time")) return "TIME";
                if (t.contains("decimal") || t.contains("numeric")) return "DECIMAL(" + (p > 0 ? p : 18) + "," + (s >= 0 ? s : 2) + ")";
                if (t.contains("float")) return "FLOAT";
                if (t.contains("double")) return "DOUBLE";
                if (t.contains("blob") || t.contains("binary")) return "LONGBLOB";
                if (t.contains("json")) return "JSON";
                return "VARCHAR(255)";
        }
    }

    /** 从完整类型提取长度/精度, 如 varchar(64) -> [64,-1], decimal(10,2) -> [10,2] */
    private int[] parseLen(String full) {
        int[] r = new int[]{0, -1};
        if (full == null) return r;
        int i = full.indexOf('(');
        if (i < 0) return r;
        int j = full.indexOf(')', i);
        if (j < 0) return r;
        String body = full.substring(i + 1, j).trim();
        String[] parts = body.split(",");
        try {
            r[0] = Integer.parseInt(parts[0].trim());
        } catch (Exception ignore) {
        }
        if (parts.length > 1) {
            try {
                r[1] = Integer.parseInt(parts[1].trim());
            } catch (Exception ignore) {
            }
        }
        return r;
    }

    private String defaultLiteral(String dv, String dataType) {
        String t = dataType == null ? "" : dataType.toLowerCase();
        if (t.contains("date") || t.contains("time")) {
            String d = dv.replaceAll("'", "");
            if (d.toLowerCase().startsWith("c") || d.toLowerCase().startsWith("current")) {
                return "CURRENT_TIMESTAMP";
            }
            return "'" + d + "'";
        }
        if (t.contains("int") || t.contains("decimal") || t.contains("numeric") || t.contains("float") || t.contains("double")) {
            return dv;
        }
        return "'" + dv.replaceAll("'", "''") + "'";
    }

    /** 标识符引用(按方言) */
    private String qcol(String name, String dbType) {
        if ("mysql".equals(dbType) || "dm".equals(dbType)) {
            return "`" + name + "`";
        }
        if ("postgresql".equals(dbType)) {
            return "\"" + name + "\"";
        }
        return "\"" + name + "\"";
    }

    private String qname(String schema, String table, String dbType) {
        if (StrUtil.isBlank(schema)) {
            return qcol(table, dbType);
        }
        return qcol(schema, dbType) + "." + qcol(table, dbType);
    }

    /** 跨库搬数据 */
    private int copyData(DbConn src, String srcSchema, String srcTable,
                         DbConn dst, String dstSchema, String dstTable,
                         List<DbColumnVo> cols, List<DbColumnVo> mappedCols, String dstType) throws Exception {
        // 源列名(原列), 目标列名(映射后)
        List<String> srcNames = cols.stream().map(DbColumnVo::getColumnName).collect(java.util.stream.Collectors.toList());
        List<String> dstNames = mappedCols.stream().map(DbColumnVo::getColumnName).collect(java.util.stream.Collectors.toList());
        String srcType = src.getDbType() == null ? "mysql" : src.getDbType().toLowerCase();
        String selCols = srcNames.stream().map(n -> qcol(n, srcType)).collect(java.util.stream.Collectors.joining(", "));
        String srcQname = qname(srcSchema, srcTable, srcType);
        String insCols = dstNames.stream().map(n -> qcol(n, dstType)).collect(java.util.stream.Collectors.joining(", "));
        String dstQname = qname(dstSchema, dstTable, dstType);
        String sel = "SELECT " + selCols + " FROM " + srcQname;
        String placeholders = String.join(", ", java.util.Collections.nCopies(srcNames.size(), "?"));
        String ins = "INSERT INTO " + dstQname + " (" + insCols + ") VALUES (" + placeholders + ")";
        int moved = 0;
        try (Connection sc = connectionManager.getConnection(src);
             Connection dc = connectionManager.getConnection(dst);
             Statement st = sc.createStatement();
             ResultSet rs = st.executeQuery(sel);
             PreparedStatement ps = dc.prepareStatement(ins)) {
            dc.setAutoCommit(false);
            int batch = 0;
            while (rs.next()) {
                for (int i = 0; i < srcNames.size(); i++) {
                    Object v = rs.getObject(i + 1);
                    ps.setObject(i + 1, v);
                }
                ps.addBatch();
                batch++;
                if (batch >= 500) {
                    ps.executeBatch();
                    dc.commit();
                    moved += batch;
                    batch = 0;
                }
            }
            if (batch > 0) {
                ps.executeBatch();
                dc.commit();
                moved += batch;
            }
        }
        return moved;
    }

    // ==================== Excel 多工作表导入 ====================

    @Override
    public Map<String, Object> importExcel(Long connId, String schema, List<Map<String, Object>> sheets,
                                           org.springframework.web.multipart.MultipartFile file) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        String dbType = StrUtil.nullToDefault(conn.getDbType(), "mysql").toLowerCase();
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> sheetResults = new ArrayList<>();
        if (sheets == null || sheets.isEmpty()) {
            throw new ServiceException("未指定工作表映射");
        }
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream())) {
            for (Map<String, Object> m : sheets) {
                String sheetName = (String) m.get("sheetName");
                String tableName = (String) m.get("tableName");
                String mode = StrUtil.nullToDefault((String) m.get("mode"), "create");
                Map<String, Object> sr = new LinkedHashMap<>();
                sr.put("sheet", sheetName);
                sr.put("table", tableName);
                sr.put("mode", mode);
                try {
                    org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet(sheetName);
                    if (sheet == null) {
                        throw new ServiceException("工作表不存在: " + sheetName);
                    }
                    List<List<Object>> rows = readSheet(sheet);
                    if (rows.size() < 2) {
                        throw new ServiceException("无数据(需表头 + 至少一行): " + sheetName);
                    }
                    List<String> headers = new ArrayList<>();
                    for (Object h : rows.get(0)) {
                        headers.add(h == null ? "col" + (headers.size() + 1) : String.valueOf(h).trim());
                    }
                    List<List<Object>> data = new ArrayList<>(rows.subList(1, rows.size()));
                    List<String> colTypes = inferExcelTypes(headers, data, dbType);
                    String tableQ = qname(schema, tableName, dbType);
                    if ("create".equals(mode)) {
                        String ddl = buildExcelTable(dbType, schema, tableName, headers, colTypes);
                        try (Connection c = connectionManager.getConnection(conn);
                             Statement st = c.createStatement()) {
                            st.execute(ddl);
                        }
                        sr.put("ddl", ddl);
                    } else {
                        // append: 校验列存在
                        try (Connection c = connectionManager.getConnection(conn);
                             Statement st = c.createStatement();
                             ResultSet rs = st.executeQuery("SELECT * FROM " + tableQ + " LIMIT 1")) {
                            java.sql.ResultSetMetaData md = rs.getMetaData();
                            Set<String> exists = new LinkedHashSet<>();
                            for (int i = 1; i <= md.getColumnCount(); i++) {
                                exists.add(md.getColumnLabel(i).toLowerCase());
                            }
                            for (String h : headers) {
                                if (!exists.contains(h.toLowerCase())) {
                                    throw new ServiceException("目标表缺少列: " + h + " (已有列: " + String.join(",", exists) + ")");
                                }
                            }
                        } catch (ServiceException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new ServiceException("校验目标表失败: " + e.getMessage());
                        }
                    }
                    int n = insertExcelRows(conn, schema, tableName, headers, data, dbType);
                    sr.put("rows", n);
                    sr.put("success", true);
                } catch (Exception e) {
                    sr.put("success", false);
                    sr.put("error", e.getMessage());
                }
                sheetResults.add(sr);
            }
        } catch (Exception e) {
            throw new ServiceException("Excel 解析失败: " + e.getMessage());
        }
        result.put("sheets", sheetResults);
        return result;
    }

    private List<List<Object>> readSheet(org.apache.poi.ss.usermodel.Sheet sheet) {
        List<List<Object>> rows = new ArrayList<>();
        org.apache.poi.ss.usermodel.DataFormatter fmt = new org.apache.poi.ss.usermodel.DataFormatter();
        int lastRow = sheet.getLastRowNum();
        for (int r = 0; r <= lastRow; r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            List<Object> vals = new ArrayList<>();
            int lastCell = row.getLastCellNum();
            for (int c = 0; c < lastCell; c++) {
                org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                if (cell == null) {
                    vals.add(null);
                    continue;
                }
                switch (cell.getCellType()) {
                    case STRING -> vals.add(cell.getStringCellValue());
                    case NUMERIC -> {
                        if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                            vals.add(cell.getLocalDateTimeCellValue());
                        } else {
                            double d = cell.getNumericCellValue();
                            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                                vals.add((long) d);
                            } else {
                                vals.add(d);
                            }
                        }
                    }
                    case BOOLEAN -> vals.add(cell.getBooleanCellValue());
                    case FORMULA -> vals.add(fmt.formatCellValue(cell));
                    default -> vals.add(null);
                }
            }
            // 跳过全空行
            boolean allNull = vals.stream().allMatch(v -> v == null || String.valueOf(v).isBlank());
            if (!allNull) {
                rows.add(vals);
            }
        }
        return rows;
    }

    /** 按样本推断列类型(目标方言) */
    private List<String> inferExcelTypes(List<String> headers, List<List<Object>> data, String dbType) {
        List<String> types = new ArrayList<>();
        int sampleN = Math.min(data.size(), 50);
        for (int c = 0; c < headers.size(); c++) {
            boolean allInt = true, allNum = true, allDate = true, allBool = true;
            int maxLen = 0;
            int nonNull = 0;
            for (int r = 0; r < sampleN; r++) {
                List<Object> row = data.get(r);
                Object v = c < row.size() ? row.get(c) : null;
                if (v == null || String.valueOf(v).isBlank()) {
                    continue;
                }
                nonNull++;
                String sv = String.valueOf(v);
                maxLen = Math.max(maxLen, sv.length());
                if (!(v instanceof Long) && !(v instanceof Integer)) allInt = false;
                if (!(v instanceof Long) && !(v instanceof Integer) && !(v instanceof Double) && !(v instanceof Float)) allNum = false;
                if (!(v instanceof java.time.LocalDateTime)) allDate = false;
                if (!(v instanceof Boolean)) allBool = false;
            }
            if (nonNull == 0) {
                types.add("VARCHAR(255)");
                continue;
            }
            if (allBool) {
                if ("oracle".equals(dbType)) types.add("NUMBER(1)");
                else if ("postgresql".equals(dbType)) types.add("BOOLEAN");
                else types.add("TINYINT");
                continue;
            }
            if (allInt) {
                if ("oracle".equals(dbType)) types.add("NUMBER(19)");
                else types.add("BIGINT");
                continue;
            }
            if (allNum) {
                if ("oracle".equals(dbType)) types.add("NUMBER(18,4)");
                else if ("postgresql".equals(dbType)) types.add("NUMERIC(18,4)");
                else types.add("DECIMAL(18,4)");
                continue;
            }
            if (allDate) {
                if ("oracle".equals(dbType)) types.add("TIMESTAMP");
                else if ("postgresql".equals(dbType)) types.add("TIMESTAMP");
                else if ("sqlserver".equals(dbType)) types.add("DATETIME2");
                else types.add("DATETIME");
                continue;
            }
            if (maxLen <= 255) {
                if ("oracle".equals(dbType)) types.add("VARCHAR2(" + maxLen + ")");
                else if ("postgresql".equals(dbType)) types.add("VARCHAR(" + maxLen + ")");
                else if ("sqlserver".equals(dbType)) types.add("NVARCHAR(" + maxLen + ")");
                else types.add("VARCHAR(" + maxLen + ")");
            } else {
                if ("oracle".equals(dbType)) types.add("CLOB");
                else if ("postgresql".equals(dbType)) types.add("TEXT");
                else if ("sqlserver".equals(dbType)) types.add("NVARCHAR(MAX)");
                else types.add("TEXT");
            }
        }
        return types;
    }

    private String buildExcelTable(String dbType, String schema, String table, List<String> headers, List<String> types) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(qname(schema, table, dbType)).append(" (\n");
        String firstType = types.isEmpty() ? "" : types.get(0).toUpperCase();
        boolean firstIsInt = firstType.startsWith("BIGINT") || firstType.startsWith("INT");
        for (int i = 0; i < headers.size(); i++) {
            sb.append("  ").append(qcol(headers.get(i), dbType)).append(' ').append(types.get(i));
            if (i == 0) {
                if ("mysql".equals(dbType) && firstIsInt) {
                    sb.append(" PRIMARY KEY AUTO_INCREMENT");
                } else if ("mysql".equals(dbType)) {
                    sb.append(" PRIMARY KEY");
                } else {
                    sb.append(" PRIMARY KEY");
                }
            }
            sb.append(",\n");
        }
        sb.setLength(sb.length() - 2);
        sb.append("\n)");
        if ("mysql".equals(dbType)) {
            sb.append(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        return sb.toString();
    }

    private int insertExcelRows(DbConn conn, String schema, String table, List<String> headers,
                                List<List<Object>> data, String dbType) throws Exception {
        String tableQ = qname(schema, table, dbType);
        String insCols = headers.stream().map(h -> qcol(h, dbType)).collect(java.util.stream.Collectors.joining(", "));
        String placeholders = String.join(", ", java.util.Collections.nCopies(headers.size(), "?"));
        String ins = "INSERT INTO " + tableQ + " (" + insCols + ") VALUES (" + placeholders + ")";
        int moved = 0;
        try (Connection c = connectionManager.getConnection(conn);
             PreparedStatement ps = c.prepareStatement(ins)) {
            c.setAutoCommit(false);
            int batch = 0;
            for (List<Object> row : data) {
                for (int i = 0; i < headers.size(); i++) {
                    Object v = i < row.size() ? row.get(i) : null;
                    if (v instanceof java.time.LocalDateTime ldt) {
                        ps.setTimestamp(i + 1, java.sql.Timestamp.valueOf(ldt));
                    } else if (v instanceof Long || v instanceof Integer) {
                        ps.setLong(i + 1, ((Number) v).longValue());
                    } else if (v instanceof Double || v instanceof Float) {
                        ps.setDouble(i + 1, ((Number) v).doubleValue());
                    } else if (v instanceof Boolean b) {
                        if ("postgresql".equals(dbType)) {
                            ps.setBoolean(i + 1, b);
                        } else {
                            ps.setInt(i + 1, b ? 1 : 0);
                        }
                    } else {
                        ps.setString(i + 1, v == null ? null : String.valueOf(v));
                    }
                }
                ps.addBatch();
                batch++;
                if (batch >= 500) {
                    ps.executeBatch();
                    c.commit();
                    moved += batch;
                    batch = 0;
                }
            }
            if (batch > 0) {
                ps.executeBatch();
                c.commit();
                moved += batch;
            }
        }
        return moved;
    }
}