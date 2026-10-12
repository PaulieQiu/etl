package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.domain.DbSqlHistory;
import pan.dromara.dbtool.domain.DbSqlScript;
import pan.dromara.dbtool.service.IDbSqlLibraryService;

import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.satoken.utils.LoginHelper;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SQL 历史与脚本库 控制器(P1 服务端化)
 * <p>历史/脚本按登录用户隔离; 权限与工作台一致(dbtool:meta:list)。</p>
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/sql")
public class DbSqlLibraryController {

    private final IDbSqlLibraryService libraryService;

    public DbSqlLibraryController(IDbSqlLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    /**
     * 记录一条执行历史(执行工作流/前端 record 调用)
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/history/record")
    public R<Void> recordHistory(@RequestBody DbSqlHistory history) {
        if (history == null) {
            return R.fail("记录不能为空");
        }
        history.setUserId(LoginHelper.getUserId());
        libraryService.recordHistory(history);
        return R.ok();
    }

    /**
     * 分页查询执行历史(当前用户)
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/history/list")
    public R<Page<DbSqlHistory>> listHistory(@RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "20") long pageSize,
                                             @RequestParam(required = false) Long connId,
                                             @RequestParam(required = false) String schemaName,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String beginTime,
                                             @RequestParam(required = false) String endTime) {
        return R.ok(libraryService.listHistory(
            LoginHelper.getUserId(), connId, schemaName, keyword, status,
            beginTime, endTime, pageNum, pageSize));
    }

    /**
     * 删除执行历史(仅本人)
     */
    @SaCheckPermission("dbtool:meta:list")
    @DeleteMapping("/history/{id}")
    public R<Void> deleteHistory(@PathVariable Long id) {
        libraryService.deleteHistory(LoginHelper.getUserId(), id);
        return R.ok();
    }

    /**
     * 保存脚本(无 id 新增, 有 id 更新)
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/script/save")
    public R<Void> saveScript(@RequestBody DbSqlScript script) {
        libraryService.saveScript(LoginHelper.getUserId(), script);
        return R.ok();
    }

    /**
     * 查询已保存脚本(当前用户, 按名称/SQL 关键词过滤)
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/script/list")
    public R<List<DbSqlScript>> listScripts(@RequestParam(required = false) String keyword) {
        return R.ok(libraryService.listScripts(LoginHelper.getUserId(), keyword));
    }

    /**
     * 删除脚本(仅本人)
     */
    @SaCheckPermission("dbtool:meta:list")
    @DeleteMapping("/script/{id}")
    public R<Void> deleteScript(@PathVariable Long id) {
        libraryService.deleteScript(LoginHelper.getUserId(), id);
        return R.ok();
    }

}
