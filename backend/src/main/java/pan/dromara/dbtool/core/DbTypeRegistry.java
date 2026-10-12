package pan.dromara.dbtool.core;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据库类型注册表
 * <p>集中维护各数据库类型的分类/图标/端口/驱动/版本/URL模板，覆盖主流 SQL / NoSQL /
 * 大数据 / 分析型 / 时间序列 / 嵌入式 / 全文搜索 / 图数据库 等分类。</p>
 * <p>builtin=true 表示驱动已内置在应用 classpath 中（mysql-connector-j / postgresql），无需下载；</p>
 * <p>否则可从 Maven Central 自动下载，或手动上传 jar。</p>
 *
 * @author doubao
 */
public class DbTypeRegistry {

    private static final Map<String, DbTypeInfo> REGISTRY = new LinkedHashMap<>();

    static {
        // ============ SQL ============
        reg(new DbTypeInfo("MYSQL", "MySQL", "SQL", true, "mysql", 3306,
            "com.mysql.cj.jdbc.Driver", "com.mysql:mysql-connector-j", "8.4.0",
            List.of("8.4.0", "8.0.33", "8.0.30", "5.7.43"),
            "jdbc:mysql://{host}:{port}/{db}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true", true));
        reg(new DbTypeInfo("POSTGRESQL", "PostgreSQL", "SQL", true, "postgresql", 5432,
            "org.postgresql.Driver", "org.postgresql:postgresql", "42.7.3",
            List.of("42.7.3", "42.7.2", "42.6.0", "42.5.4"),
            "jdbc:postgresql://{host}:{port}/{db}", true));
        reg(new DbTypeInfo("MARIADB", "MariaDB", "SQL", false, "mariadb", 3306,
            "org.mariadb.jdbc.Driver", "org.mariadb.jdbc:mariadb-java-client", "3.3.3",
            List.of("3.3.3", "3.2.0", "3.1.4", "2.7.11"),
            "jdbc:mariadb://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("ORACLE", "Oracle", "SQL", true, "oracle", 1521,
            "oracle.jdbc.OracleDriver", "com.oracle.database.jdbc:ojdbc11", "23.3.0.23.09",
            List.of("23.3.0.23.09", "21.9.0.0", "19.23.0.0", "12.2.0.1"),
            "jdbc:oracle:thin:@//{host}:{port}/{db}", false));
        reg(new DbTypeInfo("SQLSERVER", "SQL Server", "SQL", true, "sqlserver", 1433,
            "com.microsoft.sqlserver.jdbc.SQLServerDriver", "com.microsoft.sqlserver:mssql-jdbc", "12.4.2.jre11",
            List.of("12.4.2.jre11", "12.2.0.jre11", "11.2.3.jre17", "10.2.3.jre8"),
            "jdbc:sqlserver://{host}:{port};databaseName={db};encrypt=false", false));
        reg(new DbTypeInfo("AZURESQL", "Azure SQL Server", "SQL", false, "azuresql", 1433,
            "com.microsoft.sqlserver.jdbc.SQLServerDriver", "com.microsoft.sqlserver:mssql-jdbc", "12.4.2.jre11",
            List.of("12.4.2.jre11", "12.2.0.jre11"),
            "jdbc:sqlserver://{host}:{port};databaseName={db};encrypt=true;trustServerCertificate=true", false));
        reg(new DbTypeInfo("DB2", "DB2 for LUW", "SQL", false, "db2", 50000,
            "com.ibm.db2.jcc.DB2Driver", "com.ibm.db2:jcc", "11.5.9.0",
            List.of("11.5.9.0", "11.5.8.0"),
            "jdbc:db2://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("DB2I", "Db2 for IBM i", "SQL", false, "db2i", 446,
            "com.ibm.as400.access.AS400JDBCDriver", "net.sf.jt400:jt400", "20.0.7",
            List.of("20.0.7", "11.0"),
            "jdbc:as400://{host}/{db}", false));
        reg(new DbTypeInfo("DAMENG", "达梦 DM", "SQL", false, "dameng", 5236,
            "dm.jdbc.driver.DmDriver", "com.dameng:DmJdbcDriver18", "8.1.3.140",
            List.of("8.1.3.140", "8.1.2.192"),
            "jdbc:dm://{host}:{port}", false));
        reg(new DbTypeInfo("KINGBASE", "人大金仓", "SQL", false, "kingbase", 54321,
            "com.kingbase8.Driver", "cn.com.kingbase:kingbase8", "8.6.0",
            List.of("8.6.0", "8.2.0"),
            "jdbc:kingbase8://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("OCEANBASE", "OceanBase", "SQL", false, "oceanbase", 2881,
            "com.oceanbase.jdbc.Driver", "com.oceanbase:oceanbase-client", "2.4.9",
            List.of("2.4.9", "2.4.5"),
            "jdbc:oceanbase://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("TIDB", "TiDB", "SQL", false, "tidb", 4000,
            "com.mysql.cj.jdbc.Driver", "com.mysql:mysql-connector-j", "8.4.0",
            List.of("8.4.0", "8.0.33"),
            "jdbc:mysql://{host}:{port}/{db}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", true));
        reg(new DbTypeInfo("GBASE", "南大通用 GBase", "SQL", false, "gbase", 5258,
            "com.gbase.jdbc.Driver", "com.gbase:gbase-connector-java", "8.3.81.53",
            List.of("8.3.81.53"),
            "jdbc:gbase://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("HIGRES", "瀚高 HighGo", "SQL", false, "higres", 5866,
            "com.highgo.jdbc.Driver", "cn.com.highgo:highgojdbc", "6.0.5",
            List.of("6.0.5"),
            "jdbc:highgo://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("COCKROACHDB", "CockroachDB", "SQL", false, "cockroachdb", 26257,
            "org.postgresql.Driver", "org.postgresql:postgresql", "42.7.3",
            List.of("42.7.3"),
            "jdbc:postgresql://{host}:{port}/{db}", true));
        reg(new DbTypeInfo("YUGABYTEDB", "YugabyteDB", "SQL", false, "yugabytedb", 5433,
            "org.postgresql.Driver", "org.postgresql:postgresql", "42.7.3",
            List.of("42.7.3"),
            "jdbc:postgresql://{host}:{port}/{db}", true));
        reg(new DbTypeInfo("GREENPLUM", "Greenplum", "SQL", false, "greenplum", 5432,
            "org.postgresql.Driver", "org.postgresql:postgresql", "42.7.3",
            List.of("42.7.3"),
            "jdbc:postgresql://{host}:{port}/{db}", true));
        reg(new DbTypeInfo("EDB", "EDB Postgres", "SQL", false, "edb", 5444,
            "org.postgresql.Driver", "org.postgresql:postgresql", "42.7.3",
            List.of("42.7.3"),
            "jdbc:postgresql://{host}:{port}/{db}", true));
        reg(new DbTypeInfo("FIREBIRD", "Firebird", "SQL", false, "firebird", 3050,
            "org.firebirdsql.jdbc.FBDriver", "org.firebirdsql.jdbc:jaybird", "5.0.4.java11",
            List.of("5.0.4.java11", "4.0.11.java11"),
            "jdbc:firebirdsql://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("HSQLDB", "HSQLDB", "SQL", false, "hsqldb", 9001,
            "org.hsqldb.jdbc.JDBCDriver", "org.hsqldb:hsqldb", "2.7.2",
            List.of("2.7.2", "2.6.1"),
            "jdbc:hsqldb:hsql://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("DERBY", "Derby", "SQL", false, "derby", 1527,
            "org.apache.derby.jdbc.ClientDriver", "org.apache.derby:derbyclient", "10.16.1.1",
            List.of("10.16.1.1", "10.15.2.0"),
            "jdbc:derby://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("MONETDB", "MonetDB", "SQL", false, "monetdb", 50000,
            "nl.cwi.monetdb.jdbc.MonetDriver", "org.monetdb:monetdb-jdbc", "3.7",
            List.of("3.7"),
            "jdbc:monetdb://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("INFORMIX", "Informix", "SQL", false, "informix", 9088,
            "com.informix.jdbc.IfxDriver", "com.ibm.informix:ifxjdbc", "4.50.10",
            List.of("4.50.10"),
            "jdbc:informix-sqli://{host}:{port}/{db}:INFORMIXSERVER=myserver", false));
        reg(new DbTypeInfo("HANA", "SAP HANA", "SQL", false, "hana", 30015,
            "com.sap.db.jdbc.Driver", "com.sap.cloud.db.jdbc:ngdbc", "2.20.16",
            List.of("2.20.16", "2.19.22"),
            "jdbc:sap://{host}:{port}?databaseName={db}", false));
        reg(new DbTypeInfo("SYBASE", "Sybase ASE", "SQL", false, "sybase", 5000,
            "com.sybase.jdbc4.jdbc.SybDriver", "net.sourceforge.jtds:jtds", "1.3.1",
            List.of("1.3.1"),
            "jdbc:jtds:sybase://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("MSACCESS", "MS Access", "SQL", false, "msaccess", 0,
            "net.ucanaccess.jdbc.UcanaccessDriver", "net.sf.ucanaccess:ucanaccess", "5.0.1",
            List.of("5.0.1"),
            "jdbc:ucanaccess://{db}", false));
        reg(new DbTypeInfo("CUBRID", "CUBRID", "SQL", false, "cubrid", 33000,
            "cubrid.jdbc.driver.CUBRIDDriver", "org.cubrid:cubrid-jdbc", "10.2.2.8879",
            List.of("10.2.2.8879"),
            "jdbc:cubrid:{host}:{port}:{db}", false));
        reg(new DbTypeInfo("EXASOL", "Exasol", "SQL", false, "exasol", 8563,
            "com.exasol.jdbc.EXADriver", "com.exasol:exasol-jdbc", "7.1.20",
            List.of("7.1.20"),
            "jdbc:exa:{host}:{port}", false));

        // ============ NoSQL ============
        reg(new DbTypeInfo("MONGODB", "MongoDB", "NoSQL", true, "mongodb", 27017,
            "", "org.mongodb:mongodb-driver-sync", "4.11.1",
            List.of("4.11.1", "4.9.1"),
            "mongodb://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("REDIS", "Redis", "NoSQL", true, "redis", 6379,
            "", "redis.clients:jedis", "5.1.0",
            List.of("5.1.0", "4.4.6"),
            "redis://{host}:{port}/{db}", false));

        // ============ 图数据库 ============
        reg(new DbTypeInfo("NEO4J", "Neo4j", "图数据库", false, "neo4j", 7687,
            "org.neo4j.jdbc.bolt.BoltDriver", "org.neo4j:neo4j-jdbc", "6.0.2",
            List.of("6.0.2", "5.1.0"),
            "jdbc:neo4j:bolt://{host}:{port}/", false));
        reg(new DbTypeInfo("ORIENTDB", "OrientDB", "图数据库", false, "orientdb", 2424,
            "com.orientechnologies.orient.jdbc.OrientJdbcDriver", "com.orientechnologies:orientdb-jdbc", "3.2.25",
            List.of("3.2.25"),
            "jdbc:orient:remote:{host}:{port}/{db}", false));

        // ============ 大数据 ============
        reg(new DbTypeInfo("HIVE", "Apache Hive", "大数据", false, "hive", 10000,
            "org.apache.hive.jdbc.HiveDriver", "org.apache.hive:hive-jdbc", "3.1.3",
            List.of("3.1.3", "2.3.9"),
            "jdbc:hive2://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("SPARK", "Apache Spark", "大数据", false, "spark", 10001,
            "org.apache.hive.jdbc.HiveDriver", "org.apache.hive:hive-jdbc", "3.1.3",
            List.of("3.1.3"),
            "jdbc:hive2://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("PHOENIX", "Apache Phoenix", "大数据", false, "phoenix", 8765,
            "org.apache.phoenix.jdbc.PhoenixDriver", "org.apache.phoenix:phoenix-client", "5.1.3",
            List.of("5.1.3"),
            "jdbc:phoenix:{host}:{port}:/{db}", false));
        reg(new DbTypeInfo("IGNITE", "Apache Ignite", "大数据", false, "ignite", 10800,
            "org.apache.ignite.IgniteJdbcDriver", "org.apache.ignite:ignite-core", "2.16.0",
            List.of("2.16.0"),
            "jdbc:ignite:thin://{host}:{port}", false));
        reg(new DbTypeInfo("IMPALA", "Cloudera Impala", "大数据", false, "impala", 21050,
            "com.cloudera.impala.jdbc.Driver", "com.cloudera.impala:ImpalaJDBC42", "2.6.30",
            List.of("2.6.30"),
            "jdbc:impala://{host}:{port}/{db}", false));

        // ============ 分析型 ============
        reg(new DbTypeInfo("CLICKHOUSE", "ClickHouse", "分析型", false, "clickhouse", 8123,
            "com.clickhouse.jdbc.ClickHouseDriver", "com.clickhouse:clickhouse-jdbc", "0.5.0",
            List.of("0.5.0", "0.4.6"),
            "jdbc:clickhouse://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("DORIS", "Apache Doris", "分析型", false, "doris", 9030,
            "com.mysql.cj.jdbc.Driver", "com.mysql:mysql-connector-j", "8.0.33",
            List.of("8.0.33"),
            "jdbc:mysql://{host}:{port}/{db}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true", true));
        reg(new DbTypeInfo("STARROCKS", "StarRocks", "分析型", false, "starrocks", 9030,
            "com.mysql.cj.jdbc.Driver", "com.mysql:mysql-connector-j", "8.0.33",
            List.of("8.0.33"),
            "jdbc:mysql://{host}:{port}/{db}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true", true));
        reg(new DbTypeInfo("DUCKDB", "DuckDB", "分析型", false, "duckdb", 0,
            "org.duckdb.DuckDBDriver", "org.duckdb:duckdb_jdbc", "1.0.0",
            List.of("1.0.0", "0.10.2"),
            "jdbc:duckdb:{db}", false));
        reg(new DbTypeInfo("REDSHIFT", "Amazon Redshift", "分析型", false, "redshift", 5439,
            "com.amazon.redshift.jdbc.Driver", "com.amazon.redshift:redshift-jdbc42", "2.1.0.31",
            List.of("2.1.0.31"),
            "jdbc:redshift://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("SNOWFLAKE", "Snowflake", "分析型", false, "snowflake", 443,
            "net.snowflake.client.jdbc.SnowflakeDriver", "net.snowflake:snowflake-jdbc", "3.16.0",
            List.of("3.16.0"),
            "jdbc:snowflake://{host}.snowflakecomputing.com/?db={db}", false));
        reg(new DbTypeInfo("TERADATA", "Teradata", "分析型", false, "teradata", 1025,
            "com.teradata.jdbc.TeraDriver", "com.teradata.jdbc:terajdbc4", "17.20.00.12",
            List.of("17.20.00.12"),
            "jdbc:teradata://{host}/DATABASE={db}", false));
        reg(new DbTypeInfo("VERTICA", "Vertica", "分析型", false, "vertica", 5433,
            "com.vertica.jdbc.Driver", "com.vertica:vertica-jdbc", "24.2.0",
            List.of("24.2.0"),
            "jdbc:vertica://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("TRINO", "Trino", "分析型", false, "trino", 8080,
            "io.trino.jdbc.TrinoDriver", "io.trino:trino-jdbc", "448",
            List.of("448", "437"),
            "jdbc:trino://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("PRESTO", "PrestoDB", "分析型", false, "presto", 8080,
            "io.prestosql.jdbc.PrestoDriver", "io.prestosql:prestosql-jdbc", "340",
            List.of("340"),
            "jdbc:presto://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("DREMIO", "Dremio", "分析型", false, "dremio", 31010,
            "com.dremio.jdbc.Driver", "com.dremio.distribution:dremio-jdbc-driver", "24.3.2",
            List.of("24.3.2"),
            "jdbc:dremio:direct={host}:{port};schema={db}", false));
        reg(new DbTypeInfo("KYLIN", "Apache Kylin", "分析型", false, "kylin", 7070,
            "org.apache.kylin.jdbc.Driver", "org.apache.kylin:kylin-jdbc", "4.0.0",
            List.of("4.0.0"),
            "jdbc:kylin://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("CRATEDB", "CrateDB", "分析型", false, "cratedb", 5432,
            "io.crate.client.jdbc.CrateDriver", "io.crate:crate-jdbc", "2.7.1",
            List.of("2.7.1"),
            "jdbc:crate://{host}:{port}", false));

        // ============ 时间序列 ============
        reg(new DbTypeInfo("INFLUXDB", "InfluxDB", "时间序列", false, "influxdb", 8086,
            "", "com.influxdb:influxdb-client-java", "6.11.0",
            List.of("6.11.0"),
            "http://{host}:{port}", false));
        reg(new DbTypeInfo("TDENGINE", "TDengine", "时间序列", false, "tdengine", 6030,
            "com.taosdata.jdbc.TSDBDriver", "com.taosdata.jdbc:taos-jdbcdriver", "3.3.1",
            List.of("3.3.1", "3.2.7"),
            "jdbc:TAOS://{host}:{port}/{db}", false));
        reg(new DbTypeInfo("TIMESCALEDB", "TimescaleDB", "时间序列", false, "timescaledb", 5432,
            "org.postgresql.Driver", "org.postgresql:postgresql", "42.7.3",
            List.of("42.7.3"),
            "jdbc:postgresql://{host}:{port}/{db}", true));

        // ============ 嵌入式 ============
        reg(new DbTypeInfo("SQLITE", "SQLite", "嵌入式", true, "sqlite", 0,
            "org.sqlite.JDBC", "org.xerial:sqlite-jdbc", "3.45.3.0",
            List.of("3.45.3.0", "3.44.1.0", "3.42.0.0"),
            "jdbc:sqlite:{db}", false));
        reg(new DbTypeInfo("H2", "H2", "嵌入式", false, "h2", 9092,
            "org.h2.Driver", "com.h2database:h2", "2.2.224",
            List.of("2.2.224", "2.1.214"),
            "jdbc:h2:tcp://{host}:{port}/{db}", false));

        // ============ 全文搜索 ============
        // Elasticsearch 官方 SQL JDBC 驱动(x-pack-sql-jdbc), DBeaver 等工具同款接入方案
        reg(new DbTypeInfo("ELASTICSEARCH", "Elasticsearch", "全文搜索", false, "elasticsearch", 9200,
            "org.elasticsearch.xpack.sql.jdbc.EsDriver", "org.elasticsearch.plugin:x-pack-sql-jdbc", "8.13.4",
            List.of("8.13.4", "8.11.4", "7.17.19"),
            "jdbc:es://http://{host}:{port}", false));
    }

    private static void reg(DbTypeInfo info) {
        REGISTRY.put(info.getCode(), info);
    }

    /**
     * 已落地 DbDialectAdapter 的方言族(元数据/分页/DDL 走适配器; 除 MySQL 系外均为"已适配待实测")
     */
    private static final Set<String> DIALECT_ADAPTED = Set.of(
        // MySQL 协议兼容族
        "MYSQL", "MARIADB", "TIDB", "DORIS", "STARROCKS", "OCEANBASE",
        // PostgreSQL 协议兼容族(C.3.30: 国产 PG + 衍生/云)
        "POSTGRESQL", "KINGBASE", "HIGHGO", "HIGRES", "GBASE", "GREENPLUM",
        "EDB", "COCKROACHDB", "YUGABYTEDB", "TIMESCALEDB", "REDSHIFT",
        "ORACLE", "SQLSERVER", "DAMENG",
        // C.3.30 新增独立适配器
        "CLICKHOUSE", "SQLITE", "H2", "DUCKDB",
        // C.3.32 新增独立适配器 + GBase 8a 归 MySQL 族
        "VERTICA", "EXASOL", "FIREBIRD",
        // C.3.34 新增独立适配器 + Azure SQL 归 SqlServer 族
        "AZURESQL", "HSQLDB", "DB2"
    );

    /**
     * 真实数据库链路已验证的类型(MySQL 本地实测; Redis/ES 专属能力实测)
     */
    private static final Set<String> VERIFIED = Set.of("MYSQL", "MARIADB", "REDIS", "ELASTICSEARCH");

    /**
     * 网格安全写入(DbGridChangePlanner)当前覆盖
     */
    private static final Set<String> GRID_EDIT = Set.of("MYSQL", "MARIADB");

    /**
     * Schema Diff 当前覆盖
     */
    private static final Set<String> SCHEMA_DIFF = Set.of("MYSQL", "MARIADB");

    /**
     * 能力矩阵推断(P0 落地): connect 恒有;
     * SQL 类 + query; 方言族 + browse/ddl/dialect; 已验证 + verified; MySQL 系 + edit/diff;
     * Redis 专属 browse; Elasticsearch + query/browse。
     */
    public static List<String> capabilitiesOf(String code) {
        String c = code == null ? "" : code.trim().toUpperCase();
        List<String> caps = new ArrayList<>();
        caps.add("connect");
        if ("REDIS".equals(c)) {
            caps.add("browse");
            caps.add("verified");
            return caps;
        }
        if ("ELASTICSEARCH".equals(c)) {
            caps.add("query");
            caps.add("browse");
            caps.add("verified");
            return caps;
        }
        DbTypeInfo info = REGISTRY.get(c);
        if (info == null || !"SQL".equals(info.getCategory())) {
            return caps;
        }
        caps.add("query");
        if (DIALECT_ADAPTED.contains(c)) {
            caps.add("browse");
            caps.add("ddl");
            caps.add("dialect");
        }
        if (VERIFIED.contains(c)) {
            caps.add("verified");
        }
        if (GRID_EDIT.contains(c)) {
            caps.add("edit");
        }
        if (SCHEMA_DIFF.contains(c)) {
            caps.add("diff");
        }
        return caps;
    }

    /**
     * 获取全部类型(保持注册顺序); 顺带填充能力矩阵(capabilities 为不变值, 幂等)
     */
    public static List<DbTypeInfo> listAll() {
        for (DbTypeInfo info : REGISTRY.values()) {
            info.setCapabilities(capabilitiesOf(info.getCode()));
        }
        return new ArrayList<>(REGISTRY.values());
    }

    /**
     * 获取所有分类(有序去重)
     */
    public static List<String> listCategories() {
        List<String> cats = new ArrayList<>();
        for (DbTypeInfo info : REGISTRY.values()) {
            if (!cats.contains(info.getCategory())) {
                cats.add(info.getCategory());
            }
        }
        return cats;
    }

    /**
     * 按编码查询
     */
    public static DbTypeInfo of(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        return REGISTRY.get(code.trim().toUpperCase());
    }
}
