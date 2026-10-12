package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.domain.vo.DbResultVo;
import pan.dromara.dbtool.service.IDbTransactionService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 事务会话 控制器(P2)
 * <p>同一连接跨请求保持事务: 开启 / 事务内执行 / 提交 / 回滚 / 关闭 / 列表。
 * 权限与工作台一致(dbtool:meta:list)。</p>
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/txn")
public class DbTransactionController {

    private final IDbTransactionService transactionService;

    public DbTransactionController(IDbTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * 开启事务会话
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/begin")
    public R<Map<String, Object>> begin(@RequestBody Map<String, Object> body) {
        Long connId = body == null ? null : Long.valueOf(String.valueOf(body.get("connId")));
        if (connId == null) {
            return R.fail("connId 不能为空");
        }
        Object dbNameRaw = body == null ? null : (body.get("dbName") != null ? body.get("dbName") : body.get("schema"));
        String dbName = dbNameRaw == null ? null : String.valueOf(dbNameRaw);
        return R.ok(transactionService.begin(StpUtil.getLoginIdAsString(), connId, dbName));
    }

    /**
     * 事务内执行单条 SQL
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/execute")
    public R<DbResultVo> execute(@RequestBody Map<String, Object> body) {
        String sessionId = body == null ? null : String.valueOf(body.get("sessionId"));
        String sql = body == null ? null : String.valueOf(body.get("sql"));
        String confirmationToken = body == null ? null : String.valueOf(body.get("confirmationToken"));
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(sql)) {
            return R.fail("sessionId 与 sql 不能为空");
        }
        return R.ok(transactionService.execute(StpUtil.getLoginIdAsString(), sessionId, sql, confirmationToken));
    }

    /**
     * 提交事务
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/commit")
    public R<Void> commit(@RequestBody Map<String, Object> body) {
        String sessionId = body == null ? null : String.valueOf(body.get("sessionId"));
        if (StrUtil.isBlank(sessionId)) {
            return R.fail("sessionId 不能为空");
        }
        transactionService.commit(StpUtil.getLoginIdAsString(), sessionId);
        return R.ok();
    }

    /**
     * 回滚事务
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/rollback")
    public R<Void> rollback(@RequestBody Map<String, Object> body) {
        String sessionId = body == null ? null : String.valueOf(body.get("sessionId"));
        if (StrUtil.isBlank(sessionId)) {
            return R.fail("sessionId 不能为空");
        }
        transactionService.rollback(StpUtil.getLoginIdAsString(), sessionId);
        return R.ok();
    }

    /**
     * 当前用户活动事务会话列表
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list() {
        return R.ok(transactionService.list(StpUtil.getLoginIdAsString()));
    }

    /**
     * 关闭事务会话(未提交变更回滚)
     */
    @SaCheckPermission("dbtool:meta:list")
    @DeleteMapping("/{sessionId}")
    public R<Void> close(@PathVariable String sessionId) {
        transactionService.close(StpUtil.getLoginIdAsString(), sessionId);
        return R.ok();
    }

}
