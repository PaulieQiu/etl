package pan.dromara.etl.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import pan.dromara.etl.domain.EtlJob;
import pan.dromara.etl.domain.EtlJobLog;
import pan.dromara.etl.domain.KettleDesignReq;

import java.util.List;

/**
 * ETL任务 服务层
 *
 * @author doubao
 */
public interface IEtlJobService {

    /**
     * 分页查询任务(总任务树形, 含子任务)
     */
    Page<EtlJob> pageList(Integer pageNum, Integer pageSize, String jobName, String jobType, Long srcConnId, Long dstConnId, Long dirId);

    /**
     * 目录树(DIR 节点)
     */
    List<EtlJob> dirTree();

    /**
     * 查询某总任务的子任务列表
     */
    List<EtlJob> listChildren(Long parentId);

    /**
     * 查询任务
     */
    EtlJob selectById(Long id);

    /**
     * 新增任务
     */
    int insert(EtlJob job);

    /**
     * 修改任务
     */
    int update(EtlJob job);

    /**
     * 删除任务(逻辑删除)
     */
    int deleteByIds(Long[] ids);

    /**
     * 生成 DataX 配置(脱敏)
     */
    String buildJobJson(EtlJob job);

    /**
     * 执行任务(异步, 返回日志ID)
     */
    Long execute(Long jobId);

    /**
     * 执行任务(同步等待, 用于测试)
     */
    EtlJobLog executeSync(Long jobId);

    /**
     * 分页查询执行日志(支持维度筛选: 子任务/节点类型/节点ID)
     */
    Page<EtlJobLog> logPage(Integer pageNum, Integer pageSize, Long jobId, Long subtaskId, String nodeType, String nodeId, String runStatus);

    /**
     * 查询执行日志
     */
    EtlJobLog selectLogById(Long logId);

    /**
     * 查询启用调度的任务列表(供调度扫描)
     */
    List<EtlJob> listScheduleEnabledJobs();

    /**
     * 按 cron 计算并更新任务下次执行时间
     */
    void updateNextRunTime(Long jobId);

    /**
     * 解析 cron 返回下次执行时间(供前端校验), 非法返回 null
     */
    String cronNext(String cron);

    /**
     * Kettle 可视化设计: 画布节点/连线 → Kettle 转换/作业 XML
     */
    String designKettleXml(KettleDesignReq req);

    /**
     * 取消运行中的任务(DataX 杀进程 / Kettle 停转换)
     */
    void cancel(Long jobId);

    /**
     * 查询任务运行状态: RUNNING/IDLE
     */
    String state(Long jobId);
}
