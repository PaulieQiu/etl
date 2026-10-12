package pan.dromara.dbtool.service;

import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.bo.DbGridChangeBo;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbConstraintVo;
import pan.dromara.dbtool.domain.vo.DbForeignKeyVo;
import pan.dromara.dbtool.domain.vo.DbGridChangePreviewVo;
import pan.dromara.dbtool.domain.vo.DbIndexVo;
import pan.dromara.dbtool.domain.vo.DbReferenceVo;
import pan.dromara.dbtool.domain.vo.DbResultVo;
import pan.dromara.dbtool.domain.vo.DbTableVo;
import pan.dromara.dbtool.domain.vo.DbTriggerVo;

import java.util.List;
import java.util.Map;

/**
 * 数据库元数据 服务层
 * <p>仿 DBeaver: 库/表/列/索引/DDL + 表结构维护 + 数据查询执行</p>
 *
 * @author doubao
 */
public interface IDbMetaService {

    /**
     * 获取连接配置(含解密密码)
     */
    DbConn getConn(Long connId);

    /**
     * 数据库列表
     */
    List<String> databases(Long connId);

    /**
     * 表列表
     */
    List<DbTableVo> tables(Long connId, String schema);

    /**
     * 列结构
     */
    List<DbColumnVo> columns(Long connId, String schema, String table);

    /**
     * 索引列表
     */
    List<DbIndexVo> indexes(Long connId, String schema, String table);

    /**
     * 约束列表
     */
    List<DbConstraintVo> constraints(Long connId, String schema, String table);

    /**
     * 外键列表(本表引用其他表)
     */
    List<DbForeignKeyVo> foreignKeys(Long connId, String schema, String table);

    /**
     * 引用列表(其他表引用本表)
     */
    List<DbReferenceVo> references(Long connId, String schema, String table);

    /**
     * 触发器列表
     */
    List<DbTriggerVo> triggers(Long connId, String schema, String table);

    /**
     * 建表 DDL
     */
    String ddl(Long connId, String schema, String table);

    /**
     * 创建数据库
     */
    void createDatabase(Long connId, String dbName, String charset);

    /**
     * 删除表
     */
    void dropTable(Long connId, String schema, String table, String confirmationToken, String userId);

    /**
     * 重命名表
     */
    void renameTable(Long connId, String schema, String oldName, String newName);

    /**
     * 新增列
     */
    void addColumn(Long connId, String schema, String table, DbColumnVo column);

    /**
     * 删除列
     */
    void dropColumn(Long connId, String schema, String table, String columnName, String confirmationToken, String userId);

    /**
     * 修改列
     */
    void modifyColumn(Long connId, String schema, String table, String oldColumnName, DbColumnVo column);

    /**
     * 执行 DDL(建表等)
     */
    void executeDdl(Long connId, String sql);

    /**
     * 查询数据(分页)
     */
    DbResultVo query(Long connId, String sql, Integer pageNum, Integer pageSize);

    /**
     * 执行 SQL(查询或更新)
     */
    DbResultVo execute(Long connId, String sql, String confirmationToken, String userId);

    /**
     * Execute SQL under a cancellable, user-owned request identifier.
     */
    DbResultVo execute(Long connId, String sql, String confirmationToken, String userId, String executionId);

    /**
     * Execute SQL under a cancellable, user-owned request identifier, on a chosen database (dbName).
     */
    DbResultVo execute(Long connId, String sql, String confirmationToken, String userId, String executionId, String dbName);

    /**
     * Cancel an active SQL statement owned by the current user.
     */
    boolean cancelQuery(String executionId, String userId);

    /**
     * Validate and prepare row changes from a simple single-table SELECT.
     */
    DbGridChangePreviewVo previewGridChanges(DbGridChangeBo changes, String userId);

    /**
     * Atomically apply a previously previewed set of row changes.
     */
    Integer commitGridChanges(DbGridChangeBo changes, String confirmationToken, String userId);

    // ==================== Redis 可视化管理 ====================

    /**
     * Redis 键列表(SCAN)
     */
    Map<String, Object> redisKeys(Long connId, String db, String pattern, String cursor, int count);

    /**
     * Redis 键信息
     */
    Map<String, Object> redisKeyInfo(Long connId, String db, String key);

    /**
     * Redis 键值读取
     */
    Map<String, Object> redisValue(Long connId, String db, String key, String type);

    /**
     * Redis 键写入
     */
    void redisSet(Long connId, String db, String key, String type, String value, Long ttl, List<Map<String, String>> fields);

    /**
     * Redis 键删除
     */
    void redisDel(Long connId, String db, List<String> keys);

    /**
     * Redis TTL 更新
     */
    void redisExpire(Long connId, String db, String key, Long ttl);

    /**
     * Redis INFO
     */
    Map<String, Object> redisInfo(Long connId, String db);

    /**
     * Redis CLI
     */
    Object redisCli(Long connId, String db, String command);

    // ==================== 数据迁移 & Excel 导入 ====================

    /**
     * 跨库表数据迁移(支持不同数据库类型: MySQL/PostgreSQL/Oracle/DM/SQLServer)
     *
     * @param srcConnId     源连接
     * @param srcSchema     源库/模式
     * @param srcTable      源表
     * @param dstConnId     目标连接
     * @param dstSchema     目标库/模式
     * @param dstTable      目标表(新建表名或已有表)
     * @param withStructure 是否建表/同步表结构
     * @param withData      是否搬数据
     * @param dropTarget    建表前是否删除已存在的目标表
     * @param fieldMappings 字段映射 [{src:源列, dst:目标列}], 为空时按同名列映射
     */
    Map<String, Object> migrateTable(Long srcConnId, String srcSchema, String srcTable,
                                     Long dstConnId, String dstSchema, String dstTable,
                                     Boolean withStructure, Boolean withData, Boolean dropTarget,
                                     List<Map<String, String>> fieldMappings);

    /**
     * Excel 多工作表导入(每 sheet 映射一张目标表, 支持新建/追加)
     */
    Map<String, Object> importExcel(Long connId, String schema, List<Map<String, Object>> sheets, org.springframework.web.multipart.MultipartFile file);
}
