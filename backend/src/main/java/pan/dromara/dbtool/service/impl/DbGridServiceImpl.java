package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.service.IDbGridService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 服务端网格查询实现: 子查询包装 + 列名白名单参数化过滤 + 方言分页
 *
 * @author doubao
 */
@Slf4j
@Service
public class DbGridServiceImpl implements IDbGridService {

    private static final Pattern SELECT_START = Pattern.compile(
        "^\\s*(select|with|show|describe|desc|explain)\\b", Pattern.CASE_INSENSITIVE);
    private static final int QUERY_TIMEOUT_SECONDS = 120;
    private static final int MAX_PAGE_SIZE = 1000;
    private static final Set<String> OP_WHITELIST =
        Set.of("eq", "ne", "gt", "ge", "lt", "le", "like", "in", "between", "isnull", "notnull");

    private final DbConnectionManager connectionManager;
    private final IDbMetaService metaService;

    public DbGridServiceImpl(DbConnectionManager connectionManager, IDbMetaService metaService) {
        this.connectionManager = connectionManager;
        this.metaService = metaService;
    }

    @Override
    public Map<String, Object> query(Long connId, String sql, int pageNum, int pageSize,
                                     List<Map<String, Object>> filters, Map<String, Object> sort) {
        if (connId == null) {
            throw new ServiceException("connId 不能为空");
        }
        String trimSql = validateQuery(sql);
        int size = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        int page = Math.max(pageNum, 1);
        int offset = (page - 1) * size;
        DbConn conn = metaService.getConn(connId);
        String dialectName = conn.getDbType() == null ? "mysql" : conn.getDbType().toLowerCase();

        // 1) 取原始 SQL 列元数据(白名单来源), setMaxRows(1) 兼容各方言
        List<String> columns;
        List<String> columnTypes;
        try (Connection connection = connectionManager.getConnection(conn);
             java.sql.Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.setMaxRows(1);
            try (ResultSet rs = stmt.executeQuery(trimSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                columns = new ArrayList<>();
                columnTypes = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                    columnTypes.add(meta.getColumnTypeName(i));
                }
            }
        } catch (SQLException e) {
            throw new ServiceException("查询元数据失败: " + translate(e));
        }
        if (columns.isEmpty()) {
            throw new ServiceException("查询未返回任何列");
        }
        Set<String> columnSet = new HashSet<>(columns);
        String quote = isMysqlLike(dialectName) ? "`" : "\"";

        // 2) 构造 WHERE(列名白名单 + 参数化值)
        WhereClause where = buildWhere(filters, columnSet, quote);

        // 3) 构造 ORDER BY(列名白名单)
        String orderSql = buildOrder(sort, columnSet, quote);

        // 4) COUNT 真实总量
        long total;
        String countSql = "SELECT COUNT(*) FROM (" + trimSql + ") g" + where.sql;
        try (Connection connection = connectionManager.getConnection(conn);
             PreparedStatement ps = connection.prepareStatement(countSql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindParams(ps, where.params);
            try (ResultSet rs = ps.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new ServiceException("统计行数失败: " + translate(e));
        }

        // 5) 分页查询(分页值由服务端生成, 数值拼接安全; 过滤值全部参数化)
        String pageSql = "SELECT * FROM (" + trimSql + ") g" + where.sql + orderSql + paginate(dialectName, offset, size);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = connectionManager.getConnection(conn);
             PreparedStatement ps = connection.prepareStatement(pageSql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindParams(ps, where.params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < columns.size(); i++) {
                        Object v = rs.getObject(i + 1);
                        row.put(columns.get(i), v == null ? null : String.valueOf(v));
                    }
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            throw new ServiceException("分页查询失败: " + translate(e));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("columnTypes", columnTypes);
        result.put("rows", rows);
        result.put("total", total);
        return result;
    }

    private String validateQuery(String sql) {
        String trimSql = StrUtil.trim(sql);
        if (StrUtil.isBlank(trimSql)) {
            throw new ServiceException("SQL 不能为空");
        }
        if (!SELECT_START.matcher(trimSql).find()) {
            throw new ServiceException("仅支持查询语句(select/show/describe/desc/explain/with)");
        }
        if (trimSql.endsWith(";")) {
            trimSql = trimSql.substring(0, trimSql.length() - 1).trim();
        }
        if (trimSql.contains(";")) {
            throw new ServiceException("仅支持单条 SQL");
        }
        return trimSql;
    }

    private static boolean isMysqlLike(String dialect) {
        return dialect.contains("mysql") || dialect.contains("maria") || dialect.contains("dm");
    }

    private static String paginate(String dialect, int offset, int size) {
        if (isMysqlLike(dialect)) {
            return " LIMIT " + size + " OFFSET " + offset;
        }
        // PG / Oracle / SQLServer: OFFSET..FETCH NEXT..ROWS ONLY
        return " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY";
    }

    private static WhereClause buildWhere(List<Map<String, Object>> filters, Set<String> columnSet, String quote) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (filters == null || filters.isEmpty()) {
            return new WhereClause("", params);
        }
        List<String> conds = new ArrayList<>();
        for (Map<String, Object> f : filters) {
            if (f == null) {
                continue;
            }
            String column = f.get("column") == null ? null : String.valueOf(f.get("column"));
            String op = f.get("op") == null ? null : String.valueOf(f.get("op")).toLowerCase();
            if (StrUtil.isBlank(column) || !columnSet.contains(column)) {
                throw new ServiceException("过滤列不在查询结果中: " + column);
            }
            if (op == null || !OP_WHITELIST.contains(op)) {
                throw new ServiceException("不支持的过滤操作符: " + op);
            }
            String q = quote + column.replace(quote, quote + quote) + quote;
            switch (op) {
                case "eq": conds.add(q + " = ?"); params.add(str(f.get("value"))); break;
                case "ne": conds.add(q + " <> ?"); params.add(str(f.get("value"))); break;
                case "gt": conds.add(q + " > ?"); params.add(str(f.get("value"))); break;
                case "ge": conds.add(q + " >= ?"); params.add(str(f.get("value"))); break;
                case "lt": conds.add(q + " < ?"); params.add(str(f.get("value"))); break;
                case "le": conds.add(q + " <= ?"); params.add(str(f.get("value"))); break;
                case "like": conds.add(q + " LIKE ?"); params.add(str(f.get("value"))); break;
                case "in": {
                    Object v = f.get("value");
                    List<?> items = v instanceof List ? (List<?>) v : List.of(str(v));
                    if (items.isEmpty()) {
                        conds.add("1 = 0");
                    } else {
                        StringBuilder in = new StringBuilder(q + " IN (");
                        for (int i = 0; i < items.size(); i++) {
                            in.append(i > 0 ? ",?" : "?");
                            params.add(str(items.get(i)));
                        }
                        in.append(")");
                        conds.add(in.toString());
                    }
                    break;
                }
                case "between": {
                    Object v = f.get("value");
                    if (v instanceof List && ((List<?>) v).size() == 2) {
                        List<?> items = (List<?>) v;
                        conds.add(q + " BETWEEN ? AND ?");
                        params.add(str(items.get(0)));
                        params.add(str(items.get(1)));
                    } else {
                        throw new ServiceException("between 需要 [min, max] 两个值");
                    }
                    break;
                }
                case "isnull": conds.add(q + " IS NULL"); break;
                case "notnull": conds.add(q + " IS NOT NULL"); break;
                default: break;
            }
        }
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
        return new WhereClause(sql.toString(), params);
    }

    private static String buildOrder(Map<String, Object> sort, Set<String> columnSet, String quote) {
        if (sort == null || sort.get("column") == null) {
            return "";
        }
        String column = String.valueOf(sort.get("column"));
        if (!columnSet.contains(column)) {
            throw new ServiceException("排序列不在查询结果中: " + column);
        }
        String order = "desc".equalsIgnoreCase(String.valueOf(sort.get("order"))) ? "DESC" : "ASC";
        String q = quote + column.replace(quote, quote + quote) + quote;
        return " ORDER BY " + q + " " + order;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        return String.valueOf(v);
    }

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v == null) {
                ps.setNull(i + 1, java.sql.Types.VARCHAR);
            } else {
                ps.setString(i + 1, String.valueOf(v));
            }
        }
    }

    private static String translate(SQLException e) {
        String m = e.getMessage();
        return m == null ? e.toString() : m;
    }

    private static final class WhereClause {
        final String sql;
        final List<Object> params;

        WhereClause(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params;
        }
    }
}
