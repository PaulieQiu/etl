package pan.dromara.dbtool.core.dialect;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL Server 方言适配器(P0 第三个非 MySQL 方言)
 * <p>实现差异点:
 * <ul>
 *   <li>元数据: 基于 sys 目录视图(sys.databases/sys.tables/sys.views/sys.columns/sys.types/
 *       sys.indexes/sys.key_constraints/sys.foreign_keys/sys.triggers/sys.extended_properties),
 *       结果集列名与 MySQL information_schema 对齐; 表/列注释来自扩展属性 MS_Description;
 *       元数据基于当前连接数据库(数据库列表仅作展示, 跨库浏览需 USE 切换, 文档标注);</li>
 *   <li>分页: 2012+ 的 OFFSET n ROWS FETCH NEXT m ROWS ONLY(强制要求 ORDER BY, 无则补 ORDER BY (SELECT NULL));</li>
 *   <li>标识符: 方括号;</li>
 *   <li>建表 DDL: 无 SHOW CREATE TABLE, 按列元数据重建(mapType sqlserver 分支: INT/NVARCHAR/DECIMAL/DATETIME2/VARBINARY);</li>
 *   <li>列定义: IDENTITY(1,1) 自增; DEFAULT 子句; 注释需扩展属性 sp_addextendedproperty(列定义内不携带);</li>
 *   <li>改列: ALTER COLUMN 仅类型/空值, 默认值修改需 DROP/ADD CONSTRAINT(工作台暂不自动生成), 改名走 sp_rename(返回 null 由上层提示)。</li>
 * </ul></p>
 *
 * @author doubao
 */
public class SqlServerDialectAdapter implements DbDialectAdapter {

    private static final Pattern ORDER_BY_PATTERN = Pattern.compile("\\border\\s+by\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_FUNCTION_DEFAULT = Pattern.compile("^[A-Z_]+(\\(.*\\))?$");

    @Override
    public boolean supports(String dbType) {
        return dbType != null && ("SQLSERVER".equalsIgnoreCase(dbType.trim())
            || "AZURESQL".equalsIgnoreCase(dbType.trim()));
    }

    @Override
    public String quote(String name) {
        return "[" + (name == null ? "" : name.replace("]", "]]")) + "]";
    }

    @Override
    public String databasesSql() {
        return "SELECT name AS SCHEMA_NAME FROM sys.databases WHERE name NOT IN ('master','model','msdb','tempdb') ORDER BY name";
    }

    @Override
    public String tablesSql() {
        return "SELECT t.name AS TABLE_NAME, "
            + "COALESCE(CAST(ep.value AS varchar(2000)), '') AS TABLE_COMMENT, "
            + "'BASE TABLE' AS TABLE_TYPE, '' AS ENGINE, "
            + "COALESCE((SELECT SUM(p.rows) FROM sys.partitions p WHERE p.object_id = t.object_id AND p.index_id IN (0,1)), 0) AS TABLE_ROWS, "
            + "CONVERT(varchar(20), t.create_date, 120) AS CREATE_TIME "
            + "FROM sys.tables t "
            + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
            + "LEFT JOIN sys.extended_properties ep ON ep.major_id = t.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description' "
            + "WHERE s.name = ? "
            + "UNION ALL "
            + "SELECT v.name AS TABLE_NAME, "
            + "COALESCE(CAST(ep.value AS varchar(2000)), '') AS TABLE_COMMENT, "
            + "'VIEW' AS TABLE_TYPE, '' AS ENGINE, 0 AS TABLE_ROWS, "
            + "CONVERT(varchar(20), v.create_date, 120) AS CREATE_TIME "
            + "FROM sys.views v "
            + "JOIN sys.schemas s ON s.schema_id = v.schema_id "
            + "LEFT JOIN sys.extended_properties ep ON ep.major_id = v.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description' "
            + "WHERE s.name = ? "
            + "ORDER BY TABLE_TYPE, TABLE_NAME";
    }

    @Override
    public String columnsSql() {
        return "SELECT c.name AS COLUMN_NAME, "
            + "ty.name AS DATA_TYPE, "
            + "CASE "
            + "WHEN ty.name IN ('varchar','nvarchar','char','nchar','binary','varbinary') THEN "
            + "ty.name + '(' + CASE WHEN c.max_length = -1 THEN 'MAX' ELSE CAST(c.max_length / CASE WHEN ty.name LIKE 'n%' THEN 2 ELSE 1 END AS varchar(10)) END + ')' "
            + "WHEN ty.name IN ('decimal','numeric') THEN ty.name + '(' + CAST(c.precision AS varchar(10)) + ',' + CAST(c.scale AS varchar(10)) + ')' "
            + "ELSE ty.name END AS COLUMN_TYPE, "
            + "CASE c.is_nullable WHEN 1 THEN 'YES' ELSE 'NO' END AS IS_NULLABLE, "
            + "COALESCE(CAST(dc.definition AS varchar(2000)), '') AS COLUMN_DEFAULT, "
            + "COALESCE(CAST(ep.value AS varchar(2000)), '') AS COLUMN_COMMENT, "
            + "CASE WHEN pk.column_id IS NOT NULL THEN 'PRI' ELSE '' END AS COLUMN_KEY, "
            + "CASE WHEN c.is_identity = 1 THEN 'auto_increment' ELSE '' END AS EXTRA, "
            + "'' AS CHARACTER_SET_NAME, '' AS COLLATION_NAME "
            + "FROM sys.columns c "
            + "JOIN sys.tables t ON t.object_id = c.object_id "
            + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
            + "JOIN sys.types ty ON ty.user_type_id = c.system_type_id "
            + "LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id "
            + "LEFT JOIN sys.extended_properties ep ON ep.major_id = c.object_id AND ep.minor_id = c.column_id AND ep.name = 'MS_Description' "
            + "LEFT JOIN (SELECT ic.object_id, ic.column_id FROM sys.indexes i "
            + "JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id "
            + "WHERE i.is_primary_key = 1) pk ON pk.object_id = c.object_id AND pk.column_id = c.column_id "
            + "WHERE s.name = ? AND t.name = ? "
            + "ORDER BY c.column_id";
    }

    @Override
    public String indexesSql() {
        return "SELECT i.name AS INDEX_NAME, "
            + "COALESCE(COL_NAME(ic.object_id, ic.column_id), '') AS COLUMN_NAME, "
            + "ic.key_ordinal AS SEQ_IN_INDEX, "
            + "CASE WHEN i.is_unique = 1 THEN 0 ELSE 1 END AS NON_UNIQUE, "
            + "i.type_desc AS INDEX_TYPE, "
            + "'' AS INDEX_COMMENT "
            + "FROM sys.indexes i "
            + "JOIN sys.tables t ON t.object_id = i.object_id "
            + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
            + "LEFT JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 0 "
            + "WHERE s.name = ? AND t.name = ? AND i.index_id > 0 "
            + "ORDER BY i.name, ic.key_ordinal";
    }

    @Override
    public String constraintsSql() {
        return "SELECT kc.name AS CONSTRAINT_NAME, "
            + "CASE kc.type WHEN 'PK' THEN 'PRIMARY KEY' WHEN 'UQ' THEN 'UNIQUE' ELSE 'OTHER' END AS CONSTRAINT_TYPE, "
            + "COALESCE(cc.name, '') AS COLUMN_NAME, "
            + "ic.key_ordinal AS ORDINAL_POSITION "
            + "FROM sys.key_constraints kc "
            + "JOIN sys.tables t ON t.object_id = kc.parent_object_id "
            + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
            + "LEFT JOIN sys.index_columns ic ON ic.object_id = t.object_id AND ic.index_id = kc.unique_index_id "
            + "LEFT JOIN sys.columns cc ON cc.object_id = t.object_id AND cc.column_id = ic.column_id "
            + "WHERE s.name = ? AND t.name = ? AND kc.type IN ('PK','UQ') "
            + "UNION ALL "
            + "SELECT c.name AS CONSTRAINT_NAME, 'CHECK' AS CONSTRAINT_TYPE, '' AS COLUMN_NAME, 0 AS ORDINAL_POSITION "
            + "FROM sys.check_constraints c "
            + "JOIN sys.tables t ON t.object_id = c.parent_object_id "
            + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
            + "WHERE s.name = ? AND t.name = ? "
            + "ORDER BY CONSTRAINT_NAME";
    }

    @Override
    public String foreignKeysSql() {
        return "SELECT fk.name AS CONSTRAINT_NAME, "
            + "t.name AS TABLE_NAME, "
            + "COALESCE(c1.name, '') AS COLUMN_NAME, "
            + "fkc.constraint_column_id AS ORDINAL_POSITION, "
            + "rt.name AS REFERENCED_TABLE_NAME, "
            + "COALESCE(c2.name, '') AS REFERENCED_COLUMN_NAME, "
            + "'NO ACTION' AS UPDATE_RULE, "
            + "CASE fk.delete_referential_action WHEN 1 THEN 'CASCADE' WHEN 2 THEN 'SET NULL' "
            + "WHEN 3 THEN 'SET DEFAULT' ELSE 'NO ACTION' END AS DELETE_RULE "
            + "FROM sys.foreign_keys fk "
            + "JOIN sys.tables t ON t.object_id = fk.parent_object_id "
            + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
            + "JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id "
            + "JOIN sys.columns c1 ON c1.object_id = fkc.parent_object_id AND c1.column_id = fkc.parent_column_id "
            + "JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id "
            + "JOIN sys.columns c2 ON c2.object_id = fkc.referenced_object_id AND c2.column_id = fkc.referenced_column_id "
            + "WHERE s.name = ? AND t.name = ? "
            + "ORDER BY fk.name, fkc.constraint_column_id";
    }

    @Override
    public String referencesSql() {
        // 占位符: 1=被引用表 schema, 2=被引用表名
        return "SELECT fk.name AS CONSTRAINT_NAME, "
            + "t.name AS TABLE_NAME, "
            + "COALESCE(c1.name, '') AS COLUMN_NAME, "
            + "fkc.constraint_column_id AS ORDINAL_POSITION, "
            + "rt.name AS REFERENCED_TABLE_NAME, "
            + "COALESCE(c2.name, '') AS REFERENCED_COLUMN_NAME, "
            + "'NO ACTION' AS UPDATE_RULE, "
            + "CASE fk.delete_referential_action WHEN 1 THEN 'CASCADE' WHEN 2 THEN 'SET NULL' "
            + "WHEN 3 THEN 'SET DEFAULT' ELSE 'NO ACTION' END AS DELETE_RULE "
            + "FROM sys.foreign_keys fk "
            + "JOIN sys.tables t ON t.object_id = fk.parent_object_id "
            + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
            + "JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id "
            + "JOIN sys.columns c1 ON c1.object_id = fkc.parent_object_id AND c1.column_id = fkc.parent_column_id "
            + "JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id "
            + "JOIN sys.schemas rs ON rs.schema_id = rt.schema_id "
            + "JOIN sys.columns c2 ON c2.object_id = fkc.referenced_object_id AND c2.column_id = fkc.referenced_column_id "
            + "WHERE rs.name = ? AND rt.name = ? "
            + "ORDER BY t.name, fk.name, fkc.constraint_column_id";
    }

    @Override
    public String triggersSql() {
        return "SELECT tr.name AS TRIGGER_NAME, "
            + "CASE tr.is_instead_of_trigger WHEN 1 THEN 'INSTEAD OF' ELSE 'AFTER' END AS ACTION_TIMING, "
            + "CASE WHEN OBJECTPROPERTY(tr.object_id, 'ExecIsInsertTrigger') = 1 THEN 'INSERT' ELSE '' END "
            + "+ CASE WHEN OBJECTPROPERTY(tr.object_id, 'ExecIsUpdateTrigger') = 1 THEN 'UPDATE' ELSE '' END "
            + "+ CASE WHEN OBJECTPROPERTY(tr.object_id, 'ExecIsDeleteTrigger') = 1 THEN 'DELETE' ELSE '' END AS EVENT_MANIPULATION, "
            + "t.name AS EVENT_OBJECT_TABLE, "
            + "COALESCE(OBJECT_DEFINITION(tr.object_id), '') AS ACTION_STATEMENT, "
            + "CONVERT(varchar(20), tr.create_date, 120) AS CREATED "
            + "FROM sys.triggers tr "
            + "JOIN sys.tables t ON t.object_id = tr.parent_id "
            + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
            + "WHERE s.name = ? AND t.name = ? "
            + "ORDER BY tr.name";
    }

    @Override
    public String paginate(String sql, int offset, int size) {
        // 2012+ OFFSET/FETCH 强制要求 ORDER BY; 无则补 ORDER BY (SELECT NULL)
        String s = ORDER_BY_PATTERN.matcher(sql).find() ? sql : sql + " ORDER BY (SELECT NULL)";
        return s + " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY";
    }

    @Override
    public String showCreateTableSql(String schema, String table) {
        // SQL Server 无 SHOW CREATE TABLE, 由上层按列元数据重建
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        // SQL Server 无 IF NOT EXISTS(2016+ 可加); 不拼接 MySQL 风格 charset
        return "CREATE DATABASE " + quote(dbName);
    }

    @Override
    public String dropTableSql(String schema, String table) {
        // DROP TABLE IF EXISTS 需 SQL Server 2016+
        return "DROP TABLE IF EXISTS " + quote(schema) + "." + quote(table);
    }

    @Override
    public String renameTableSql(String schema, String oldName, String newName) {
        return "EXEC sp_rename '" + quote(schema) + "." + quote(oldName) + "', " + quote(newName);
    }

    @Override
    public String addColumnSql(String schema, String table, String colDdl) {
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " ADD " + colDdl;
    }

    @Override
    public String dropColumnSql(String schema, String table, String column) {
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " DROP COLUMN " + quote(column);
    }

    @Override
    public String modifyColumnSql(String schema, String table, String oldColumnName, DbColumnVo column) {
        boolean rename = StrUtil.isNotBlank(oldColumnName) && !oldColumnName.equals(column.getColumnName());
        if (rename) {
            // 改名走 sp_rename, 与类型/空值修改不能合并, 返回 null 由上层提示
            return null;
        }
        String type = StrUtil.isNotBlank(column.getColumnType()) ? column.getColumnType() : column.getDataType();
        if (StrUtil.isBlank(type)) {
            throw new ServiceException("列类型不能为空");
        }
        boolean notNull = column.getNullable() != null && "NO".equalsIgnoreCase(column.getNullable());
        // SQL Server ALTER COLUMN 不支持 DEFAULT 变更(需 DROP/ADD CONSTRAINT), 工作台暂不自动生成
        return "ALTER TABLE " + quote(schema) + "." + quote(table)
            + " ALTER COLUMN " + quote(column.getColumnName()) + " " + type
            + (notNull ? " NOT NULL" : " NULL");
    }

    @Override
    public String columnDdl(DbColumnVo c) {
        String type = StrUtil.isNotBlank(c.getColumnType()) ? c.getColumnType() : c.getDataType();
        if (StrUtil.isBlank(type)) {
            throw new ServiceException("列类型不能为空");
        }
        StringBuilder sb = new StringBuilder(quote(c.getColumnName())).append(' ').append(type);
        if (c.getExtra() != null && c.getExtra().toLowerCase(Locale.ROOT).contains("auto_increment")) {
            sb.append(" IDENTITY(1,1)");
            return sb.toString();
        }
        boolean notNull = c.getNullable() != null && "NO".equalsIgnoreCase(c.getNullable());
        sb.append(notNull ? " NOT NULL" : " NULL");
        String def = c.getDefaultValue();
        if (def != null && !def.isEmpty() && !"NULL".equalsIgnoreCase(def)) {
            sb.append(" DEFAULT ").append(defaultLiteral(c.getDataType(), def));
        }
        // 注释需扩展属性 sp_addextendedproperty(列定义内不携带)
        return sb.toString();
    }

    private String defaultLiteral(String dataType, String def) {
        if (SQL_FUNCTION_DEFAULT.matcher(def.trim()).matches()) {
            // GETDATE()/CURRENT_TIMESTAMP 等不加引号
            return def;
        }
        if (dataType == null) {
            return "'" + def.replace("'", "''") + "'";
        }
        String dt = dataType.toLowerCase(Locale.ROOT);
        boolean numeric = dt.contains("int") || dt.contains("decimal") || dt.contains("numeric")
            || dt.contains("float") || dt.contains("real") || dt.contains("money")
            || dt.contains("bit") || dt.contains("bigint") || dt.contains("smallint") || dt.contains("tinyint");
        return numeric ? def : "'" + def.replace("'", "''") + "'";
    }
}
