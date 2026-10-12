package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.core.DbOperationConfirmationService;
import pan.dromara.dbtool.core.DbTransactionSessionRegistry;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbResultVo;
import pan.dromara.dbtool.service.IDbMetaService;
import pan.dromara.dbtool.service.IDbTransactionService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 事务会话服务实现
 *
 * @author doubao
 */
@Slf4j
@Service
public class DbTransactionServiceImpl implements IDbTransactionService {

    /** 支持切换当前库(catalog)的类型: MySQL 协议族 + SQLServer */
    private static final Set<String> CATALOG_SWITCH_TYPES = Set.of(
        "MYSQL", "MARIADB", "TIDB", "DORIS", "STARROCKS", "OCEANBASE", "GBASE", "SQLSERVER");

    /** 事务内查询结果行数上限 */
    private static final int TXN_MAX_ROWS = 200;
    /** 事务内语句超时(秒) */
    private static final int TXN_TIMEOUT_SECONDS = 120;

    private static final Pattern SELECT_PATTERN = Pattern.compile(
        "^\\s*(select|show|describe|desc|explain|with)\\b", Pattern.CASE_INSENSITIVE);

    private final DbTransactionSessionRegistry sessionRegistry;
    private final DbConnectionManager connectionManager;
    private final DbOperationConfirmationService confirmationService;
    private final IDbMetaService metaService;

    public DbTransactionServiceImpl(DbTransactionSessionRegistry sessionRegistry,
                                    DbConnectionManager connectionManager,
                                    DbOperationConfirmationService confirmationService,
                                    IDbMetaService metaService) {
        this.sessionRegistry = sessionRegistry;
        this.connectionManager = connectionManager;
        this.confirmationService = confirmationService;
        this.metaService = metaService;
    }

    @Override
    public Map<String, Object> begin(String userId, Long connId) {
        return begin(userId, connId, null);
    }

    @Override
    public Map<String, Object> begin(String userId, Long connId, String dbName) {
        DbConn conn = metaService.getConn(connId);
        if (DbAccessPolicy.isReadOnly(conn)) {
            throw new ServiceException("只读连接不能开启事务会话");
        }
        if ("REDIS".equalsIgnoreCase(conn.getDbType()) || "ELASTICSEARCH".equalsIgnoreCase(conn.getDbType())) {
            throw new ServiceException("仅支持 JDBC 关系型数据库开启事务会话");
        }
        Connection connection = connectionManager.getConnection(conn);
        if (conn.getDbType() != null
                && CATALOG_SWITCH_TYPES.contains(conn.getDbType().trim().toUpperCase(Locale.ROOT))) {
            String target = StrUtil.isNotBlank(dbName) ? dbName : conn.getDatabaseName();
            if (StrUtil.isNotBlank(target)) {
                try (Statement st = connection.createStatement()) {
                    st.execute("USE `" + target.replace("`", "``") + "`");
                } catch (SQLException e) {
                    throw new ServiceException("切换数据库失败: " + e.getMessage());
                }
            }
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new ServiceException("开启事务失败: " + e.getMessage());
        }
        DbTransactionSessionRegistry.TransactionSession session = sessionRegistry.begin(userId, connId, connection);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sessionId", session.getSessionId());
        info.put("connId", connId);
        info.put("connName", conn.getConnName());
        info.put("beginTime", session.getCreatedAt());
        return info;
    }

    @Override
    public DbResultVo execute(String userId, String sessionId, String sql, String confirmationToken) {
        DbTransactionSessionRegistry.TransactionSession session = sessionRegistry.getValid(userId, sessionId);
        DbConn conn = metaService.getConn(session.getConnId());
        String trimSql = DbAccessPolicy.normalizeLeadingComments(sql);
        if (StrUtil.isBlank(trimSql)) {
            throw new ServiceException("SQL 不能为空");
        }
        if (DbAccessPolicy.isReadOnly(conn)) {
            DbAccessPolicy.requireReadOnlyStatement(trimSql);
        }
        if (trimSql.endsWith(";")) {
            trimSql = trimSql.substring(0, trimSql.length() - 1).trim();
        }
        if (trimSql.contains(";")) {
            throw new ServiceException("事务会话内仅支持单条 SQL");
        }
        // 高危语句确认(与工作台执行同一套规则)
        String dangerWarning = DbAccessPolicy.dangerWarning(trimSql);
        if (dangerWarning != null && !confirmationService.consume(confirmationToken, session.getConnId(), trimSql, userId)) {
            DbResultVo warn = new DbResultVo();
            warn.setSuccess(false);
            warn.setHighRisk(true);
            warn.setWarning(dangerWarning);
            warn.setMessage("高危SQL, 需确认后执行");
            warn.setSqlType("dangerous");
            warn.setConfirmationToken(confirmationService.issue(session.getConnId(), trimSql, userId));
            return warn;
        }
        long start = System.currentTimeMillis();
        DbResultVo result = new DbResultVo();
        Connection connection = session.getConnection();
        try (Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(TXN_TIMEOUT_SECONDS);
            if (SELECT_PATTERN.matcher(trimSql).find()) {
                result.setSqlType("select");
                List<Map<String, Object>> rows = new ArrayList<>();
                stmt.setMaxRows(TXN_MAX_ROWS);
                try (ResultSet rs = stmt.executeQuery(trimSql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    List<String> columns = new ArrayList<>();
                    List<String> columnTypes = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(meta.getColumnLabel(i));
                        columnTypes.add(meta.getColumnTypeName(i));
                    }
                    result.setColumns(columns);
                    result.setColumnTypes(columnTypes);
                    while (rs.next() && rows.size() < TXN_MAX_ROWS) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            Object v = rs.getObject(i);
                            row.put(meta.getColumnLabel(i), v == null ? null : String.valueOf(v));
                        }
                        rows.add(row);
                    }
                }
                result.setRows(rows);
                result.setTotal((long) rows.size());
            } else {
                result.setSqlType("update");
                boolean hasResultSet = stmt.execute(trimSql);
                int affected = stmt.getUpdateCount();
                result.setAffectedRows(!hasResultSet && affected >= 0 ? affected : 0);
            }
        } catch (SQLException e) {
            // 语句错误: 该语句回滚, 事务保持打开, 用户可继续执行或回滚
            throw new ServiceException(translateSqlError(e.getMessage()));
        }
        result.setSuccess(true);
        result.setMessage("执行成功(事务未提交, 请确认后提交或回滚)");
        result.setCostMs(System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public void commit(String userId, String sessionId) {
        DbTransactionSessionRegistry.TransactionSession session = sessionRegistry.remove(userId, sessionId);
        sessionRegistry.commitAndRelease(session);
    }

    @Override
    public void rollback(String userId, String sessionId) {
        DbTransactionSessionRegistry.TransactionSession session = sessionRegistry.remove(userId, sessionId);
        sessionRegistry.rollbackAndRelease(session);
    }

    @Override
    public void close(String userId, String sessionId) {
        rollback(userId, sessionId);
    }

    @Override
    public List<Map<String, Object>> list(String userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DbTransactionSessionRegistry.SessionInfo info : sessionRegistry.listByUser(userId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", info.getSessionId());
            item.put("connId", info.getConnId());
            item.put("beginTime", info.getCreatedAt());
            item.put("lastActiveAt", info.getLastActiveAt());
            try {
                DbConn conn = metaService.getConn(info.getConnId());
                item.put("connName", conn.getConnName());
            } catch (Exception ignore) {
                item.put("connName", "连接已删除");
            }
            result.add(item);
        }
        return result;
    }

    private static String translateSqlError(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "SQL 执行失败";
        }
        return raw;
    }
}
