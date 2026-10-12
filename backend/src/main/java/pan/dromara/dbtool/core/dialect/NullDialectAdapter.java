package pan.dromara.dbtool.core.dialect;

import pan.dromara.dbtool.domain.vo.DbColumnVo;

/**
 * 未适配方言的安全降级适配器(C.3.34)
 * <p>注册表中尚无独立方言适配器的 SQL 类型(如 DERBY/MonetDB/Informix/HANA/Sybase/
 * Hive/Snowflake/Teradata/Trino/Presto 等)不再回退 {@link MySqlDialectAdapter}(避免以
 * MySQL 的 information_schema/反引号/LIMIT offset,size 语法错误执行), 而是返回本适配器:</p>
 * <ul>
 *   <li>所有元数据/DDL SQL 返回 null, 由上层抛出"未适配元数据浏览/结构操作"中文提示;</li>
 *   <li>分页返回 null, 由上层安全降级为不追加 LIMIT、仅用 setMaxRows 限制返回行数;</li>
 *   <li>能力矩阵(capabilitiesOf)对这些类型仅声明 connect + query, 前端不展示浏览/DDL 入口。</li>
 * </ul>
 * <p>与 dbx dialects 的对齐: dbx 为这些类型提供了 yaml 方言定义, 本工程按"可连接、
 * 基础查询可用、元数据/结构操作明确报错"的诚实边界处理, 待真实实例回归后再逐个补齐适配器。</p>
 *
 * @author doubao
 */
public class NullDialectAdapter implements DbDialectAdapter {

    /** 仅作 dialect() 未命中时的回退, 不参与按类型匹配 */
    @Override
    public boolean supports(String dbType) {
        return false;
    }

    @Override
    public String quote(String name) {
        return null;
    }

    @Override
    public String databasesSql() {
        return null;
    }

    @Override
    public String tablesSql() {
        return null;
    }

    @Override
    public String columnsSql() {
        return null;
    }

    @Override
    public String indexesSql() {
        return null;
    }

    @Override
    public String constraintsSql() {
        return null;
    }

    @Override
    public String foreignKeysSql() {
        return null;
    }

    @Override
    public String referencesSql() {
        return null;
    }

    @Override
    public String triggersSql() {
        return null;
    }

    @Override
    public String paginate(String sql, int offset, int size) {
        return null;
    }

    @Override
    public String showCreateTableSql(String schema, String table) {
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        return null;
    }

    @Override
    public String dropTableSql(String schema, String table) {
        return null;
    }

    @Override
    public String renameTableSql(String schema, String oldName, String newName) {
        return null;
    }

    @Override
    public String addColumnSql(String schema, String table, String colDdl) {
        return null;
    }

    @Override
    public String dropColumnSql(String schema, String table, String column) {
        return null;
    }

    @Override
    public String modifyColumnSql(String schema, String table, String oldColumnName, DbColumnVo column) {
        return null;
    }

    @Override
    public String columnDdl(DbColumnVo c) {
        return null;
    }
}
