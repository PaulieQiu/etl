package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.domain.DbDriver;
import pan.dromara.dbtool.service.IDbDriverService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据库驱动 控制器
 *
 * @author doubao
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/dbtool/driver")
public class DbDriverController extends BaseController {

    private final IDbDriverService driverService;

    /**
     * 分页查询驱动列表
     */
    @SaCheckPermission("dbtool:driver:list")
    @GetMapping("/list")
    public R<Page<DbDriver>> list(@RequestParam(defaultValue = "1") Integer pageNum,
                                  @RequestParam(defaultValue = "10") Integer pageSize,
                                  @RequestParam(required = false) String dbType,
                                  @RequestParam(required = false) String keyword) {
        return R.ok(driverService.pageList(pageNum, pageSize, dbType, keyword));
    }

    /**
     * 查询全部驱动
     */
    @SaCheckPermission("dbtool:driver:list")
    @GetMapping("/listAll")
    public R<List<DbDriver>> listAll() {
        return R.ok(driverService.listAll());
    }

    /**
     * 自动下载驱动(从 Maven Central)
     *
     * @param dbType  数据库类型
     * @param version 版本
     */
    @SaCheckPermission("dbtool:driver:add")
    @Log(title = "数据库驱动", businessType = BusinessType.INSERT)
    @PostMapping("/download")
    public R<DbDriver> download(@RequestParam String dbType, @RequestParam(required = false) String version) {
        return R.ok(driverService.download(dbType, version));
    }

    /**
     * 手动上传驱动 jar
     *
     * @param dbType  数据库类型
     * @param version 版本
     * @param file    jar 文件
     */
    @SaCheckPermission("dbtool:driver:add")
    @Log(title = "数据库驱动", businessType = BusinessType.IMPORT)
    @PostMapping("/upload")
    public R<DbDriver> upload(@RequestParam(required = false) String dbType,
                              @RequestParam(required = false) String version,
                              @RequestParam("file") MultipartFile file) {
        return R.ok(driverService.upload(dbType, version, file));
    }

    /**
     * 编辑驱动信息
     */
    @SaCheckPermission("dbtool:driver:edit")
    @Log(title = "数据库驱动", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> update(@RequestBody DbDriver driver) {
        return toAjax(driverService.update(driver));
    }

    /**
     * 删除驱动
     */
    @SaCheckPermission("dbtool:driver:remove")
    @Log(title = "数据库驱动", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable Long[] ids) {
        if (ObjectUtil.isNull(ids)) {
            return R.fail("参数不能为空");
        }
        return toAjax(driverService.remove(List.of(ids)));
    }
}
