package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.service.IDbMcpService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MCP(Model Context Protocol) Server 控制器(P5): HTTP + JSON-RPC 2.0
 * <p>端点: POST /dbtool/mcp, 请求体为 JSON-RPC(initialize/tools/list/tools/call)。</p>
 * <p>仅白名单连接、只读工具; 每次调用审计。</p>
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/mcp")
public class DbMcpController {

    private final IDbMcpService mcpService;

    public DbMcpController(IDbMcpService mcpService) {
        this.mcpService = mcpService;
    }

    @SaCheckPermission("dbtool:meta:list")
    @PostMapping
    public R<Map<String, Object>> handle(@RequestBody Map<String, Object> body) {
        return R.ok(mcpService.handle(body == null ? Map.of() : body, LoginHelper.getUserId()));
    }
}
