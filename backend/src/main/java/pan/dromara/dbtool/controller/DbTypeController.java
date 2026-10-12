package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.core.DbTypeInfo;
import pan.dromara.dbtool.core.DbTypeRegistry;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据库类型 控制器
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/type")
public class DbTypeController {

    /**
     * 数据库类型清单(含分类/图标/默认端口/驱动/版本/URL模板)
     */
    @SaCheckPermission("dbtool:conn:list")
    @GetMapping("/list")
    public R<Map<String, Object>> list() {
        List<DbTypeInfo> types = DbTypeRegistry.listAll();
        List<String> categories = DbTypeRegistry.listCategories();
        return R.ok(Map.of("types", types, "categories", categories));
    }
}
