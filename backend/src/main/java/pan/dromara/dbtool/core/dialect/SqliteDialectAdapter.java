package pan.dromara.dbtool.core.dialect;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import java.util.Locale;

/**
 * SQLite 方言适配器(C.3.30 新增)
 * <p>单文件库, 无多库/模式概念: databasesSql 返回 pragma_database_list;
 * 表/索引/触发器取自 sqlite_master, 列结构通过表值函数 pragma_table_info(?)
 * (JDBC 绑定顺序固定为 (schema, table), 用 (SELECT ? AS _s, ? AS _t) 子查询消费并
 * 引用 _t 作为 pragma 参数, SQL 已在本机 sqlite3 实测通过)。
 * 分页 LIMIT size OFFSET offset; 标识符双引号; 无 SHOW CREATE TABLE(按列元数据重建)。
 * 注意: SQLite 的 INTEGER PRIMARY KEY 隐含自增, pragma 的 notnull 对主键列返回 0,
 * 故主键列 IS_NULLABLE 显示 YES 属 SQLite 语义, 已标注"已适配待实测"。</p>
 *
 * @author doubao
 */
public class SqliteDialectAdapter implements DbDialectAdapter {

    @Override
    public boolean supports(String dbType) {
        return dbType != null && "SQLITE".equalsIgnoreCase(dbType.trim());
    }

    @Override
    public String quote(String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    @Override
    public String databasesSql() {
        return "SELECT name AS SCHEMA_NAME FROM pragma_database_list()";
    }

    @Override
    public String tablesSql() {
        return "SELECT name AS TABLE_NAME, '' AS TABLE_COMMENT, type AS TABLE_TYPE, '' AS ENGINE, "
            + "0 AS TABLE_ROWS, '' AS CREATE_TIME "
            + "FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' AND ? = ? "
            + "ORDER BY type, name";
    }

    @Override
    public String columnsSql() {
        return "SELECT p.name AS COLUMN_NAME, p.type AS DATA_TYPE, p.type AS COLUMN_TYPE, "
            + "CASE WHEN p.\"notnull\" = 0 THEN 'YES' ELSE 'NO' END AS IS_NULLABLE, "
            + "COALESCE(p.dflt_value, '') AS COLUMN_DEFAULT, '' AS COLUMN_COMMENT, "
            + "CASE WHEN p.pk > 0 THEN 'PRI' ELSE '' END AS COLUMN_KEY, '' AS EXTRA, "
            + "'' AS CHARACTER_SET_NAME, '' AS COLLATION_NAME "
            + "FROM (SELECT ? AS _s, ? AS _t) _x CROSS JOIN pragma_table_info(_x._t) AS p "
            + "ORDER BY p.cid";
    }

    @Override
    public String indexesSql() {
        return "SELECT name AS INDEX_NAME, '' AS COLUMN_NAME, 0 AS SEQ_IN_INDEX, "
            + "CASE WHEN \"unique\" = 1 THEN 0 ELSE 1 END AS NON_UNIQUE, '' AS INDEX_TYPE, '' AS INDEX_COMMENT "
            + "FROM sqlite_master WHERE type = 'index' AND ? IS NOT NULL AND tbl_name = ? ORDER BY name";
    }

    @Override
    public String constraintsSql() {
        // SQLite 无独立约束目录(主键/唯一见 sqlite_master 建表 SQL), 返回空结果集
        return emptyMeta("CONSTRAINT_NAME, CONSTRAINT_TYPE, COLUMN_NAME, ORDINAL_POSITION");
    }

    @Override
    public String foreignKeysSql() {
        return "SELECT 'fk_' || p.id AS CONSTRAINT_NAME, _x._t AS TABLE_NAME, p.\"from\" AS COLUMN_NAME, "
            + "p.seq AS ORDINAL_POSITION, p.\"table\" AS REFERENCED_TABLE_NAME, p.\"to\" AS REFERENCED_COLUMN_NAME, "
            + "p.on_update AS UPDATE_RULE, p.on_delete AS DELETE_RULE "
            + "FROM (SELECT ? AS _s, ? AS _t) _x CROSS JOIN pragma_foreign_key_list(_x._t) AS p "
            + "ORDER BY p.id, p.seq";
    }

    @Override
    public String referencesSql() {
        // SQLite 无全局"被引用"目录, 返回空结果集
        return emptyMeta("CONSTRAINT_NAME, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, "
            + "REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME, UPDATE_RULE, DELETE_RULE");
    }

    @Override
    public String triggersSql() {
        return "SELECT name AS TRIGGER_NAME, '' AS ACTION_TIMING, '' AS EVENT_MANIPULATION, "
            + "tbl_name AS EVENT_OBJECT_TABLE, sql AS ACTION_STATEMENT, '' AS CREATED "
            + "FROM sqlite_master WHERE type = 'trigger' AND ? IS NOT NULL AND tbl_name = ? ORDER BY name";
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
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        // SQLite 单文件库, 无 CREATE DATABASE
        return null;
    }

    @Override
    public String dropTableSql(String schema, String table) {
        return "DROP TABLE IF EXISTS " + quote(table);
    }

    @Override
    public String renameTableSql(String schema, String oldName, String newName) {
        return "ALTER TABLE " + quote(oldName) + " RENAME TO " + quote(newName);
    }

    @Override
    public String addColumnSql(String schema, String table, String colDdl) {
        return "ALTER TABLE " + quote(table) + " ADD COLUMN " + colDdl;
    }

    @Override
    public String dropColumnSql(String schema, String table, String column) {
        // SQLite 3.35+ 支持 DROP COLUMN; 更早版本需整表重建
        return "ALTER TABLE " + quote(table) + " DROP COLUMN " + quote(column);
    }

    @Override
    public String modifyColumnSql(String schema, String table, String oldColumnName, DbColumnVo column) {
        // SQLite 不支持 ALTER ... MODIFY, 改列需整表重建
        return null;
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
            // SQLite dflt_value 返回 SQL 文本(含引号), 直接沿用避免双重转义
            sb.append(" DEFAULT ").append(def);
        }
        return sb.toString();
    }
}
