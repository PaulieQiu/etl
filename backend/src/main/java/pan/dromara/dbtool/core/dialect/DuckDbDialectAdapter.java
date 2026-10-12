package pan.dromara.dbtool.core.dialect;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import java.util.Locale;

/**
 * DuckDB 方言适配器(C.3.30 新增)
 * <p>元数据基于 information_schema(tables/columns/schemata)与表值函数
 * duckdb_indexes()/duckdb_constraints(); 分页 LIMIT size OFFSET offset;
 * 标识符双引号; 无 SHOW CREATE TABLE(按列元数据重建); 自增列 GENERATED ALWAYS AS IDENTITY。
 * 外键明细 DuckDB 约束目录无直接 referenced 列, foreignKeys/references 返回空结果集;
 * 表/列注释无标准视图, 按空处理。</p>
 *
 * @author doubao
 */
public class DuckDbDialectAdapter implements DbDialectAdapter {

    @Override
    public boolean supports(String dbType) {
        return dbType != null && "DUCKDB".equalsIgnoreCase(dbType.trim());
    }

    @Override
    public String quote(String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    @Override
    public String databasesSql() {
        return "SELECT schema_name AS SCHEMA_NAME FROM information_schema.schemata ORDER BY schema_name";
    }

    @Override
    public String tablesSql() {
        return "SELECT table_name AS TABLE_NAME, '' AS TABLE_COMMENT, table_type AS TABLE_TYPE, "
            + "'' AS ENGINE, 0 AS TABLE_ROWS, '' AS CREATE_TIME "
            + "FROM information_schema.tables WHERE table_schema = ? ORDER BY table_type, table_name";
    }

    @Override
    public String columnsSql() {
        return "SELECT column_name AS COLUMN_NAME, data_type AS DATA_TYPE, data_type AS COLUMN_TYPE, "
            + "COALESCE(is_nullable,'YES') AS IS_NULLABLE, COALESCE(column_default,'') AS COLUMN_DEFAULT, "
            + "'' AS COLUMN_COMMENT, '' AS COLUMN_KEY, '' AS EXTRA, "
            + "'' AS CHARACTER_SET_NAME, '' AS COLLATION_NAME "
            + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
            + "ORDER BY ordinal_position";
    }

    @Override
    public String indexesSql() {
        return "SELECT index_name AS INDEX_NAME, '' AS COLUMN_NAME, 0 AS SEQ_IN_INDEX, "
            + "CASE WHEN is_unique THEN 0 ELSE 1 END AS NON_UNIQUE, index_type AS INDEX_TYPE, '' AS INDEX_COMMENT "
            + "FROM duckdb_indexes() WHERE schema_name = ? AND table_name = ? ORDER BY index_name";
    }

    @Override
    public String constraintsSql() {
        return "SELECT constraint_name AS CONSTRAINT_NAME, constraint_type AS CONSTRAINT_TYPE, "
            + "'' AS COLUMN_NAME, 0 AS ORDINAL_POSITION "
            + "FROM duckdb_constraints() WHERE schema_name = ? AND table_name = ? ORDER BY constraint_name";
    }

    @Override
    public String foreignKeysSql() {
        // DuckDB 约束目录无 referenced 表/列明细, 返回空结果集
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
        return "SELECT " + aliasList + " WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String paginate(String sql, int offset, int size) {
        return sql + " LIMIT " + size + " OFFSET " + offset;
    }

    @Override
    public String showCreateTableSql(String schema, String table) {
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        // DuckDB 嵌入式库, 无 CREATE DATABASE
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
        String col = quote(column.getColumnName());
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(quote(schema)).append('.').append(quote(table));
        sb.append(" ALTER COLUMN ").append(col).append(" TYPE ").append(type);
        boolean notNull = column.getNullable() != null && "NO".equalsIgnoreCase(column.getNullable());
        sb.append(", ALTER COLUMN ").append(col).append(notNull ? " SET NOT NULL" : " DROP NOT NULL");
        return sb.toString();
    }

    @Override
    public String columnDdl(DbColumnVo c) {
        String type = StrUtil.isNotBlank(c.getColumnType()) ? c.getColumnType() : c.getDataType();
        if (StrUtil.isBlank(type)) {
            throw new ServiceException("列类型不能为空");
        }
        StringBuilder sb = new StringBuilder(quote(c.getColumnName())).append(' ').append(type);
        if (c.getExtra() != null && c.getExtra().toLowerCase(Locale.ROOT).contains("auto_increment")) {
            sb.append(" GENERATED ALWAYS AS IDENTITY");
            return sb.toString();
        }
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
            || dt.contains("float") || dt.contains("double") || dt.contains("real")
            || dt.contains("bigint") || dt.contains("smallint") || dt.contains("tinyint")
            || dt.contains("bit") || dt.contains("bool") || dt.contains("date") || dt.contains("time")
            || dt.contains("timestamp");
        return numeric ? def : "'" + def.replace("'", "''") + "'";
    }
}
