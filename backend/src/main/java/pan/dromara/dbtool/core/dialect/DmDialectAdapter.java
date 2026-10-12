package pan.dromara.dbtool.core.dialect;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import java.util.Locale;

/**
 * 达梦 DM 方言适配器(P0 第四个非 MySQL 方言)
 * <p>达梦 8 提供与 MySQL 兼容的 information_schema 视图(SCHEMATA/TABLES/COLUMNS/STATISTICS/
 * TABLE_CONSTRAINTS/KEY_COLUMN_USAGE/REFERENTIAL_CONSTRAINTS), 因此元数据 SQL 直接继承
 * {@link MySqlDialectAdapter}; 分页同样兼容 LIMIT offset,size。仅覆盖下列方言差异:
 * <ul>
 *   <li>标识符: 双引号(达梦默认, 非反引号);</li>
 *   <li>建库: 达梦为实例/模式体系, 无 MySQL 式 CREATE DATABASE, 返回 null 由上层提示;</li>
 *   <li>建表 DDL: 无 SHOW CREATE TABLE, 按列元数据重建(mapType dm 分支: INT/BIGINT/VARCHAR/DECIMAL/FLOAT/DOUBLE/BLOB/TEXT);</li>
 *   <li>列定义: IDENTITY(1,1) 自增; 无列级 COMMENT(需独立 COMMENT ON COLUMN);</li>
 *   <li>改列: MODIFY 支持类型/默认值/空值, 改名走 RENAME COLUMN(一次调用内不合并, 返回 null 由上层提示)。</li>
 * </ul>
 * 达梦 information_schema 视图与 MySQL 的列级兼容性、触发器视图(TRIGGERS)是否存在,
 * 需真实达梦实例回归确认; 若缺少某视图应改用 SYSOBJECTS 系统视图。</p>
 *
 * @author doubao
 */
public class DmDialectAdapter extends MySqlDialectAdapter {

    @Override
    public boolean supports(String dbType) {
        if (dbType == null) {
            return false;
        }
        String t = dbType.trim().toUpperCase(Locale.ROOT);
        return "DAMENG".equals(t) || "DM".equals(t);
    }

    @Override
    public String quote(String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    @Override
    public String showCreateTableSql(String schema, String table) {
        // 达梦无 SHOW CREATE TABLE, 由上层按列元数据重建
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        // 达梦为实例/模式体系, 无 MySQL 式 CREATE DATABASE, 由上层提示不支持
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
            // 改名走 RENAME COLUMN, 与类型/默认值修改不能合并, 返回 null 由上层提示
            return null;
        }
        String type = StrUtil.isNotBlank(column.getColumnType()) ? column.getColumnType() : column.getDataType();
        if (StrUtil.isBlank(type)) {
            throw new ServiceException("列类型不能为空");
        }
        String col = quote(column.getColumnName());
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(quote(schema)).append('.').append(quote(table));
        sb.append(" MODIFY ").append(col).append(' ').append(type);
        String def = column.getDefaultValue();
        if (def != null && !def.isEmpty() && !"NULL".equalsIgnoreCase(def)) {
            sb.append(" DEFAULT ").append(defaultLiteral(column.getDataType(), def));
        }
        boolean notNull = column.getNullable() != null && "NO".equalsIgnoreCase(column.getNullable());
        sb.append(notNull ? " NOT NULL" : " NULL");
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
            sb.append(" IDENTITY(1,1)");
            return sb.toString();
        }
        boolean notNull = c.getNullable() != null && "NO".equalsIgnoreCase(c.getNullable());
        sb.append(notNull ? " NOT NULL" : " NULL");
        String def = c.getDefaultValue();
        if (def != null && !def.isEmpty() && !"NULL".equalsIgnoreCase(def)) {
            sb.append(" DEFAULT ").append(defaultLiteral(c.getDataType(), def));
        }
        // 达梦无列级 COMMENT 语法, 注释需独立 COMMENT ON COLUMN
        return sb.toString();
    }

    private String defaultLiteral(String dataType, String def) {
        if (dataType == null) {
            return "'" + def.replace("'", "''") + "'";
        }
        String dt = dataType.toLowerCase(Locale.ROOT);
        boolean numeric = dt.contains("int") || dt.contains("decimal") || dt.contains("numeric")
            || dt.contains("float") || dt.contains("double") || dt.contains("bigint")
            || dt.contains("smallint") || dt.contains("tinyint") || dt.contains("bit");
        return numeric ? def : "'" + def.replace("'", "''") + "'";
    }
}
