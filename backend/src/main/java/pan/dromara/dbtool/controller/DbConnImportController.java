package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.service.IDbConnImportService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 连接配置导入 控制器(P4)
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/conns/import")
public class DbConnImportController {

    private final IDbConnImportService importService;

    public DbConnImportController(IDbConnImportService importService) {
        this.importService = importService;
    }

    /**
     * 解析导入文件, 返回候选连接列表(只读评估)
     */
    @SaCheckPermission("dbtool:conn:list")
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        String content = body == null ? null : String.valueOf(body.get("content"));
        String fileName = body == null ? null : String.valueOf(body.getOrDefault("fileName", ""));
        return R.ok(importService.preview(content == null ? "" : content, fileName == null ? "" : fileName));
    }

    /**
     * 按选择项导入连接
     */
    @SaCheckPermission("dbtool:conn:add")
    @PostMapping("/apply")
    public R<Map<String, Object>> apply(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = body == null ? null
            : (List<Map<String, Object>>) body.get("candidates");
        return R.ok(importService.apply(candidates));
    }
}
