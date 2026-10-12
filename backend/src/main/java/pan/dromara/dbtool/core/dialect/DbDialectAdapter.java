package pan.dromara.dbtool.core.dialect;

import pan.dromara.dbtool.domain.vo.DbColumnVo;

/**
 * 数据库方言适配器
 * <p>为不同数据库类型提供: 元数据查询 SQL、分页包装、标识符引用与结构操作 DDL。
 * 元数据 SQL 的结果集列名与 MySQL information_schema 保持别名对齐,
 * 使上层解析逻辑无需按数据库类型分支; 分页/结构操作等有方言差异的 SQL 由各适配器独立实现。</p>
 *
 * @author doubao
 */
public interface DbDialectAdapter {

    /**
     * 当前适配器是否支持该数据库类型(忽略大小写)
     */
    boolean supports(String dbType);

    /**
     * 标识符引用(表名/列名/模式名)
     */
    String quote(String name);

    /**
     * 数据库/模式列表 SQL(结果第 1 列为名称, 系统库由上层按 SYS_SCHEMAS 过滤)
     */
    String databasesSql();

    /**
     * 表列表 SQL(? = schema; 列别名: TABLE_NAME / TABLE_COMMENT / TABLE_TYPE / ENGINE / TABLE_ROWS / CREATE_TIME)
     */
    String tablesSql();

    /**
     * 列结构 SQL(? = schema, ? = table; 列别名与 MySQL information_schema.COLUMNS 对齐:
     * COLUMN_NAME/DATA_TYPE/COLUMN_TYPE/IS_NULLABLE/COLUMN_DEFAULT/COLUMN_COMMENT/COLUMN_KEY/EXTRA/CHARACTER_SET_NAME/COLLATION_NAME)
     */
    String columnsSql();

    /**
     * 索引 SQL(? = schema, ? = table; 列别名: INDEX_NAME/COLUMN_NAME/SEQ_IN_INDEX/NON_UNIQUE/INDEX_TYPE/INDEX_COMMENT)
     */
    String indexesSql();

    /**
     * 约束 SQL(? = schema, ? = table; 列别名: CONSTRAINT_NAME/CONSTRAINT_TYPE/COLUMN_NAME/ORDINAL_POSITION)
     */
    String constraintsSql();

    /**
     * 外键 SQL(? = schema, ? = table; 列别名: CONSTRAINT_NAME/TABLE_NAME/COLUMN_NAME/ORDINAL_POSITION/
     * REFERENCED_TABLE_NAME/REFERENCED_COLUMN_NAME/UPDATE_RULE/DELETE_RULE)
     */
    String foreignKeysSql();

    /**
     * 引用本表的外键 SQL(? = schema, ? = 被引用表名; 列别名同 foreignKeysSql)
     */
    String referencesSql();

    /**
     * 触发器 SQL(? = schema, ? = table; 列别名: TRIGGER_NAME/ACTION_TIMING/EVENT_MANIPULATION/
     * EVENT_OBJECT_TABLE/ACTION_STATEMENT/CREATED)
     */
    String triggersSql();

    /**
     * 分页包装(保持原 SQL 语义)
     */
    String paginate(String sql, int offset, int size);

    /**
     * 建表 DDL 查询 SQL(schema/table); 返回 null 表示该方言无 SHOW CREATE TABLE, 需按列元数据重建
     */
    String showCreateTableSql(String schema, String table);

    /**
     * 建库 SQL(MySQL 的 IF NOT EXISTS / CHARACTER SET 为方言语法)
     */
    String createDatabaseSql(String dbName, String charset);

    /**
     * 删除表 SQL
     */
    String dropTableSql(String schema, String table);

    /**
     * 重命名表 SQL
     */
    String renameTableSql(String schema, String oldName, String newName);

    /**
     * 新增列 SQL(colDdl 由 {@link #columnDdl} 生成)
     */
    String addColumnSql(String schema, String table, String colDdl);

    /**
     * 删除列 SQL
     */
    String dropColumnSql(String schema, String table, String column);

    /**
     * 修改列 SQL(含改名); 返回 null 表示当前方言不支持该修改操作
     */
    String modifyColumnSql(String schema, String table, String oldColumnName, DbColumnVo column);

    /**
     * 生成列定义 DDL(类型/非空/默认/自增/注释的方言差异在此收敛)
     */
    String columnDdl(DbColumnVo c);
}
