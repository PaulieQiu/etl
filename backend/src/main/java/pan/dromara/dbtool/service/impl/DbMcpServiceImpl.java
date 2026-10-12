package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbResultVo;
import pan.dromara.dbtool.domain.vo.DbTableVo;
import pan.dromara.dbtool.service.IDbMcpService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 实现(P5): 工具集只读、白名单连接、逐项审计
 * <p>JSON-RPC 2.0 方法: initialize / tools/list / tools/call / ping。</p>
 * <p>工具: list_databases, list_tables, describe_table, get_ddl,
 * run_readonly_query(仅 SELECT/SHOW/DESC/EXPLAIN, 强制 LIMIT 100), explain_sql。</p>
 *
 * @author doubao
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbMcpServiceImpl implements IDbMcpService {

    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final int QUERY_ROW_LIMIT = 100;

    private final IDbMetaService metaService;
    private final DbAiAssistServiceImpl aiAssistService;

    @Override
    public Map<String, Object> handle(Map<String, Object> request, Long userId) {
        Object id = request.get("id");
        String method = request.get("method") == null ? "" : String.valueOf(request.get("method"));
        Map<String, Object> params = castParams(request.get("params"));
        try {
            Object result;
            switch (method) {
                case "initialize" -> result = initialize();
                case "tools/list" -> result = toolsList();
                case "tools/call" -> result = toolsCall(params, userId);
                case "ping" -> result = Map.of("status", "ok");
                default -> throw new McpException(-32601, "方法不存在: " + method);
            }
            return ok(id, result);
        } catch (McpException e) {
            return err(id, e.code, e.getMessage());
        } catch (Exception e) {
            log.warn("MCP 调用失败: method={}, {}", method, e.getMessage());
            return err(id, -32000, "服务端错误: " + e.getMessage());
        }
    }

    // ================= 方法实现 =================

    private Map<String, Object> initialize() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        capabilities.put("readOnly", true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", capabilities);
        result.put("serverInfo", Map.of("name", "dbtool-mcp", "version", "1.0.0"));
        return result;
    }

    private Map<String, Object> toolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("list_databases", "列出连接可访问的数据库", List.of(arg("connId", "integer", true))));
        tools.add(tool("list_tables", "列出指定 schema 的数据表", List.of(
            arg("connId", "integer", true), arg("schema", "string", true))));
        tools.add(tool("describe_table", "查看表结构(列/类型/注释)", List.of(
            arg("connId", "integer", true), arg("schema", "string", true), arg("table", "string", true))));
        tools.add(tool("get_ddl", "获取建表 DDL", List.of(
            arg("connId", "integer", true), arg("schema", "string", true), arg("table", "string", true))));
        tools.add(tool("run_readonly_query", "执行只读查询(仅 SELECT/SHOW/DESC/EXPLAIN, 强制 LIMIT 100)", List.of(
            arg("connId", "integer", true), arg("sql", "string", true))));
        tools.add(tool("explain_sql", "解释 SQL(类型/涉及表/风险)", List.of(
            arg("connId", "integer", true), arg("sql", "string", true))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        return result;
    }

    private Map<String, Object> toolsCall(Map<String, Object> params, Long userId) {
        String name = params.get("name") == null ? "" : String.valueOf(params.get("name"));
        Map<String, Object> args = castParams(params.get("arguments"));
        Long connId = parseConnId(args.get("connId"));
        aiAssistService.requireAllowed(connId);
        Map<String, Object> content;
        switch (name) {
            case "list_databases" -> content = listDatabases(connId);
            case "list_tables" -> content = listTables(connId, str(args.get("schema")));
            case "describe_table" -> content = describeTable(connId, str(args.get("schema")), str(args.get("table")));
            case "get_ddl" -> content = getDdl(connId, str(args.get("schema")), str(args.get("table")));
            case "run_readonly_query" -> content = runReadonlyQuery(connId, str(args.get("sql")), userId);
            case "explain_sql" -> content = explainSql(connId, str(args.get("sql")), userId);
            default -> throw new McpException(-32602, "工具不存在: " + name);
        }
        List<Map<String, Object>> contentList = new ArrayList<>();
        contentList.add(content);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", contentList);
        result.put("isError", false);
        return result;
    }

    // ================= 工具实现 =================

    private Map<String, Object> listDatabases(Long connId) {
        return textContent(String.join("\n", metaService.databases(connId)));
    }

    private Map<String, Object> listTables(Long connId, String schema) {
        requireSchema(schema);
        StringBuilder sb = new StringBuilder("schema=" + schema + "\n");
        for (DbTableVo t : metaService.tables(connId, schema)) {
            sb.append(t.getTableName());
            if (StrUtil.isNotBlank(t.getTableComment())) {
                sb.append("  // ").append(t.getTableComment());
            }
            sb.append('\n');
        }
        return textContent(sb.toString());
    }

    private Map<String, Object> describeTable(Long connId, String schema, String table) {
        requireSchema(schema);
        requireTable(table);
        StringBuilder sb = new StringBuilder("table=" + table + "\n");
        for (DbColumnVo c : metaService.columns(connId, schema, table)) {
            sb.append(c.getColumnName()).append('\t').append(c.getColumnType());
            if (StrUtil.isNotBlank(c.getColumnComment())) {
                sb.append("\t// ").append(c.getColumnComment());
            }
            sb.append('\n');
        }
        return textContent(sb.toString());
    }

    private Map<String, Object> getDdl(Long connId, String schema, String table) {
        requireSchema(schema);
        requireTable(table);
        String ddl = metaService.ddl(connId, schema, table);
        return textContent(StrUtil.blankToDefault(ddl, "(无 DDL 或方言不支持)"));
    }

    private Map<String, Object> runReadonlyQuery(Long connId, String sql, Long userId) {
        requireSql(sql);
        DbAccessPolicy.requireReadOnlyStatement(sql);
        String safeSql = ensureLimit(sql);
        DbResultVo result = metaService.query(connId, safeSql, 1, QUERY_ROW_LIMIT);
        aiAssistService.audit(userId, connId, "MCP_CALL", "run_readonly_query",
            StrUtil.maxLength(sql, 300),
            "rows=" + (result.getRows() == null ? 0 : result.getRows().size()), "LOW");
        StringBuilder sb = new StringBuilder();
        if (result.getColumns() != null) {
            sb.append(String.join(", ", result.getColumns())).append('\n');
        }
        if (result.getRows() != null) {
            for (Map<String, Object> row : result.getRows()) {
                sb.append(String.join(" | ", row.values().stream().map(String::valueOf).toList())).append('\n');
            }
        }
        sb.append("-- 共 ").append(result.getRows() == null ? 0 : result.getRows().size()).append(" 行(上限 ").append(QUERY_ROW_LIMIT).append(")");
        return textContent(sb.toString());
    }

    private Map<String, Object> explainSql(Long connId, String sql, Long userId) {
        requireSql(sql);
        Map<String, Object> explained = aiAssistService.explain(connId, sql, userId);
        aiAssistService.audit(userId, connId, "MCP_CALL", "explain_sql",
            StrUtil.maxLength(sql, 300),
            "type=" + explained.get("statementType") + ",risk=" + explained.get("riskLevel"),
            String.valueOf(explained.get("riskLevel")));
        return textContent("语句类型: " + explained.get("statementType")
            + "\n只读: " + explained.get("readOnly")
            + "\n风险: " + explained.get("riskLevel")
            + (explained.get("warning") == null ? "" : "\n警示: " + explained.get("warning"))
            + "\n涉及表: " + String.join(", ", (List<String>) explained.get("tables")));
    }

    // ================= 辅助 =================

    private String ensureLimit(String sql) {
        if (sql.toLowerCase().contains("select") && !sql.toLowerCase().contains("limit")) {
            return sql + " LIMIT " + QUERY_ROW_LIMIT;
        }
        return sql;
    }

    private void requireSchema(String schema) {
        if (StrUtil.isBlank(schema)) {
            throw new McpException(-32602, "缺少参数: schema");
        }
    }

    private void requireTable(String table) {
        if (StrUtil.isBlank(table)) {
            throw new McpException(-32602, "缺少参数: table");
        }
    }

    private void requireSql(String sql) {
        if (StrUtil.isBlank(sql)) {
            throw new McpException(-32602, "缺少参数: sql");
        }
    }

    private Long parseConnId(Object value) {
        if (value == null) {
            throw new McpException(-32602, "缺少参数: connId");
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new McpException(-32602, "connId 必须为数字");
        }
    }

    private Map<String, Object> textContent(String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "text");
        item.put("text", text);
        return item;
    }

    private Map<String, Object> tool(String name, String description, List<Map<String, Object>> args) {
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map<String, Object> arg : args) {
            properties.put(String.valueOf(arg.get("name")), Map.of("type", String.valueOf(arg.get("type"))));
        }
        inputSchema.put("properties", properties);
        List<String> required = new ArrayList<>();
        for (Map<String, Object> arg : args) {
            if (Boolean.TRUE.equals(arg.get("required"))) {
                required.add(String.valueOf(arg.get("name")));
            }
        }
        inputSchema.put("required", required);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", inputSchema);
        return t;
    }

    private Map<String, Object> arg(String name, String type, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("required", required);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castParams(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> ok(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("result", result);
        response.put("id", id);
        return response;
    }

    private Map<String, Object> err(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        response.put("id", id);
        return response;
    }

    /** JSON-RPC 错误 */
    private static final class McpException extends RuntimeException {
        private final int code;

        private McpException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
