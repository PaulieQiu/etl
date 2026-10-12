package pan.dromara.dbtool.core.dialect;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import java.util.Locale;

/**
 * Vertica 方言适配器(C.3.32 新增)
 * <p>分析型列存数据库, 元数据取自 v_catalog(tables/columns/schemata);
 * 无传统索引目录(投影替代)、无触发器, 对应接口返回恒空结果集并消费 (schema, table) 参数;
 * 标识符双引号且大小写敏感; 分页 LIMIT size OFFSET offset; 无 SHOW CREATE TABLE,
 * 建表由列元数据重建。dbx vertica.yaml 对照实现, 属"已适配待实测"。</p>
 *
 * @author doubao
 */
public class VerticaDialectAdapter implements DbDialectAdapter {

    @Override
    public boolean supports(String dbType) {
        return dbType != null && "VERTICA".equalsIgnoreCase(dbType.trim());
    }

    @Override
    public String quote(String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    @Override
    public String databasesSql() {
        return "SELECT schema_name AS SCHEMA_NAME FROM v_catalog.schemata ORDER BY schema_name";
    }

    @Override
    public String tablesSql() {
        return "SELECT table_name AS TABLE_NAME, "
            + "COALESCE(comment, '') AS TABLE_COMMENT, "
            + "CASE WHEN is_super_projection IS NULL AND table_type IS NOT NULL THEN UPPER(table_type) ELSE 'TABLE' END AS TABLE_TYPE, "
            + "'' AS ENGINE, "
            + "0 AS TABLE_ROWS, "
            + "'' AS CREATE_TIME "
            + "FROM v_catalog.tables WHERE table_schema = ? AND table_type IN ('TABLE','VIEW') "
            + "ORDER BY table_type, table_name";
    }

    @Override
    public String columnsSql() {
        return "SELECT column_name AS COLUMN_NAME, "
            + "data_type AS DATA_TYPE, data_type AS COLUMN_TYPE, "
            + "is_nullable AS IS_NULLABLE, "
            + "COALESCE(column_default, '') AS COLUMN_DEFAULT, "
            + "COALESCE(comment, '') AS COLUMN_COMMENT, "
            + "'' AS COLUMN_KEY, "
            + "CASE WHEN is_identity THEN 'auto_increment' ELSE '' END AS EXTRA, "
            + "'' AS CHARACTER_SET_NAME, "
            + "'' AS COLLATION_NAME "
            + "FROM v_catalog.columns WHERE table_schema = ? AND table_name = ? "
            + "ORDER BY ordinal_position";
    }

    @Override
    public String indexesSql() {
        // Vertica 无传统索引目录(数据按投影存储), 返回空结果集
        return emptyMeta("INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE, INDEX_TYPE, INDEX_COMMENT");
    }

    @Override
    public String constraintsSql() {
        return "SELECT c.constraint_name AS CONSTRAINT_NAME, "
            + "c.constraint_type AS CONSTRAINT_TYPE, "
            + "COALESCE(cc.column_name, '') AS COLUMN_NAME, "
            + "COALESCE(cc.column_position, 0) AS ORDINAL_POSITION "
            + "FROM v_catalog.constraint_columns c "
            + "LEFT JOIN v_catalog.constraint_columns cc ON cc.constraint_name = c.constraint_name "
            + "WHERE c.table_schema = ? AND c.table_name = ? "
            + "ORDER BY c.constraint_name, cc.column_position";
    }

    @Override
    public String foreignKeysSql() {
        // Vertica 系统目录无 FK 引用列明细, 返回空结果集(约束类型在 constraintsSql 可见)
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
        // Vertica 无触发器
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
        // Vertica 无 SHOW CREATE TABLE, 按列元数据重建
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        // Vertica 使用 schema 概念, 无 MySQL 风格 CREATE DATABASE
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
            + " ALTER COLUMN " + quote(column.getColumnName()) + " SET DATA TYPE " + type;
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
            || dt.contains("money") || dt.contains("interval") || dt.contains("uuid");
        return numeric ? def : "'" + def.replace("'", "''") + "'";
    }
}
