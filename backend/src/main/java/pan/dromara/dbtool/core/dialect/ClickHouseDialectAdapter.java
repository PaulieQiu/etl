package pan.dromara.dbtool.core.dialect;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import java.util.Locale;

/**
 * ClickHouse 方言适配器(C.3.30 新增)
 * <p>元数据基于 system 库(system.databases/tables/columns/data_skipping_indices);
 * 分页兼容 LIMIT offset,size; 标识符反引号; 无外键/约束/触发器目录(返回空结果集)。
 * CH 支持 SHOW CREATE TABLE 与 RENAME TABLE; 列定义支持 DEFAULT 与 COMMENT 列级语法,
 * 无 AUTO_INCREMENT 语义(自增列由引擎内置, 不适用列级自增)。
 * 在线 DDL(ALTER ... MODIFY COLUMN)在新版本可用, 旧版本可能受限, 标注"已适配待实测"。</p>
 *
 * @author doubao
 */
public class ClickHouseDialectAdapter implements DbDialectAdapter {

    @Override
    public boolean supports(String dbType) {
        return dbType != null && "CLICKHOUSE".equalsIgnoreCase(dbType.trim());
    }

    @Override
    public String quote(String name) {
        return "`" + (name == null ? "" : name.replace("`", "``")) + "`";
    }

    @Override
    public String databasesSql() {
        return "SELECT name FROM system.databases ORDER BY name";
    }

    @Override
    public String tablesSql() {
        return "SELECT name AS TABLE_NAME, comment AS TABLE_COMMENT, 'BASE TABLE' AS TABLE_TYPE, "
            + "engine AS ENGINE, COALESCE(total_rows,0) AS TABLE_ROWS, '' AS CREATE_TIME "
            + "FROM system.tables WHERE database = ? ORDER BY name";
    }

    @Override
    public String columnsSql() {
        return "SELECT name AS COLUMN_NAME, type AS DATA_TYPE, type AS COLUMN_TYPE, "
            + "COALESCE(is_nullable,'') AS IS_NULLABLE, COALESCE(default_expression,'') AS COLUMN_DEFAULT, "
            + "comment AS COLUMN_COMMENT, '' AS COLUMN_KEY, '' AS EXTRA, "
            + "'' AS CHARACTER_SET_NAME, '' AS COLLATION_NAME "
            + "FROM system.columns WHERE database = ? AND table = ? ORDER BY position";
    }

    @Override
    public String indexesSql() {
        // CH 跳数索引(数据跳过索引), 无普通 B-tree 索引目录
        return "SELECT index_name AS INDEX_NAME, '' AS COLUMN_NAME, 1 AS SEQ_IN_INDEX, 0 AS NON_UNIQUE, "
            + "'SKIP' AS INDEX_TYPE, '' AS INDEX_COMMENT "
            + "FROM system.data_skipping_indices WHERE database = ? AND table = ? ORDER BY index_name";
    }

    @Override
    public String constraintsSql() {
        return emptyMeta("CONSTRAINT_NAME, CONSTRAINT_TYPE, COLUMN_NAME, ORDINAL_POSITION");
    }

    @Override
    public String foreignKeysSql() {
        return emptyMeta("CONSTRAINT_NAME, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, "
            + "REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME, UPDATE_RULE, DELETE_RULE");
    }

    @Override
    public String referencesSql() {
        return emptyMeta("CONSTRAINT_NAME, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, "
            + "REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME, UPDATE_RULE, DELETE_RULE");
    }

    @Override
    public String triggersSql() {
        return emptyMeta("TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, "
            + "EVENT_OBJECT_TABLE, ACTION_STATEMENT, CREATED");
    }

    /** 无对应目录的方言: 返回恒空结果集, 且消费上层固定传入的 (schema, table) 两个参数 */
    private String emptyMeta(String aliasList) {
        return "SELECT " + aliasList + " FROM system.one WHERE 1=0 AND ? = ?";
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
        // CH 建库无字符集参数(引擎级配置), 忽略 charset
        return "CREATE DATABASE IF NOT EXISTS " + quote(dbName);
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
        if (rename) {
            return null;
        }
        String type = StrUtil.isNotBlank(column.getColumnType()) ? column.getColumnType() : column.getDataType();
        if (StrUtil.isBlank(type)) {
            throw new ServiceException("列类型不能为空");
        }
        return "ALTER TABLE " + quote(schema) + "." + quote(table)
            + " MODIFY COLUMN " + quote(column.getColumnName()) + " " + type;
    }

    @Override
    public String columnDdl(DbColumnVo c) {
        String type = StrUtil.isNotBlank(c.getColumnType()) ? c.getColumnType() : c.getDataType();
        if (StrUtil.isBlank(type)) {
            throw new ServiceException("列类型不能为空");
        }
        StringBuilder sb = new StringBuilder(quote(c.getColumnName())).append(' ').append(type);
        boolean notNull = c.getNullable() != null && "NO".equalsIgnoreCase(c.getNullable());
        sb.append(notNull ? " NOT NULL" : " NULL");
        String def = c.getDefaultValue();
        if (def != null && !def.isEmpty() && !"NULL".equalsIgnoreCase(def)) {
            sb.append(" DEFAULT ").append(defaultLiteral(c.getDataType(), def));
        }
        if (StrUtil.isNotBlank(c.getColumnComment())) {
            sb.append(" COMMENT '").append(c.getColumnComment().replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    private String defaultLiteral(String dataType, String def) {
        if (dataType == null) {
            return "'" + def.replace("'", "''") + "'";
        }
        String dt = dataType.toLowerCase(Locale.ROOT);
        boolean numeric = dt.contains("int") || dt.contains("decimal") || dt.contains("float")
            || dt.contains("double") || dt.contains("number") || dt.contains("uint") || dt.contains("uuid")
            || dt.contains("date") || dt.contains("time") || dt.contains("bool");
        return numeric ? def : "'" + def.replace("'", "''") + "'";
    }
}
