package pan.dromara.dbtool.service;

import pan.dromara.dbtool.domain.vo.DbResultVo;

import java.util.List;
import java.util.Map;

/**
 * 事务会话服务(P2)
 * <p>同一数据库连接上跨请求保持事务, 支持事务内执行、提交、回滚、关闭。</p>
 *
 * @author doubao
 */
public interface IDbTransactionService {

    /**
     * 开启事务会话
     *
     * @param userId 当前登录用户
     * @param connId 数据库连接配置ID
     * @return 会话信息(sessionId/connId/connName/创建时间)
     */
    Map<String, Object> begin(String userId, Long connId);

    /**
     * 开启事务会话(可选择目标库 dbName, MySQL 族/SQLServer 在会话连接上切换 catalog)
     */
    Map<String, Object> begin(String userId, Long connId, String dbName);

    /**
     * 在事务会话内执行单条 SQL(支持 SELECT 与写语句; 高危语句需确认 token)
     */
    DbResultVo execute(String userId, String sessionId, String sql, String confirmationToken);

    /**
     * 提交事务并归还连接
     */
    void commit(String userId, String sessionId);

    /**
     * 回滚事务并归还连接
     */
    void rollback(String userId, String sessionId);

    /**
     * 关闭事务会话(未提交变更回滚; 仅关闭本会话不结束事务中的其他会话)
     */
    void close(String userId, String sessionId);

    /**
     * 当前用户活动事务会话列表
     */
    List<Map<String, Object>> list(String userId);
}
