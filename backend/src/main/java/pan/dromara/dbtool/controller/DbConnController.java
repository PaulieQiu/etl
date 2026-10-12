package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbResultVo;
import pan.dromara.dbtool.service.IDbConnService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据库连接配置 控制器
 *
 * @author doubao
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dbtool/conn")
public class DbConnController extends BaseController {

    private final IDbConnService connService;
    private final DbConnectionManager connectionManager;

    /**
     * 分页查询数据库连接列表
     */
    @SaCheckPermission("dbtool:conn:list")
    @GetMapping("/list")
    public R<Page<DbConn>> list(@RequestParam(defaultValue = "1") Integer pageNum,
                                @RequestParam(defaultValue = "10") Integer pageSize,
                                @RequestParam(required = false) String connName,
                                @RequestParam(required = false) String dbType) {
        Page<DbConn> page = connService.pageList(pageNum, pageSize, connName, dbType);
        // 不返回密码
        page.getRecords().forEach(c -> c.setPassword(null));
        return R.ok(page);
    }

    /**
     * 查询全部连接(供工作台选择)
     */
    @SaCheckPermission("dbtool:conn:list")
    @GetMapping("/listAll")
    public R<List<DbConn>> listAll() {
        List<DbConn> list = connService.listAll();
        list.forEach(c -> c.setPassword(null));
        return R.ok(list);
    }

    /**
     * 查询连接详情(返回脱敏密码)
     */
    @SaCheckPermission("dbtool:conn:query")
    @GetMapping("/{id}")
    public R<DbConn> getInfo(@PathVariable Long id) {
        DbConn conn = connService.getById(id);
        conn.setPassword(null);
        return R.ok(conn);
    }

    /**
     * 新增数据库连接
     */
    @SaCheckPermission("dbtool:conn:add")
    @Log(title = "数据库连接", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Void> add(@RequestBody DbConn bo) {
        // 空值统一置为空串, 避免 NOT NULL 无默认值字段(如 username)导致插入失败(Redis/Elasticsearch 等无用户名)
        bo.setUsername(StrUtil.nullToEmpty(bo.getUsername()));
        bo.setPassword(StrUtil.nullToEmpty(bo.getPassword()));
        bo.setDatabaseName(StrUtil.nullToEmpty(bo.getDatabaseName()));
        bo.setDbVersion(StrUtil.nullToEmpty(bo.getDbVersion()));
        bo.setParams(StrUtil.nullToEmpty(bo.getParams()));
        if (StrUtil.isNotBlank(bo.getPassword())) {
            bo.setPassword(connService.encryptPassword(bo.getPassword()));
        }
        bo.setDelFlag("0");
        return toAjax(connService.add(bo));
    }

    /**
     * 修改数据库连接
     */
    @SaCheckPermission("dbtool:conn:edit")
    @Log(title = "数据库连接", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> edit(@RequestBody DbConn bo) {
        if (ObjectUtil.isNull(bo.getId())) {
            return R.fail("主键不能为空");
        }
        DbConn old = connService.getById(bo.getId());
        if (StrUtil.isNotBlank(bo.getPassword())) {
            bo.setPassword(connService.encryptPassword(bo.getPassword()));
        } else if (old != null) {
            bo.setPassword(old.getPassword());
        }
        // 连接配置变更, 关闭旧连接池
        connectionManager.close(bo.getId());
        return toAjax(connService.update(bo));
    }

    /**
     * 删除数据库连接
     */
    @SaCheckPermission("dbtool:conn:remove")
    @Log(title = "数据库连接", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable Long[] ids) {
        for (Long id : ids) {
            connectionManager.close(id);
        }
        return toAjax(connService.remove(List.of(ids)));
    }

    /**
     * 测试数据库连接
     */
    @SaCheckPermission("dbtool:conn:add")
    @PostMapping("/test")
    public R<DbResultVo> test(@RequestBody DbConn bo) {
        // 测试时若密码为空且存在配置, 用已存密码
        if (StrUtil.isBlank(bo.getPassword()) && ObjectUtil.isNotNull(bo.getId())) {
            DbConn old = connService.getById(bo.getId());
            if (old != null) {
                bo.setPassword(old.getPassword());
            }
        }
        return R.ok(connService.testConnection(bo));
    }

    /**
     * 关闭连接池
     */
    @SaCheckPermission("dbtool:conn:list")
    @GetMapping("/close/{id}")
    public R<Void> closePool(@PathVariable Long id) {
        connectionManager.close(id);
        return R.ok();
    }
}
