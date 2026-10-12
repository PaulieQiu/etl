package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.DbConnCrypto;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbTableVo;
import pan.dromara.dbtool.service.IDbAiAssistService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI SQL 助手实现(P5): 离线元数据驱动, 不依赖外部大模型
 * <ul>
 *   <li>assist: 自然语言需求 → 元数据上下文 → 规则生成 SQL(只返回不执行)</li>
 *   <li>explain: SQL 拆解/类型/涉及表/风险分级</li>
 *   <li>安全: 连接白名单(dbtool.ai.allowConns, 空=默认拒绝所有) + 只读语义 + 危险语句检测 + 审计落表</li>
 * </ul>
 *
 * @author doubao
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbAiAssistServiceImpl implements IDbAiAssistService {

    private static final int MAX_TABLES = 100;
    private static final int MAX_COLUMNS_PER_TABLE = 200;

    /** 危险语句正则(写入/DDL 前置关键词) */
    private static final Pattern WRITE_LEAD = Pattern.compile(
        "^\\s*(insert|update|delete|replace|truncate|drop|alter|create|grant|revoke|call|rename|set\\s+global|load\\s+data)\\b",
        Pattern.CASE_INSENSITIVE);
    /** 表名提取: FROM/JOIN/UPDATE/INTO/ALTER TABLE/DROP TABLE 后的标识符 */
    private static final Pattern TABLE_REF = Pattern.compile(
        "(?:from|join|update|into|table)\\s+([`\"\\[\\w.]+)`?\"?\\[?",
        Pattern.CASE_INSENSITIVE);

    private final IDbMetaService metaService;
    private final JdbcTemplate jdbcTemplate;

    /** AI/MCP 允许访问的连接白名单(逗号分隔 connId); 空 = 默认拒绝所有连接(安全默认) */
    @Value("${dbtool.ai.allowConns:}")
    private String allowConns;
    /** 审计保留天数(<=0 关闭按天清理) */
    @Value("${dbtool.ai.auditRetentionDays:90}")
    private int auditRetentionDays;
    /** 审计最大保留条数(<=0 关闭按条数清理) */
    @Value("${dbtool.ai.auditMaxRows:10000}")
    private int auditMaxRows;

    @PostConstruct
    public void init() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dbtool_ai_audit (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT DEFAULT NULL COMMENT '用户ID',
                    conn_id BIGINT DEFAULT NULL COMMENT '连接ID',
                    action VARCHAR(32) DEFAULT NULL COMMENT 'AI_ASSIST/AI_EXPLAIN/MCP_CALL',
                    tool VARCHAR(64) DEFAULT NULL COMMENT 'MCP工具名/动作',
                    input_summary VARCHAR(500) DEFAULT NULL COMMENT '输入摘要',
                    output_summary VARCHAR(500) DEFAULT NULL COMMENT '输出摘要',
                    risk_level VARCHAR(16) DEFAULT 'LOW' COMMENT 'LOW/MEDIUM/HIGH',
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
                    create_dept BIGINT DEFAULT NULL COMMENT '创建部门',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    KEY idx_audit_user_time (user_id, created_at)
                ) COMMENT='dbtool AI/MCP 调用审计'
                """);
        } catch (Exception e) {
            log.warn("dbtool_ai_audit 建表失败: {}", e.getMessage());
        }
        // P5 边界: 审计保留策略(按天/按条数)
        cleanupAudit();
    }

    @Override
    public Map<String, Object> assist(Long connId, String schema, String request, Long userId) {
        if (StrUtil.isBlank(request)) {
            throw new ServiceException("需求描述不能为空");
        }
        DbConn conn = requireAllowed(connId);
        String sql = request.trim();
        String risk = "LOW";
        String generated;
        if (isExplainRequest(sql)) {
            // 用户粘贴 SQL 请求解释/优化
            generated = buildExplainSql(connId, schema, sql);
            risk = "MEDIUM";
        } else {
            generated = generateByRules(connId, schema, sql);
            if (WRITE_LEAD.matcher(generated).find()) {
                risk = "MEDIUM";
            }
            if (DbAccessPolicy.dangerWarning(generated) != null) {
                risk = "HIGH";
            }
        }
        audit(userId, connId, "AI_ASSIST", "assist", StrUtil.maxLength(sql, 300),
            StrUtil.maxLength(generated, 300), risk);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sql", generated);
        result.put("riskLevel", risk);
        result.put("readOnly", DbAccessPolicy.isReadOnlyStatement(generated));
        result.put("warning", DbAccessPolicy.dangerWarning(generated));
        result.put("connName", conn.getConnName());
        result.put("schema", StrUtil.blankToDefault(schema, ""));
        return result;
    }

    @Override
    public Map<String, Object> explain(Long connId, String sql, Long userId) {
        if (StrUtil.isBlank(sql)) {
            throw new ServiceException("SQL 不能为空");
        }
        DbConn conn = requireAllowed(connId);
        String normalized = DbAccessPolicy.normalizeLeadingComments(sql);
        if (normalized.isEmpty()) {
            throw new ServiceException("SQL 内容为空(可能全部为注释)");
        }
        String firstKeyword = firstKeyword(normalized);
        boolean readOnly = DbAccessPolicy.isReadOnlyStatement(normalized);
        String warning = DbAccessPolicy.dangerWarning(normalized);
        String risk = warning != null ? "HIGH" : (readOnly ? "LOW" : "MEDIUM");
        List<String> tables = extractTables(normalized);
        int statementCount = countStatements(normalized);
        // 行数提示: 只读查询且无 LIMIT 时建议加
        boolean suggestLimit = readOnly && !normalized.toLowerCase(Locale.ROOT).contains("limit");
        audit(userId, connId, "AI_EXPLAIN", "explain", StrUtil.maxLength(normalized, 300),
            "type=" + firstKeyword + ",tables=" + tables.size() + ",risk=" + risk, risk);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statementType", firstKeyword);
        result.put("readOnly", readOnly);
        result.put("riskLevel", risk);
        result.put("warning", warning);
        result.put("tables", tables);
        result.put("statementCount", statementCount);
        result.put("suggestLimit", suggestLimit);
        result.put("connName", conn.getConnName());
        return result;
    }

    @Override
    public Map<String, Object> context(Long connId, String schema, Long userId) {
        DbConn conn = requireAllowed(connId);
        List<String> dbs = safeDatabases(connId);
        String targetSchema = StrUtil.blankToDefault(schema, dbs.isEmpty() ? "" : dbs.get(0));
        List<Map<String, Object>> tables = new ArrayList<>();
        for (DbTableVo table : safeTables(connId, targetSchema)) {
            if (tables.size() >= MAX_TABLES) {
                break;
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", table.getTableName());
            t.put("comment", table.getTableComment());
            List<String> cols = new ArrayList<>();
            for (DbColumnVo col : safeColumns(connId, targetSchema, table.getTableName())) {
                if (cols.size() >= MAX_COLUMNS_PER_TABLE) {
                    break;
                }
                cols.add(col.getColumnName() + " " + col.getColumnType());
            }
            t.put("columns", cols);
            tables.add(t);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connName", conn.getConnName());
        result.put("schema", targetSchema);
        result.put("databases", dbs);
        result.put("tables", tables);
        result.put("tableCount", tables.size());
        return result;
    }

    // ================= 生成规则 =================

    private String generateByRules(Long connId, String schema, String request) {
        String lower = request.toLowerCase(Locale.ROOT);
        String table = matchTable(connId, schema, request);
        if (table == null) {
            // 未匹配到表: 给出可用表清单提示
            List<DbTableVo> tables = safeTables(connId, StrUtil.blankToDefault(schema, ""));
            StringBuilder sb = new StringBuilder("-- 未在 [");
            sb.append(StrUtil.blankToDefault(schema, "?"))
              .append("] 库中匹配到表名。可用表(")
              .append(Math.min(tables.size(), MAX_TABLES)).append(" 个):\n-- ");
            for (int i = 0; i < Math.min(tables.size(), 20); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(tables.get(i).getTableName());
            }
            if (tables.size() > 20) {
                sb.append(" ...");
            }
            sb.append("\n请使用上述表名重试, 例如: 查一下 ").append(tables.isEmpty() ? "表名" : tables.get(0).getTableName()).append(" 的所有字段");
            return sb.toString();
        }
        String schemaPrefixed = quoteTable(schema, table);
        List<String> cols = columnNames(connId, schema, table);
        if (lower.contains("count") || lower.contains("统计") || lower.contains("多少") || lower.contains("数量")) {
            return "SELECT COUNT(*) AS cnt FROM " + schemaPrefixed + ";";
        }
        if (lower.contains("所有字段") || lower.contains("全部字段") || lower.contains("列") || lower.contains("结构") || lower.contains("desc") || lower.contains("describe")) {
            if (lower.contains("desc") || lower.contains("describe")) {
                return "DESC " + schemaPrefixed + ";";
            }
            return "SELECT * FROM " + schemaPrefixed + " LIMIT 100;";
        }
        if (lower.contains("插入") || lower.contains("新增") || lower.contains("insert") || lower.contains("写入")) {
            String sampleCols = cols.isEmpty() ? "col1" : String.join(", ", cols.subList(0, Math.min(cols.size(), 6)));
            return "-- 生成 INSERT 模板(写入操作, 执行需二次确认)\nINSERT INTO " + schemaPrefixed
                + " (" + sampleCols + ") VALUES (" + placeholder(cols) + ");";
        }
        if (lower.contains("更新") || lower.contains("修改") || lower.contains("update")) {
            return "-- 生成 UPDATE 模板(写入操作, 执行需二次确认; 建议带 WHERE)\nUPDATE " + schemaPrefixed
                + " SET col = 'value' WHERE id = 1;";
        }
        if (lower.contains("删除") || lower.contains("delete")) {
            return "-- 生成 DELETE 模板(高危操作, 执行需二次确认; 必须带 WHERE)\nDELETE FROM " + schemaPrefixed
                + " WHERE id = 1;";
        }
        if (lower.contains("建表") || lower.contains("创建表") || lower.contains("create table")) {
            return "-- 生成 CREATE TABLE 模板(DDL, 执行需二次确认)\nCREATE TABLE " + schemaPrefixed + " (\n"
                + "    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',\n"
                + "    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'\n"
                + ") COMMENT='新表';";
        }
        if (lower.contains("查询") || lower.contains("查一下") || lower.contains("看看") || lower.contains("select") || lower.contains("list")) {
            if (!cols.isEmpty()) {
                String pick = String.join(", ", cols.subList(0, Math.min(cols.size(), 8)));
                return "SELECT " + pick + " FROM " + schemaPrefixed + " LIMIT 100;";
            }
            return "SELECT * FROM " + schemaPrefixed + " LIMIT 100;";
        }
        // 默认: 全量示例查询
        return "SELECT * FROM " + schemaPrefixed + " LIMIT 100;";
    }

    private String buildExplainSql(Long connId, String schema, String sql) {
        // 用户请求里粘贴了 SQL(含 select/from 等关键词), 原样返回并附加说明
        String normalized = DbAccessPolicy.normalizeLeadingComments(sql);
        boolean readOnly = DbAccessPolicy.isReadOnlyStatement(normalized);
        String warning = DbAccessPolicy.dangerWarning(normalized);
        StringBuilder sb = new StringBuilder("-- 已识别为 SQL 解释请求, 请核对后执行(只读: " + readOnly + ")");
        if (warning != null) {
            sb.append("\n-- 风险提示: ").append(warning);
        }
        sb.append("\n").append(normalized);
        return sb.toString();
    }

    private boolean isExplainRequest(String request) {
        String lower = request.toLowerCase(Locale.ROOT);
        return (lower.contains("解释") || lower.contains("说明") || lower.contains("分析") || lower.contains("优化"))
            && (lower.contains("select") || lower.contains("from") || lower.contains("update") || lower.contains("insert") || lower.contains("delete"));
    }

    /** 在请求中匹配库内表名: 精确匹配优先; 包含匹配选最长命中表名(避免短表名前缀误命中) */
    private String matchTable(Long connId, String schema, String request) {
        String target = StrUtil.blankToDefault(schema, "");
        String lowerReq = request.toLowerCase(Locale.ROOT).trim();
        List<DbTableVo> tables = safeTables(connId, target);
        // 1) 精确匹配优先
        for (DbTableVo table : tables) {
            String name = table.getTableName();
            if (name != null && name.toLowerCase(Locale.ROOT).equals(lowerReq)) {
                return name;
            }
        }
        // 2) 包含匹配: 请求包含表名或表名包含请求词, 选最长命中(如 mdm_material_unit 优先于 mdm_material)
        String best = null;
        int bestLen = -1;
        for (DbTableVo table : tables) {
            String name = table.getTableName();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lowerReq.contains(lower) || lower.contains(lowerReq)) {
                if (lower.length() > bestLen) {
                    bestLen = lower.length();
                    best = name;
                }
            }
        }
        return best;
    }

    private String quoteTable(String schema, String table) {
        String t = "`" + table + "`";
        return StrUtil.isBlank(schema) ? t : "`" + schema + "`." + t;
    }

    private String placeholder(List<String> cols) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(cols.isEmpty() ? 1 : cols.size(), 6);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    // ================= SQL 解析 =================

    private String firstKeyword(String sql) {
        String s = sql.trim();
        if (s.isEmpty()) {
            return "EMPTY";
        }
        Matcher m = Pattern.compile("^([a-zA-Z]+)").matcher(s);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private int countStatements(String sql) {
        int count = 0;
        for (String part : sql.split(";")) {
            if (!DbAccessPolicy.normalizeLeadingComments(part).isEmpty()) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    private List<String> extractTables(String sql) {
        List<String> tables = new ArrayList<>();
        Matcher m = TABLE_REF.matcher(sql);
        while (m.find()) {
            String name = m.group(1).replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
            // 去掉 schema 前缀, 保留最后一段
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                name = name.substring(dot + 1);
            }
            if (!name.isEmpty() && !tables.contains(name) && !"dual".equalsIgnoreCase(name)) {
                tables.add(name);
            }
        }
        return tables;
    }

    // ================= 元数据(异常静默为安全失败) =================

    private List<String> safeDatabases(Long connId) {
        try {
            return metaService.databases(connId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<DbTableVo> safeTables(Long connId, String schema) {
        try {
            if (StrUtil.isBlank(schema)) {
                return new ArrayList<>();
            }
            return metaService.tables(connId, schema);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<DbColumnVo> safeColumns(Long connId, String schema, String table) {
        try {
            if (StrUtil.isBlank(schema) || StrUtil.isBlank(table)) {
                return new ArrayList<>();
            }
            return metaService.columns(connId, schema, table);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<String> columnNames(Long connId, String schema, String table) {
        List<String> names = new ArrayList<>();
        for (DbColumnVo col : safeColumns(connId, schema, table)) {
            if (col.getColumnName() != null) {
                names.add(col.getColumnName());
            }
        }
        return names;
    }

    // ================= 白名单与审计 =================

    DbConn requireAllowed(Long connId) {
        if (connId == null) {
            throw new ServiceException("连接ID不能为空");
        }
        List<String> allowed = parseAllowConns();
        if (allowed.isEmpty() || !allowed.contains(String.valueOf(connId))) {
            throw new ServiceException("该连接不在 AI/MCP 白名单内, 请在 dbtool.ai.allowConns 配置后使用");
        }
        DbConn conn = metaService.getConn(connId);
        if (conn == null) {
            throw new ServiceException("数据库连接不存在");
        }
        return conn;
    }

    List<String> parseAllowConns() {
        List<String> list = new ArrayList<>();
        if (StrUtil.isNotBlank(allowConns)) {
            for (String s : allowConns.split(",")) {
                if (StrUtil.isNotBlank(s)) {
                    list.add(s.trim());
                }
            }
        }
        return list;
    }

    void audit(Long userId, Long connId, String action, String tool,
               String inputSummary, String outputSummary, String risk) {
        try {
            jdbcTemplate.update("""
                INSERT INTO dbtool_ai_audit
                    (user_id, conn_id, action, tool, input_summary, output_summary, risk_level, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, connId, action, tool, inputSummary, outputSummary, risk, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("AI 审计写入失败: {}", e.getMessage());
        }
        // 每次写入后顺带执行保留清理(审计调用为低频, 成本可接受)
        cleanupAudit();
    }

    /**
     * 审计保留策略(P5 边界): 按天清理过期记录 + 按条数上限保留最新记录。
     * 审计表长期无上限增长会拖慢查询并占用空间, 清理仅删除审计留痕,
     * 不影响 AI/MCP 功能; 上限阈值可配置(<=0 关闭对应维度)。
     */
    private void cleanupAudit() {
        try {
            if (auditRetentionDays > 0) {
                jdbcTemplate.update("DELETE FROM dbtool_ai_audit WHERE created_at < ?",
                    LocalDateTime.now().minusDays(auditRetentionDays));
            }
            if (auditMaxRows > 0) {
                // 保留最新 auditMaxRows 条(派生表包裹, 规避 MySQL 同表子查询限制)
                jdbcTemplate.update("""
                    DELETE FROM dbtool_ai_audit
                    WHERE id NOT IN (SELECT id FROM (SELECT id FROM dbtool_ai_audit ORDER BY id DESC LIMIT ?) tmp)
                    """, auditMaxRows);
            }
        } catch (Exception e) {
            log.warn("AI 审计保留清理失败: {}", e.getMessage());
        }
    }
}
