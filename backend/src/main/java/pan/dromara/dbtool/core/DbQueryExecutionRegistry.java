package pan.dromara.dbtool.core;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks active JDBC statements so the owning user can request cancellation.
 */
@Component
public class DbQueryExecutionRegistry {

    private final ConcurrentHashMap<String, Execution> executions = new ConcurrentHashMap<>();

    public Execution begin(String executionId, String userId) {
        if (executionId == null || executionId.isBlank() || executionId.length() > 128
            || userId == null || userId.isBlank()) {
            throw new ServiceException("查询任务标识或用户无效");
        }
        Execution execution = new Execution(userId);
        if (executions.putIfAbsent(executionId, execution) != null) {
            throw new ServiceException("查询任务标识已在使用");
        }
        return execution;
    }

    public boolean cancel(String executionId, String userId) {
        if (executionId == null || executionId.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        Execution execution = executions.get(executionId);
        if (execution == null || !execution.userId.equals(userId)) {
            return false;
        }
        execution.cancelled.set(true);
        Statement statement = execution.statement.get();
        if (statement != null) {
            try {
                statement.cancel();
            } catch (Exception e) {
                throw new ServiceException("取消查询失败: " + e.getMessage());
            }
        }
        return true;
    }

    public void attach(String executionId, Execution execution, Statement statement) {
        execution.statement.set(statement);
        if (execution.cancelled.get()) {
            try {
                statement.cancel();
            } catch (Exception e) {
                throw new ServiceException("取消查询失败: " + e.getMessage());
            }
        }
    }

    public void finish(String executionId, Execution execution) {
        execution.statement.set(null);
        executions.remove(executionId, execution);
    }

    public static final class Execution {
        private final String userId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Statement> statement = new AtomicReference<>();

        private Execution(String userId) {
            this.userId = userId;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
