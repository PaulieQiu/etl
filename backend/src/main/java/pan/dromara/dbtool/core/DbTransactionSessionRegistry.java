package pan.dromara.dbtool.core;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 事务会话注册表(P2 事务会话)
 * <p>会话持有从连接池借出的 Connection 并关闭 autoCommit, 跨请求复用;
 * 同一会话内语句串行执行, commit/rollback 后连接归还连接池并清理状态。</p>
 *
 * @author doubao
 */
@Component
public class DbTransactionSessionRegistry {

    /** 每用户同时最多活动会话数 */
    private static final int MAX_SESSIONS_PER_USER = 5;
    /** 会话空闲超时(毫秒), 超过后后台回收(回滚未提交变更) */
    private static final long IDLE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, TransactionSession> sessions = new ConcurrentHashMap<>();

    /**
     * 创建事务会话(连接已由调用方 setAutoCommit(false))
     */
    public TransactionSession begin(String userId, Long connId, Connection connection) {
        long active = sessions.values().stream()
            .filter(s -> s.userId.equals(userId)).count();
        if (active >= MAX_SESSIONS_PER_USER) {
            throw new ServiceException("同时最多开启 " + MAX_SESSIONS_PER_USER + " 个事务会话, 请先提交或关闭其他会话");
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        TransactionSession ts = new TransactionSession(sessionId, userId, connId, connection);
        sessions.put(sessionId, ts);
        return ts;
    }

    /**
     * 获取会话并校验归属与存在性(校验通过即刷新活跃时间)
     */
    public TransactionSession getValid(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            throw new ServiceException("事务会话标识或用户无效");
        }
        TransactionSession ts = sessions.get(sessionId);
        if (ts == null || !ts.userId.equals(userId)) {
            throw new ServiceException("事务会话不存在或不属于当前用户");
        }
        ts.lastActiveAt = System.currentTimeMillis();
        return ts;
    }

    /**
     * 移除会话(调用方负责 commit/rollback 后归还连接)
     */
    public TransactionSession remove(String userId, String sessionId) {
        TransactionSession ts = sessions.get(sessionId);
        if (ts == null || !ts.userId.equals(userId)) {
            throw new ServiceException("事务会话不存在或不属于当前用户");
        }
        sessions.remove(sessionId, ts);
        return ts;
    }

    /**
     * 当前用户活动会话列表(不含连接对象)
     */
    public List<SessionInfo> listByUser(String userId) {
        return sessions.values().stream()
            .filter(s -> s.userId.equals(userId))
            .map(SessionInfo::of)
            .collect(Collectors.toList());
    }

    /**
     * 后台回收空闲超时会话: 回滚未提交变更并归还连接
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void sweepExpired() {
        long now = System.currentTimeMillis();
        for (TransactionSession ts : sessions.values()) {
            if (now - ts.lastActiveAt > IDLE_TIMEOUT_MS) {
                if (sessions.remove(ts.sessionId, ts)) {
                    rollbackAndRelease(ts);
                }
            }
        }
    }

    /**
     * 回滚并归还连接(重置 autoCommit/readOnly 并清警告, 避免连接池状态泄漏)
     */
    public void rollbackAndRelease(TransactionSession ts) {
        Connection connection = ts.connection;
        try {
            connection.rollback();
        } catch (SQLException e) {
            // 回滚失败不阻塞归还
        }
        releaseQuietly(connection);
    }

    /**
     * 提交并归还连接
     */
    public void commitAndRelease(TransactionSession ts) {
        Connection connection = ts.connection;
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new ServiceException("事务提交失败: " + e.getMessage());
        } finally {
            releaseQuietly(connection);
        }
    }

    private static void releaseQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignore) {
            // 忽略重置失败
        }
        try {
            connection.setReadOnly(false);
        } catch (SQLException ignore) {
            // 忽略重置失败
        }
        try {
            connection.clearWarnings();
        } catch (SQLException ignore) {
            // 忽略清理失败
        }
        try {
            connection.close();
        } catch (SQLException ignore) {
            // 忽略关闭失败
        }
    }

    /**
     * 事务会话(连接池借出连接, 会话内串行)
     */
    public static final class TransactionSession {
        private final String sessionId;
        private final String userId;
        private final Long connId;
        private final Connection connection;
        private final long createdAt;
        private volatile long lastActiveAt;

        private TransactionSession(String sessionId, String userId, Long connId, Connection connection) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.connId = connId;
            this.connection = connection;
            this.createdAt = System.currentTimeMillis();
            this.lastActiveAt = this.createdAt;
        }

        public String getSessionId() {
            return sessionId;
        }

        public Long getConnId() {
            return connId;
        }

        public Connection getConnection() {
            return connection;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }

    /**
     * 会话对外信息(不含连接)
     */
    public static final class SessionInfo {
        private String sessionId;
        private Long connId;
        private long createdAt;
        private long lastActiveAt;

        private static SessionInfo of(TransactionSession ts) {
            SessionInfo info = new SessionInfo();
            info.sessionId = ts.sessionId;
            info.connId = ts.connId;
            info.createdAt = ts.createdAt;
            info.lastActiveAt = ts.lastActiveAt;
            return info;
        }

        public String getSessionId() {
            return sessionId;
        }

        public Long getConnId() {
            return connId;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getLastActiveAt() {
            return lastActiveAt;
        }
    }
}
