package pan.dromara.dbtool.core;

import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.bo.DbGridChangeBo;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONUtil;
import org.dromara.common.core.exception.ServiceException;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates result-grid write requests and creates deterministic prepared-statement plans.
 */
public final class DbGridChangePlanner {

    private static final Pattern SIMPLE_TABLE_SELECT = Pattern.compile(
        "(?is)^\\s*select\\s+\\*\\s+from\\s+((?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|\\[[^\\]]+\\]|[\\w$-]+)(?:\\s*\\.\\s*(?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|\\[[^\\]]+\\]|[\\w$-]+))?)(.*)$"
    );
    private static final Pattern UNSUPPORTED_QUERY_CLAUSE = Pattern.compile(
        "(?i)\\b(join|union|group|having|distinct|into|for\\s+update)\\b"
    );
    private static final Pattern QUERY_BOUNDARY = Pattern.compile("(?i)\\b(where|order\\s+by|limit|offset|fetch|for)\\b");
    private static final Pattern TABLE_ALIAS = Pattern.compile(
        "(?is)^(?:as\\s+)?(?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|\\[[^\\]]+\\]|[\\w$-]+)$"
    );

    private DbGridChangePlanner() {
    }

    public static void validateRequest(DbGridChangeBo request, DbConn conn) {
        if (request == null || request.getConnId() == null || StrUtil.isBlank(request.getTable())
            || StrUtil.isBlank(request.getSourceSql()) || request.getChanges() == null || request.getChanges().isEmpty()) {
            throw new ServiceException("数据变更请求不完整");
        }
        if (request.getChanges().size() > 200) {
            throw new ServiceException("单次网格保存最多支持 200 行，请拆分后重试");
        }
        String dbType = StrUtil.nullToEmpty(conn.getDbType());
        if ("REDIS".equalsIgnoreCase(dbType) || "ELASTICSEARCH".equalsIgnoreCase(dbType)) {
            throw new ServiceException("结果网格行编辑仅支持关系型数据库");
        }
        String sql = DbAccessPolicy.normalizeLeadingComments(request.getSourceSql()).trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        Matcher matcher = SIMPLE_TABLE_SELECT.matcher(sql);
        if (!matcher.matches() || sql.indexOf(';') >= 0) {
            throw new ServiceException("仅支持编辑 SELECT * FROM 单表的结果；联表、表达式、聚合和多语句结果均为只读");
        }
        String suffix = matcher.group(2);
        if (UNSUPPORTED_QUERY_CLAUSE.matcher(suffix).find()) {
            throw new ServiceException("该查询包含联表或非基础表结果，不能通过结果网格修改");
        }
        String suffixStart = suffix.stripLeading();
        if (!suffixStart.isEmpty() && !QUERY_BOUNDARY.matcher(suffixStart).lookingAt()
            && !TABLE_ALIAS.matcher(suffixStart.split("(?i)\\b(where|order\\s+by|limit|offset|fetch)\\b", 2)[0].trim()).matches()) {
            throw new ServiceException("查询来源不是可安全编辑的单表结果");
        }
        String fromTail = QUERY_BOUNDARY.split(suffix, 2)[0];
        if (fromTail.contains(",") || Pattern.compile("(?i)\\bfrom\\b").matcher(suffix).find()) {
            throw new ServiceException("该查询包含多个数据源，不能通过结果网格修改");
        }
        String tableExpression = matcher.group(1);
        int dot = tableExpression.lastIndexOf('.');
        String parsedTable = unquoteIdentifier(tableExpression.substring(dot + 1).trim());
        if (!parsedTable.equalsIgnoreCase(request.getTable())) {
            throw new ServiceException("提交的数据表与查询来源不一致");
        }
        String requestedSchema = StrUtil.blankToDefault(request.getSchema(), conn.getDatabaseName());
        if (dot >= 0) {
            String parsedSchema = unquoteIdentifier(tableExpression.substring(0, dot).trim());
            if (!parsedSchema.equalsIgnoreCase(requestedSchema)) {
                throw new ServiceException("查询来源数据库与当前数据变更数据库不一致");
            }
        } else if (!StrUtil.isBlank(requestedSchema)
            && !requestedSchema.equalsIgnoreCase(conn.getDatabaseName())) {
            throw new ServiceException("未限定 Schema 的查询只能写入连接默认数据库");
        }
        if (StrUtil.isBlank(requestedSchema)) {
            throw new ServiceException("数据变更必须指定数据库/Schema");
        }
    }

    public static List<PlannedGridChange> buildPlan(DbGridChangeBo request, DbConn conn, List<DbColumnVo> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            throw new ServiceException("无法读取目标表结构");
        }
        Map<String, String> actualNames = new LinkedHashMap<>();
        List<String> primaryKeys = new ArrayList<>();
        for (DbColumnVo column : metadata) {
            if (StrUtil.isBlank(column.getColumnName())) {
                throw new ServiceException("目标表元数据包含空列名");
            }
            String key = column.getColumnName().toLowerCase(Locale.ROOT);
            if (actualNames.putIfAbsent(key, column.getColumnName()) != null) {
                throw new ServiceException("目标表包含大小写冲突的列名，不能安全地编辑");
            }
            if (Boolean.TRUE.equals(column.getPrimary()) || "PRI".equalsIgnoreCase(column.getColumnKey())) {
                primaryKeys.add(column.getColumnName());
            }
        }
        if (primaryKeys.isEmpty()) {
            throw new ServiceException("目标表没有主键，不能安全地更新或删除行");
        }
        String schema = StrUtil.blankToDefault(request.getSchema(), conn.getDatabaseName());
        String table = qualifiedName(conn.getDbType(), schema, request.getTable());
        List<PlannedGridChange> plan = new ArrayList<>();
        for (DbGridChangeBo.RowChange change : request.getChanges()) {
            if (change == null) {
                throw new ServiceException("变更内容不能为空");
            }
            String operation = StrUtil.nullToEmpty(change.getOperation()).toUpperCase(Locale.ROOT);
            Map<String, Object> values = normalizeValues(change.getValues(), actualNames);
            if ("INSERT".equals(operation)) {
                if (values.isEmpty()) {
                    throw new ServiceException("新增行至少需要一个字段值");
                }
                List<String> columns = new ArrayList<>(values.keySet());
                List<Object> parameters = new ArrayList<>(values.values());
                String sql = "INSERT INTO " + table + " (" + columns.stream()
                    .map(name -> quoteIdentifier(conn.getDbType(), name)).collect(Collectors.joining(", "))
                    + ") VALUES (" + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
                plan.add(new PlannedGridChange(operation, sql, parameters));
                continue;
            }
            if (!"UPDATE".equals(operation) && !"DELETE".equals(operation)) {
                throw new ServiceException("不支持的行变更类型: " + operation);
            }
            Map<String, Object> originals = normalizeValues(change.getOriginalValues(), actualNames);
            if (originals.size() != metadata.size()) {
                throw new ServiceException("原始行数据不完整，无法执行并发冲突检测；请刷新查询结果后重试");
            }
            for (String primaryKey : primaryKeys) {
                if (!originals.containsKey(primaryKey)) {
                    throw new ServiceException("原始行缺少主键 " + primaryKey + "，不能安全地修改或删除");
                }
            }
            List<Object> parameters = new ArrayList<>();
            String predicate = rowPredicate(conn.getDbType(), originals, parameters);
            String sql;
            if ("UPDATE".equals(operation)) {
                if (values.isEmpty()) {
                    throw new ServiceException("更新行没有可保存的字段");
                }
                String assignments = values.keySet().stream()
                    .map(name -> quoteIdentifier(conn.getDbType(), name) + " = ?")
                    .collect(Collectors.joining(", "));
                List<Object> updateParameters = new ArrayList<>(values.values());
                updateParameters.addAll(parameters);
                parameters = updateParameters;
                sql = "UPDATE " + table + " SET " + assignments + " WHERE " + predicate;
            } else {
                sql = "DELETE FROM " + table + " WHERE " + predicate;
            }
            plan.add(new PlannedGridChange(operation, sql, parameters));
        }
        return List.copyOf(plan);
    }

    public static String digest(List<PlannedGridChange> plan) {
        List<Map<String, Object>> canonical = plan.stream().map(change -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("operation", change.operation());
            item.put("sql", change.sql());
            item.put("parameters", change.parameters());
            return item;
        }).toList();
        return SecureUtil.sha256(JSONUtil.toJsonStr(canonical));
    }

    private static Map<String, Object> normalizeValues(Map<String, Object> source, Map<String, String> actualNames) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.startsWith("__")) {
                continue;
            }
            String actual = actualNames.get(key.toLowerCase(Locale.ROOT));
            if (actual == null) {
                throw new ServiceException("提交了目标表中不存在的列: " + key);
            }
            if (normalized.containsKey(actual)) {
                throw new ServiceException("重复提交列: " + key);
            }
            normalized.put(actual, entry.getValue());
        }
        return normalized;
    }

    private static String rowPredicate(String dbType, Map<String, Object> originalValues, List<Object> parameters) {
        List<String> conditions = new ArrayList<>();
        originalValues.forEach((name, value) -> {
            String identifier = quoteIdentifier(dbType, name);
            if (value == null) {
                conditions.add(identifier + " IS NULL");
            } else {
                conditions.add(identifier + " = ?");
                parameters.add(value);
            }
        });
        return String.join(" AND ", conditions);
    }

    private static String qualifiedName(String dbType, String schema, String table) {
        return quoteIdentifier(dbType, schema) + "." + quoteIdentifier(dbType, table);
    }

    private static String quoteIdentifier(String dbType, String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.indexOf('\0') >= 0) {
            throw new ServiceException("数据库标识符不能为空或包含非法字符");
        }
        String normalizedType = StrUtil.nullToEmpty(dbType).toUpperCase(Locale.ROOT);
        if ("SQLSERVER".equals(normalizedType) || "MSSQL".equals(normalizedType)) {
            return "[" + identifier.replace("]", "]]") + "]";
        }
        if ("MYSQL".equals(normalizedType) || "MARIADB".equals(normalizedType)) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String unquoteIdentifier(String identifier) {
        String value = identifier.trim();
        if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
            return value.substring(1, value.length() - 1).replace("``", "`");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        if (value.length() >= 2 && value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1).replace("]]", "]");
        }
        return value;
    }

    public record PlannedGridChange(String operation, String sql, List<Object> parameters) {
        public PlannedGridChange {
            parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        }
    }
}
