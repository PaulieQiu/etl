package pan.dromara.dbtool.core.dialect;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import java.util.Locale;

/**
 * Exasol 方言适配器(C.3.32 新增)
 * <p>分析型 MPP 数据库, 元数据取自 EXA_ALL_TABLES / EXA_ALL_COLUMNS / EXA_SCHEMATA;
 * 无传统索引目录与系统触发器目录, 对应接口返回恒空结果集并消费 (schema, table) 参数;
 * 标识符双引号且大小写敏感; 分页 LIMIT size OFFSET offset; 无 SHOW CREATE TABLE。
 * dbx exasol.yaml 对照实现, 属"已适配待实测"。</p>
 *
 * @author doubao
 */
public class ExasolDialectAdapter implements DbDialectAdapter {

    @Override
    public boolean supports(String dbType) {
        return dbType != null && "EXASOL".equalsIgnoreCase(dbType.trim());
    }

    @Override
    public String quote(String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    @Override
    public String databasesSql() {
        return "SELECT schema_name AS SCHEMA_NAME FROM exa_schemata ORDER BY schema_name";
    }

    @Override
    public String tablesSql() {
        return "SELECT table_name AS TABLE_NAME, "
            + "'' AS TABLE_COMMENT, "
            + "CASE WHEN table_type = 'VIEW' THEN 'VIEW' ELSE 'TABLE' END AS TABLE_TYPE, "
            + "'' AS ENGINE, "
            + "0 AS TABLE_ROWS, "
            + "'' AS CREATE_TIME "
            + "FROM exa_all_tables WHERE table_schema = ? ORDER BY table_type, table_name";
    }

    @Override
    public String columnsSql() {
        return "SELECT column_name AS COLUMN_NAME, "
            + "column_type AS DATA_TYPE, column_type AS COLUMN_TYPE, "
            + "CASE WHEN is_nullable = 'Y' OR is_nullable = 'TRUE' THEN 'YES' ELSE 'NO' END AS IS_NULLABLE, "
            + "COALESCE(column_default, '') AS COLUMN_DEFAULT, "
            + "'' AS COLUMN_COMMENT, "
            + "'' AS COLUMN_KEY, "
            + "'' AS EXTRA, "
            + "'' AS CHARACTER_SET_NAME, "
            + "'' AS COLLATION_NAME "
            + "FROM exa_all_columns WHERE column_schema = ? AND column_table = ? "
            + "ORDER BY column_ordinal_position";
    }

    @Override
    public String indexesSql() {
        // Exasol 无传统索引目录, 返回空结果集
        return emptyMeta("INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE, INDEX_TYPE, INDEX_COMMENT");
    }

    @Override
    public String constraintsSql() {
        // Exasol 系统目录无通用约束明细视图, 返回空结果集
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
        return emptyMeta("TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, ACTION_STATEMENT, CREATED");
    }

    /** 无对应目录的方言: 返回恒空结果集, 且消费上层固定传入的 (schema, table) 两个参数 */
    private String emptyMeta(String aliasList) {
        return "SELECT " + aliasList + " WHERE 1 = 0 AND ? IS NOT NULL AND ? IS NOT NULL";
    }

    @Override
    public String paginate(String sql, int offset, int size) {
        return sql + " LIMIT " + size + " OFFSET " + offset;
    }

    @Override
    public String showCreateTableSql(String schema, String table) {
        // Exasol 无 SHOW CREATE TABLE, 按列元数据重建
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        // Exasol 使用 schema 概念, 无 MySQL 风格 CREATE DATABASE
        return null;
    }

    @Override
    public String dropTableSql(String schema, String table) {
        return "DROP TABLE IF EXISTS " + quote(schema) + "." + quote(table);
    }

    @Override
    public String renameTableSql(String schema, String oldName, String newName) {
        return "ALTER TABLE " + quote(schema) + "." + quote(oldName) + " RENAME TO " + quote(newName);
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
        return sb.toString();
    }

    private String defaultLiteral(String dataType, String def) {
        if (dataType == null) {
            return "'" + def.replace("'", "''") + "'";
        }
        String dt = dataType.toLowerCase(Locale.ROOT);
        boolean numeric = dt.contains("int") || dt.contains("decimal") || dt.contains("numeric")
            || dt.contains("double") || dt.contains("float") || dt.contains("bool")
            || dt.contains("real") || dt.contains("bigint") || dt.contains("smallint")
            || dt.contains("money") || dt.contains("interval");
        return numeric ? def : "'" + def.replace("'", "''") + "'";
    }
}
