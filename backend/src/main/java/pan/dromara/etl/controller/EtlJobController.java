package pan.dromara.etl.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
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
import pan.dromara.etl.domain.EtlJob;
import pan.dromara.etl.domain.EtlJobLog;
import pan.dromara.etl.domain.KettleDesignReq;
import pan.dromara.etl.service.IEtlJobService;
import pan.dromara.etl.service.impl.KettleService;

import java.util.List;

/**
 * ETL任务 控制器
 *
 * @author doubao
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/etl/job")
public class EtlJobController extends BaseController {

    private final IEtlJobService jobService;
    private final KettleService kettleService;

    /**
     * 分页查询任务列表
     */
    @SaCheckPermission("etl:job:list")
    @GetMapping("/list")
    public R<PageResult<EtlJob>> list(@RequestParam(defaultValue = "1") Integer pageNum,
                                      @RequestParam(defaultValue = "10") Integer pageSize,
                                      @RequestParam(required = false) String jobName,
                                      @RequestParam(required = false) String jobType,
                                      @RequestParam(required = false) Long srcConnId,
                                      @RequestParam(required = false) Long dstConnId,
                                      @RequestParam(required = false) Long dirId) {
        Page<EtlJob> page = jobService.pageList(pageNum, pageSize, jobName, jobType, srcConnId, dstConnId, dirId);
        return R.ok(PageResult.build(page.getRecords(), page.getTotal()));
    }

    /**
     * 目录树(DIR 节点)
     */
    @SaCheckPermission("etl:job:list")
    @GetMapping("/dirTree")
    public R<List<EtlJob>> dirTree() {
        return R.ok(jobService.dirTree());
    }

    /**
     * 查询某总任务的子任务列表
     */
    @SaCheckPermission("etl:job:list")
    @GetMapping("/children/{parentId}")
    public R<List<EtlJob>> children(@PathVariable Long parentId) {
        return R.ok(jobService.listChildren(parentId));
    }

    /**
     * 查询任务详情
     */
    @SaCheckPermission("etl:job:query")
    @GetMapping("/{id}")
    public R<EtlJob> getInfo(@PathVariable Long id) {
        return R.ok(jobService.selectById(id));
    }

    /**
     * 生成 DataX 配置(脱敏, 供预览)
     */
    @SaCheckPermission("etl:job:query")
    @PostMapping("/preview")
    public R<String> preview(@RequestBody EtlJob job) {
        return R.ok(jobService.buildJobJson(job));
    }

    /**
     * Kettle 可视化设计: 画布节点/连线 → Kettle 转换/作业 XML
     */
    @SaCheckPermission("etl:job:query")
    @PostMapping("/designXml")
    public R<String> designXml(@RequestBody KettleDesignReq req) {
        return R.ok("操作成功", jobService.designKettleXml(req));
    }

    /**
     * 预览转换 XML(按当前表单配置即时生成, 不落库)
     */
    @SaCheckPermission("etl:job:query")
    @PostMapping("/previewXml")
    public R<String> previewXml(@RequestBody EtlJob job) throws Exception {
        int multiCnt = job.getSrcList() == null ? 0 : job.getSrcList().split("srcTable").length - 1;
        if (kettleService.isJoinMode(job)) {
            return R.ok(kettleService.buildTransXmlJoin(job));
        }
        if (multiCnt >= 2) {
            return R.ok(kettleService.buildTransXmlMulti(job));
        }
        return R.ok(kettleService.buildTransXml(job));
    }

    /**
     * 新增任务
     */
    @SaCheckPermission("etl:job:add")
    @Log(title = "ETL任务", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@RequestBody EtlJob job) {
        jobService.insert(job);
        return R.ok(job.getId());
    }

    /**
     * 修改任务
     */
    @SaCheckPermission("etl:job:edit")
    @Log(title = "ETL任务", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> edit(@RequestBody EtlJob job) {
        return toAjax(jobService.update(job));
    }

    /**
     * 删除任务
     */
    @SaCheckPermission("etl:job:remove")
    @Log(title = "ETL任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable Long[] ids) {
        return toAjax(jobService.deleteByIds(ids));
    }

    /**
     * 执行任务(异步), 返回执行日志ID
     */
    @SaCheckPermission("etl:job:run")
    @Log(title = "ETL任务", businessType = BusinessType.OTHER)
    @PostMapping("/run/{id}")
    public R<Long> run(@PathVariable Long id) {
        try {
            return R.ok(jobService.execute(id));
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 执行任务(同步), 用于快速测试
     */
    @SaCheckPermission("etl:job:run")
    @PostMapping("/runSync/{id}")
    public R<EtlJobLog> runSync(@PathVariable Long id) {
        try {
            return R.ok(jobService.executeSync(id));
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 取消运行中的任务
     */
    @SaCheckPermission("etl:job:run")
    @PostMapping("/cancel/{id}")
    public R<Void> cancel(@PathVariable Long id) {
        try {
            jobService.cancel(id);
            return R.ok();
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 查询任务运行状态: RUNNING/IDLE
     */
    @SaCheckPermission("etl:job:query")
    @GetMapping("/state/{id}")
    public R<String> state(@PathVariable Long id) {
        return R.ok(jobService.state(id));
    }

    /**
     * 分页查询执行日志(支持按子任务/节点类型/节点ID维度筛选)
     */
    @SaCheckPermission("etl:log:list")
    @GetMapping("/log/list")
    public R<PageResult<EtlJobLog>> logList(@RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "10") Integer pageSize,
                                            @RequestParam(required = false) Long jobId,
                                            @RequestParam(required = false) Long subtaskId,
                                            @RequestParam(required = false) String nodeType,
                                            @RequestParam(required = false) String nodeId,
                                            @RequestParam(required = false) String runStatus) {
        Page<EtlJobLog> page = jobService.logPage(pageNum, pageSize, jobId, subtaskId, nodeType, nodeId, runStatus);
        return R.ok(PageResult.build(page.getRecords(), page.getTotal()));
    }

    /**
     * 校验 cron 并返回下次执行时间(非法返回空)
     */
    @SaCheckPermission("etl:job:query")
    @PostMapping("/cron/next")
    public R<String> cronNext(@RequestBody String cron) {
        if (cron == null) {
            return R.ok("操作成功", null);
        }
        String raw = cron.trim();
        // @RequestBody String 会保留 JSON 字符串的引号, 去掉首尾引号
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return R.ok("操作成功", jobService.cronNext(raw.trim()));
    }

    /**
     * 查询执行日志详情(完整)
     */
    @SaCheckPermission("etl:log:query")
    @GetMapping("/log/{id}")
    public R<EtlJobLog> logInfo(@PathVariable Long id) {
        return R.ok(jobService.selectLogById(id));
    }
}
