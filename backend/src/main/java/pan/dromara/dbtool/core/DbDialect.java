package pan.dromara.dbtool.core;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

/**
 * 数据库方言(驱动与 JDBC URL 模板)
 *
 * @author doubao
 */
public enum DbDialect {

    MYSQL("com.mysql.cj.jdbc.Driver",
        "jdbc:mysql://{host}:{port}/{db}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true"),
    POSTGRESQL("org.postgresql.Driver",
        "jdbc:postgresql://{host}:{port}/{db}"),
    ORACLE("oracle.jdbc.OracleDriver",
        "jdbc:oracle:thin:@//{host}:{port}/{db}"),
    SQLSERVER("com.microsoft.sqlserver.jdbc.SQLServerDriver",
        "jdbc:sqlserver://{host}:{port};databaseName={db};encrypt=false");

    private final String driverClass;
    private final String urlTemplate;

    DbDialect(String driverClass, String urlTemplate) {
        this.driverClass = driverClass;
        this.urlTemplate = urlTemplate;
    }

    public String getDriverClass() {
        return driverClass;
    }

    /**
     * 根据数据库类型获取方言, 默认 MYSQL
     */
    public static DbDialect of(String dbType) {
        if (StrUtil.isBlank(dbType)) {
            return MYSQL;
        }
        try {
            return valueOf(dbType.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return MYSQL;
        }
    }

    /**
     * 构建 JDBC URL
     *
     * @param host   主机
     * @param port   端口
     * @param db     数据库名(可为空)
     * @param params 扩展参数(可选, 追加到 url)
     */
    public String buildUrl(String host, int port, String db, String params) {
        String url = urlTemplate
            .replace("{host}", host)
            .replace("{port}", String.valueOf(port))
            .replace("{db}", StrUtil.blankToDefault(db, ""));
        if (StrUtil.isNotBlank(params)) {
            String sep = url.contains("?") ? "&" : "?";
            url = url + sep + params.trim().replaceAll("^[?&]+", "");
        }
        return url;
    }
}
