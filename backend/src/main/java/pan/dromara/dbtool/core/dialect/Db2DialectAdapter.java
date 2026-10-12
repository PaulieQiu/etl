package pan.dromara.dbtool.core.dialect;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import java.util.Locale;

/**
 * DB2 for LUW 方言适配器(C.3.34 新增)
 * <p>元数据基于 SYSCAT 目录视图(SYSCAT.SCHEMAS/TABLES/COLUMNS/INDEXES/INDEXCOLUSE/
 * TABCONST/REFERENCES/TRIGGERS), 结果集列别名与 MySQL information_schema 对齐;
 * 标识符双引号; 分页 DB2 LUW 11.1+ 支持 OFFSET/FETCH(11.1 以下需 ROWNUMBER() OVER 包装,
 * 能力矩阵标注); 无 SHOW CREATE TABLE, 建表按列元数据重建; 建库由实例管理, 返回 null 由上层提示。</p>
 * <p>标注"已适配待实测": SYSCAT 视图列级兼容性需真实 DB2 实例回归确认。</p>
 *
 * @author doubao
 */
public class Db2DialectAdapter implements DbDialectAdapter {

    @Override
    public boolean supports(String dbType) {
        if (dbType == null) {
            return false;
        }
        String t = dbType.trim().toUpperCase(Locale.ROOT);
        // DB2 for LUW 使用 SYSCAT 元数据; DB2 for i(DB2I) 使用 QSYS2 系统目录, 差异过大暂不承接
        return "DB2".equals(t);
    }

    @Override
    public String quote(String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    @Override
    public String databasesSql() {
        return "SELECT SCHEMANAME FROM SYSCAT.SCHEMAS ORDER BY SCHEMANAME";
    }

    @Override
    public String tablesSql() {
        return "SELECT TABNAME AS TABLE_NAME, COALESCE(REMARKS,'') AS TABLE_COMMENT, TYPE AS TABLE_TYPE, "
            + "'' AS ENGINE, 0 AS TABLE_ROWS, '' AS CREATE_TIME "
            + "FROM SYSCAT.TABLES WHERE TABSCHEMA = ? ORDER BY TYPE, TABNAME";
    }

    @Override
    public String columnsSql() {
        return "SELECT COLNAME AS COLUMN_NAME, TYPENAME AS DATA_TYPE, TYPENAME AS COLUMN_TYPE, "
            + "CASE WHEN NULLS = 'Y' THEN 'YES' ELSE 'NO' END AS IS_NULLABLE, "
            + "COALESCE(DEFAULT,'') AS COLUMN_DEFAULT, COALESCE(REMARKS,'') AS COLUMN_COMMENT, "
            + "'' AS COLUMN_KEY, '' AS EXTRA, "
            + "COALESCE(CODEPAGE,0) AS CHARACTER_SET_NAME, '' AS COLLATION_NAME "
            + "FROM SYSCAT.COLUMNS WHERE TABSCHEMA = ? AND TABNAME = ? ORDER BY COLNO";
    }

    @Override
    public String indexesSql() {
        return "SELECT i.INDNAME AS INDEX_NAME, ic.COLNAME AS COLUMN_NAME, ic.COLSEQ AS SEQ_IN_INDEX, "
            + "CASE WHEN i.UNIQUERULE = 'D' THEN 1 ELSE 0 END AS NON_UNIQUE, "
            + "'BTREE' AS INDEX_TYPE, COALESCE(i.REMARKS,'') AS INDEX_COMMENT "
            + "FROM SYSCAT.INDEXES i JOIN SYSCAT.INDEXCOLUSE ic "
            + "ON i.INDSCHEMA = ic.INDSCHEMA AND i.INDNAME = ic.INDNAME "
            + "WHERE ic.TABSCHEMA = ? AND ic.TABNAME = ? ORDER BY i.INDNAME, ic.COLSEQ";
    }

    @Override
    public String constraintsSql() {
        return "SELECT CONSTNAME AS CONSTRAINT_NAME, TYPE AS CONSTRAINT_TYPE, '' AS COLUMN_NAME, 0 AS ORDINAL_POSITION "
            + "FROM SYSCAT.TABCONST WHERE TABSCHEMA = ? AND TABNAME = ? ORDER BY CONSTNAME";
    }

    @Override
    public String foreignKeysSql() {
        return "SELECT CONSTNAME AS CONSTRAINT_NAME, TABNAME AS TABLE_NAME, FKCOLNAME AS COLUMN_NAME, "
            + "KEYSEQ AS ORDINAL_POSITION, REFTABNAME AS REFERENCED_TABLE_NAME, REFCOLNAME AS REFERENCED_COLUMN_NAME, "
            + "'' AS UPDATE_RULE, '' AS DELETE_RULE "
            + "FROM SYSCAT.REFERENCES WHERE TABSCHEMA = ? AND TABNAME = ? ORDER BY CONSTNAME, KEYSEQ";
    }

    @Override
    public String referencesSql() {
        return "SELECT CONSTNAME AS CONSTRAINT_NAME, TABNAME AS TABLE_NAME, FKCOLNAME AS COLUMN_NAME, "
            + "KEYSEQ AS ORDINAL_POSITION, REFTABNAME AS REFERENCED_TABLE_NAME, REFCOLNAME AS REFERENCED_COLUMN_NAME, "
            + "'' AS UPDATE_RULE, '' AS DELETE_RULE "
            + "FROM SYSCAT.REFERENCES WHERE REFTABSCHEMA = ? AND REFTABNAME = ? ORDER BY TABNAME, CONSTNAME, KEYSEQ";
    }

    @Override
    public String triggersSql() {
        return "SELECT TRIGNAME AS TRIGGER_NAME, "
            + "CASE TRIGTIME WHEN 'A' THEN 'AFTER' WHEN 'B' THEN 'BEFORE' ELSE TRIGTIME END AS ACTION_TIMING, "
            + "CASE TRIGEVENT WHEN 'I' THEN 'INSERT' WHEN 'U' THEN 'UPDATE' WHEN 'D' THEN 'DELETE' ELSE TRIGEVENT END AS EVENT_MANIPULATION, "
            + "TABNAME AS EVENT_OBJECT_TABLE, COALESCE(TEXT,'') AS ACTION_STATEMENT, COALESCE(REMARKS,'') AS CREATED "
            + "FROM SYSCAT.TRIGGERS WHERE TRIGSCHEMA = ? AND TABNAME = ? ORDER BY TRIGNAME";
    }

    @Override
    public String paginate(String sql, int offset, int size) {
        // DB2 LUW 11.1+ 支持 OFFSET/FETCH(要求 ORDER BY, 无则补); 11.1 以下需 ROWNUMBER() OVER
        String s = ORDER_BY_PATTERN.matcher(sql).find() ? sql : sql + " ORDER BY (SELECT NULL)";
        return s + " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY";
    }

    private static final java.util.regex.Pattern ORDER_BY_PATTERN =
        java.util.regex.Pattern.compile("\\bORDER\\s+BY\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    @Override
    public String showCreateTableSql(String schema, String table) {
        // DB2 无 SHOW CREATE TABLE, 由上层按列元数据重建
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        // DB2 数据库由实例管理, 不支持工作台建库
        return null;
    }

    @Override
    public String dropTableSql(String schema, String table) {
        return "DROP TABLE " + quote(schema) + "." + quote(table);
    }

    @Override
    public String renameTableSql(String schema, String oldName, String newName) {
        return "RENAME TABLE " + quote(schema) + "." + quote(oldName) + " TO " + quote(newName);
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
            if (isNumericDefault(c.getDataType(), def)) {
                sb.append(" DEFAULT ").append(def);
            } else {
                sb.append(" DEFAULT '").append(def.replace("'", "''")).append("'");
            }
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
        return dt.contains("int") || dt.contains("decimal") || dt.contains("double")
            || dt.contains("float") || dt.contains("bigint") || dt.contains("smallint");
    }
}
