package pan.dromara.etl.service.impl;

import pan.dromara.etl.util.CronNextUtil;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mail.core.MailBuilder;
import pan.dromara.etl.domain.EtlJob;
import pan.dromara.etl.domain.EtlJobLog;
import pan.dromara.etl.domain.KettleDesignReq;
import pan.dromara.etl.mapper.EtlJobLogMapper;
import pan.dromara.etl.mapper.EtlJobMapper;
import pan.dromara.etl.service.IEtlJobService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;

/**
 * ETL任务 服务实现
 *
 * @author doubao
 */
@Slf4j
@Service
public class EtlJobServiceImpl implements IEtlJobService {

    private final EtlJobMapper jobMapper;
    private final EtlJobLogMapper jobLogMapper;
    private final DataXService dataXService;
    private final KettleService kettleService;
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "etl-datax-runner");
        t.setDaemon(true);
        return t;
    });
    /** 取消标记 (jobId -> 1) */
    private final ConcurrentHashMap<Long, String> cancelFlags = new ConcurrentHashMap<>();

    public EtlJobServiceImpl(EtlJobMapper jobMapper, EtlJobLogMapper jobLogMapper, DataXService dataXService, KettleService kettleService) {
        this.jobMapper = jobMapper;
        this.jobLogMapper = jobLogMapper;
        this.dataXService = dataXService;
        this.kettleService = kettleService;
    }

    @Override
    public Page<EtlJob> pageList(Integer pageNum, Integer pageSize, String jobName, String jobType, Long srcConnId, Long dstConnId, Long dirId) {
        LambdaQueryWrapper<EtlJob> qw = new LambdaQueryWrapper<>();
        if (dirId != null) {
            // 点击目录: 顶层 = 该目录(及子孙目录)的直接子级(目录/总任务), 再递归构建树
            java.util.Set<Long> dirIds = new java.util.HashSet<>();
            dirIds.add(dirId);
            collectDirIds(dirId, dirIds);
            qw.in(EtlJob::getParentId, dirIds)
                .like(StrUtil.isNotBlank(jobName), EtlJob::getJobName, jobName)
                .eq(StrUtil.isNotBlank(jobType), EtlJob::getJobType, jobType)
                .eq(srcConnId != null, EtlJob::getSrcConnId, srcConnId)
                .eq(dstConnId != null, EtlJob::getDstConnId, dstConnId)
                .orderByDesc(EtlJob::getCreateTime);
        } else {
            // 默认: 顶层 = parent_id=0(根目录+根总任务), 子任务随顶层递归返回
            qw.eq(EtlJob::getParentId, 0L)
                .like(StrUtil.isNotBlank(jobName), EtlJob::getJobName, jobName)
                .eq(StrUtil.isNotBlank(jobType), EtlJob::getJobType, jobType)
                .eq(srcConnId != null, EtlJob::getSrcConnId, srcConnId)
                .eq(dstConnId != null, EtlJob::getDstConnId, dstConnId)
                .orderByDesc(EtlJob::getCreateTime);
        }
        Page<EtlJob> page = jobMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        page.getRecords().forEach(this::buildJobTree);
        return page;
    }

    /**
     * 收集 dirId 下全部子孙目录 id(不含自身)
     */
    private void collectDirIds(Long dirId, java.util.Set<Long> acc) {
        List<EtlJob> subs = jobMapper.selectList(new LambdaQueryWrapper<EtlJob>()
            .eq(EtlJob::getParentId, dirId).eq(EtlJob::getJobType, "DIR"));
        for (EtlJob s : subs) {
            if (acc.add(s.getId())) {
                collectDirIds(s.getId(), acc);
            }
        }
    }

    @Override
    public List<EtlJob> dirTree() {
        List<EtlJob> dirs = jobMapper.selectList(new LambdaQueryWrapper<EtlJob>()
            .eq(EtlJob::getJobType, "DIR")
            .orderByAsc(EtlJob::getCreateTime));
        java.util.Map<Long, List<EtlJob>> byParent = dirs.stream()
            .collect(java.util.stream.Collectors.groupingBy(EtlJob::getParentId));
        dirs.forEach(d -> {
            List<EtlJob> ch = byParent.getOrDefault(d.getId(), new java.util.ArrayList<>());
            ch.forEach(c -> c.setNodeType("DIR"));
            d.setChildren(ch);
            d.setNodeType("DIR");
        });
        return byParent.getOrDefault(0L, new java.util.ArrayList<>());
    }

    /**
     * 递归构建任务树并标注节点类型: DIR=目录 / PARENT=总任务 / CHILD=子任务
     */
    private void buildJobTree(EtlJob node) {
        node.setJobJson(null);
        node.setKettleXml(null);
        if (isDir(node)) {
            node.setNodeType("DIR");
        } else if (isParent(node)) {
            node.setNodeType("PARENT");
        } else {
            node.setNodeType("CHILD");
        }
        List<EtlJob> children = listChildren(node.getId());
        node.setChildren(children);
        children.forEach(this::buildJobTree);
    }

    @Override
    public List<EtlJob> listChildren(Long parentId) {
        if (parentId == null) {
            return List.of();
        }
        return jobMapper.selectList(new LambdaQueryWrapper<EtlJob>()
            .eq(EtlJob::getParentId, parentId)
            .orderByAsc(EtlJob::getCreateTime));
    }

    @Override
    public EtlJob selectById(Long id) {
        EtlJob job = jobMapper.selectById(id);
        if (job != null && isParent(job)) {
            job.setChildren(listChildren(id));
        }
        return job;
    }

    @Override
    public int insert(EtlJob job) {
        if (job.getJobType() == null) {
            job.setJobType("DATAX");
        }
        normalizeEngine(job);
        if (job.getParentId() == null) {
            job.setParentId(0L);
        }
        if (job.getSpeedChannel() == null) {
            job.setSpeedChannel(3);
        }
        if (job.getSpeedByte() == null) {
            job.setSpeedByte(0L);
        }
        if (job.getBatchSize() == null) {
            job.setBatchSize(1024);
        }
        if (isDir(job)) {
            // 目录节点: 不生成引擎定义
            job.setJobJson(null);
            job.setKettleXml(null);
        } else if (!isParent(job)) {
            // 子任务: 生成引擎定义
            if (isKettle(job)) {
                job.setJobJson(null);
                job.setKettleXml(buildKettleXmlSafe(job));
            } else {
                job.setKettleXml(null);
                job.setJobJson(maskPassword(buildJobJsonSafe(job)));
            }
        } else {
            // 总任务: 不生成引擎定义, 由子任务承载
            job.setJobJson(null);
            job.setKettleXml(null);
        }
        job.setNextRunTime(calcNext(job.getScheduleCron(), job.getScheduleEnabled()));
        return jobMapper.insert(job);
    }

    @Override
    public int update(EtlJob job) {
        normalizeEngine(job);
        if (job.getParentId() == null) {
            job.setParentId(0L);
        }
        if (isDir(job)) {
            // 目录节点: 仅名称/上级/状态变更, 不生成引擎定义
            job.setJobJson(null);
            job.setKettleXml(null);
        } else if (!isParent(job)) {
            if (isKettle(job)) {
                job.setJobJson(null);
                job.setKettleXml(buildKettleXmlSafe(job));
            } else {
                job.setKettleXml(null);
                job.setJobJson(maskPassword(buildJobJsonSafe(job)));
            }
        } else {
            job.setJobJson(null);
            job.setKettleXml(null);
        }
        job.setNextRunTime(calcNext(job.getScheduleCron(), job.getScheduleEnabled()));
        return jobMapper.updateById(job);
    }

    @Override
    public int deleteByIds(Long[] ids) {
        int n = 0;
        for (Long id : ids) {
            n += deleteNodeTree(id);
        }
        return n;
    }

    /**
     * 递归删除节点及其子孙(目录→子目录/任务, 总任务→子任务)
     */
    private int deleteNodeTree(Long id) {
        EtlJob node = jobMapper.selectById(id);
        if (node != null && isDir(node)) {
            // 目录: 其下(递归)存在任务记录时不允许删除
            if (hasTaskUnderDir(id)) {
                throw new ServiceException("目录[" + node.getJobName() + "]下存在任务记录, 不允许删除, 请先清空其下任务");
            }
        }
        int n = 0;
        List<EtlJob> children = listChildren(id);
        for (EtlJob c : children) {
            n += deleteNodeTree(c.getId());
        }
        n += jobMapper.deleteById(id);
        return n;
    }

    /**
     * 目录下(递归)是否存在任务记录(非目录节点)
     */
    private boolean hasTaskUnderDir(Long dirId) {
        List<EtlJob> children = listChildren(dirId);
        for (EtlJob c : children) {
            if (!isDir(c)) {
                return true;
            }
            if (hasTaskUnderDir(c.getId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String buildJobJson(EtlJob job) {
        return maskPassword(buildJobJsonSafe(job));
    }

    private String buildJobJsonSafe(EtlJob job) {
        if (isKettle(job) || isParent(job)) {
            return null;
        }
        if (pan.dromara.etl.util.EtlFileBridge.isBridgeDst(job)) {
            return "{\"error\":\"Excel/API 目标仅支持 Kettle 引擎(DataX 无对应插件), 任务已自动切换为 KETTLE\"}";
        }
        if (StrUtil.isNotBlank(job.getJobJson()) && !"{\"error\"".startsWith(StrUtil.sub(job.getJobJson(), 0, 8))) {
            return job.getJobJson();
        }
        try {
            return dataXService.buildJobJson(job);
        } catch (Exception e) {
            log.warn("生成 DataX 配置失败: {}", e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 引擎归一: Excel/API 目标仍强制 Kettle 引擎(DataX 无 excel/api writer 插件);
     * Excel/API 源 DataX 已支持(桥接 CSV + txtfilereader), 不再强制切换。
     */
    private void normalizeEngine(EtlJob job) {
        if (isParent(job) || isDir(job)) {
            return;
        }
        if (pan.dromara.etl.util.EtlFileBridge.isBridgeDst(job)) {
            job.setJobType("KETTLE");
        }
    }

    private String buildKettleXmlSafe(EtlJob job) {
        // Excel/API 源或目标: 保存期不生成 XML(此时 id 未生成, 桥接路径无效);
        // 运行时 runTrans 按类型重建(含桥接文件生成), 避免保存即访问文件/API
        if (pan.dromara.etl.util.EtlFileBridge.isBridgeSrc(job) || pan.dromara.etl.util.EtlFileBridge.isBridgeDst(job)) {
            return null;
        }
        if (StrUtil.isNotBlank(job.getKettleXml())) {
            return job.getKettleXml();
        }
        try {
            // 统一管线模式: 配置了清洗结果表 / 清洗规则 / 质量规则 / 段2映射 时, 走 源→[清洗]→[结果表]→[质量]→目标
            boolean hasClean = job.getCleanConnId() != null && StrUtil.isNotBlank(job.getCleanTable());
            boolean pipelineMode = hasClean
                || StrUtil.isNotBlank(job.getCleanRules())
                || StrUtil.isNotBlank(job.getQualityRules())
                || StrUtil.isNotBlank(job.getDstMapping());
            if (pipelineMode) {
                if (hasClean) {
                    // 有清洗结果表: Job 模式, 段1写结果表后段2读回写目标表(保证先写后读)
                    job.setKettleType("JOB");
                    return kettleService.buildPipelineKjb(job);
                }
                return kettleService.buildTransXmlPipeline(job);
            }
            // JOIN 关联模式 / 多源(>=2) UNION ALL 合并 / 单源
            if (StrUtil.isNotBlank(job.getSrcList())) {
                try {
                    var arr = cn.hutool.json.JSONUtil.parseArray(job.getSrcList());
                    long n = arr.stream().filter(o -> o instanceof cn.hutool.json.JSONObject
                        && StrUtil.isNotBlank(((cn.hutool.json.JSONObject) o).getStr("srcTable"))).count();
                    if (kettleService.isJoinMode(job)) {
                        return kettleService.buildTransXmlJoin(job);
                    }
                    if (n >= 2) {
                        return kettleService.buildTransXmlMulti(job);
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }
            return kettleService.buildTransXml(job);
        } catch (Exception e) {
            log.warn("生成 Kettle 转换失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean isDir(EtlJob job) {
        return job != null && "DIR".equalsIgnoreCase(job.getJobType());
    }

    /**
     * 校验任务的所有祖先目录均处于启用状态
     */
    private void checkDirStatus(EtlJob job) {
        Long pid = job.getParentId();
        while (pid != null && pid != 0L) {
            EtlJob p = jobMapper.selectById(pid);
            if (p == null) {
                break;
            }
            if (isDir(p) && p.getStatus() != null && !"0".equals(p.getStatus())) {
                throw new ServiceException("父级目录[" + p.getJobName() + "]已停用, 无法执行任务, 请先启用目录");
            }
            pid = p.getParentId();
        }
    }

    private boolean isParent(EtlJob job) {
        if (job == null || isDir(job)) {
            return false;
        }
        Long pid = job.getParentId();
        if (pid == null || pid == 0L) {
            return true;
        }
        // 目录下挂载的总任务: 父节点是目录
        EtlJob p = jobMapper.selectById(pid);
        return p != null && isDir(p);
    }

    private boolean isKettle(EtlJob job) {
        return "KETTLE".equalsIgnoreCase(job.getJobType());
    }

    private String maskPassword(String json) {
        if (StrUtil.isBlank(json)) {
            return json;
        }
        try {
            JSONObject root = JSONUtil.parseObj(json);
            JSONObject job = root.getJSONObject("job");
            JSONObject content = job.getJSONArray("content").getJSONObject(0);
            for (String key : new String[]{"reader", "writer"}) {
                JSONObject part = content.getJSONObject(key);
                JSONObject param = part.getJSONObject("parameter");
                if (param != null && param.containsKey("password")) {
                    param.set("password", "******");
                }
            }
            return root.toStringPretty();
        } catch (Exception e) {
            return json;
        }
    }

    @Override
    public Long execute(Long jobId) {
        EtlJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new ServiceException("任务不存在: " + jobId);
        }
        if (job.getStatus() != null && !"0".equals(job.getStatus())) {
            throw new ServiceException("任务[" + job.getJobName() + "]已停用, 请先启用后再执行");
        }
        if (isDir(job)) {
            throw new ServiceException("目录节点不可执行, 请选择总任务或子任务执行");
        }
        checkDirStatus(job);
        if (isParent(job)) {
            // 总任务: 建父日志, 异步顺序执行子任务
            EtlJobLog jobLog = new EtlJobLog();
            jobLog.setJobId(jobId);
            jobLog.setNodeType("TASK");
            jobLog.setNodeId(String.valueOf(jobId));
            jobLog.setNodeName(job.getJobName());
            jobLog.setRunStatus("RUNNING");
            jobLog.setStartTime(new Date());
            jobLog.setCreateTime(new Date());
            jobLogMapper.insert(jobLog);
            Long logId = jobLog.getId();
            executor.submit(() -> {
                try {
                    List<EtlJob> children = listChildren(jobId);
                    if (children.isEmpty()) {
                        EtlJobLog cur = jobLogMapper.selectById(logId);
                        cur.setRunStatus("FAILED");
                        cur.setErrorMsg("总任务未配置子任务");
                        cur.setEndTime(new Date());
                        jobLogMapper.updateById(cur);
                        return;
                    }
                    StringBuilder summary = new StringBuilder();
                    int[] res = runChildren(job, children, summary);
                    int ok = res[0];
                    int skipped = res[1];
                    EtlJobLog cur = jobLogMapper.selectById(logId);
                    cur.setRunStatus((ok + skipped) == children.size() ? "SUCCESS" : "FAILED");
                    cur.setEndTime(new Date());
                    cur.setTotalRecords((long) children.size());
                    cur.setLogText(summary.toString());
                    if ((ok + skipped) != children.size()) {
                        cur.setErrorMsg("部分子任务执行失败");
                    }
                    jobLogMapper.updateById(cur);
                } catch (Exception e) {
                    EtlJobLog cur = jobLogMapper.selectById(logId);
                    if (cur != null) {
                        cur.setRunStatus("FAILED");
                        cur.setErrorMsg(e.getMessage());
                        cur.setEndTime(new Date());
                        jobLogMapper.updateById(cur);
                    }
                }
            });
            return logId;
        }
        // 单任务/子任务: 统一走 runChildWithNodes(增量/重试/告警/取消 全链路生效)
        EtlJobLog jobLog = new EtlJobLog();
        jobLog.setJobId(jobId);
        jobLog.setNodeType("SUBTASK");
        jobLog.setNodeId(String.valueOf(jobId));
        jobLog.setNodeName(job.getJobName());
        jobLog.setRunStatus("RUNNING");
        jobLog.setStartTime(new Date());
        jobLog.setCreateTime(new Date());
        jobLogMapper.insert(jobLog);
        Long logId = jobLog.getId();

        executor.submit(() -> {
            try {
                runChildWithNodes(job);
                // 容器日志与 runChildWithNodes 实际日志对齐(取消/重试语义可见)
                EtlJobLog real = jobLogMapper.selectOne(new LambdaQueryWrapper<EtlJobLog>()
                    .eq(EtlJobLog::getJobId, jobId)
                    .eq(EtlJobLog::getNodeType, "SUBTASK")
                    .eq(EtlJobLog::getNodeName, job.getJobName())
                    .orderByDesc(EtlJobLog::getId)
                    .last("LIMIT 1"));
                EtlJobLog cur = jobLogMapper.selectById(logId);
                if (real != null && cur != null) {
                    cur.setRunStatus(real.getRunStatus());
                    cur.setErrorMsg(real.getErrorMsg());
                    cur.setTotalRecords(real.getTotalRecords());
                    cur.setEndTime(real.getEndTime());
                    cur.setLogText(real.getLogText());
                    jobLogMapper.updateById(cur);
                }
            } catch (Exception e) {
                EtlJobLog cur = jobLogMapper.selectById(logId);
                if (cur != null) {
                    cur.setRunStatus("FAILED");
                    cur.setErrorMsg(e.getMessage());
                    cur.setEndTime(new Date());
                    jobLogMapper.updateById(cur);
                }
            }
        });
        return logId;
    }

    @Override
    public EtlJobLog executeSync(Long jobId) {
        EtlJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new ServiceException("任务不存在: " + jobId);
        }
        if (job.getStatus() != null && !"0".equals(job.getStatus())) {
            throw new ServiceException("任务[" + job.getJobName() + "]已停用, 请先启用后再执行");
        }
        if (isParent(job)) {
            List<EtlJob> children = listChildren(jobId);
            EtlJobLog jobLog = new EtlJobLog();
            jobLog.setJobId(jobId);
            jobLog.setNodeType("TASK");
            jobLog.setNodeId(String.valueOf(jobId));
            jobLog.setNodeName(job.getJobName());
            jobLog.setRunStatus("RUNNING");
            jobLog.setStartTime(new Date());
            jobLog.setCreateTime(new Date());
            jobLogMapper.insert(jobLog);
            StringBuilder summary = new StringBuilder();
            int[] res = runChildren(job, children, summary);
            int ok = res[0];
            int skipped = res[1];
            jobLog.setRunStatus((ok + skipped) == children.size() ? "SUCCESS" : "FAILED");
            jobLog.setEndTime(new Date());
            jobLog.setTotalRecords((long) children.size());
            jobLog.setLogText(summary.toString());
            if ((ok + skipped) != children.size()) {
                jobLog.setErrorMsg("部分子任务执行失败");
            }
            jobLogMapper.updateById(jobLog);
            return jobLog;
        }
        // 子任务统一走 runChildWithNodes: 增量注入/失败重试/告警/执行状态 全链路生效
        runChildWithNodes(job);
        return jobLogMapper.selectOne(new LambdaQueryWrapper<EtlJobLog>()
            .eq(EtlJobLog::getJobId, jobId)
            .eq(EtlJobLog::getNodeType, "SUBTASK")
            .orderByDesc(EtlJobLog::getId)
            .last("LIMIT 1"));
    }

    /** srcList 是否含 JOIN/LEFT_JOIN(JOIN 多源组合) */
    private boolean srcListJoinMode(EtlJob child) {
        if (StrUtil.isBlank(child.getSrcList())) {
            return false;
        }
        try {
            cn.hutool.json.JSONArray arr = JSONUtil.parseArray(child.getSrcList());
            for (int i = 0; i < arr.size(); i++) {
                Object o = arr.get(i);
                if (o instanceof cn.hutool.json.JSONObject) {
                    String jt = ((cn.hutool.json.JSONObject) o).getStr("joinType", "");
                    if ("JOIN".equalsIgnoreCase(jt) || "LEFT_JOIN".equalsIgnoreCase(jt) || "LEFT JOIN".equalsIgnoreCase(jt)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 执行子任务(含自身日志), 供总任务调用
     */
    private boolean runChildWithNodes(EtlJob child) {
        // 复用调用方已插入的同子任务 RUNNING 容器日志, 避免每次执行产生重复 SUBTASK 日志
        EtlJobLog childLog = jobLogMapper.selectOne(new LambdaQueryWrapper<EtlJobLog>()
            .eq(EtlJobLog::getJobId, child.getId())
            .eq(EtlJobLog::getNodeType, "SUBTASK")
            .eq(EtlJobLog::getNodeName, child.getJobName())
            .eq(EtlJobLog::getRunStatus, "RUNNING")
            .orderByDesc(EtlJobLog::getId)
            .last("LIMIT 1"));
        if (childLog == null) {
            childLog = new EtlJobLog();
            childLog.setJobId(child.getId());
            childLog.setNodeType("SUBTASK");
            childLog.setNodeId(String.valueOf(child.getId()));
            childLog.setNodeName(child.getJobName());
            childLog.setRunStatus("RUNNING");
            childLog.setStartTime(new Date());
            childLog.setCreateTime(new Date());
            jobLogMapper.insert(childLog);
        }
        markExec(child, "RUNNING");
        EtlJobLog result = null;
        String savedWhere = child.getSrcWhere();
        try {
            // 增量条件注入: incrField > incrLastValue 与已有 where 合并
            boolean incr = StrUtil.isNotBlank(child.getIncrField()) && StrUtil.isNotBlank(child.getIncrLastValue());
            log.info("ETL-INC jobId={} incrField={} incrLastValue={} savedWhere={} incr={}",
                child.getId(), child.getIncrField(), child.getIncrLastValue(), savedWhere, incr);
            if (incr) {
                // JOIN 多源组合: 增量字段必须限定主表别名 t0.(多表同名列会产生歧义)
                String incrFieldRef = srcListJoinMode(child) ? "t0." + child.getIncrField() : child.getIncrField();
                String incrCond = incrFieldRef + " > '" + child.getIncrLastValue().replace("'", "''") + "'";
                child.setSrcWhere(StrUtil.isBlank(savedWhere) ? incrCond : "(" + savedWhere + ") AND " + incrCond);
                child.setKettleXml(null); // 强制重建转换(含增量条件)
            }
            try {
                int maxRetry = child.getRetryCount() == null ? 0 : child.getRetryCount();
                int interval = child.getRetryInterval() == null ? 30 : child.getRetryInterval();
                for (int attempt = 0; attempt <= maxRetry; attempt++) {
                    if (cancelFlags.containsKey(child.getId())) {
                        break; // 取消标记由循环后统一处理(覆盖为取消语义), 此处仅中断
                    }
                    if (attempt > 0) {
                        try {
                            Thread.sleep(interval * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        // 重试前重置子任务日志状态
                        childLog.setRunStatus("RUNNING");
                        childLog.setEndTime(null);
                        childLog.setErrorMsg(null);
                        jobLogMapper.updateById(childLog);
                    }
                    result = runByType(child, childLog);
                    if (result != null && "SUCCESS".equals(result.getRunStatus())) {
                        break;
                    }
                    if (attempt < maxRetry) {
                        log.warn("子任务[{}]执行失败, 第{}次重试/共{}次(间隔{}s): {}",
                            child.getJobName(), attempt + 1, maxRetry, interval,
                            result == null ? "未知错误" : result.getErrorMsg());
                    }
                }
            } finally {
                child.setSrcWhere(savedWhere);
                if (!incr) {
                    child.setKettleXml(null); // 始终让引擎按最新配置重建, 避免缓存过期
                }
            }
            // 用户取消: 覆盖引擎失败信息为取消语义
            if (cancelFlags.containsKey(child.getId())) {
                cancelFlags.remove(child.getId());
                if (result != null) {
                    result.setRunStatus("FAILED");
                    result.setErrorMsg("任务已被用户取消");
                    result.setEndTime(new Date());
                    jobLogMapper.updateById(result);
                } else {
                    childLog.setRunStatus("FAILED");
                    childLog.setErrorMsg("任务已被用户取消");
                    childLog.setEndTime(new Date());
                    jobLogMapper.updateById(childLog);
                }
                return false;
            }
            boolean childOk = result != null && "SUCCESS".equals(result.getRunStatus());
            if (childOk) {
                writeNodeLogs(child, result.getId());
            }
            notifyJob(child, childOk, result == null ? null : result.getErrorMsg());
            return childOk;
        } finally {
            child.setSrcWhere(savedWhere); // 还原注入前的 where, 防止残留到后续运行
            markExec(child, "IDLE");
        }
    }

    /**
     * 并行/顺序执行子任务列表(总任务)
     */
    private int[] runChildren(EtlJob job, List<EtlJob> children, StringBuilder summary) {
        boolean parallel = "PARALLEL".equalsIgnoreCase(job.getRunMode());
        int[] ok = {0};
        int[] skipped = {0};
        if (parallel) {
            // DAG 分层并行: 按 depIds 分波, 同层并行执行, 层间等待依赖完成(优先波次先执行)
            java.util.Map<Long, EtlJob> byId = children.stream()
                .collect(Collectors.toMap(EtlJob::getId, c -> c));
            java.util.Map<Long, java.util.Set<Long>> depMap = new java.util.HashMap<>();
            for (EtlJob c : children) {
                java.util.Set<Long> s = new java.util.HashSet<>();
                if (StrUtil.isNotBlank(c.getDepIds())) {
                    for (String d : c.getDepIds().split(",")) {
                        try {
                            long id = Long.parseLong(d.trim());
                            if (byId.containsKey(id)) {
                                s.add(id);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                depMap.put(c.getId(), s);
            }
            List<EtlJob> pending = new java.util.ArrayList<>(children);
            int wave = 0;
            while (!pending.isEmpty()) {
                List<EtlJob> layer = pending.stream()
                    .filter(c -> depMap.get(c.getId()).stream()
                        .noneMatch(did -> pending.stream().anyMatch(p -> p.getId().equals(did))))
                    .collect(Collectors.toList());
                if (layer.isEmpty()) {
                    summary.append("子任务依赖存在环, 剩余 ").append(pending.size()).append(" 个子任务跳过\n");
                    skipped[0] += pending.size();
                    break;
                }
                wave++;
                summary.append("[第 ").append(wave).append(" 波] ").append(layer.size())
                    .append(" 个子任务并行执行\n");
                List<CompletableFuture<Void>> futures = layer.stream()
                    .map(c -> CompletableFuture.runAsync(() -> {
                        try {
                            if (!checkDeps(c)) {
                                skipped[0]++;
                                summary.append("子任务[").append(c.getJobName()).append("] 跳过(前置依赖未全部成功)\n");
                                return;
                            }
                            boolean childOk = runChildWithNodes(c);
                            if (childOk) {
                                ok[0]++;
                                summary.append("子任务[").append(c.getJobName()).append("] 成功\n");
                            } else {
                                summary.append("子任务[").append(c.getJobName()).append("] 失败(见子任务日志)\n");
                            }
                        } catch (Exception e) {
                            summary.append("子任务[").append(c.getJobName()).append("] 失败: ").append(e.getMessage()).append("\n");
                        }
                    }, executor))
                    .collect(Collectors.toList());
                for (CompletableFuture<Void> f : futures) {
                    f.join();
                }
                pending.removeIf(c -> layer.contains(c));
            }
        } else {
            for (EtlJob child : children) {
                try {
                    if (!checkDeps(child)) {
                        skipped[0]++;
                        summary.append("子任务[").append(child.getJobName()).append("] 跳过(前置依赖未全部成功)\n");
                        continue;
                    }
                    boolean childOk = runChildWithNodes(child);
                    if (childOk) {
                        ok[0]++;
                        summary.append("子任务[").append(child.getJobName()).append("] 成功\n");
                    } else {
                        summary.append("子任务[").append(child.getJobName()).append("] 失败(见子任务日志)\n");
                    }
                } catch (Exception e) {
                    summary.append("子任务[").append(child.getJobName()).append("] 失败: ").append(e.getMessage()).append("\n");
                }
            }
        }
        return new int[]{ok[0], skipped[0]};
    }

    /**
     * DAG 依赖检查: 子任务的前置依赖(最近一次执行)是否全部 SUCCESS
     */
    private boolean checkDeps(EtlJob child) {
        if (StrUtil.isBlank(child.getDepIds())) {
            return true;
        }
        String[] deps = child.getDepIds().split(",");
        for (String dep : deps) {
            dep = dep.trim();
            if (!dep.matches("\\d+")) {
                continue;
            }
            EtlJobLog last = jobLogMapper.selectOne(new LambdaQueryWrapper<EtlJobLog>()
                .eq(EtlJobLog::getJobId, Long.valueOf(dep))
                .eq(EtlJobLog::getNodeType, "SUBTASK")
                .orderByDesc(EtlJobLog::getId)
                .last("LIMIT 1"));
            if (last == null || !"SUCCESS".equals(last.getRunStatus())) {
                log.warn("子任务[{}] 前置依赖[{}]未通过(最近状态={}), 跳过", child.getJobName(), dep,
                    last == null ? "无执行记录" : last.getRunStatus());
                return false;
            }
        }
        return true;
    }

    /**
     * 更新任务执行状态
     */
    private void markExec(EtlJob job, String state) {
        try {
            job.setExecState(state);
            if ("IDLE".equals(state)) {
                job.setExecPid(null);
            }
            // 只更新执行状态与进程ID, 禁止整行写回(运行期注入的 srcWhere/临时字段不得落库)
            jobMapper.update(null, new LambdaUpdateWrapper<EtlJob>()
                .eq(EtlJob::getId, job.getId())
                .set(EtlJob::getExecState, state)
                .set("IDLE".equals(state), EtlJob::getExecPid, null));
        } catch (Exception e) {
            log.warn("更新执行状态失败: {}", e.getMessage());
        }
    }

    /**
     * 告警通知(MAIL): 按 notifyOn 时机发送执行结果邮件
     */
    private void notifyJob(EtlJob job, boolean ok, String errorMsg) {
        try {
            String type = StrUtil.blankToDefault(job.getNotifyType(), "NONE");
            if (!"MAIL".equalsIgnoreCase(type)) {
                return;
            }
            String on = StrUtil.blankToDefault(job.getNotifyOn(), "FAILED");
            boolean need = "ALL".equalsIgnoreCase(on)
                || (ok && "SUCCESS".equalsIgnoreCase(on))
                || (!ok && "FAILED".equalsIgnoreCase(on));
            if (!need || StrUtil.isBlank(job.getNotifyMails())) {
                return;
            }
            String status = ok ? "成功" : "失败";
            StringBuilder content = new StringBuilder();
            content.append("ETL 任务执行告警\n\n");
            content.append("任务名称: ").append(StrUtil.nullToEmpty(job.getJobName())).append("\n");
            content.append("引擎类型: ").append(StrUtil.blankToDefault(job.getJobType(), "-")).append("\n");
            content.append("源: ").append(job.getSrcConnId() == null ? "-" : job.getSrcConnId()).append(".").append(StrUtil.nullToEmpty(job.getSrcTable())).append("\n");
            content.append("目标: ").append(job.getDstConnId() == null ? "-" : job.getDstConnId()).append(".").append(StrUtil.nullToEmpty(job.getDstTable())).append("\n");
            content.append("执行状态: ").append(status).append("\n");
            if (StrUtil.isNotBlank(errorMsg)) {
                content.append("错误信息: ").append(errorMsg).append("\n");
            }
            content.append("执行时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n\n");
            content.append("此为系统自动通知, 请勿回复");
            MailBuilder.of().to(job.getNotifyMails())
                .subject("[ETL] " + StrUtil.nullToEmpty(job.getJobName()) + " 执行" + status)
                .text(content.toString()).send();
        } catch (Exception e) {
            log.warn("发送告警邮件失败: {}", e.getMessage());
        }
    }

    /**
     * 取消运行中的任务(DataX 杀进程 / Kettle 停转换, 日志标记取消)
     */
    @Override
    public void cancel(Long jobId) {
        EtlJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new ServiceException("任务不存在: " + jobId);
        }
        boolean running = "RUNNING".equals(job.getExecState())
            || DataXService.isRunning(jobId)
            || KettleService.isRunning(jobId);
        if (!running) {
            throw new ServiceException("任务当前未在运行, 无需取消");
        }
        cancelFlags.put(jobId, "1");
        DataXService.cancel(jobId);
        KettleService.cancel(jobId);
        // 运行中日志标记为取消
        List<EtlJobLog> runningLogs = jobLogMapper.selectList(new LambdaQueryWrapper<EtlJobLog>()
            .eq(EtlJobLog::getJobId, jobId)
            .eq(EtlJobLog::getRunStatus, "RUNNING"));
        for (EtlJobLog l : runningLogs) {
            l.setRunStatus("FAILED");
            l.setErrorMsg("任务已被用户取消");
            l.setEndTime(new Date());
            jobLogMapper.updateById(l);
        }
        job.setExecState("IDLE");
        job.setExecPid(null);
        jobMapper.updateById(job);
    }

    /**
     * 查询任务运行状态: RUNNING/IDLE
     */
    @Override
    public String state(Long jobId) {
        EtlJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new ServiceException("任务不存在: " + jobId);
        }
        if ("RUNNING".equals(job.getExecState()) || DataXService.isRunning(jobId) || KettleService.isRunning(jobId)) {
            return "RUNNING";
        }
        return "IDLE";
    }

    /**
     * 子任务执行成功后, 记录各节点日志(源数据源/源表/清洗规则/质量规则/目标)
     */
    private void writeNodeLogs(EtlJob job, Long logId) {
        try {
            String baseLog = jobLogMapper.selectById(logId) != null ? jobLogMapper.selectById(logId).getLogText() : "";
            // 源数据源节点
            if (job.getSrcConnId() != null) {
                EtlJobLog n = new EtlJobLog();
                n.setJobId(job.getId());
                n.setSubtaskId(job.getId());
                n.setNodeType("DATA_SOURCE");
                n.setNodeId(String.valueOf(job.getSrcConnId()));
                n.setNodeName("源数据源:" + job.getSrcConnId());
                n.setRunStatus("SUCCESS");
                n.setStartTime(new Date());
                n.setEndTime(new Date());
                n.setCreateTime(new Date());
                n.setLogText("数据源连接验证通过\n" + baseLog);
                jobLogMapper.insert(n);
            }
            // 源表节点(多源)
            String srcList = job.getSrcList();
            if (StrUtil.isNotBlank(srcList)) {
                try {
                    var arr = JSONUtil.parseArray(srcList);
                    for (Object o : arr) {
                        JSONObject item = (JSONObject) o;
                        String t = item.getStr("srcTable", "");
                        if (StrUtil.isBlank(t)) {
                            continue;
                        }
                        EtlJobLog n = new EtlJobLog();
                        n.setJobId(job.getId());
                        n.setSubtaskId(job.getId());
                        n.setNodeType("TABLE");
                        n.setNodeId(t);
                        n.setNodeName("源表:" + t);
                        n.setRunStatus("SUCCESS");
                        n.setStartTime(new Date());
                        n.setEndTime(new Date());
                        n.setCreateTime(new Date());
                        n.setLogText("源表读取完成\n" + baseLog);
                        jobLogMapper.insert(n);
                    }
                } catch (Exception ignored) {
                }
            } else if (StrUtil.isNotBlank(job.getSrcTable())) {
                EtlJobLog n = new EtlJobLog();
                n.setJobId(job.getId());
                n.setSubtaskId(job.getId());
                n.setNodeType("TABLE");
                n.setNodeId(job.getSrcTable());
                n.setNodeName("源表:" + job.getSrcTable());
                n.setRunStatus("SUCCESS");
                n.setStartTime(new Date());
                n.setEndTime(new Date());
                n.setCreateTime(new Date());
                n.setLogText("源表读取完成\n" + baseLog);
                jobLogMapper.insert(n);
            }
            // 清洗规则节点
            writeRuleNodeLogs(job, job.getCleanRules(), "CLEAN_RULE", "清洗规则");
            // 质量规则节点
            writeRuleNodeLogs(job, job.getQualityRules(), "QUALITY_RULE", "质量规则");
            // 目标表节点
            if (StrUtil.isNotBlank(job.getDstTable())) {
                EtlJobLog n = new EtlJobLog();
                n.setJobId(job.getId());
                n.setSubtaskId(job.getId());
                n.setNodeType("TABLE");
                n.setNodeId(job.getDstTable());
                n.setNodeName("目标表:" + job.getDstTable());
                n.setRunStatus("SUCCESS");
                n.setStartTime(new Date());
                n.setEndTime(new Date());
                n.setCreateTime(new Date());
                n.setLogText("目标表写入完成\n" + baseLog);
                jobLogMapper.insert(n);
            }
        } catch (Exception e) {
            log.warn("写入节点日志失败: {}", e.getMessage());
        }
    }

    private void writeRuleNodeLogs(EtlJob job, String rulesJson, String nodeType, String label) {
        if (StrUtil.isBlank(rulesJson)) {
            return;
        }
        try {
            var arr = JSONUtil.parseArray(rulesJson);
            for (Object o : arr) {
                JSONObject item = (JSONObject) o;
                // 兼容分组格式 [{srcTable, rules:[{ruleName,...}]}] 与旧平铺 [{ruleName,...}]
                String table = item.getStr("srcTable", "");
                cn.hutool.json.JSONArray inner = item.getJSONArray("rules");
                if (inner != null) {
                    for (Object io : inner) {
                        JSONObject rule = (JSONObject) io;
                        String rname = rule.getStr("ruleName", "");
                        if (StrUtil.isBlank(rname)) {
                            continue;
                        }
                        EtlJobLog n = new EtlJobLog();
                        n.setJobId(job.getId());
                        n.setSubtaskId(job.getId());
                        n.setNodeType(nodeType);
                        n.setNodeId(rname);
                        n.setNodeName(label + ":" + (StrUtil.isNotBlank(table) ? table + "/" : "") + rname);
                        n.setRunStatus("SUCCESS");
                        n.setStartTime(new Date());
                        n.setEndTime(new Date());
                        n.setCreateTime(new Date());
                        n.setLogText("规则执行完成");
                        jobLogMapper.insert(n);
                    }
                    continue;
                }
                String name = item.getStr("ruleName", "");
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                EtlJobLog n = new EtlJobLog();
                n.setJobId(job.getId());
                n.setSubtaskId(job.getId());
                n.setNodeType(nodeType);
                n.setNodeId(name);
                n.setNodeName(label + ":" + name);
                n.setRunStatus("SUCCESS");
                n.setStartTime(new Date());
                n.setEndTime(new Date());
                n.setCreateTime(new Date());
                n.setLogText("规则[" + name + "]执行完成\n规则配置: " + item.toString());
                jobLogMapper.insert(n);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 按任务类型分派执行器: DATAX -> DataXService, KETTLE -> KettleService
     */
    private EtlJobLog runByType(EtlJob job, EtlJobLog jobLog) {
        if (isKettle(job)) {
            return kettleService.run(job, jobLog);
        }
        return dataXService.run(job, jobLog);
    }

    @Override
    public Page<EtlJobLog> logPage(Integer pageNum, Integer pageSize, Long jobId, Long subtaskId, String nodeType, String nodeId, String runStatus) {
        LambdaQueryWrapper<EtlJobLog> qw = new LambdaQueryWrapper<>();
        if (jobId != null) {
            // job 可能是总任务(含子任务)或子任务: 总任务时匹配其全部子任务日志
            EtlJob job = jobMapper.selectById(jobId);
            boolean isTop = isParent(job);
            if (isTop) {
                List<Long> ids = new java.util.ArrayList<>();
                ids.add(jobId);
                List<EtlJob> children = jobMapper.selectList(new LambdaQueryWrapper<EtlJob>()
                    .eq(EtlJob::getParentId, jobId));
                if (children != null && !children.isEmpty()) {
                    ids.addAll(children.stream().map(EtlJob::getId).collect(Collectors.toList()));
                }
                qw.in(EtlJobLog::getJobId, ids);
            } else {
                qw.eq(EtlJobLog::getJobId, jobId);
            }
        }
        qw.eq(subtaskId != null, EtlJobLog::getSubtaskId, subtaskId)
            .eq(StrUtil.isNotBlank(nodeType), EtlJobLog::getNodeType, nodeType)
            .like(StrUtil.isNotBlank(nodeId), EtlJobLog::getNodeId, nodeId)
            .eq(StrUtil.isNotBlank(runStatus), EtlJobLog::getRunStatus, runStatus)
            .orderByDesc(EtlJobLog::getCreateTime);
        Page<EtlJobLog> page = jobLogMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        page.getRecords().forEach(l -> {
            if (l.getLogText() != null && l.getLogText().length() > 5000) {
                l.setLogText(l.getLogText().substring(l.getLogText().length() - 5000));
            }
        });
        return page;
    }

    @Override
    public EtlJobLog selectLogById(Long logId) {
        return jobLogMapper.selectById(logId);
    }

    @Override
    public List<EtlJob> listScheduleEnabledJobs() {
        return jobMapper.selectList(new LambdaQueryWrapper<EtlJob>()
            .eq(EtlJob::getScheduleEnabled, "1")
            .isNotNull(EtlJob::getNextRunTime));
    }

    @Override
    public void updateNextRunTime(Long jobId) {
        EtlJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        job.setLastRunTime(new Date());
        job.setNextRunTime(calcNext(job.getScheduleCron(), job.getScheduleEnabled()));
        jobMapper.update(null, new LambdaUpdateWrapper<EtlJob>()
            .eq(EtlJob::getId, job.getId())
            .set(EtlJob::getLastRunTime, job.getLastRunTime())
            .set(EtlJob::getNextRunTime, job.getNextRunTime()));
    }

    @Override
    public String cronNext(String cron) {
        Date next = calcNext(cron, "1");
        if (next == null) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(next);
    }

    @Override
    public String designKettleXml(KettleDesignReq req) {
        try {
            return kettleService.designXml(req);
        } catch (Exception e) {
            throw new ServiceException("Kettle 设计生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算下次执行时间: cron 合法且启用则返回下次时间, 否则 null
     */
    private Date calcNext(String cron, String enabled) {
        if (!"1".equals(enabled) || StrUtil.isBlank(cron)) {
            return null;
        }
        // 7 段 cron 解析(支持秒 分 时 日 月 周 年, 兼容 6 段), 详见 CronNextUtil
        return CronNextUtil.next(cron);
    }
}
