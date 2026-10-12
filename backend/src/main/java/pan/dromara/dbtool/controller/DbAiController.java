package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.service.IDbAiAssistService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI SQL 助手控制器(P5): 离线元数据驱动, 生成/解释 SQL, 只读白名单 + 审计
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/ai")
public class DbAiController {

    private final IDbAiAssistService aiAssistService;

    public DbAiController(IDbAiAssistService aiAssistService) {
        this.aiAssistService = aiAssistService;
    }

    /** 生成 SQL(自然语言需求 → SQL; 只返回不执行) */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/assist")
    public R<Map<String, Object>> assist(@RequestBody Map<String, Object> body) {
        Long connId = body == null || body.get("connId") == null
            ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String schema = body == null ? null : String.valueOf(body.get("schema"));
        String request = body == null ? null : String.valueOf(body.get("request"));
        return R.ok(aiAssistService.assist(connId, schema, request, LoginHelper.getUserId()));
    }

    /** 解释 SQL(类型/涉及表/风险分级) */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/explain")
    public R<Map<String, Object>> explain(@RequestBody Map<String, Object> body) {
        Long connId = body == null || body.get("connId") == null
            ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String sql = body == null ? null : String.valueOf(body.get("sql"));
        return R.ok(aiAssistService.explain(connId, sql, LoginHelper.getUserId()));
    }

    /** 元数据上下文(供前端提示/调试) */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/context")
    public R<Map<String, Object>> context(@RequestParam Long connId, @RequestParam(required = false) String schema) {
        return R.ok(aiAssistService.context(connId, schema, LoginHelper.getUserId()));
    }
}
