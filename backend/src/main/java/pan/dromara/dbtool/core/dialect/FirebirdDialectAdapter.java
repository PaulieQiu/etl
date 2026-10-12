package pan.dromara.dbtool.core.dialect;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import java.util.Locale;

/**
 * Firebird 方言适配器(C.3.32 新增)
 * <p>嵌入式关系型数据库, 无多库/模式概念: databasesSql 返回单行 MAIN;
 * 元数据取自 RDB$RELATIONS / RDB$RELATION_FIELDS / RDB$FIELDS / RDB$INDICES /
 * RDB$RELATION_CONSTRAINTS / RDB$REF_CONSTRAINTS / RDB$TRIGGERS;
 * 上层 JDBC 固定绑定 (schema, table) 两个参数, 用 (SELECT ? AS _s, ? AS _t FROM RDB$DATABASE)
 * 子查询消费并按 UPPER 与系统目录名比较(兼容未加引号建表的大写化存储);
 * 标识符双引号且大小写敏感, 最大 31 字符; 分页 ROWS offset TO offset+size(Firebird 3+);
 * 无 SHOW CREATE TABLE。dbx firebird.yaml 对照实现, 属"已适配待实测"。</p>
 *
 * @author doubao
 */
public class FirebirdDialectAdapter implements DbDialectAdapter {

    @Override
    public boolean supports(String dbType) {
        return dbType != null && "FIREBIRD".equalsIgnoreCase(dbType.trim());
    }

    @Override
    public String quote(String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    @Override
    public String databasesSql() {
        // Firebird 单库, 返回一个逻辑模式行
        return "SELECT 'MAIN' AS SCHEMA_NAME FROM RDB$DATABASE";
    }

    @Override
    public String tablesSql() {
        return "SELECT r.RDB$RELATION_NAME AS TABLE_NAME, "
            + "'' AS TABLE_COMMENT, "
            + "CASE WHEN r.RDB$VIEW_BLR IS NULL THEN 'BASE TABLE' ELSE 'VIEW' END AS TABLE_TYPE, "
            + "'' AS ENGINE, "
            + "0 AS TABLE_ROWS, "
            + "'' AS CREATE_TIME "
            + "FROM RDB$RELATIONS r WHERE r.RDB$SYSTEM_FLAG = 0 AND ? = ? "
            + "ORDER BY TABLE_TYPE, TABLE_NAME";
    }

    @Override
    public String columnsSql() {
        return "SELECT rf.RDB$FIELD_NAME AS COLUMN_NAME, "
            + "CASE f.RDB$FIELD_TYPE "
            + "WHEN 7 THEN 'SMALLINT' WHEN 8 THEN 'INTEGER' WHEN 10 THEN 'FLOAT' "
            + "WHEN 12 THEN 'DATE' WHEN 13 THEN 'TIME' WHEN 14 THEN 'CHAR' "
            + "WHEN 16 THEN 'BIGINT' WHEN 27 THEN 'DOUBLE PRECISION' "
            + "WHEN 35 THEN 'TIMESTAMP' WHEN 37 THEN 'VARCHAR' "
            + "ELSE COALESCE(f.RDB$FIELD_SOURCE, '') END AS DATA_TYPE, "
            + "CASE f.RDB$FIELD_TYPE "
            + "WHEN 14 THEN 'CHAR(' || f.RDB$FIELD_LENGTH || ')' "
            + "WHEN 37 THEN 'VARCHAR(' || f.RDB$FIELD_LENGTH || ')' "
            + "WHEN 7 THEN 'SMALLINT' WHEN 8 THEN 'INTEGER' WHEN 16 THEN 'BIGINT' "
            + "WHEN 27 THEN 'DOUBLE PRECISION' WHEN 10 THEN 'FLOAT' "
            + "WHEN 12 THEN 'DATE' WHEN 13 THEN 'TIME' WHEN 35 THEN 'TIMESTAMP' "
            + "ELSE COALESCE(f.RDB$FIELD_SOURCE, '') END AS COLUMN_TYPE, "
            + "CASE WHEN rf.RDB$NULL_FLAG = 1 OR f.RDB$NULL_FLAG = 1 THEN 'NO' ELSE 'YES' END AS IS_NULLABLE, "
            + "COALESCE(rf.RDB$DEFAULT_SOURCE, f.RDB$DEFAULT_SOURCE, '') AS COLUMN_DEFAULT, "
            + "'' AS COLUMN_COMMENT, "
            + "CASE WHEN EXISTS (SELECT 1 FROM RDB$INDEX_SEGMENTS s "
            + "JOIN RDB$INDICES i ON i.RDB$INDEX_NAME = s.RDB$INDEX_NAME "
            + "WHERE i.RDB$RELATION_NAME = rf.RDB$RELATION_NAME AND i.RDB$UNIQUE_FLAG = 1 "
            + "AND s.RDB$FIELD_NAME = rf.RDB$FIELD_NAME) THEN 'UNI' ELSE '' END AS COLUMN_KEY, "
            + "'' AS EXTRA, "
            + "'' AS CHARACTER_SET_NAME, "
            + "'' AS COLLATION_NAME "
            + "FROM (SELECT ? AS _s, ? AS _t FROM RDB$DATABASE) _x "
            + "JOIN RDB$RELATION_FIELDS rf ON UPPER(rf.RDB$RELATION_NAME) = UPPER(_x._t) "
            + "JOIN RDB$FIELDS f ON f.RDB$FIELD_NAME = rf.RDB$FIELD_SOURCE "
            + "ORDER BY rf.RDB$FIELD_POSITION";
    }

    @Override
    public String indexesSql() {
        return "SELECT i.RDB$INDEX_NAME AS INDEX_NAME, "
            + "s.RDB$FIELD_NAME AS COLUMN_NAME, "
            + "s.RDB$FIELD_POSITION AS SEQ_IN_INDEX, "
            + "CASE WHEN i.RDB$UNIQUE_FLAG = 1 THEN 0 ELSE 1 END AS NON_UNIQUE, "
            + "CASE WHEN i.RDB$INDEX_INACTIVE = 1 THEN 'DISABLED' ELSE 'BTREE' END AS INDEX_TYPE, "
            + "'' AS INDEX_COMMENT "
            + "FROM (SELECT ? AS _s, ? AS _t FROM RDB$DATABASE) _x "
            + "JOIN RDB$INDICES i ON UPPER(i.RDB$RELATION_NAME) = UPPER(_x._t) "
            + "JOIN RDB$INDEX_SEGMENTS s ON s.RDB$INDEX_NAME = i.RDB$INDEX_NAME "
            + "ORDER BY i.RDB$INDEX_NAME, s.RDB$FIELD_POSITION";
    }

    @Override
    public String constraintsSql() {
        return "SELECT rc.RDB$CONSTRAINT_NAME AS CONSTRAINT_NAME, "
            + "rc.RDB$CONSTRAINT_TYPE AS CONSTRAINT_TYPE, "
            + "COALESCE(s.RDB$FIELD_NAME, '') AS COLUMN_NAME, "
            + "COALESCE(s.RDB$FIELD_POSITION, 0) AS ORDINAL_POSITION "
            + "FROM (SELECT ? AS _s, ? AS _t FROM RDB$DATABASE) _x "
            + "JOIN RDB$RELATION_CONSTRAINTS rc ON UPPER(rc.RDB$RELATION_NAME) = UPPER(_x._t) "
            + "LEFT JOIN RDB$INDEX_SEGMENTS s ON s.RDB$INDEX_NAME = rc.RDB$INDEX_NAME "
            + "ORDER BY rc.RDB$CONSTRAINT_NAME, s.RDB$FIELD_POSITION";
    }

    @Override
    public String foreignKeysSql() {
        return "SELECT rc.RDB$CONSTRAINT_NAME AS CONSTRAINT_NAME, "
            + "_x._t AS TABLE_NAME, "
            + "COALESCE(s.RDB$FIELD_NAME, '') AS COLUMN_NAME, "
            + "COALESCE(s.RDB$FIELD_POSITION, 0) AS ORDINAL_POSITION, "
            + "ref.RDB$RELATION_NAME AS REFERENCED_TABLE_NAME, "
            + "COALESCE(rs.RDB$FIELD_NAME, '') AS REFERENCED_COLUMN_NAME, "
            + "COALESCE(refc.RDB$UPDATE_RULE, 'NO ACTION') AS UPDATE_RULE, "
            + "COALESCE(refc.RDB$DELETE_RULE, 'NO ACTION') AS DELETE_RULE "
            + "FROM (SELECT ? AS _s, ? AS _t FROM RDB$DATABASE) _x "
            + "JOIN RDB$RELATION_CONSTRAINTS rc ON UPPER(rc.RDB$RELATION_NAME) = UPPER(_x._t) "
            + "AND rc.RDB$CONSTRAINT_TYPE = 'FOREIGN KEY' "
            + "JOIN RDB$REF_CONSTRAINTS refc ON refc.RDB$CONSTRAINT_NAME = rc.RDB$CONSTRAINT_NAME "
            + "JOIN RDB$RELATION_CONSTRAINTS ref ON ref.RDB$CONSTRAINT_NAME = refc.RDB$CONST_NAME_UQ "
            + "LEFT JOIN RDB$INDEX_SEGMENTS s ON s.RDB$INDEX_NAME = rc.RDB$INDEX_NAME "
            + "LEFT JOIN RDB$INDEX_SEGMENTS rs ON rs.RDB$INDEX_NAME = ref.RDB$INDEX_NAME "
            + "AND rs.RDB$FIELD_POSITION = s.RDB$FIELD_POSITION "
            + "ORDER BY s.RDB$FIELD_POSITION";
    }

    @Override
    public String referencesSql() {
        return "SELECT rc.RDB$CONSTRAINT_NAME AS CONSTRAINT_NAME, "
            + "rc.RDB$RELATION_NAME AS TABLE_NAME, "
            + "COALESCE(s.RDB$FIELD_NAME, '') AS COLUMN_NAME, "
            + "COALESCE(s.RDB$FIELD_POSITION, 0) AS ORDINAL_POSITION, "
            + "_x._t AS REFERENCED_TABLE_NAME, "
            + "COALESCE(rs.RDB$FIELD_NAME, '') AS REFERENCED_COLUMN_NAME, "
            + "COALESCE(refc.RDB$UPDATE_RULE, 'NO ACTION') AS UPDATE_RULE, "
            + "COALESCE(refc.RDB$DELETE_RULE, 'NO ACTION') AS DELETE_RULE "
            + "FROM (SELECT ? AS _s, ? AS _t FROM RDB$DATABASE) _x "
            + "JOIN RDB$REF_CONSTRAINTS refc ON 1 = 1 "
            + "JOIN RDB$RELATION_CONSTRAINTS ref ON ref.RDB$CONSTRAINT_NAME = refc.RDB$CONST_NAME_UQ "
            + "AND UPPER(ref.RDB$RELATION_NAME) = UPPER(_x._t) "
            + "JOIN RDB$RELATION_CONSTRAINTS rc ON rc.RDB$CONSTRAINT_NAME = refc.RDB$CONSTRAINT_NAME "
            + "AND rc.RDB$CONSTRAINT_TYPE = 'FOREIGN KEY' "
            + "LEFT JOIN RDB$INDEX_SEGMENTS s ON s.RDB$INDEX_NAME = rc.RDB$INDEX_NAME "
            + "LEFT JOIN RDB$INDEX_SEGMENTS rs ON rs.RDB$INDEX_NAME = ref.RDB$INDEX_NAME "
            + "AND rs.RDB$FIELD_POSITION = s.RDB$FIELD_POSITION "
            + "ORDER BY rc.RDB$RELATION_NAME, s.RDB$FIELD_POSITION";
    }

    @Override
    public String triggersSql() {
        return "SELECT t.RDB$TRIGGER_NAME AS TRIGGER_NAME, "
            + "CASE t.RDB$TRIGGER_TYPE "
            + "WHEN 1 THEN 'BEFORE' WHEN 2 THEN 'AFTER' "
            + "WHEN 3 THEN 'BEFORE' WHEN 4 THEN 'AFTER' "
            + "WHEN 5 THEN 'BEFORE' WHEN 6 THEN 'AFTER' ELSE '' END AS ACTION_TIMING, "
            + "CASE t.RDB$TRIGGER_TYPE "
            + "WHEN 1 THEN 'INSERT' WHEN 2 THEN 'INSERT' "
            + "WHEN 3 THEN 'UPDATE' WHEN 4 THEN 'UPDATE' "
            + "WHEN 5 THEN 'DELETE' WHEN 6 THEN 'DELETE' ELSE '' END AS EVENT_MANIPULATION, "
            + "t.RDB$RELATION_NAME AS EVENT_OBJECT_TABLE, "
            + "COALESCE(t.RDB$TRIGGER_SOURCE, '') AS ACTION_STATEMENT, "
            + "'' AS CREATED "
            + "FROM (SELECT ? AS _s, ? AS _t FROM RDB$DATABASE) _x "
            + "JOIN RDB$TRIGGERS t ON UPPER(t.RDB$RELATION_NAME) = UPPER(_x._t) "
            + "ORDER BY t.RDB$TRIGGER_NAME";
    }

    @Override
    public String paginate(String sql, int offset, int size) {
        // Firebird 3+ ROWS 子句: ROWS start TO end
        return sql + " ROWS " + (offset + 1) + " TO " + (offset + size);
    }

    @Override
    public String showCreateTableSql(String schema, String table) {
        // Firebird 无 SHOW CREATE TABLE, 按列元数据重建
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        // Firebird 单库(建库由服务端维护), 返回 null
        return null;
    }

    @Override
    public String dropTableSql(String schema, String table) {
        return "DROP TABLE " + quote(table);
    }

    @Override
    public String renameTableSql(String schema, String oldName, String newName) {
        return "ALTER TABLE " + quote(oldName) + " TO " + quote(newName);
    }

    @Override
    public String addColumnSql(String schema, String table, String colDdl) {
        return "ALTER TABLE " + quote(table) + " ADD " + colDdl;
    }

    @Override
    public String dropColumnSql(String schema, String table, String column) {
        return "ALTER TABLE " + quote(table) + " DROP " + quote(column);
    }

    @Override
    public String modifyColumnSql(String schema, String table, String oldColumnName, DbColumnVo column) {
        boolean rename = StrUtil.isNotBlank(oldColumnName) && !oldColumnName.equals(column.getColumnName());
        if (rename) {
            // Firebird 改列名需 ALTER COLUMN ... TO, 与类型修改不能合并, 暂不支持
            return null;
        }
        String type = StrUtil.isNotBlank(column.getColumnType()) ? column.getColumnType() : column.getDataType();
        if (StrUtil.isBlank(type)) {
            throw new ServiceException("列类型不能为空");
        }
        return "ALTER TABLE " + quote(table) + " ALTER COLUMN " + quote(column.getColumnName()) + " TYPE " + type;
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
            || dt.contains("money") || dt.contains("date") || dt.contains("time");
        return numeric ? def : "'" + def.replace("'", "''") + "'";
    }
}
