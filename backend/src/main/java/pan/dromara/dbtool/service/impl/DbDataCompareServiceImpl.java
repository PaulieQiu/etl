package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.core.DbOperationConfirmationService;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.service.IDbDataCompareService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据对比实现: keyset 差集 + 全列指纹比较 + 分批参数化同步
 *
 * @author doubao
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbDataCompareServiceImpl implements IDbDataCompareService {

    private static final int QUERY_TIMEOUT_SECONDS = 300;
    /** 单表比较行数上限(超出提示加 whereSql 缩小范围) */
    private static final long MAX_COMPARE_ROWS = 200000;
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_\\u4e00-\\u9fa5]+$");

    private final DbConnectionManager connectionManager;
    private final IDbMetaService metaService;
    private final DbOperationConfirmationService confirmationService;

    @Override
    public Map<String, Object> preview(Map<String, Object> params) {
        CompareRequest request = parse(params, false);
        Map<String, RowEntry> sourceRows = loadRows(request, true);
        Map<String, RowEntry> targetRows = loadRows(request, false);
        long sourceCount = sourceRows.size();
        long targetCount = targetRows.size();

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        List<Map<String, Object>> diff = new ArrayList<>();
        long addedCount = 0;
        long missingCount = 0;
        long diffCount = 0;
        long sameCount = 0;
        final int sampleLimit = 50;

        for (Map.Entry<String, RowEntry> entry : sourceRows.entrySet()) {
            RowEntry target = targetRows.get(entry.getKey());
            if (target == null) {
                addedCount++;
                if (added.size() < sampleLimit) {
                    added.add(rowView(entry.getValue()));
                }
            } else if (!entry.getValue().fingerprint.equals(target.fingerprint)) {
                diffCount++;
                if (diff.size() < sampleLimit) {
                    Map<String, Object> view = rowView(entry.getValue());
                    view.put("_target", rowView(target));
                    diff.add(view);
                }
            } else {
                sameCount++;
            }
        }
        for (Map.Entry<String, RowEntry> entry : targetRows.entrySet()) {
            if (!sourceRows.containsKey(entry.getKey())) {
                missingCount++;
                if (missing.size() < sampleLimit) {
                    missing.add(rowView(entry.getValue()));
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sourceRows", sourceCount);
        summary.put("targetRows", targetCount);
        summary.put("added", addedCount);
        summary.put("missing", missingCount);
        summary.put("diff", diffCount);
        summary.put("same", sameCount);
        summary.put("sampleLimited", sampleLimit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("added", added);
        samples.put("missing", missing);
        samples.put("diff", diff);
        result.put("samples", samples);
        result.put("columns", request.allColumns);
        result.put("keyColumns", request.keyColumns);
        return result;
    }

    @Override
    public Map<String, Object> apply(Map<String, Object> params) {
        CompareRequest request = parse(params, true);
        DbConn target = metaService.getConn(request.targetConnId);
        DbAccessPolicy.requireWritable(target);
        String operations = StrUtil.nullToEmpty(String.valueOf(params.get("operations")));
        Set<String> ops = new LinkedHashSet<>();
        for (String op : operations.split(",")) {
            String o = op.trim().toUpperCase(Locale.ROOT);
            if (o.equals("INSERT") || o.equals("UPDATE") || o.equals("DELETE")) {
                ops.add(o);
            }
        }
        if (ops.isEmpty()) {
            throw new ServiceException("至少选择一种同步操作(insert/update/delete)");
        }
        // 数据变更一律要求一次性确认令牌
        String token = params.get("confirmationToken") == null ? null : String.valueOf(params.get("confirmationToken"));
        String payload = canonicalPayload(request, ops);
        if (!confirmationService.consume(token, request.targetConnId, payload, String.valueOf(params.get("userId")))) {
            throw new ServiceException("缺少有效的操作确认凭证, 请先发起确认");
        }
        int batchSize = params.get("batchSize") == null ? DEFAULT_BATCH_SIZE
            : Math.max(1, Math.min(Integer.parseInt(String.valueOf(params.get("batchSize"))), 5000));

        Map<String, RowEntry> sourceRows = loadRows(request, true);
        Map<String, RowEntry> targetRows = loadRows(request, false);

        Map<String, Object> applied = new LinkedHashMap<>();
        long insertApplied = 0;
        long updateApplied = 0;
        long deleteApplied = 0;
        List<Map<String, Object>> failed = new ArrayList<>();

        // 每批独立事务; 失败批次记录错误继续后续批次(检查点语义)
        try (Connection connection = connectionManager.getConnection(target)) {
            if (ops.contains("INSERT")) {
                long[] r = applyInsert(connection, request, sourceRows, targetRows, batchSize, failed);
                insertApplied = r[0];
            }
            if (ops.contains("UPDATE")) {
                long[] r = applyUpdate(connection, request, sourceRows, targetRows, batchSize, failed);
                updateApplied = r[0];
            }
            if (ops.contains("DELETE")) {
                long[] r = applyDelete(connection, request, sourceRows, targetRows, batchSize, failed);
                deleteApplied = r[0];
            }
        } catch (SQLException e) {
            throw new ServiceException("同步执行失败: " + e.getMessage());
        }

        applied.put("insert", insertApplied);
        applied.put("update", updateApplied);
        applied.put("delete", deleteApplied);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", applied);
        result.put("failed", failed);
        return result;
    }

    // ================= 行加载 =================

    private Map<String, RowEntry> loadRows(CompareRequest request, boolean source) {
        Long connId = source ? request.sourceConnId : request.targetConnId;
        String schema = source ? request.sourceSchema : request.targetSchema;
        String table = source ? request.sourceTable : request.targetTable;
        DbConn conn = metaService.getConn(connId);
        Map<String, RowEntry> rows = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        List<String> selectColumns = new ArrayList<>();
        selectColumns.addAll(request.keyColumns);
        for (String column : request.allColumns) {
            if (!selectColumns.contains(column)) {
                selectColumns.add(column);
            }
        }
        sql.append(String.join(", ", selectColumns.stream().map(this::quote).toList()));
        sql.append(" FROM ").append(quote(schema)).append(".").append(quote(table));
        if (StrUtil.isNotBlank(request.whereSql)) {
            sql.append(" WHERE ").append(request.whereSql);
        }
        try (Connection connection = connectionManager.getConnection(conn);
             Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.setFetchSize(1000);
            stmt.setMaxRows((int) Math.min(MAX_COMPARE_ROWS, Integer.MAX_VALUE));
            try (ResultSet rs = stmt.executeQuery(sql.toString())) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    StringBuilder key = new StringBuilder();
                    StringBuilder fingerprint = new StringBuilder();
                    List<Object> values = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        String column = meta.getColumnLabel(i);
                        Object value = rs.getObject(i);
                        String rendered = value == null ? "\u0000NULL" : String.valueOf(value);
                        if (request.keyColumns.contains(column)) {
                            key.append(rendered).append("\u0001");
                        }
                        if (request.compareColumns.contains(column)) {
                            fingerprint.append(rendered).append("\u0001");
                        }
                        values.add(value);
                    }
                    rows.put(key.toString(), new RowEntry(request.keyColumns, values, key.toString(), fingerprint.toString()));
                }
            }
            if (rows.size() >= MAX_COMPARE_ROWS) {
                throw new ServiceException("单表比较达到行数上限(20 万行), 请添加 whereSql 缩小范围后重试");
            }
        } catch (SQLException e) {
            throw new ServiceException("读取表数据失败(" + (source ? "源" : "目标") + "): " + e.getMessage());
        }
        return rows;
    }

    private Map<String, Object> rowView(RowEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        for (int i = 0; i < entry.columns.size(); i++) {
            view.put(entry.columns.get(i), entry.values.get(i));
        }
        return view;
    }

    // ================= 分批应用 =================

    private long[] applyInsert(Connection connection, CompareRequest request, Map<String, RowEntry> sourceRows,
                               Map<String, RowEntry> targetRows, int batchSize, List<Map<String, Object>> failed)
        throws SQLException {
        List<RowEntry> toInsert = sourceRows.values().stream()
            .filter(e -> !targetRows.containsKey(e.keyFingerprint())).toList();
        if (toInsert.isEmpty()) {
            return new long[]{0};
        }
        String columns = String.join(", ", request.allColumns.stream().map(this::quote).toList());
        String sql = "INSERT INTO " + quote(request.targetSchema) + "." + quote(request.targetTable)
            + " (" + columns + ") VALUES (" + String.join(", ", request.allColumns.stream().map(c -> "?").toList()) + ")";
        return applyBatches(connection, sql, request, toInsert, batchSize, failed);
    }

    private long[] applyUpdate(Connection connection, CompareRequest request, Map<String, RowEntry> sourceRows,
                               Map<String, RowEntry> targetRows, int batchSize, List<Map<String, Object>> failed)
        throws SQLException {
        List<RowEntry> toUpdate = new ArrayList<>();
        for (Map.Entry<String, RowEntry> entry : sourceRows.entrySet()) {
            RowEntry target = targetRows.get(entry.getKey());
            if (target != null && !entry.getValue().fingerprint.equals(target.fingerprint)) {
                toUpdate.add(entry.getValue());
            }
        }
        if (toUpdate.isEmpty()) {
            return new long[]{0};
        }
        List<String> setColumns = request.compareColumns.stream()
            .filter(c -> !request.keyColumns.contains(c)).toList();
        String sql = "UPDATE " + quote(request.targetSchema) + "." + quote(request.targetTable) + " SET "
            + String.join(", ", setColumns.stream().map(c -> quote(c) + " = ?").toList())
            + " WHERE " + String.join(" AND ", request.keyColumns.stream().map(c -> quote(c) + " = ?").toList());
        List<Object[]> rows = new ArrayList<>();
        for (RowEntry entry : toUpdate) {
            List<Object> bind = new ArrayList<>();
            for (String column : setColumns) {
                bind.add(valueOf(entry, column));
            }
            for (String column : request.keyColumns) {
                bind.add(valueOf(entry, column));
            }
            rows.add(bind.toArray());
        }
        return applyBatches(connection, sql, request, rows, batchSize, failed);
    }

    private long[] applyDelete(Connection connection, CompareRequest request, Map<String, RowEntry> sourceRows,
                               Map<String, RowEntry> targetRows, int batchSize, List<Map<String, Object>> failed)
        throws SQLException {
        List<RowEntry> toDelete = targetRows.values().stream()
            .filter(e -> !sourceRows.containsKey(e.keyFingerprint())).toList();
        if (toDelete.isEmpty()) {
            return new long[]{0};
        }
        String sql = "DELETE FROM " + quote(request.targetSchema) + "." + quote(request.targetTable)
            + " WHERE " + String.join(" AND ", request.keyColumns.stream().map(c -> quote(c) + " = ?").toList());
        List<Object[]> rows = new ArrayList<>();
        for (RowEntry entry : toDelete) {
            List<Object> bind = new ArrayList<>();
            for (String column : request.keyColumns) {
                bind.add(valueOf(entry, column));
            }
            rows.add(bind.toArray());
        }
        return applyBatches(connection, sql, request, rows, batchSize, failed);
    }

    /** 每批独立事务; 失败批次记录错误并继续(不整体回滚, 检查点语义) */
    private long[] applyBatches(Connection connection, String sql, CompareRequest request, List<?> rows,
                                int batchSize, List<Map<String, Object>> failed) throws SQLException {
        long applied = 0;
        int batchIndex = 0;
        connection.setAutoCommit(true);
        for (int i = 0; i < rows.size(); i += batchSize) {
            List<?> batch = rows.subList(i, Math.min(i + batchSize, rows.size()));
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int failedInBatch = 0;
                for (Object item : batch) {
                    Object[] bind = item instanceof RowEntry entry ? entry.bindValues(request) : (Object[]) item;
                    for (int b = 0; b < bind.length; b++) {
                        Object v = bind[b];
                        if (v == null) {
                            ps.setNull(b + 1, java.sql.Types.VARCHAR);
                        } else {
                            ps.setString(b + 1, String.valueOf(v));
                        }
                    }
                    ps.addBatch();
                }
                try {
                    int[] results = ps.executeBatch();
                    for (int r : results) {
                        if (r == Statement.EXECUTE_FAILED) {
                            failedInBatch++;
                        }
                    }
                    applied += batch.size() - failedInBatch;
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    failed.add(Map.of("batch", batchIndex, "operation", sql.substring(0, 6).trim(), "count", batch.size(), "error", e.getMessage()));
                }
            }
            batchIndex++;
        }
        connection.setAutoCommit(true);
        return new long[]{applied};
    }

    // ================= 参数解析 =================

    private CompareRequest parse(Map<String, Object> params, boolean forApply) {
        if (params == null) {
            throw new ServiceException("参数不能为空");
        }
        CompareRequest request = new CompareRequest();
        request.sourceConnId = requireLong(params.get("sourceConnId"), "sourceConnId");
        request.targetConnId = requireLong(params.get("targetConnId"), "targetConnId");
        request.sourceSchema = requireString(params.get("sourceSchema"), "sourceSchema");
        request.targetSchema = requireString(params.get("targetSchema"), "targetSchema");
        request.sourceTable = requireIdentifier(params.get("sourceTable"), "sourceTable");
        request.targetTable = requireIdentifier(params.get("targetTable"), "targetTable");
        requireCompatible(metaService.getConn(request.sourceConnId), metaService.getConn(request.targetConnId));
        List<String> keyColumns = stringList(params.get("keyColumns"));
        if (keyColumns.isEmpty()) {
            // 默认取源表主键
            keyColumns = primaryKeys(request.sourceConnId, request.sourceSchema, request.sourceTable);
        }
        if (keyColumns.isEmpty()) {
            throw new ServiceException("未指定匹配键且源表无主键, 请手动选择 keyColumns");
        }
        List<String> ignoreColumns = stringList(params.get("ignoreColumns"));
        List<String> allColumns = new ArrayList<>();
        for (DbColumnVo column : metaService.columns(request.sourceConnId, request.sourceSchema, request.sourceTable)) {
            if (column != null && StrUtil.isNotBlank(column.getColumnName())
                && !ignoreColumns.contains(column.getColumnName())) {
                allColumns.add(column.getColumnName());
            }
        }
        for (String key : keyColumns) {
            if (!allColumns.contains(key)) {
                throw new ServiceException("匹配键不在源表列中: " + key);
            }
        }
        List<String> compareColumns = new ArrayList<>(allColumns);
        for (String column : allColumns) {
            if (ignoreColumns.contains(column)) {
                compareColumns.remove(column);
            }
        }
        request.keyColumns = keyColumns;
        request.allColumns = allColumns;
        request.compareColumns = compareColumns;
        request.whereSql = params.get("whereSql") == null ? null : String.valueOf(params.get("whereSql")).trim();
        return request;
    }

    private List<String> primaryKeys(Long connId, String schema, String table) {
        List<String> keys = new ArrayList<>();
        for (DbColumnVo column : metaService.columns(connId, schema, table)) {
            if (column != null && "PRI".equalsIgnoreCase(column.getColumnKey())) {
                keys.add(column.getColumnName());
            }
        }
        return keys;
    }

    private void requireCompatible(DbConn source, DbConn target) {
        if (source == null || target == null) {
            throw new ServiceException("源或目标数据库连接不存在");
        }
        String sourceType = normalize(source.getDbType());
        String targetType = normalize(target.getDbType());
        if ((!sourceType.equals("mysql") && !sourceType.equals("mariadb"))
            || (!targetType.equals("mysql") && !targetType.equals("mariadb"))) {
            throw new ServiceException("Data Compare 当前仅支持 MySQL/MariaDB 连接");
        }
    }

    private Long requireLong(Object value, String name) {
        if (value == null) {
            throw new ServiceException(name + " 不能为空");
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new ServiceException(name + " 无效");
        }
    }

    private String requireString(Object value, String name) {
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            throw new ServiceException(name + " 不能为空");
        }
        return String.valueOf(value).trim();
    }

    private String requireIdentifier(Object value, String name) {
        String identifier = requireString(value, name);
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new ServiceException(name + " 只能是字母数字下划线或中文");
        }
        return identifier;
    }

    private List<String> stringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    list.add(String.valueOf(item));
                }
            }
        }
        return list;
    }

    private static Object valueOf(RowEntry entry, String column) {
        int idx = entry.columns.indexOf(column);
        return idx >= 0 ? entry.values.get(idx) : null;
    }

    private String quote(String identifier) {
        return "`" + StrUtil.nullToEmpty(identifier).replace("`", "``") + "`";
    }

    private String canonicalPayload(CompareRequest request, Set<String> ops) {
        return "COMPARE_APPLY:" + request.targetConnId + ":" + request.targetSchema + "." + request.targetTable
            + ":" + String.join(",", request.keyColumns) + ":" + String.join(",", ops);
    }

    private String normalize(String value) {
        return StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /** 行条目: 键指纹/值列表/比较指纹 */
    private static final class RowEntry {
        private final List<String> columns;
        private final List<Object> values;
        private final String keyFingerprint;
        private final String fingerprint;

        RowEntry(List<String> columns, List<Object> values, String keyFingerprint, String fingerprint) {
            this.columns = columns;
            this.values = values;
            this.keyFingerprint = keyFingerprint;
            this.fingerprint = fingerprint;
        }

        String keyFingerprint() {
            return keyFingerprint;
        }

        Object[] bindValues(CompareRequest request) {
            List<Object> bind = new ArrayList<>();
            for (String column : request.allColumns) {
                bind.add(valueOf(this, column));
            }
            return bind.toArray();
        }
    }

    private static final class CompareRequest {
        private Long sourceConnId;
        private Long targetConnId;
        private String sourceSchema;
        private String targetSchema;
        private String sourceTable;
        private String targetTable;
        private List<String> keyColumns;
        private List<String> allColumns;
        private List<String> compareColumns;
        private String whereSql;
    }
}
