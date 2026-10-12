package pan.dromara.dbtool.core.dialect;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import java.util.Locale;
import java.util.Set;

/**
 * MySQL / MariaDB 方言(默认适配器)
 * <p>保持历史行为: information_schema 元数据、LIMIT offset,size 分页、反引号标识符、
 * AUTO_INCREMENT/COMMENT 列定义、SHOW CREATE TABLE 与 CREATE DATABASE IF NOT EXISTS。
 * TiDB/Doris/StarRocks 兼容 MySQL 协议, 同样适用本适配器。</p>
 *
 * @author doubao
 */
public class MySqlDialectAdapter implements DbDialectAdapter {

    private static final Set<String> NUMERIC_TYPES = Set.of(
        "int", "tinyint", "smallint", "mediumint", "bigint", "float", "double", "decimal", "bit", "year"
    );

    @Override
    public boolean supports(String dbType) {
        String t = dbType == null ? "" : dbType;
        // MySQL 协议兼容族: MariaDB/TiDB/Doris/StarRocks 兼容 information_schema 与 LIMIT offset,size;
        // OceanBase(MySQL 模式) 同属本族(C.3.30); GBase 8a(南大通用, dbx gbase.yaml 证实
        // 反引号/INFORMATION_SCHEMA/LIMIT 分页) 自 C.3.32 起归入本族(修正自 C.3.30 的 PG 误归)。
        return "mysql".equalsIgnoreCase(t) || "mariadb".equalsIgnoreCase(t)
            || "tidb".equalsIgnoreCase(t) || "doris".equalsIgnoreCase(t) || "starrocks".equalsIgnoreCase(t)
            || "oceanbase".equalsIgnoreCase(t) || "gbase".equalsIgnoreCase(t);
    }

    @Override
    public String quote(String name) {
        return "`" + (name == null ? "" : name.replace("`", "``")) + "`";
    }

    @Override
    public String databasesSql() {
        return "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME";
    }

    @Override
    public String tablesSql() {
        return "SELECT TABLE_NAME, IFNULL(TABLE_COMMENT,'') AS TABLE_COMMENT, TABLE_TYPE, IFNULL(ENGINE,'') AS ENGINE, "
            + "IFNULL(TABLE_ROWS,0) AS TABLE_ROWS, IFNULL(CREATE_TIME,'') AS CREATE_TIME "
            + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_TYPE, TABLE_NAME";
    }

    @Override
    public String columnsSql() {
        return "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, IFNULL(COLUMN_DEFAULT, '') AS COLUMN_DEFAULT, "
            + "IFNULL(COLUMN_COMMENT,'') AS COLUMN_COMMENT, IFNULL(COLUMN_KEY,'') AS COLUMN_KEY, IFNULL(EXTRA,'') AS EXTRA, "
            + "IFNULL(CHARACTER_SET_NAME,'') AS CHARACTER_SET_NAME, IFNULL(COLLATION_NAME,'') AS COLLATION_NAME "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
    }

    @Override
    public String indexesSql() {
        return "SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE, INDEX_TYPE, IFNULL(INDEX_COMMENT,'') AS INDEX_COMMENT "
            + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX";
    }

    @Override
    public String constraintsSql() {
        return "SELECT tc.CONSTRAINT_NAME, tc.CONSTRAINT_TYPE, kcu.COLUMN_NAME, kcu.ORDINAL_POSITION "
            + "FROM information_schema.TABLE_CONSTRAINTS tc "
            + "LEFT JOIN information_schema.KEY_COLUMN_USAGE kcu "
            + "ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME AND tc.TABLE_SCHEMA = kcu.TABLE_SCHEMA AND tc.TABLE_NAME = kcu.TABLE_NAME "
            + "WHERE tc.TABLE_SCHEMA = ? AND tc.TABLE_NAME = ? ORDER BY tc.CONSTRAINT_NAME, kcu.ORDINAL_POSITION";
    }

    @Override
    public String foreignKeysSql() {
        return "SELECT kcu.CONSTRAINT_NAME, kcu.TABLE_NAME, kcu.COLUMN_NAME, kcu.ORDINAL_POSITION, "
            + "kcu.REFERENCED_TABLE_NAME, kcu.REFERENCED_COLUMN_NAME, "
            + "rc.UPDATE_RULE, rc.DELETE_RULE "
            + "FROM information_schema.KEY_COLUMN_USAGE kcu "
            + "LEFT JOIN information_schema.REFERENTIAL_CONSTRAINTS rc "
            + "ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND kcu.TABLE_SCHEMA = rc.CONSTRAINT_SCHEMA "
            + "WHERE kcu.TABLE_SCHEMA = ? AND kcu.TABLE_NAME = ? AND kcu.REFERENCED_TABLE_NAME IS NOT NULL "
            + "ORDER BY kcu.CONSTRAINT_NAME, kcu.ORDINAL_POSITION";
    }

    @Override
    public String referencesSql() {
        return "SELECT kcu.CONSTRAINT_NAME, kcu.TABLE_NAME, kcu.COLUMN_NAME, kcu.ORDINAL_POSITION, "
            + "kcu.REFERENCED_TABLE_NAME, kcu.REFERENCED_COLUMN_NAME, "
            + "rc.UPDATE_RULE, rc.DELETE_RULE "
            + "FROM information_schema.KEY_COLUMN_USAGE kcu "
            + "LEFT JOIN information_schema.REFERENTIAL_CONSTRAINTS rc "
            + "ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND kcu.TABLE_SCHEMA = rc.CONSTRAINT_SCHEMA "
            + "WHERE kcu.TABLE_SCHEMA = ? AND kcu.REFERENCED_TABLE_NAME = ? "
            + "ORDER BY kcu.TABLE_NAME, kcu.CONSTRAINT_NAME, kcu.ORDINAL_POSITION";
    }

    @Override
    public String triggersSql() {
        return "SELECT TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, "
            + "ACTION_STATEMENT, CREATED "
            + "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ? AND EVENT_OBJECT_TABLE = ? "
            + "ORDER BY TRIGGER_NAME";
    }

    @Override
    public String paginate(String sql, int offset, int size) {
        return sql + " LIMIT " + offset + ", " + size;
    }

    @Override
    public String showCreateTableSql(String schema, String table) {
        return "SHOW CREATE TABLE " + quote(schema) + "." + quote(table);
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        return "CREATE DATABASE IF NOT EXISTS " + quote(dbName)
            + (StrUtil.isNotBlank(charset) ? " DEFAULT CHARACTER SET " + charset : "");
    }

    @Override
    public String dropTableSql(String schema, String table) {
        return "DROP TABLE IF EXISTS " + quote(schema) + "." + quote(table);
    }

    @Override
    public String renameTableSql(String schema, String oldName, String newName) {
        return "RENAME TABLE " + quote(schema) + "." + quote(oldName) + " TO " + quote(schema) + "." + quote(newName);
    }

    @Override
    public String addColumnSql(String schema, String table, String colDdl) {
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " ADD COLUMN " + colDdl;
    }

    @Override
    public String dropColumnSql(String schema, String table, String column) {
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " DROP COLUMN " + quote(column);
    }

    @Override
    public String modifyColumnSql(String schema, String table, String oldColumnName, DbColumnVo column) {
        boolean rename = StrUtil.isNotBlank(oldColumnName) && !oldColumnName.equals(column.getColumnName());
        return "ALTER TABLE " + quote(schema) + "." + quote(table)
            + (rename ? " CHANGE COLUMN " + quote(oldColumnName) + " " : " MODIFY COLUMN ")
            + columnDdl(column);
    }

    @Override
    public String columnDdl(DbColumnVo c) {
        StringBuilder sb = new StringBuilder(quote(c.getColumnName()));
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
}
