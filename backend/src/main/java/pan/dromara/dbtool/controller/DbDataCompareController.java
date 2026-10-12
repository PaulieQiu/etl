package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.DbOperationConfirmationService;
import pan.dromara.dbtool.service.IDbDataCompareService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据对比与同步 控制器(P3)
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/compare")
public class DbDataCompareController {

    private final IDbDataCompareService compareService;
    private final IDbMetaService metaService;
    private final DbOperationConfirmationService confirmationService;

    public DbDataCompareController(IDbDataCompareService compareService, IDbMetaService metaService,
                                   DbOperationConfirmationService confirmationService) {
        this.compareService = compareService;
        this.metaService = metaService;
        this.confirmationService = confirmationService;
    }

    /**
     * 数据对比预览(只读)
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        return R.ok(compareService.preview(body));
    }

    /**
     * 数据同步确认凭证(一次性, 2 分钟)
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/confirmation")
    public R<String> confirmation(@RequestBody Map<String, Object> body) {
        Long connId = body == null || body.get("connId") == null
            ? null : Long.valueOf(String.valueOf(body.get("connId")));
        if (connId == null) {
            return R.fail("connId 不能为空");
        }
        DbAccessPolicy.requireWritable(metaService.getConn(connId));
        String payload = body.get("payload") == null ? "" : String.valueOf(body.get("payload"));
        return R.ok(confirmationService.issue(connId, payload, StpUtil.getLoginIdAsString()));
    }

    /**
     * 分批应用同步(需一次性确认凭证)
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/apply")
    public R<Map<String, Object>> apply(@RequestBody Map<String, Object> body) {
        if (body != null) {
            body.put("userId", StpUtil.getLoginIdAsString());
        }
        return R.ok(compareService.apply(body));
    }

}
