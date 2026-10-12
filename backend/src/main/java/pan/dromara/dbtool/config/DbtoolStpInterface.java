package pan.dromara.dbtool.config;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 独立运行模式：授予当前用户全部 dbtool 权限。
 * <p>生产环境如需细粒度鉴权，请替换为基于数据库的真实实现。</p>
 *
 * @author doubao
 */
@Configuration
public class DbtoolStpInterface implements StpInterface {

    private static final List<String> ALL_PERMS = List.of(
        "dbtool:conn:list", "dbtool:conn:query", "dbtool:conn:add", "dbtool:conn:edit", "dbtool:conn:remove",
        "dbtool:driver:list", "dbtool:driver:add", "dbtool:driver:edit", "dbtool:driver:remove",
        "dbtool:meta:list", "dbtool:meta:add", "dbtool:meta:edit", "dbtool:meta:remove"
    );

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return ALL_PERMS;
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of("admin");
    }
}
