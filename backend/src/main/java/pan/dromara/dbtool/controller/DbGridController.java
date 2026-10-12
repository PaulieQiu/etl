package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.service.IDbGridService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 服务端网格查询 控制器(P2)
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/grid")
public class DbGridController {

    private final IDbGridService gridService;

    public DbGridController(IDbGridService gridService) {
        this.gridService = gridService;
    }

    /**
     * 服务端过滤/排序/分页查询
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/query")
    public R<Map<String, Object>> query(@RequestBody Map<String, Object> body) {
        Long connId = body == null ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String sql = body == null ? null : String.valueOf(body.get("sql"));
        int pageNum = body == null || body.get("pageNum") == null ? 1 : Integer.parseInt(String.valueOf(body.get("pageNum")));
        int pageSize = body == null || body.get("pageSize") == null ? 100 : Integer.parseInt(String.valueOf(body.get("pageSize")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = body == null || !(body.get("filters") instanceof List)
            ? null : (List<Map<String, Object>>) body.get("filters");
        @SuppressWarnings("unchecked")
        Map<String, Object> sort = body == null || !(body.get("sort") instanceof Map)
            ? null : (Map<String, Object>) body.get("sort");
        if (connId == null || StrUtil.isBlank(sql)) {
            return R.fail("connId 与 sql 不能为空");
        }
        return R.ok(gridService.query(connId, sql, pageNum, pageSize, filters, sort));
    }

}
