package pan.dromara.etl.service.impl;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.Condition;
import org.pentaho.di.core.ObjectLocationSpecificationMethod;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entries.trans.JobEntryTrans;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.core.Result;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.logging.KettleLogStore;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entries.special.JobEntrySpecial;
import org.pentaho.di.job.entries.sql.JobEntrySQL;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;
import org.pentaho.di.trans.steps.sortedmerge.SortedMergeMeta;import org.pentaho.di.trans.steps.mergejoin.MergeJoinMeta;
import org.pentaho.di.trans.steps.selectvalues.SelectValuesMeta;
import org.pentaho.di.trans.steps.uniquerows.UniqueRowsMeta;
import org.pentaho.di.trans.steps.filterrows.FilterRowsMeta;
import org.pentaho.di.trans.steps.csvinput.CsvInputMeta;
import org.pentaho.di.trans.steps.textfileinput.TextFileInputField;
import org.pentaho.di.trans.steps.stringoperations.StringOperationsMeta;
import org.pentaho.di.trans.steps.calculator.CalculatorMeta;
import org.pentaho.di.trans.steps.calculator.CalculatorMetaFunction;
import org.pentaho.di.trans.steps.ifnull.IfNullMeta;
import org.pentaho.di.trans.steps.textfileoutput.TextFileOutputMeta;
import org.pentaho.di.trans.steps.httppost.HTTPPOSTMeta;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaAndData;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.database.Database;
import org.springframework.stereotype.Service;
import pan.dromara.dbtool.core.DbConnCrypto;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.service.IDbConnService;
import pan.dromara.etl.domain.EtlJob;
import pan.dromara.etl.domain.EtlJobLog;
import pan.dromara.etl.domain.KettleDesignReq;
import pan.dromara.etl.mapper.EtlJobLogMapper;
import pan.dromara.etl.util.EtlFileBridge;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kettle ETL 引擎服务: 库内调用 Kettle 执行转换/作业
 * 引擎为本地自编译 kettle-engine 9.4.0.0-343(见《组织主数据迁移说明》§12.8)
 * - TRANS: TableInput → TableOutput 转换
 * - JOB: 作业(可含 SQL 作业项 / 内嵌转换作业项)
 *
 * @author doubao
 */
@Slf4j
@Service
public class KettleService {

    private final IDbConnService dbConnService;
    private final EtlJobLogMapper jobLogMapper;
    private volatile boolean inited = false;

    /** 运行中的转换/作业 (jobId -> 对象), 供取消 */
    private static final Map<Long, Trans> RUNNING_TRANS = new ConcurrentHashMap<>();
    private static final Map<Long, org.pentaho.di.job.Job> RUNNING_JOBS = new ConcurrentHashMap<>();

    /** 取消运行中的 Kettle 任务 */
    public static void cancel(Long jobId) {
        Trans t = RUNNING_TRANS.get(jobId);
        if (t != null) {
            try {
                t.stopAll();
            } catch (Exception ignored) {
            }
        }
        org.pentaho.di.job.Job j = RUNNING_JOBS.get(jobId);
        if (j != null) {
            try {
                j.stopAll();
            } catch (Exception ignored) {
            }
        }
        RUNNING_TRANS.remove(jobId);
        RUNNING_JOBS.remove(jobId);
    }

    /** 是否正在运行 */
    public static boolean isRunning(Long jobId) {
        return RUNNING_TRANS.containsKey(jobId) || RUNNING_JOBS.containsKey(jobId);
    }

    public KettleService(IDbConnService dbConnService, EtlJobLogMapper jobLogMapper) {
        this.dbConnService = dbConnService;
        this.jobLogMapper = jobLogMapper;
    }

    /**
     * Kettle 环境初始化(幂等)
     */
    @PostConstruct
    public synchronized void ensureInit() {
        if (inited) {
            return;
        }
        try {
            KettleEnvironment.init();
            inited = true;
            log.info("KettleEnvironment.init() OK");
        } catch (Throwable e) {
            log.error("KettleEnvironment.init() 失败: {}", e.getMessage(), e);
            inited = false;
        }
    }

    /**
     * 生成 TableInput → TableOutput 转换 XML(按 EtlJob 配置自动构建)
     */
    public String buildTransXml(EtlJob job) throws Exception {
        String srcType = StrUtil.blankToDefault(job.getSrcType(), EtlFileBridge.TABLE).toUpperCase();
        String dstType = StrUtil.blankToDefault(job.getDstType(), EtlFileBridge.TABLE).toUpperCase();

        TransMeta tm = new TransMeta();
        tm.setName("etl_" + StrUtil.nullToEmpty(job.getJobName()).replaceAll("\\s+", "_") + "_" + System.nanoTime());

        // ============ 源: TABLE → TableInput; EXCEL/API → 桥接 CSV → CsvInput ============
        StepMeta input;
        int x = 80;
        if (EtlFileBridge.TABLE.equals(srcType)) {
            DbConn src = dbConnService.getById(job.getSrcConnId());
            if (src == null) {
                throw new RuntimeException("源数据源不存在: src=" + job.getSrcConnId());
            }
            String srcSchema = StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName());
            DatabaseMeta srcDb = toDatabaseMeta("src_db", src, srcSchema);
            tm.addDatabase(srcDb);
            TableInputMeta tim = new TableInputMeta();
            tim.setDatabaseMeta(srcDb);
            tim.setSQL("SELECT * FROM " + quoteTable(srcSchema, job.getSrcTable(), src) + suffixWhere(job));
            tim.setRowLimit("0");
            input = new StepMeta("TableInput", "TableInput", tim);
        } else if (EtlFileBridge.EXCEL.equals(srcType) || EtlFileBridge.API.equals(srcType)) {
            EtlFileBridge.BridgeResult br = EtlFileBridge.srcToCsv(job);
            CsvInputMeta cim = new CsvInputMeta();
            cim.setFilename(br.csvPath);
            cim.setDelimiter(",");
            cim.setEnclosure("\"");
            cim.setEncoding("UTF-8");
            cim.setHeaderPresent(true);
            cim.setBufferSize("50000");
            TextFileInputField[] fields = new TextFileInputField[br.columns.size()];
            for (int i = 0; i < br.columns.size(); i++) {
                TextFileInputField f = new TextFileInputField(br.columns.get(i), ValueMetaInterface.TYPE_STRING, -1);
                fields[i] = f;
            }
            cim.setInputFields(fields);
            input = new StepMeta("CsvInput", "CsvInput", cim);
        } else {
            throw new RuntimeException("不支持的源类型: " + srcType + "(仅 TABLE/EXCEL/API)");
        }
        input.setLocation(x, 80);
        input.drawStep();
        tm.addStep(input);

        // ============ 目标: TABLE → TableOutput; EXCEL → TextFileOutput(CSV); API → HTTPPOST ============
        StepMeta output;
        x += 240;
        if (EtlFileBridge.TABLE.equals(dstType)) {
            DbConn dst = dbConnService.getById(job.getDstConnId());
            if (dst == null) {
                throw new RuntimeException("目标数据源不存在: dst=" + job.getDstConnId());
            }
            String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
            DatabaseMeta dstDb = toDatabaseMeta("dst_db", dst, dstSchema);
            tm.addDatabase(dstDb);
            TableOutputMeta tom = new TableOutputMeta();
            tom.setDatabaseMeta(dstDb);
            tom.setTablename(job.getDstTable());
            tom.setCommitSize(job.getBatchSize() == null ? 1024 : job.getBatchSize());
            tom.setTruncateTable("TRUNCATE".equalsIgnoreCase(job.getDstWriteMode()) || "REPLACE".equalsIgnoreCase(job.getDstWriteMode()));
            output = new StepMeta("TableOutput", "TableOutput", tom);
        } else if (EtlFileBridge.EXCEL.equals(dstType)) {
            if (StrUtil.isBlank(job.getDstExcelPath())) {
                throw new RuntimeException("目标Excel文件路径未配置(dstExcelPath)");
            }
            TextFileOutputMeta fom = new TextFileOutputMeta();
            fom.setFilename(EtlFileBridge.dstCsvPath(job));
            fom.setSeparator(",");
            fom.setEnclosure("\"");
            fom.setEncoding("UTF-8");
            fom.setHeaderEnabled(true);
            fom.setFileAppended(false);
            fom.setCreateParentFolder(true);
            output = new StepMeta("TextFileOutput", "TextFileOutput", fom);
        } else if (EtlFileBridge.API.equals(dstType)) {
            if (StrUtil.isBlank(job.getDstApiUrl())) {
                throw new RuntimeException("目标API地址未配置(dstApiUrl)");
            }
            HTTPPOSTMeta hpm = new HTTPPOSTMeta();
            hpm.setUrl(job.getDstApiUrl());
            hpm.setRequestEntity(StrUtil.blankToDefault(job.getDstApiBodyTpl(), "{}"));
            hpm.setConnectionTimeout("15000");
            hpm.setSocketTimeout("60000");
            output = new StepMeta("HTTPPOST", "HTTPPOST", hpm);
        } else {
            throw new RuntimeException("不支持的目标类型: " + dstType + "(仅 TABLE/EXCEL/API)");
        }
        output.setLocation(x, 80);
        output.drawStep();
        tm.addStep(output);

        tm.addTransHop(new TransHopMeta(input, output));
        return XMLHandler.getXMLHeader() + tm.getXML();
    }

    /**
     * 生成简单作业 XML(自动): TRUNCATE 目标表(TRUNCATE 模式)
     * 说明: 自编译引擎不支持内嵌转换(EMBEDDED), 作业默认生成 SQL 作业项;
     * 数据同步请用转换(TRANS)类型, 如需"清空+同步"可先执行作业清空再执行转换
     */
    public String buildJobXml(EtlJob job) throws Exception {
        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (dst == null) {
            throw new RuntimeException("目标数据源不存在: dst=" + job.getDstConnId());
        }
        JobMeta jm = new JobMeta();
        jm.setName("etl_job_" + StrUtil.nullToEmpty(job.getJobName()).replaceAll("\\s+", "_") + "_" + System.nanoTime());

        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
        DatabaseMeta dstDb = toDatabaseMeta("dst_db", dst, dstSchema);
        jm.addDatabase(dstDb);

        String sql = null;
        if ("TRUNCATE".equalsIgnoreCase(job.getDstWriteMode())) {
            sql = "TRUNCATE TABLE " + quoteTable(dstSchema, job.getDstTable(), dst);
        }
        if (StrUtil.isBlank(sql)) {
            throw new RuntimeException("作业自动生成仅支持 TRUNCATE 写入模式(用于清空目标表), 数据同步请使用转换类型或可视化设计器添加 SQL 作业项");
        }
        JobEntrySQL jes = new JobEntrySQL();
        jes.setName("清空目标表");
        jes.setDatabase(dstDb);
        jes.setSQL(sql);
        JobEntryCopy jec = new JobEntryCopy(jes);
        jec.setLocation(80, 80);
        jec.setDrawn(true);
        jm.addJobEntry(jec);
        // Start 入口作业项(Kettle 作业必须以 Start 为起点)
        JobEntrySpecial startEntry = new JobEntrySpecial("START", true, false);
        JobEntryCopy startCopy = new JobEntryCopy(startEntry);
        startCopy.setLocation(20, 80);
        startCopy.setDrawn(true);
        jm.addJobEntry(startCopy);
        jm.addJobHop(new org.pentaho.di.job.JobHopMeta(startCopy, jec));
        return XMLHandler.getXMLHeader() + jm.getXML();
    }

    /**
     * 可视化设计器: 按画布节点/连线生成 Kettle XML
     */
    public String designXml(KettleDesignReq req) throws Exception {
        if ("JOB".equalsIgnoreCase(req.getKettleType())) {
            return buildJobXmlFromDesign(req);
        }
        return buildTransXmlFromDesign(req);
    }

    // ==================== 执行 ====================

    /**
     * 执行 Kettle 任务(阻塞, 按 kettleType 分派转换/作业)
     */
    public EtlJobLog run(EtlJob job, EtlJobLog jobLog) {
        ensureInit();
        if (!inited) {
            jobLog.setRunStatus("FAILED");
            jobLog.setErrorMsg("Kettle 引擎未初始化(KettleEnvironment.init 失败), 请检查后端日志");
            jobLog.setEndTime(new Date());
            jobLogMapper.updateById(jobLog);
            return jobLog;
        }
        if ("JOB".equalsIgnoreCase(job.getKettleType())) {
            return runJob(job, jobLog);
        }
        return runTrans(job, jobLog);
    }

    /**
     * 执行转换(TRANS)
     */
    private EtlJobLog runTrans(EtlJob job, EtlJobLog jobLog) {
        // 目标为 Excel/API 且无管线(单段): 后端桥接直转(不构建 Kettle 转换)
        // 自编译 Kettle 的 TextFileOutput 需显式字段列表、HTTPPOST 不支持 ${字段} 模板替换, 故单段统一走后端处理
        boolean dstBridge = EtlFileBridge.isBridgeDst(job);
        boolean multiStage = hasPipeline(job);
        if (dstBridge && !multiStage) {
            return runBridgeDst(job, jobLog);
        }
        File ktr = null;
        try {
            boolean bridgeSrc = EtlFileBridge.isBridgeSrc(job);
            String xml = job.getKettleXml();
            ensureCleanTable(job);
            ensureDstTable(job);
            if (multiStage) {
                // 两段顺序执行(避免单转换并行竞态: 段2 读清洗结果表必须晚于段1 提交):
                //   段1 源→[清洗规则]→清洗结果表; 段2 结果表→[质量规则]→目标表/桥接临时表
                ensureBridgeTmpTable(job);
                Path dir = Files.createTempDirectory("kettle_trans_");
                String seg1 = buildPipelineSeg1Xml(job);
                job.setPipeSeg1Xml(seg1);
                job.setKettleXml(seg1);
                ktr = Files.writeString(dir.resolve("seg1.ktr"), seg1, StandardCharsets.UTF_8).toFile();
                try {
                    Files.writeString(java.nio.file.Paths.get("D:/project/etl-engine/seg_dump_seg1_" + job.getId() + ".xml"), seg1, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                Trans trans1 = new Trans(new TransMeta(ktr.getAbsolutePath()));
                try {
                    for (org.pentaho.di.trans.step.StepMeta sm : trans1.getTransMeta().getSteps()) {
                        if (sm.getStepMetaInterface() instanceof org.pentaho.di.trans.steps.ifnull.IfNullMeta) {
                            org.pentaho.di.trans.steps.ifnull.IfNullMeta inm = (org.pentaho.di.trans.steps.ifnull.IfNullMeta) sm.getStepMetaInterface();
                            log.warn("DIAG IfNull[{}] selectValuesType={} valueTypes={} vt0={} fields={}",
                                    sm.getName(), inm.isSelectValuesType(),
                                    inm.getValueTypes() == null ? "NULL" : inm.getValueTypes().length,
                                    (inm.getValueTypes() != null && inm.getValueTypes().length > 0) ? inm.getValueTypes()[0].getTypeName() : "-",
                                    inm.getFields() == null ? "NULL" : inm.getFields().length);
                            try {
                                org.pentaho.di.core.row.RowMetaInterface inMeta = trans1.getTransMeta().getStepFields(sm);
                                StringBuilder sb = new StringBuilder();
                                if (inMeta != null) {
                                    for (int fi = 0; fi < inMeta.size(); fi++) {
                                        sb.append(inMeta.getValueMeta(fi).getName()).append(":").append(inMeta.getValueMeta(fi).getTypeDesc()).append(";");
                                    }
                                }
                                log.warn("DIAG IfNull inputMeta={} typeCodes={}", sb,
                                        java.util.Arrays.toString(org.pentaho.di.core.row.ValueMetaInterface.typeCodes));
                                java.util.Map<String, Integer> lt = new java.util.HashMap<>();
                                if (inm.getValueTypes() != null) {
                                    for (int ti = 0; ti < inm.getValueTypes().length; ti++) {
                                        lt.put(inm.getValueTypes()[ti].getTypeName(), ti);
                                    }
                                }
                                log.warn("DIAG IfNull ListTypes={}", lt);
                            } catch (Exception e2) {
                                log.warn("DIAG IfNull meta err: " + e2.getMessage());
                            }
                        }
                        if (sm.getStepMetaInterface() instanceof org.pentaho.di.trans.steps.calculator.CalculatorMeta) {
                            org.pentaho.di.trans.steps.calculator.CalculatorMeta cm = (org.pentaho.di.trans.steps.calculator.CalculatorMeta) sm.getStepMetaInterface();
                            if (cm.getCalculation() != null && cm.getCalculation().length > 0) {
                                log.warn("DIAG Calc[{}] type={} fn={} A={} B={}", sm.getName(),
                                        cm.getCalculation()[0].getCalcTypeDesc(),
                                        cm.getCalculation()[0].getFieldName(),
                                        cm.getCalculation()[0].getFieldA(),
                                        cm.getCalculation()[0].getFieldB());
                            }
                        }
                        if (sm.getStepMetaInterface() instanceof org.pentaho.di.trans.steps.uniquerows.UniqueRowsMeta) {
                            org.pentaho.di.trans.steps.uniquerows.UniqueRowsMeta um = (org.pentaho.di.trans.steps.uniquerows.UniqueRowsMeta) sm.getStepMetaInterface();
                            log.warn("DIAG Unique[{}] compare={} ci={} reject={}", sm.getName(),
                                    java.util.Arrays.toString(um.getCompareFields()),
                                    java.util.Arrays.toString(um.getCaseInsensitive()),
                                    um.isRejectDuplicateRow());
                        }
                    }
                } catch (Exception ex) {
                    log.warn("DIAG error: " + ex.getMessage());
                }
                trans1.prepareExecution(new String[0]);
                trans1.startThreads();
                trans1.waitUntilFinished();
                Result r1 = trans1.getResult();
                String log1 = collectLogText(trans1.getLogChannelId());
                if (r1 != null && r1.getNrErrors() > 0) {
                    jobLog.setRunStatus("FAILED");
                    jobLog.setErrorMsg("段1(清洗)执行出错, 错误数=" + r1.getNrErrors() + "\n" + tailError(log1));
                    cleanupTemp(ktr);
                    return jobLog;
                }
                // 注意: 段1 与段2 共用同一临时目录, 段1 后不得清理目录(否则段2.ktr 写不进去)
                String seg2 = buildPipelineSeg2Xml(job);
                job.setPipeSeg2Xml(seg2);
                job.setKettleXml(seg2);
                ktr = Files.writeString(dir.resolve("seg2.ktr"), seg2, StandardCharsets.UTF_8).toFile();
                Trans trans2 = new Trans(new TransMeta(ktr.getAbsolutePath()));
                trans2.prepareExecution(new String[0]);
                trans2.startThreads();
                trans2.waitUntilFinished();
                Result r2 = trans2.getResult();
                String log2 = collectLogText(trans2.getLogChannelId());
                long errors = r2 == null ? 1 : r2.getNrErrors();
                long written = collectWrittenRows(trans2, r2);
                String logText = log1 + "\n" + log2;
                if (logText.length() > 400_000) {
                    logText = logText.substring(logText.length() - 400_000);
                }
                jobLog.setTotalRecords(written);
                jobLog.setLogText(logText);
                if (errors == 0) {
                    jobLog.setRunStatus("SUCCESS");
                    if (dstBridge) {
                        // 目标非表 + 多段: 桥接临时表 → Excel/API 导出
                        exportBridgeTmp(job, jobLog);
                    }
                } else {
                    jobLog.setRunStatus("FAILED");
                    jobLog.setErrorMsg("转换执行出错, 错误数=" + errors + "\n" + tailError(logText));
                }
                cleanupTemp(ktr);
                return jobLog;
            }
            if (bridgeSrc) {
                // 非表源: 桥接 CSV 每次重建, 强制刷新 XML
                xml = buildTransXml(job);
                job.setKettleXml(xml);
            } else if (StrUtil.isBlank(xml)) {
                // JOIN 关联模式 / 多源(>=2) UNION ALL 合并 / 单源
                int multiCnt = countSrcList(job);
                xml = isJoinMode(job) ? buildTransXmlJoin(job) : (multiCnt >= 2 ? buildTransXmlMulti(job) : buildTransXml(job));
                job.setKettleXml(xml);
            }
            Path dir = Files.createTempDirectory("kettle_trans_");
            ktr = Files.writeString(dir.resolve("trans.ktr"), xml, StandardCharsets.UTF_8).toFile();
            try {
                Files.writeString(java.nio.file.Paths.get("D:/project/etl-engine/seg_dump_trans_" + job.getId() + ".xml"), xml, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }

            TransMeta tm = new TransMeta(ktr.getAbsolutePath());
            Trans trans = new Trans(tm);
            trans.prepareExecution(new String[0]);
            trans.startThreads();
            trans.waitUntilFinished();

            Result result = trans.getResult();
            long errors = result == null ? 1 : result.getNrErrors();
            long written = collectWrittenRows(trans, result);
            String logText = collectLogText(trans.getLogChannelId());
            if (logText.length() > 400_000) {
                logText = logText.substring(logText.length() - 400_000);
            }

            jobLog.setTotalRecords(written);
            jobLog.setLogText(logText);
            if (errors == 0) {
                jobLog.setRunStatus("SUCCESS");
                if (dstBridge && multiStage) {
                    // 目标非表 + 多段: 桥接临时表 → Excel/API 导出
                    exportBridgeTmp(job, jobLog);
                }
            } else {
                jobLog.setRunStatus("FAILED");
                jobLog.setErrorMsg("转换执行出错, 错误数=" + errors + "\n" + tailError(logText));
            }
        } catch (Exception e) {
            log.error("Kettle 转换执行失败: {}", e.getMessage(), e);
            jobLog.setRunStatus("FAILED");
            jobLog.setErrorMsg("Kettle 转换执行异常: " + e.getMessage());
        } finally {
            EtlFileBridge.cleanup(EtlFileBridge.srcCsvPath(job), EtlFileBridge.dstCsvPath(job));
            dropBridgeTmpTable(job);
            cleanupTemp(ktr);
            jobLog.setEndTime(new Date());
            jobLogMapper.updateById(jobLog);
        }
        return jobLog;
    }

    /**
     * 目标 Excel/API 后端桥接执行: 源(表/Excel/API) → CSV → 目标(Excel/API)
     */
    private EtlJobLog runBridgeDst(EtlJob job, EtlJobLog jobLog) {
        String dstType = StrUtil.blankToDefault(job.getDstType(), EtlFileBridge.TABLE).toUpperCase();
        try {
            String csv = EtlFileBridge.srcCsvPath(job);
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(csv));
            if (EtlFileBridge.TABLE.equalsIgnoreCase(StrUtil.blankToDefault(job.getSrcType(), EtlFileBridge.TABLE))) {
                tableToCsv(job, csv);
            } else {
                EtlFileBridge.srcToCsv(job);
            }
            long rows;
            String logText;
            if (EtlFileBridge.EXCEL.equals(dstType)) {
                if (StrUtil.isBlank(job.getDstExcelPath())) {
                    throw new RuntimeException("目标Excel文件路径未配置(dstExcelPath)");
                }
                rows = EtlFileBridge.csvRowCount(csv);
                EtlFileBridge.csvToExcel(csv, job.getDstExcelPath(), job.getDstExcelSheet());
                logText = "桥接完成: 源" + StrUtil.blankToDefault(job.getSrcType(), EtlFileBridge.TABLE)
                        + " → Excel(" + job.getDstExcelPath() + "), 行数=" + rows;
            } else {
                rows = EtlFileBridge.pushCsvToApi(csv, job);
                logText = "桥接完成: 源" + StrUtil.blankToDefault(job.getSrcType(), EtlFileBridge.TABLE)
                        + " → API(" + job.getDstApiUrl() + "), 推送行数=" + rows;
            }
            jobLog.setRunStatus("SUCCESS");
            jobLog.setTotalRecords(rows);
            jobLog.setLogText(logText);
        } catch (Exception e) {
            log.error("目标桥接执行失败: {}", e.getMessage(), e);
            jobLog.setRunStatus("FAILED");
            jobLog.setErrorMsg("目标桥接执行异常: " + e.getMessage());
        } finally {
            EtlFileBridge.cleanup(EtlFileBridge.srcCsvPath(job));
            jobLog.setEndTime(new Date());
            jobLogMapper.updateById(jobLog);
        }
        return jobLog;
    }

    /** 是否多段管线(配置了清洗/质量规则或清洗结果表) */
    private boolean hasPipeline(EtlJob job) {
        return (job.getCleanConnId() != null && StrUtil.isNotBlank(job.getCleanTable()))
            || StrUtil.isNotBlank(job.getCleanRules())
            || StrUtil.isNotBlank(job.getQualityRules());
    }

    /** 目标非表桥接临时表名(建在清洗结果库) */
    private String bridgeTmpTable(EtlJob job) {
        return "etl_bridge_tmp_" + job.getId();
    }

    /** 建桥接临时表(目标非表多段管线用): 列=段2映射目标列, 空则回退清洗结果表列 */
    private void ensureBridgeTmpTable(EtlJob job) throws Exception {
        if (!EtlFileBridge.isBridgeDst(job)) {
            return;
        }
        DbConn clean = dbConnService.getById(job.getCleanConnId());
        if (clean == null) {
            throw new RuntimeException("目标为Excel/API的多段管线需配置清洗结果表作为中间承接");
        }
        String cleanSchema = StrUtil.blankToDefault(job.getCleanSchema(), clean.getDatabaseName());
        String tbl = bridgeTmpTable(job);
        DatabaseMeta dbm = toDatabaseMeta("bridge_tmp", clean, cleanSchema);
        Database db = new Database(dbm);
        try {
            db.connect();
            db.execStatement("DROP TABLE IF EXISTS " + quoteTable(cleanSchema, tbl, clean));
            List<String> cols = bridgeTmpCols(job, clean, cleanSchema);
            if (cols.isEmpty()) {
                throw new RuntimeException("目标为Excel/API的管线未解析到目标列(请配置段2映射或确认清洗结果表有列)");
            }
            StringBuilder sql = new StringBuilder("CREATE TABLE ").append(quoteTable(cleanSchema, tbl, clean)).append(" (");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(quoteIdent(cols.get(i), clean)).append(" VARCHAR(255)");
            }
            sql.append(")");
            db.execStatement(sql.toString());
            log.info("桥接临时表已创建: {}", sql);
        } finally {
            try {
                db.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

    /** 桥接临时表列: 段2映射目标列(dstMapping), 空则回退清洗结果表列名 */
    private List<String> bridgeTmpCols(EtlJob job, DbConn clean, String cleanSchema) throws Exception {
        List<String> cols = new ArrayList<>();
        for (Map<String, String> m : parseFlatMapping(job.getDstMapping())) {
            String d = m.get("dstField");
            if (StrUtil.isNotBlank(d) && !cols.contains(d)) {
                cols.add(d);
            }
        }
        if (!cols.isEmpty()) {
            return cols;
        }
        if (StrUtil.isNotBlank(job.getCleanTable())) {
            DatabaseMeta dbm = toDatabaseMeta("bridge_tmp_cols", clean, cleanSchema);
            Database db = new Database(dbm);
            try {
                db.connect();
                try (java.sql.ResultSet rs = db.getConnection().getMetaData()
                        .getColumns(null, cleanSchema, job.getCleanTable(), null)) {
                    while (rs.next()) {
                        cols.add(rs.getString("COLUMN_NAME"));
                    }
                }
            } finally {
                try {
                    db.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
        return cols;
    }

    /** 删除桥接临时表(执行结束清理) */
    private void dropBridgeTmpTable(EtlJob job) {
        if (job == null || job.getId() == null || !EtlFileBridge.isBridgeDst(job)
                || job.getCleanConnId() == null) {
            return;
        }
        try {
            DbConn clean = dbConnService.getById(job.getCleanConnId());
            if (clean == null) {
                return;
            }
            String cleanSchema = StrUtil.blankToDefault(job.getCleanSchema(), clean.getDatabaseName());
            DatabaseMeta dbm = toDatabaseMeta("bridge_tmp_drop", clean, cleanSchema);
            Database db = new Database(dbm);
            try {
                db.connect();
                db.execStatement("DROP TABLE IF EXISTS " + quoteTable(cleanSchema, bridgeTmpTable(job), clean));
            } finally {
                try {
                    db.disconnect();
                } catch (Exception ignore) {
                }
            }
        } catch (Exception e) {
            log.warn("删除桥接临时表失败: {}", e.getMessage());
        }
    }

    /** 桥接临时表 → 目标 Excel/API 导出(多段管线目标非表) */
    private void exportBridgeTmp(EtlJob job, EtlJobLog jobLog) throws Exception {
        DbConn clean = dbConnService.getById(job.getCleanConnId());
        if (clean == null) {
            throw new RuntimeException("清洗结果数据源不存在: clean=" + job.getCleanConnId());
        }
        String cleanSchema = StrUtil.blankToDefault(job.getCleanSchema(), clean.getDatabaseName());
        String csv = EtlFileBridge.srcCsvPath(job);
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(csv));
        tableToCsv(clean, cleanSchema, bridgeTmpTable(job), "", csv);
        String dstType = StrUtil.blankToDefault(job.getDstType(), EtlFileBridge.TABLE).toUpperCase();
        long rows;
        if (EtlFileBridge.EXCEL.equals(dstType)) {
            if (StrUtil.isBlank(job.getDstExcelPath())) {
                throw new RuntimeException("目标Excel文件路径未配置(dstExcelPath)");
            }
            rows = EtlFileBridge.csvRowCount(csv);
            EtlFileBridge.csvToExcel(csv, job.getDstExcelPath(), job.getDstExcelSheet());
            jobLog.setLogText((StrUtil.nullToEmpty(jobLog.getLogText())) + "\n桥接导出: 管线结果 → Excel("
                + job.getDstExcelPath() + "), 行数=" + rows);
        } else {
            rows = EtlFileBridge.pushCsvToApi(csv, job);
            jobLog.setLogText((StrUtil.nullToEmpty(jobLog.getLogText())) + "\n桥接导出: 管线结果 → API("
                + job.getDstApiUrl() + "), 推送行数=" + rows);
        }
        jobLog.setTotalRecords(rows);
    }

    /**
     * 表源 → CSV(UTF-8, 首行表头): 后端 JDBC 查询(复用 Kettle Database 连接)
     */
    private void tableToCsv(EtlJob job, String csvPath) throws Exception {
        DbConn src = dbConnService.getById(job.getSrcConnId());
        if (src == null) {
            throw new RuntimeException("源数据源不存在: src=" + job.getSrcConnId());
        }
        String srcSchema = StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName());
        tableToCsv(src, srcSchema, job.getSrcTable(), suffixWhere(job), csvPath);
    }

    /** 通用表 → CSV(指定连接/库/表/where) */
    private void tableToCsv(DbConn src, String srcSchema, String table, String whereSql, String csvPath) throws Exception {
        if (src == null) {
            throw new RuntimeException("数据源不存在");
        }
        DatabaseMeta srcDb = toDatabaseMeta("bridge_src", src, srcSchema);
        Database db = new Database(srcDb);
        try {
            db.connect();
            java.sql.Connection conn = db.getConnection();
            String sql = "SELECT * FROM " + quoteTable(srcSchema, table, src) + StrUtil.nullToEmpty(whereSql);
            long rows = 0;
            try (java.sql.Statement st = conn.createStatement();
                 java.sql.ResultSet rs = st.executeQuery(sql);
                 BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvPath), StandardCharsets.UTF_8))) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> cols = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    cols.add(md.getColumnLabel(i));
                }
                w.write(joinCsvLine(cols));
                w.newLine();
                while (rs.next()) {
                    List<String> vals = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        Object v = rs.getObject(i);
                        vals.add(v == null ? "" : String.valueOf(v));
                    }
                    w.write(joinCsvLine(vals));
                    w.newLine();
                    rows++;
                }
            }
            log.info("桥接 tableToCsv {}.{} 行数={} -> {}", srcSchema, table, rows, csvPath);
        } finally {
            try {
                db.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

    /** CSV 单行编码(与 EtlFileBridge 内部实现一致的简单逗号/双引号规则) */
    private static String joinCsvLine(List<String> vals) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = vals.get(i) == null ? "" : vals.get(i);
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }

    /**
     * 确保清洗结果表存在: TRUNCATE 模式下不存在则按段1映射(fieldMapping)自动建表
     */
    private void ensureCleanTable(EtlJob job) throws Exception {
        if (job.getCleanConnId() == null || StrUtil.isBlank(job.getCleanTable())) {
            return;
        }
        if (!"TRUNCATE".equalsIgnoreCase(job.getCleanWriteMode())) {
            return;
        }
        DbConn clean = dbConnService.getById(job.getCleanConnId());
        if (clean == null) {
            return;
        }
        String cleanSchema = StrUtil.blankToDefault(job.getCleanSchema(), clean.getDatabaseName());
        DatabaseMeta cleanDb = toDatabaseMeta("clean_db", clean, cleanSchema);
        Database db = new Database(cleanDb);
        try {
            db.connect();
            java.sql.DatabaseMetaData md = db.getConnection().getMetaData();
            try (java.sql.ResultSet rs = md.getTables(null, cleanSchema, job.getCleanTable(), null)) {
                if (rs.next()) {
                    return;
                }
            }
            List<String> colDefs = new ArrayList<>();
            LinkedHashSet<String> cols = new LinkedHashSet<>();
            if (StrUtil.isNotBlank(job.getFieldMapping())) {
                try {
                    cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(job.getFieldMapping());
                    for (Object o : arr) {
                        cn.hutool.json.JSONObject g = (cn.hutool.json.JSONObject) o;
                        cn.hutool.json.JSONArray maps = g.getJSONArray("maps");
                        if (maps == null) continue;
                        for (Object mo : maps) {
                            String dstF = ((cn.hutool.json.JSONObject) mo).getStr("dstField", "");
                            if (StrUtil.isNotBlank(dstF)) {
                                cols.add(dstF);
                            }
                        }
                    }
                    for (String c : cols) {
                        colDefs.add(quoteIdent(c, clean) + " VARCHAR(255)");
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }
            if (colDefs.isEmpty()) {
                throw new RuntimeException("清洗结果表不存在且未配置段1映射, 请先手动创建清洗结果表: " + job.getCleanTable());
            }
            String sql = "CREATE TABLE IF NOT EXISTS " + quoteTable(cleanSchema, job.getCleanTable(), clean)
                    + " (" + String.join(", ", colDefs) + ")";
            db.execStatement(sql);
            log.info("清洗结果表不存在, 已按段1映射自动建表: {}", sql);
        } finally {
            try {
                db.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 确保目标表存在: TRUNCATE 模式下目标表不存在则自动建表(同数据源 MySQL 用 LIKE 建结构)
     */
    private void ensureDstTable(EtlJob job) throws Exception {
        if (job.getDstConnId() == null || StrUtil.isBlank(job.getDstTable())) {
            return;
        }
        if (!"TRUNCATE".equalsIgnoreCase(job.getDstWriteMode())) {
            return;
        }
        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (dst == null) {
            return;
        }
        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
        DatabaseMeta dstDb = toDatabaseMeta("dst_db", dst, dstSchema);
        Database db = new Database(dstDb);
        try {
            db.connect();
            java.sql.DatabaseMetaData md = db.getConnection().getMetaData();
            try (java.sql.ResultSet rs = md.getTables(null, dstSchema, job.getDstTable(), null)) {
                if (rs.next()) {
                    return; // 目标表已存在
                }
            }
            // 建表列: 优先段2映射 dstMapping(结果表→目标表), 其次段1/旧映射 fieldMapping
            List<String> colDefs = new ArrayList<>();
            LinkedHashSet<String> dstCols = new LinkedHashSet<>();
            String mappingJson = StrUtil.isNotBlank(job.getDstMapping()) ? job.getDstMapping() : job.getFieldMapping();
            if (StrUtil.isNotBlank(mappingJson)) {
                try {
                    cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(mappingJson);
                    boolean grouped = arr.size() > 0 && arr.get(0) instanceof cn.hutool.json.JSONObject
                            && ((cn.hutool.json.JSONObject) arr.get(0)).getJSONArray("maps") != null;
                    for (Object o : arr) {
                        cn.hutool.json.JSONObject g = (cn.hutool.json.JSONObject) o;
                        cn.hutool.json.JSONArray maps = grouped ? g.getJSONArray("maps") : null;
                        if (maps != null) {
                            for (Object mo : maps) {
                                String dstF = ((cn.hutool.json.JSONObject) mo).getStr("dstField", "");
                                if (StrUtil.isNotBlank(dstF)) {
                                    dstCols.add(dstF);
                                }
                            }
                        } else {
                            String dstF = g.getStr("dstField", "");
                            if (StrUtil.isNotBlank(dstF)) {
                                dstCols.add(dstF);
                            }
                        }
                    }
                    for (String c : dstCols) {
                        colDefs.add(quoteIdent(c, dst) + " VARCHAR(255)");
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }
            if (!colDefs.isEmpty()) {
                String sql = "CREATE TABLE IF NOT EXISTS " + quoteTable(dstSchema, job.getDstTable(), dst)
                        + " (" + String.join(", ", colDefs) + ")";
                db.execStatement(sql);
                log.info("目标表不存在, 已按字段映射自动建表: {}", sql);
                return;
            }
            // 同数据源 MySQL: CREATE TABLE ... LIKE 复制表结构
            if (job.getSrcConnId() != null && job.getSrcConnId().equals(job.getDstConnId())
                    && StrUtil.isNotBlank(job.getSrcTable()) && "MYSQL".equalsIgnoreCase(dst.getDbType())) {
                String srcSchema = StrUtil.blankToDefault(job.getSrcSchema(), dst.getDatabaseName());
                String sql = "CREATE TABLE IF NOT EXISTS " + quoteTable(dstSchema, job.getDstTable(), dst)
                        + " LIKE " + quoteTable(srcSchema, job.getSrcTable(), dst);
                db.execStatement(sql);
                log.info("目标表不存在, 已自动建表: {}", sql);
            } else {
                throw new RuntimeException("目标表不存在且暂不支持自动建表, 请先手动创建目标表: " + job.getDstTable()
                        + " (同数据源 MySQL 可自动建表)");
            }
        } finally {
            try {
                db.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 执行作业(JOB); 管线模式(pipeSeg1Xml 非空)时顺序执行段1(源→清洗结果表)与段2(结果表→目标表)
     */
    private EtlJobLog runJob(EtlJob job, EtlJobLog jobLog) {
        File kjb = null;
        try {
            if (StrUtil.isNotBlank(job.getPipeSeg1Xml())) {
                return runPipelineSegs(job, jobLog);
            }
            String xml = job.getKettleXml();
            if (StrUtil.isBlank(xml)) {
                xml = buildJobXml(job);
                job.setKettleXml(xml);
            }
            Path dir = Files.createTempDirectory("kettle_job_");
            kjb = Files.writeString(dir.resolve("job.kjb"), xml, StandardCharsets.UTF_8).toFile();

            JobMeta jm = new JobMeta(kjb.getAbsolutePath(), null);
            Job kjob = new Job(null, jm);
            if (job != null && job.getId() != null) {
                RUNNING_JOBS.put(job.getId(), kjob);
            }
            kjob.setLogLevel(org.pentaho.di.core.logging.LogLevel.BASIC);
            kjob.run();
            kjob.waitUntilFinished();

            Result result = kjob.getResult();
            long errors = result == null ? 1 : result.getNrErrors();
            String logText = collectLogText(kjob.getLogChannelId());
            if (logText.length() > 400_000) {
                logText = logText.substring(logText.length() - 400_000);
            }

            jobLog.setTotalRecords(parseJobWrittenRows(logText));
            jobLog.setLogText(logText);
            if (errors == 0) {
                jobLog.setRunStatus("SUCCESS");
            } else {
                jobLog.setRunStatus("FAILED");
                jobLog.setErrorMsg("作业执行出错, 错误数=" + errors + "\n" + tailError(logText));
            }
        } catch (Exception e) {
            log.error("Kettle 作业执行失败: {}", e.getMessage(), e);
            jobLog.setRunStatus("FAILED");
            jobLog.setErrorMsg("Kettle 作业执行异常: " + e.getMessage());
        } finally {
            if (job != null && job.getId() != null) {
                RUNNING_JOBS.remove(job.getId());
            }
            cleanupTemp(kjb);
            jobLog.setEndTime(new Date());
            jobLogMapper.updateById(jobLog);
        }
        return jobLog;
    }

    /** 管线顺序执行: 段1(源→清洗结果表) 成功后执行 段2(结果表→目标表); 目标非表时段2写桥接临时表, 完成后导出 */
    private EtlJobLog runPipelineSegs(EtlJob job, EtlJobLog jobLog) {
        File seg1 = null;
        File seg2 = null;
        try {
            ensureCleanTable(job);
            ensureDstTable(job);
            boolean dstBridge = EtlFileBridge.isBridgeDst(job);
            if (dstBridge) {
                ensureBridgeTmpTable(job);
            }
            Path dir = Files.createTempDirectory("kettle_pipe_");
            seg1 = Files.writeString(dir.resolve("seg1.ktr"), job.getPipeSeg1Xml(), StandardCharsets.UTF_8).toFile();
            seg2 = Files.writeString(dir.resolve("seg2.ktr"), job.getPipeSeg2Xml(), StandardCharsets.UTF_8).toFile();
            try {
                Files.writeString(java.nio.file.Paths.get("D:/project/etl-engine/seg_dump_seg1.xml"), job.getPipeSeg1Xml(), StandardCharsets.UTF_8);
                Files.writeString(java.nio.file.Paths.get("D:/project/etl-engine/seg_dump_seg2.xml"), job.getPipeSeg2Xml(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }

            StringBuilder sb = new StringBuilder();
            // 段1: 源 → 清洗结果表
            TransMeta tm1 = new TransMeta(seg1.getAbsolutePath());
            Trans t1 = new Trans(tm1);
            t1.prepareExecution(new String[0]);
            t1.startThreads();
            t1.waitUntilFinished();
            Result r1 = t1.getResult();
            long e1 = r1 == null ? 1 : r1.getNrErrors();
            sb.append(collectLogText(t1.getLogChannelId()));
            if (e1 != 0) {
                String logText = sb.length() > 400_000 ? sb.substring(sb.length() - 400_000) : sb.toString();
                jobLog.setLogText(logText);
                jobLog.setTotalRecords(0L);
                jobLog.setRunStatus("FAILED");
                jobLog.setErrorMsg("段1(源→清洗结果表)执行出错, 错误数=" + e1 + "\n" + tailError(logText));
                return jobLog;
            }
            // 段2: 结果表 → 目标表
            TransMeta tm2 = new TransMeta(seg2.getAbsolutePath());
            Trans t2 = new Trans(tm2);
            t2.prepareExecution(new String[0]);
            t2.startThreads();
            t2.waitUntilFinished();
            Result r2 = t2.getResult();
            long e2 = r2 == null ? 1 : r2.getNrErrors();
            sb.append(collectLogText(t2.getLogChannelId()));
            String logText = sb.length() > 400_000 ? sb.substring(sb.length() - 400_000) : sb.toString();
            jobLog.setLogText(logText);
            jobLog.setTotalRecords(collectWrittenRows(t2, r2));
            if (e2 == 0) {
                jobLog.setRunStatus("SUCCESS");
                if (dstBridge) {
                    // 目标非表: 桥接临时表 → Excel/API 导出
                    exportBridgeTmp(job, jobLog);
                }
            } else {
                jobLog.setRunStatus("FAILED");
                jobLog.setErrorMsg("段2(结果表→目标表)执行出错, 错误数=" + e2 + "\n" + tailError(logText));
            }
            return jobLog;
        } catch (Exception e) {
            log.error("Kettle 管线执行失败: {}", e.getMessage(), e);
            jobLog.setRunStatus("FAILED");
            jobLog.setErrorMsg("Kettle 管线执行异常: " + e.getMessage());
            return jobLog;
        } finally {
            dropBridgeTmpTable(job);
            cleanupTemp(seg1);
            cleanupTemp(seg2);
            jobLog.setEndTime(new Date());
            jobLogMapper.updateById(jobLog);
        }
    }

    /**
     * 多源子任务转换 XML(多个源表 UNION ALL 合并写入目标表)
     * srcList: [{srcTable, srcWhere}] 至少 2 个源; 否则回退单源 buildTransXml
     */
    public String buildTransXmlMulti(EtlJob job) throws Exception {
        String srcType = StrUtil.blankToDefault(job.getSrcType(), EtlFileBridge.TABLE).toUpperCase();
        if (!EtlFileBridge.TABLE.equals(srcType)) {
            throw new RuntimeException("多源合并(UNION ALL)仅支持数据库表源, 当前源类型=" + srcType);
        }
        List<cn.hutool.json.JSONObject> srcs = new ArrayList<>();
        if (StrUtil.isNotBlank(job.getSrcList())) {
            try {
                cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(job.getSrcList());
                for (Object o : arr) {
                    cn.hutool.json.JSONObject item = (cn.hutool.json.JSONObject) o;
                    if (StrUtil.isNotBlank(item.getStr("srcTable"))) {
                        srcs.add(item);
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (srcs.size() < 2) {
            return buildTransXml(job);
        }
        KettleDesignReq req = new KettleDesignReq();
        req.setKettleType("TRANS");
        req.setJobName(job.getJobName());
        List<KettleDesignReq.DesignNode> nodes = new ArrayList<>();
        int i = 0;
        for (cn.hutool.json.JSONObject s0 : srcs) {
            i++;
            KettleDesignReq.DesignNode n = new KettleDesignReq.DesignNode();
            n.setId("in" + i);
            n.setType("tableInput");
            n.setX(60);
            n.setY(60 + i * 80);
            java.util.Map<String, Object> p0 = new java.util.HashMap<>();
            p0.put("srcConnId", s0.getLong("srcConnId", job.getSrcConnId()));
            p0.put("srcSchema", StrUtil.blankToDefault(s0.getStr("srcSchema", ""), StrUtil.blankToDefault(job.getSrcSchema(), "")));
            p0.put("srcTable", s0.getStr("srcTable"));
            p0.put("srcWhere", s0.getStr("srcWhere", ""));
            n.setProps(p0);
            nodes.add(n);
        }
        KettleDesignReq.DesignNode out = new KettleDesignReq.DesignNode();
        out.setId("out");
        out.setType("tableOutput");
        out.setX(400);
        out.setY(60);
        java.util.Map<String, Object> op = new java.util.HashMap<>();
        op.put("dstConnId", job.getDstConnId());
        op.put("dstSchema", StrUtil.blankToDefault(job.getDstSchema(), ""));
        op.put("dstTable", job.getDstTable());
        op.put("writeMode", job.getDstWriteMode());
        op.put("batchSize", job.getBatchSize() == null ? 1024 : job.getBatchSize());
        out.setProps(op);
        nodes.add(out);
        req.setNodes(nodes);
        List<KettleDesignReq.DesignNode> ins = nodes.stream()
            .filter(n -> "tableInput".equals(n.getType())).collect(java.util.stream.Collectors.toList());
        return buildTransMultiXmlFromDesign(req, ins, out, job.getFieldMapping());
    }


    /**
     * 判断是否 JOIN 关联模式(多源表通过关联字段组合)
     */
    public boolean isJoinMode(EtlJob job) {
        if (StrUtil.isBlank(job.getSrcList())) {
            return false;
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(job.getSrcList());
            for (int i = 1; i < arr.size(); i++) {
                Object o = arr.get(i);
                if (o instanceof cn.hutool.json.JSONObject) {
                    String jt = ((cn.hutool.json.JSONObject) o).getStr("joinType", "");
                    if ("JOIN".equalsIgnoreCase(jt) || "LEFT_JOIN".equalsIgnoreCase(jt)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return false;
    }

    /**
     * 解析分组字段映射 → {表名: [{srcField, dstField}]}
     */
    private Map<String, List<Map<String, String>>> parseMappingGroup(String fieldMapping) {
        Map<String, List<Map<String, String>>> byTable = new LinkedHashMap<>();
        if (StrUtil.isBlank(fieldMapping)) {
            return byTable;
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(fieldMapping);
            for (Object o : arr) {
                cn.hutool.json.JSONObject g = (cn.hutool.json.JSONObject) o;
                String t = g.getStr("srcTable", "");
                cn.hutool.json.JSONArray maps = g.getJSONArray("maps");
                if (maps == null) {
                    maps = g.getJSONArray("mappings");
                }
                if (maps == null) continue;
                List<Map<String, String>> list = byTable.computeIfAbsent(t, k -> new ArrayList<>());
                for (Object mo : maps) {
                    cn.hutool.json.JSONObject m = (cn.hutool.json.JSONObject) mo;
                    String srcF = m.getStr("srcField", "");
                    String dstF = m.getStr("dstField", "");
                    if (StrUtil.isBlank(dstF)) continue;
                    Map<String, String> mm = new LinkedHashMap<>();
                    mm.put("srcField", srcF);
                    mm.put("dstField", dstF);
                    list.add(mm);
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return byTable;
    }

    /**
     * 生成 JOIN 关联模式转换 XML: 多表通过关联字段组合为一个查询写入目标表
     */
    public String buildTransXmlJoin(EtlJob job) throws Exception {
        String srcType = StrUtil.blankToDefault(job.getSrcType(), EtlFileBridge.TABLE).toUpperCase();
        if (!EtlFileBridge.TABLE.equals(srcType)) {
            throw new RuntimeException("JOIN 关联组合仅支持数据库表源, 当前源类型=" + srcType);
        }
        List<cn.hutool.json.JSONObject> srcs = new ArrayList<>();
        if (StrUtil.isNotBlank(job.getSrcList())) {
            try {
                cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(job.getSrcList());
                for (Object o : arr) {
                    cn.hutool.json.JSONObject item = (cn.hutool.json.JSONObject) o;
                    if (StrUtil.isNotBlank(item.getStr("srcTable"))) {
                        srcs.add(item);
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (srcs.size() < 2) {
            return buildTransXml(job);
        }
        // 各源行数据源(缺省取任务级)
        Long mainConnId = srcs.get(0).getLong("srcConnId", job.getSrcConnId());
        DbConn mainConn = dbConnService.getById(mainConnId);
        if (mainConn == null) {
            mainConn = dbConnService.getById(job.getSrcConnId());
        }
        if (mainConn == null) {
            throw new RuntimeException("数据源不存在: src=" + mainConnId);
        }
        String mainSchema0 = StrUtil.blankToDefault(srcs.get(0).getStr("srcSchema", ""), mainConn.getDatabaseName());
        // 是否全部同数据源/库 → 单 SQL JOIN
        boolean sameDb = true;
        for (cn.hutool.json.JSONObject item : srcs) {
            Long cid = item.getLong("srcConnId", job.getSrcConnId());
            if (cid == null) cid = job.getSrcConnId();
            DbConn c = cid != null ? dbConnService.getById(cid) : null;
            String sch = StrUtil.blankToDefault(item.getStr("srcSchema", ""), c != null ? c.getDatabaseName() : "");
            if (cid == null || !cid.equals(mainConnId) || !mainSchema0.equalsIgnoreCase(sch)) {
                sameDb = false;
                break;
            }
        }
        if (sameDb) {
            return buildTransXmlJoinSameDb(job, srcs, mainConn, mainSchema0);
        }
        return buildTransXmlJoinCrossDb(job, srcs);
    }

    // ==================== 统一数据流管线(源→[清洗规则]→[清洗结果表]→[质量规则]→目标) ====================

    /**
     * 生成统一数据流管线转换 XML。
     * 数据流: 源(单/多) → [清洗规则] → [清洗结果表] → [质量规则] → 目标表
     * - 段1映射 fieldMapping(源字段→结果表列/目标列, 分组格式); 写入模式 cleanWriteMode/dstWriteMode(INSERT/REPLACE/TRUNCATE)
     * - 配置了清洗结果表(cleanConnId+cleanTable)时: 源→结果表 落库后, 再 结果表→目标表(段2映射 dstMapping)
     * - 清洗规则(全局)作用于源→结果表段; 质量规则(全局)作用于结果表→目标段
     * - 规则真实步骤: 去重复→UniqueRows, 去空值/非空校验→FilterRows(字段非空); 其余规则类型仅记录日志不生成步骤
     */
    public String buildTransXmlPipeline(EtlJob job) throws Exception {
        String srcType = StrUtil.blankToDefault(job.getSrcType(), EtlFileBridge.TABLE).toUpperCase();
        String dstType = StrUtil.blankToDefault(job.getDstType(), EtlFileBridge.TABLE).toUpperCase();
        if (EtlFileBridge.TABLE.equals(srcType) && EtlFileBridge.TABLE.equals(dstType)) {
            // 全表源/目标: 无额外限制
        } else if (!EtlFileBridge.TABLE.equals(srcType) && !EtlFileBridge.TABLE.equals(dstType)) {
            throw new RuntimeException("管线(源→清洗→质量→目标)源与目标不能同时为 Excel/API, 中间需以表承接(如清洗结果表)");
        }
        // 目标非表: 要求清洗结果表作为中间承接(临时表建在清洗结果库)
        boolean dstBridge = !EtlFileBridge.TABLE.equals(dstType);
        if (dstBridge && (job.getCleanConnId() == null || StrUtil.isBlank(job.getCleanTable()))) {
            throw new RuntimeException("目标为Excel/API的多段管线需配置清洗结果表作为中间承接(临时表建在清洗结果库)");
        }
        List<cn.hutool.json.JSONObject> srcs = parseSrcs(job);
        if (srcs.isEmpty() && !EtlFileBridge.TABLE.equals(srcType)) {
            // 非表源: 虚拟源条目(单源 CsvInput)
            cn.hutool.json.JSONObject one = new cn.hutool.json.JSONObject();
            one.set("srcTable", "__bridge__");
            srcs.add(one);
        }
        if (srcs.isEmpty()) {
            throw new RuntimeException("未配置源表");
        }
        Map<String, List<Map<String, String>>> byTable = parseMappingGroup(job.getFieldMapping());
        List<cn.hutool.json.JSONObject> cleanRules = parseRuleList(job.getCleanRules());
        List<cn.hutool.json.JSONObject> qualityRules = parseRuleList(job.getQualityRules());

        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (dst == null && !dstBridge) {
            throw new RuntimeException("目标数据源不存在: dst=" + job.getDstConnId());
        }
        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst != null ? dst.getDatabaseName() : "");
        boolean hasClean = job.getCleanConnId() != null && StrUtil.isNotBlank(job.getCleanTable());
        DbConn cleanConn = hasClean ? dbConnService.getById(job.getCleanConnId()) : null;
        if (hasClean && cleanConn == null) {
            throw new RuntimeException("清洗结果数据源不存在: clean=" + job.getCleanConnId());
        }
        String cleanSchema = hasClean ? StrUtil.blankToDefault(job.getCleanSchema(), cleanConn.getDatabaseName()) : null;
        List<Map<String, String>> dstMaps = parseFlatMapping(job.getDstMapping());
        // 目标非表: 输出到清洗结果库的桥接临时表, Kettle 执行后由后端导出
        if (dstBridge) {
            dst = cleanConn;
            dstSchema = cleanSchema;
        }

        TransMeta tm = new TransMeta();
        tm.setName("etl_" + StrUtil.nullToEmpty(job.getJobName()).replaceAll("\\s+", "_") + "_" + System.nanoTime());
        DatabaseMeta dstDb = toDatabaseMeta("dst_db", dst, dstSchema);
        tm.addDatabase(dstDb);

        // ---- 1. 源步骤链(单源 / UNION 合并 / JOIN 组合) ----
        LinkedHashSet<String> midCols = new LinkedHashSet<>();
        StepMeta prev;
        if (srcs.size() == 1) {
            prev = buildSingleSourceStep(tm, job, srcs.get(0), byTable, midCols,
                    rulesForTable(cleanRules, srcs.get(0).getStr("srcTable")));
        } else {
            boolean anyJoin = srcs.stream().anyMatch(it -> {
                String jt = it.getStr("joinType", "");
                return "JOIN".equalsIgnoreCase(jt) || "LEFT_JOIN".equalsIgnoreCase(jt);
            });
            prev = anyJoin ? buildJoinSourceChain(tm, job, srcs, byTable, midCols)
                           : buildUnionSourceChain(tm, job, srcs, byTable, midCols, cleanRules);
        }

        // ---- 2. 清洗规则步骤(作用于合并流; 表源规则已 SQL 注入时跳过; 非表源无 SQL 注入, 必须走步骤链) ----
        boolean rulesInjected = EtlFileBridge.TABLE.equals(srcType) && !cleanRules.isEmpty();
        if (!rulesInjected) {
            prev = appendFilterSteps(tm, prev, cleanRules, midCols, "清洗");
        }

        int y = 80;
        if (hasClean) {
            // ---- 3a. 写入清洗结果表 ----
            DatabaseMeta cleanDb = toDatabaseMeta("clean_db", cleanConn, cleanSchema);
            tm.addDatabase(cleanDb);
            TableOutputMeta tom = new TableOutputMeta();
            tom.setDatabaseMeta(cleanDb);
            tom.setTablename(job.getCleanTable());
            tom.setCommitSize(job.getBatchSize() == null ? 1024 : job.getBatchSize());
            tom.setTruncateTable("TRUNCATE".equalsIgnoreCase(job.getCleanWriteMode()) || "REPLACE".equalsIgnoreCase(job.getCleanWriteMode()));
            StepMeta out = new StepMeta("TableOutput", "清洗结果输出", tom);
            out.setLocation(260, y);
            out.drawStep();
            tm.addStep(out);
            tm.addTransHop(new TransHopMeta(prev, out));
            // ---- 4. 结果表读回 → 质量规则 → 段2映射 → 目标表 ----
            TableInputMeta tim2 = new TableInputMeta();
            tim2.setDatabaseMeta(cleanDb);
            tim2.setSQL("SELECT * FROM " + quoteTable(cleanSchema, job.getCleanTable(), cleanConn));
            tim2.setRowLimit("0");
            StepMeta in2 = new StepMeta("TableInput", "结果表输入", tim2);
            in2.setLocation(480, y);
            in2.drawStep();
            tm.addStep(in2);
            tm.addTransHop(new TransHopMeta(out, in2));
            StepMeta prev2 = in2;
            prev2 = appendFilterSteps(tm, prev2, qualityRules, midCols, "质量");
            if (!dstMaps.isEmpty()) {
                prev2 = appendSelectValues(tm, prev2, dstMaps);
            }
            TableOutputMeta tom2 = new TableOutputMeta();
            tom2.setDatabaseMeta(dstDb);
            tom2.setTablename(dstBridge ? bridgeTmpTable(job) : job.getDstTable());
            tom2.setCommitSize(job.getBatchSize() == null ? 1024 : job.getBatchSize());
            tom2.setTruncateTable("TRUNCATE".equalsIgnoreCase(job.getDstWriteMode()));
            StepMeta out2 = new StepMeta("TableOutput", "目标表输出", tom2);
            out2.setLocation(700, y);
            out2.drawStep();
            tm.addStep(out2);
            tm.addTransHop(new TransHopMeta(prev2, out2));
        } else {
            // ---- 3b. 直接写入目标表(无中间结果表) ----
            TableOutputMeta tom = new TableOutputMeta();
            tom.setDatabaseMeta(dstDb);
            tom.setTablename(dstBridge ? bridgeTmpTable(job) : job.getDstTable());
            tom.setCommitSize(job.getBatchSize() == null ? 1024 : job.getBatchSize());
            tom.setTruncateTable("TRUNCATE".equalsIgnoreCase(job.getDstWriteMode()) || "REPLACE".equalsIgnoreCase(job.getDstWriteMode()));
            StepMeta out = new StepMeta("TableOutput", "目标表输出", tom);
            out.setLocation(260, y);
            out.drawStep();
            tm.addStep(out);
            tm.addTransHop(new TransHopMeta(prev, out));
        }
        return XMLHandler.getXMLHeader() + tm.getXML();
    }

    /**
     * 管线定义: 生成 段1(ktr: 源→[清洗]→清洗结果表) + 段2(ktr: 结果表→[质量]→目标表)。
     * 段1/段2 XML 写入 job.pipeSeg1Xml/pipeSeg2Xml; 返回段1 XML 作为 kettleXml 占位。
     * 执行时由 runJob 检测 pipeSeg1Xml 非空 → 顺序执行两段(保证先写结果表后读回)。
     */
    public String buildPipelineKjb(EtlJob job) throws Exception {
        String seg1 = buildPipelineSeg1Xml(job);
        String seg2 = buildPipelineSeg2Xml(job);
        job.setPipeSeg1Xml(seg1);
        job.setPipeSeg2Xml(seg2);
        return seg1;
    }

    /** 管线段1: 源(单/多) → [清洗规则] → 清洗结果表 */
    private String buildPipelineSeg1Xml(EtlJob job) throws Exception {
        List<cn.hutool.json.JSONObject> srcs = parseSrcs(job);
        if (srcs.isEmpty() && !EtlFileBridge.TABLE.equalsIgnoreCase(StrUtil.blankToDefault(job.getSrcType(), EtlFileBridge.TABLE))) {
            // 非表源: 虚拟源条目(单源 CsvInput)
            cn.hutool.json.JSONObject one = new cn.hutool.json.JSONObject();
            one.set("srcTable", "__bridge__");
            srcs.add(one);
        }
        if (srcs.isEmpty()) {
            throw new RuntimeException("未配置源表");
        }
        Map<String, List<Map<String, String>>> byTable = parseMappingGroup(job.getFieldMapping());
        List<cn.hutool.json.JSONObject> cleanRules = parseRuleList(job.getCleanRules());
        DbConn cleanConn = dbConnService.getById(job.getCleanConnId());
        if (cleanConn == null) {
            throw new RuntimeException("清洗结果数据源不存在: clean=" + job.getCleanConnId());
        }
        String cleanSchema = StrUtil.blankToDefault(job.getCleanSchema(), cleanConn.getDatabaseName());

        TransMeta tm = new TransMeta();
        tm.setName("etl_seg1_" + StrUtil.nullToEmpty(job.getJobName()).replaceAll("\\s+", "_") + "_" + System.nanoTime());
        DatabaseMeta cleanDb = toDatabaseMeta("clean_db", cleanConn, cleanSchema);
        tm.addDatabase(cleanDb);

        LinkedHashSet<String> midCols = new LinkedHashSet<>();
        StepMeta prev;
        boolean rulesInjected = false;
        if (srcs.size() == 1) {
            prev = buildSingleSourceStep(tm, job, srcs.get(0), byTable, midCols, cleanRules);
            if (EtlFileBridge.TABLE.equalsIgnoreCase(job.getSrcType())) {
                // 表源: 规则经 SQL 注入时跳过步骤链, 避免双重过滤
                rulesInjected = !cleanRules.isEmpty();
            } else {
                // 非表源(EXCEL/API): 清洗规则已在 CSV 层由 CsvRuleProcessor 处理, 步骤链不再重复
                rulesInjected = true;
            }
        } else {
            boolean anyJoin = srcs.stream().anyMatch(it -> {
                String jt = it.getStr("joinType", "");
                return "JOIN".equalsIgnoreCase(jt) || "LEFT_JOIN".equalsIgnoreCase(jt);
            });
            prev = anyJoin ? buildJoinSourceChain(tm, job, srcs, byTable, midCols)
                           : buildUnionSourceChain(tm, job, srcs, byTable, midCols, cleanRules);
            rulesInjected = anyJoin ? false : !cleanRules.isEmpty();
        }
        // 规则已 SQL 注入时跳过步骤链, 避免双重过滤
        if (!rulesInjected) {
            prev = appendFilterSteps(tm, prev, cleanRules, midCols, "清洗");
        }

        TableOutputMeta tom = new TableOutputMeta();
        tom.setDatabaseMeta(cleanDb);
        tom.setTablename(job.getCleanTable());
        tom.setCommitSize(job.getBatchSize() == null ? 1024 : job.getBatchSize());
        tom.setTruncateTable("TRUNCATE".equalsIgnoreCase(job.getCleanWriteMode()) || "REPLACE".equalsIgnoreCase(job.getCleanWriteMode()));
        StepMeta out = new StepMeta("TableOutput", "清洗结果输出", tom);
        out.setLocation(260, 80);
        out.drawStep();
        tm.addStep(out);
        tm.addTransHop(new TransHopMeta(prev, out));
        return XMLHandler.getXMLHeader() + tm.getXML();
    }

    /** 管线段2: 清洗结果表读回 → [质量规则] → 段2映射 → 目标表 */
    private String buildPipelineSeg2Xml(EtlJob job) throws Exception {
        DbConn cleanConn = dbConnService.getById(job.getCleanConnId());
        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (cleanConn == null) {
            throw new RuntimeException("清洗结果数据源不存在: clean=" + job.getCleanConnId());
        }
        boolean dstBridge = EtlFileBridge.isBridgeDst(job);
        if (dst == null && !dstBridge) {
            throw new RuntimeException("目标数据源不存在: dst=" + job.getDstConnId());
        }
        String cleanSchema = StrUtil.blankToDefault(job.getCleanSchema(), cleanConn.getDatabaseName());
        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst != null ? dst.getDatabaseName() : cleanSchema);
        // 目标非表: 输出到清洗结果库的桥接临时表, 执行后由后端导出
        if (dstBridge) {
            dst = cleanConn;
            dstSchema = cleanSchema;
        }
        List<cn.hutool.json.JSONObject> qualityRules = parseRuleList(job.getQualityRules());
        List<Map<String, String>> dstMaps = parseFlatMapping(job.getDstMapping());

        TransMeta tm = new TransMeta();
        tm.setName("etl_seg2_" + StrUtil.nullToEmpty(job.getJobName()).replaceAll("\\s+", "_") + "_" + System.nanoTime());
        DatabaseMeta cleanDb = toDatabaseMeta("clean_db", cleanConn, cleanSchema);
        DatabaseMeta dstDb = toDatabaseMeta("dst_db", dst, dstSchema);
        tm.addDatabase(cleanDb);
        tm.addDatabase(dstDb);

        TableInputMeta tim = new TableInputMeta();
        tim.setDatabaseMeta(cleanDb);
        String seg2Sql = "SELECT * FROM " + quoteTable(cleanSchema, job.getCleanTable(), cleanConn);
        List<String> seg2Cols = new ArrayList<>();
        for (Map<String, String> m : dstMaps) {
            String sf = m.get("srcField");
            if (StrUtil.isNotBlank(sf) && !seg2Cols.contains(sf)) {
                seg2Cols.add(sf);
            }
        }
        if (!qualityRules.isEmpty()) {
            // 质量规则走步骤链(FilterRows/UniqueRows), 不注入 SQL:
            // Kettle TableInput 对注入 WHERE/IN 子句存在解析问题(查询返回 0 行), 步骤链更可靠
        }
        tim.setSQL(seg2Sql);
        tim.setRowLimit("0");
        StepMeta in = new StepMeta("TableInput", "结果表输入", tim);
        in.setLocation(60, 80);
        in.drawStep();
        tm.addStep(in);
        StepMeta prev = in;

        prev = appendFilterSteps(tm, prev, qualityRules, new LinkedHashSet<>(seg2Cols), "质量");
        if (!dstMaps.isEmpty()) {
            prev = appendSelectValues(tm, prev, dstMaps);
        }
        TableOutputMeta tom = new TableOutputMeta();
        tom.setDatabaseMeta(dstDb);
        tom.setTablename(dstBridge ? bridgeTmpTable(job) : job.getDstTable());
        tom.setCommitSize(job.getBatchSize() == null ? 1024 : job.getBatchSize());
        tom.setTruncateTable("TRUNCATE".equalsIgnoreCase(job.getDstWriteMode()) || "REPLACE".equalsIgnoreCase(job.getDstWriteMode()));
        StepMeta out = new StepMeta("TableOutput", "目标表输出", tom);
        out.setLocation(480, 80);
        out.drawStep();
        tm.addStep(out);
        tm.addTransHop(new TransHopMeta(prev, out));
        return XMLHandler.getXMLHeader() + tm.getXML();
    }

    /** 解析源表列表(srcList 优先, 空则回退单表) */
    private List<cn.hutool.json.JSONObject> parseSrcs(EtlJob job) {
        List<cn.hutool.json.JSONObject> srcs = new ArrayList<>();
        if (StrUtil.isNotBlank(job.getSrcList())) {
            try {
                cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(job.getSrcList());
                for (Object o : arr) {
                    cn.hutool.json.JSONObject item = (cn.hutool.json.JSONObject) o;
                    if (StrUtil.isNotBlank(item.getStr("srcTable"))) {
                        srcs.add(item);
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (srcs.isEmpty() && StrUtil.isNotBlank(job.getSrcTable())) {
            cn.hutool.json.JSONObject one = new cn.hutool.json.JSONObject();
            one.set("srcTable", job.getSrcTable());
            one.set("srcWhere", job.getSrcWhere());
            one.set("srcConnId", job.getSrcConnId());
            one.set("srcSchema", job.getSrcSchema());
            srcs.add(one);
        }
        return srcs;
    }

    /** 按源表取清洗规则: 分组格式按 srcTable 匹配; 平铺格式全量返回 */
    private List<cn.hutool.json.JSONObject> rulesForTable(List<cn.hutool.json.JSONObject> rules, String srcTable) {
        List<cn.hutool.json.JSONObject> out = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            return out;
        }
        boolean grouped = rules.stream().anyMatch(r -> r.getJSONArray("rules") != null);
        if (!grouped) {
            return rules;
        }
        for (cn.hutool.json.JSONObject r : rules) {
            String t = r.getStr("srcTable", "");
            if (StrUtil.isBlank(t) || t.equals(srcTable)) {
                cn.hutool.json.JSONArray inner = r.getJSONArray("rules");
                if (inner != null) {
                    for (Object io : inner) {
                        out.add((cn.hutool.json.JSONObject) io);
                    }
                }
            }
        }
        return out;
    }

    /** 规则配置字段提取: field/column 或裸字段名 */
    private String ruleField(String config) {
        if (StrUtil.isBlank(config)) {
            return "";
        }
        String c = config.trim();
        try {
            if (c.startsWith("{")) {
                cn.hutool.json.JSONObject o = cn.hutool.json.JSONUtil.parseObj(c);
                String f = o.getStr("field", "");
                if (StrUtil.isNotBlank(f)) return f;
                String col = o.getStr("column", "");
                if (StrUtil.isNotBlank(col)) return col;
            }
        } catch (Exception ignored) {
        }
        return c;
    }

    private String ruleCfg(String config, String key, String dft) {
        if (StrUtil.isBlank(config)) {
            return dft;
        }
        String c = config.trim();
        try {
            if (c.startsWith("{")) {
                cn.hutool.json.JSONObject o = cn.hutool.json.JSONUtil.parseObj(c);
                String v = o.getStr(key, "");
                if (StrUtil.isNotBlank(v)) return v;
            }
        } catch (Exception ignored) {
        }
        return dft;
    }

    /**
     * 把规则注入"表输入 SQL"(与 DataX buildRuleSql 语义同构):
     * - 过滤类(去空值/非空/范围/枚举/格式REGEXP/关联完整性/自定义SQL) → WHERE 叠加
     * - 去重/唯一 → 指定字段走 ROW_NUMBER 窗口(MySQL8+/PG/达梦), 否则 DISTINCT
     * - 列表达式(格式规范化/单位换算/默认值填充) → 改写 SELECT 列
     * colNames 为空(无字段映射)时列表达式规则跳过(仅过滤生效)。
     */
    private String injectRulesToSql(String baseSql, String srcWhere, DbConn conn,
                                    List<cn.hutool.json.JSONObject> rules,
                                    List<String> colNames, LinkedHashSet<String> midCols) {
        String q = "MYSQL".equalsIgnoreCase(conn.getDbType()) ? "`" : "\"";
        java.util.Map<String, String> colExpr = new java.util.LinkedHashMap<>();
        java.util.List<String> whereParts = new java.util.ArrayList<>();
        boolean distinct = false;
        String uniqueField = null;
        boolean mysql = "MYSQL".equalsIgnoreCase(conn.getDbType());
        boolean winFn = mysql; // 窗口函数: MySQL8+/PG/DM/Oracle; 老库回落 DISTINCT
        for (cn.hutool.json.JSONObject r : rules) {
            if (r == null) continue;
            String type = r.getStr("ruleType", "");
            String config = r.getStr("ruleConfig", "");
            String field = ruleField(config);
            if ("去空值".equals(type) || "NOT_NULL".equalsIgnoreCase(type)
                    || "非空校验".equals(type) || "非空".equals(type)) {
                if (StrUtil.isNotBlank(field)) {
                    whereParts.add(q + field + q + " IS NOT NULL");
                }
            } else if ("去重复".equals(type) || "去重".equals(type) || "DEDUP".equalsIgnoreCase(type)
                    || "DISTINCT".equalsIgnoreCase(type) || "唯一校验".equals(type) || "UNIQUE".equalsIgnoreCase(type)) {
                if (StrUtil.isNotBlank(field) && winFn) {
                    uniqueField = field;
                } else {
                    distinct = true;
                }
            } else if ("范围校验".equals(type) || "RANGE".equalsIgnoreCase(type)) {
                String min = ruleCfg(config, "min", null);
                String max = ruleCfg(config, "max", null);
                if (StrUtil.isNotBlank(field)) {
                    if (StrUtil.isNotBlank(min) && StrUtil.isNotBlank(max)) {
                        whereParts.add(q + field + q + " >= " + min + " AND " + q + field + q + " <= " + max);
                    } else if (StrUtil.isNotBlank(min)) {
                        whereParts.add(q + field + q + " >= " + min);
                    } else if (StrUtil.isNotBlank(max)) {
                        whereParts.add(q + field + q + " <= " + max);
                    }
                }
            } else if ("枚举校验".equals(type) || "ENUM".equalsIgnoreCase(type)) {
                String list = ruleCfg(config, "list", "");
                if (StrUtil.isBlank(list)) {
                    list = ruleCfg(config, "values", "");
                }
                if (StrUtil.isNotBlank(field) && StrUtil.isNotBlank(list)) {
                    String trimmed = list.trim();
                    java.util.List<String> vlist = new java.util.ArrayList<>();
                    if (trimmed.startsWith("[")) {
                        // JSON 数组: ["ACTIVE","INACTIVE"] → 解析为元素列表
                        try {
                            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(trimmed);
                            for (Object o : arr) {
                                if (o != null) vlist.add(String.valueOf(o));
                            }
                        } catch (Exception ignored) {
                            vlist.clear();
                        }
                    }
                    if (vlist.isEmpty()) {
                        for (String v : list.split(",")) {
                            String t = v.trim();
                            if (!t.isEmpty()) vlist.add(t);
                        }
                    }
                    String[] vs = vlist.toArray(new String[0]);
                    java.util.List<String> lit = new java.util.ArrayList<>();
                    for (String v : vs) {
                        String t = v.trim();
                        if (t.isEmpty()) continue;
                        boolean num = t.matches("-?\\d+(\\.\\d+)?");
                        lit.add(num ? t : "'" + t.replace("'", "''") + "'");
                    }
                    if (!lit.isEmpty()) {
                        whereParts.add(q + field + q + " IN (" + String.join(", ", lit) + ")");
                    }
                }
            } else if ("格式校验".equals(type) || "REGEXP".equalsIgnoreCase(type) || "FORMAT".equalsIgnoreCase(type)) {
                String pattern = ruleCfg(config, "pattern", "");
                if (StrUtil.isNotBlank(field) && StrUtil.isNotBlank(pattern)) {
                    if (mysql) {
                        whereParts.add(q + field + q + " REGEXP '" + pattern.replace("'", "''") + "'");
                    } else {
                        // 非 MySQL: 用 LIKE/INSTR 兜底(仅简单模式), 复杂正则提示走 MySQL
                        whereParts.add(q + field + q + " IS NOT NULL");
                    }
                }
            } else if ("关联完整性".equals(type) || "FK".equalsIgnoreCase(type)) {
                if (StrUtil.isNotBlank(field)) {
                    whereParts.add(q + field + q + " IS NOT NULL");
                }
            } else if ("SQL 清洗".equals(type) || "SQL_CLEAN".equalsIgnoreCase(type)
                    || "自定义 SQL".equals(type) || "CUSTOM_SQL".equalsIgnoreCase(type)) {
                String sql = ruleCfg(config, "sql", "");
                if (StrUtil.isNotBlank(sql)) {
                    whereParts.add("(" + sql + ")");
                }
            } else if ("格式规范化".equals(type) || "NORMALIZE".equalsIgnoreCase(type)
                    || "单位换算".equals(type) || "UNIT_CONVERT".equalsIgnoreCase(type)
                    || "默认值填充".equals(type) || "DEFAULT_VALUE".equalsIgnoreCase(type)) {
                if (StrUtil.isNotBlank(field)) {
                    String action = ruleCfg(config, "action", "").toLowerCase();
                    String expr = null;
                    if ("格式规范化".equals(type) || "NORMALIZE".equalsIgnoreCase(type)) {
                        if ("lower".equals(action)) {
                            expr = "LOWER(" + q + field + q + ")";
                        } else if ("trim".equals(action)) {
                            expr = "TRIM(" + q + field + q + ")";
                        } else {
                            expr = "UPPER(" + q + field + q + ")";
                        }
                    } else if ("单位换算".equals(type) || "UNIT_CONVERT".equalsIgnoreCase(type)) {
                        String factor = ruleCfg(config, "factor", "1");
                        try {
                            double f = Double.parseDouble(factor);
                            expr = "(" + q + field + q + " * " + f + ")";
                        } catch (Exception ignored) {
                        }
                    } else if ("默认值填充".equals(type) || "DEFAULT_VALUE".equalsIgnoreCase(type)) {
                        String dv = ruleCfg(config, "defaultValue", ruleCfg(config, "value", ""));
                        if (StrUtil.isNotBlank(dv)) {
                            boolean num = dv.matches("-?\\d+(\\.\\d+)?");
                            expr = "COALESCE(" + q + field + q + ", " + (num ? dv : "'" + dv.replace("'", "''") + "'") + ")";
                        }
                    }
                    if (expr != null && colNames != null && colNames.contains(field)) {
                        colExpr.put(field, expr + " AS " + q + field + q);
                    }
                }
            }
        }
        // 组装
        StringBuilder out = new StringBuilder(baseSql);
        java.util.List<String> whereAll = new java.util.ArrayList<>();
        if (StrUtil.isNotBlank(srcWhere)) {
            whereAll.add("(" + srcWhere + ")");
        }
        whereAll.addAll(whereParts);
        if (!whereAll.isEmpty()) {
            out.append(" WHERE ").append(String.join(" AND ", whereAll));
        }
        String sql = out.toString();
        if (uniqueField != null && colNames != null && !colNames.isEmpty()) {
            // 窗口去重包装: SELECT 显式列(排除rn) FROM (SELECT cols, rn FROM sql) tq WHERE rn=1
            java.util.List<String> newCols = new java.util.ArrayList<>();
            for (String cn : colNames) {
                String e = colExpr.get(cn);
                newCols.add(e != null ? e : q + cn + q);
            }
            String innerCols = String.join(", ", newCols);
            java.util.List<String> outer = new java.util.ArrayList<>();
            for (String cn : colNames) {
                outer.add("tq." + q + cn + q);
            }
            sql = "SELECT " + String.join(", ", outer) + " FROM (SELECT " + innerCols + ", ROW_NUMBER() OVER (PARTITION BY "
                    + q + uniqueField + q + " ORDER BY (SELECT NULL)) AS " + q + "rn" + q + " FROM "
                    + sql.substring(sql.indexOf(" FROM ") + 6) + ") tq WHERE tq." + q + "rn" + q + " = 1";
        } else if (uniqueField != null) {
            // 无列清单(无法排除 rn): 回落整行 DISTINCT
            sql = "SELECT DISTINCT *" + sql.substring(sql.indexOf(" FROM "));
        } else if (distinct) {
            sql = "SELECT DISTINCT *" + sql.substring(sql.indexOf(" FROM "));
        } else if (!colExpr.isEmpty() && colNames != null) {
            java.util.List<String> newCols = new java.util.ArrayList<>();
            for (String cn : colNames) {
                String e = colExpr.get(cn);
                newCols.add(e != null ? e : q + cn + q);
            }
            sql = "SELECT " + String.join(", ", newCols) + sql.substring(sql.indexOf(" FROM "));
        }
        return sql;
    }

    /** 解析规则列表: 兼容全局格式 [{ruleName,ruleType,ruleConfig}] 与分组格式 [{srcTable,rules:[]}] */
    private List<cn.hutool.json.JSONObject> parseRuleList(String rulesJson) {
        List<cn.hutool.json.JSONObject> out = new ArrayList<>();
        if (StrUtil.isBlank(rulesJson)) {
            return out;
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(rulesJson);
            for (Object o : arr) {
                cn.hutool.json.JSONObject item = (cn.hutool.json.JSONObject) o;
                cn.hutool.json.JSONArray inner = item.getJSONArray("rules");
                if (inner != null) {
                    for (Object io : inner) {
                        cn.hutool.json.JSONObject r = (cn.hutool.json.JSONObject) io;
                        if (StrUtil.isNotBlank(r.getStr("ruleName", "")) || StrUtil.isNotBlank(r.getStr("ruleType", ""))) {
                            out.add(r);
                        }
                    }
                } else if (StrUtil.isNotBlank(item.getStr("ruleName", "")) || StrUtil.isNotBlank(item.getStr("ruleType", ""))) {
                    out.add(item);
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return out;
    }

    /** 解析平铺映射 [{srcField,dstField}] */
    private List<Map<String, String>> parseFlatMapping(String mappingJson) {
        List<Map<String, String>> out = new ArrayList<>();
        if (StrUtil.isBlank(mappingJson)) {
            return out;
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(mappingJson);
            for (Object o : arr) {
                cn.hutool.json.JSONObject m = (cn.hutool.json.JSONObject) o;
                String srcF = m.getStr("srcField", "");
                String dstF = m.getStr("dstField", "");
                if (StrUtil.isBlank(srcF) && StrUtil.isBlank(dstF)) {
                    continue;
                }
                Map<String, String> mm = new LinkedHashMap<>();
                mm.put("srcField", srcF);
                mm.put("dstField", dstF);
                out.add(mm);
            }
        } catch (Exception ignored) {
            // ignore
        }
        return out;
    }

    /** 单源 TableInput(SELECT 映射列 AS 中间列; 无映射 SELECT *) */
    private StepMeta buildSingleSourceStep(TransMeta tm, EtlJob job, cn.hutool.json.JSONObject s0,
                                           Map<String, List<Map<String, String>>> byTable, LinkedHashSet<String> midCols,
                                           List<cn.hutool.json.JSONObject> tableRules) throws Exception {
        String srcType = StrUtil.blankToDefault(job.getSrcType(), EtlFileBridge.TABLE).toUpperCase();
        if (!EtlFileBridge.TABLE.equals(srcType)) {
            // 非表源(EXCEL/API): 桥接 CSV → 清洗规则先经 CsvRuleProcessor(CSV 层, 与 DataX 引擎一致) → CsvInput
            EtlFileBridge.BridgeResult br = EtlFileBridge.srcToCsv(job);
            List<cn.hutool.json.JSONObject> csvCleanRules = parseRuleList(job.getCleanRules());
            if (!csvCleanRules.isEmpty()) {
                try {
                    String cleaned = pan.dromara.etl.util.CsvRuleProcessor.process(job, br.csvPath, br.columns);
                    br.csvPath = cleaned;
                    java.util.List<String> hdr = pan.dromara.etl.util.CsvRuleProcessor.readHeader(cleaned);
                    if (!hdr.isEmpty()) {
                        br.columns = hdr;
                    }
                } catch (Exception ex) {
                    throw new RuntimeException("CSV 清洗规则执行失败: " + ex.getMessage(), ex);
                }
            }
            for (String c : br.columns) {
                midCols.add(c);
            }
            CsvInputMeta cim = new CsvInputMeta();
            cim.setFilename(br.csvPath);
            cim.setDelimiter(",");
            cim.setEnclosure("\"");
            cim.setEncoding("UTF-8");
            cim.setHeaderPresent(true);
            cim.setBufferSize("50000");
            TextFileInputField[] fields = new TextFileInputField[br.columns.size()];
            for (int i = 0; i < br.columns.size(); i++) {
                fields[i] = new TextFileInputField(br.columns.get(i), ValueMetaInterface.TYPE_STRING, -1);
            }
            cim.setInputFields(fields);
            StepMeta input = new StepMeta("CsvInput", "源文件输入", cim);
            input.setLocation(60, 80);
            input.drawStep();
            tm.addStep(input);
            return input;
        }
        Long cid = s0.getLong("srcConnId", job.getSrcConnId());
        if (cid == null) cid = job.getSrcConnId();
        DbConn src = dbConnService.getById(cid);
        if (src == null) {
            throw new RuntimeException("源数据源不存在: " + cid);
        }
        String sch = StrUtil.blankToDefault(s0.getStr("srcSchema", ""), src.getDatabaseName());
        String tbl = s0.getStr("srcTable");
        String q = "MYSQL".equalsIgnoreCase(src.getDbType()) ? "`" : "\"";
        List<Map<String, String>> maps = byTable.get(tbl);
        StringBuilder sql = new StringBuilder("SELECT ");
        List<String> colNames = new ArrayList<>();
        if (maps == null || maps.isEmpty()) {
            sql.append("*");
        } else {
            List<String> cols = new ArrayList<>();
            for (Map<String, String> m : maps) {
                String srcF = m.get("srcField");
                String dstF = m.get("dstField");
                if (StrUtil.isBlank(srcF)) continue;
                String out = StrUtil.isBlank(dstF) ? srcF : dstF;
                cols.add(q + srcF + q + " AS " + q + out + q);
                colNames.add(out);
                midCols.add(out);
            }
            sql.append(String.join(", ", cols));
        }
        sql.append(" FROM ").append(quoteTable(sch, tbl, src));
        String where = s0.getStr("srcWhere", "");
        String baseSql = sql.toString();
        if (tableRules != null && !tableRules.isEmpty()) {
            baseSql = injectRulesToSql(baseSql, where, src, tableRules, colNames, midCols);
        } else if (StrUtil.isNotBlank(where)) {
            baseSql = baseSql + " WHERE " + where;
        }
        DatabaseMeta srcDb = toDatabaseMeta("src_db", src, sch);
        tm.addDatabase(srcDb);
        TableInputMeta tim = new TableInputMeta();
        tim.setDatabaseMeta(srcDb);
        tim.setSQL(baseSql);
        tim.setRowLimit("0");
        StepMeta input = new StepMeta("TableInput", "源表输入", tim);
        input.setLocation(60, 80);
        input.drawStep();
        tm.addStep(input);
        return input;
    }

    /** 多源 UNION 合并: 各源 SELECT 映射列 AS 中间列(并集) → SortedMerge */
    private StepMeta buildUnionSourceChain(TransMeta tm, EtlJob job, List<cn.hutool.json.JSONObject> srcs,
                                           Map<String, List<Map<String, String>>> byTable, LinkedHashSet<String> midCols,
                                           List<cn.hutool.json.JSONObject> cleanRules) throws Exception {
        StepMeta prev = null;
        List<StepMeta> inputs = new ArrayList<>();
        for (int i = 0; i < srcs.size(); i++) {
            cn.hutool.json.JSONObject item = srcs.get(i);
            Long cid = item.getLong("srcConnId", job.getSrcConnId());
            if (cid == null) cid = job.getSrcConnId();
            DbConn src = dbConnService.getById(cid);
            if (src == null) {
                throw new RuntimeException("源数据源不存在: " + cid);
            }
            String sch = StrUtil.blankToDefault(item.getStr("srcSchema", ""), src.getDatabaseName());
            String tbl = item.getStr("srcTable");
            String q = "MYSQL".equalsIgnoreCase(src.getDbType()) ? "`" : "\"";
            List<Map<String, String>> maps = byTable.get(tbl);
            StringBuilder sql = new StringBuilder("SELECT ");
            List<String> cols = new ArrayList<>();
            List<String> colNames = new ArrayList<>();
            if (maps == null || maps.isEmpty()) {
                cols.add("*");
            } else {
                for (Map<String, String> m : maps) {
                    String srcF = m.get("srcField");
                    String dstF = m.get("dstField");
                    if (StrUtil.isBlank(srcF)) continue;
                    String out = StrUtil.isBlank(dstF) ? srcF : dstF;
                    cols.add(q + srcF + q + " AS " + q + out + q);
                    colNames.add(out);
                    midCols.add(out);
                }
            }
            sql.append(String.join(", ", cols));
            sql.append(" FROM ").append(quoteTable(sch, tbl, src));
            String where = item.getStr("srcWhere", "");
            String baseSql = sql.toString();
            List<cn.hutool.json.JSONObject> tableRules = rulesForTable(cleanRules, tbl);
            if (!tableRules.isEmpty()) {
                baseSql = injectRulesToSql(baseSql, where, src, tableRules, colNames, midCols);
            } else if (StrUtil.isNotBlank(where)) {
                baseSql = baseSql + " WHERE " + where;
            }
            DatabaseMeta srcDb = toDatabaseMeta("src_db_" + i, src, sch);
            tm.addDatabase(srcDb);
            TableInputMeta tim = new TableInputMeta();
            tim.setDatabaseMeta(srcDb);
            tim.setSQL(sql.toString());
            tim.setRowLimit("0");
            StepMeta input = new StepMeta("TableInput", "源表输入" + (i + 1), tim);
            input.setLocation(60, 80 + i * 140);
            input.drawStep();
            tm.addStep(input);
            inputs.add(input);
            prev = input;
        }
        SortedMergeMeta smm = new SortedMergeMeta();
        smm.setFieldName(new String[0]);
        smm.setAscending(new boolean[0]);
        StepMeta sm = new StepMeta("SortedMerge", "合并排序", smm);
        sm.setLocation(280, 80);
        sm.drawStep();
        tm.addStep(sm);
        for (StepMeta in : inputs) {
            tm.addTransHop(new TransHopMeta(in, sm));
        }
        return sm;
    }

    /** 多源 JOIN 组合: 同库单 SQL / 跨库 MergeJoin 链 */
    private StepMeta buildJoinSourceChain(TransMeta tm, EtlJob job, List<cn.hutool.json.JSONObject> srcs,
                                          Map<String, List<Map<String, String>>> byTable, LinkedHashSet<String> midCols) throws Exception {
        Long mainConnId = srcs.get(0).getLong("srcConnId", job.getSrcConnId());
        DbConn mainConn = dbConnService.getById(mainConnId != null ? mainConnId : job.getSrcConnId());
        if (mainConn == null) {
            throw new RuntimeException("源数据源不存在: " + mainConnId);
        }
        String mainSchema0 = StrUtil.blankToDefault(srcs.get(0).getStr("srcSchema", ""), mainConn.getDatabaseName());
        boolean sameDb = true;
        for (cn.hutool.json.JSONObject item : srcs) {
            Long cid = item.getLong("srcConnId", job.getSrcConnId());
            if (cid == null) cid = job.getSrcConnId();
            DbConn c = cid != null ? dbConnService.getById(cid) : null;
            String sch = StrUtil.blankToDefault(item.getStr("srcSchema", ""), c != null ? c.getDatabaseName() : "");
            if (cid == null || !cid.equals(mainConnId) || !mainSchema0.equalsIgnoreCase(sch)) {
                sameDb = false;
                break;
            }
        }
        if (sameDb) {
            return buildJoinSameDbStep(tm, job, srcs, mainConn, mainSchema0, byTable, midCols);
        }
        return buildJoinCrossDbStep(tm, job, srcs, byTable, midCols);
    }

    /** 同库 JOIN: 单条 JOIN SQL(SELECT 映射列 AS 中间列) → TableInput */
    private StepMeta buildJoinSameDbStep(TransMeta tm, EtlJob job, List<cn.hutool.json.JSONObject> srcs, DbConn src, String srcSchema,
                                         Map<String, List<Map<String, String>>> byTable, LinkedHashSet<String> midCols) throws Exception {
        String q = "MYSQL".equalsIgnoreCase(src.getDbType()) ? "`" : "\"";
        List<String> cols = new ArrayList<>();
        LinkedHashSet<String> dstSeen = new LinkedHashSet<>();
        for (int i = 0; i < srcs.size(); i++) {
            String t = srcs.get(i).getStr("srcTable");
            String alias = "t" + i;
            List<Map<String, String>> maps = byTable.get(t);
            if (maps == null || maps.isEmpty()) {
                if (dstSeen.isEmpty()) {
                    cols.add(alias + ".*");
                    dstSeen.add("*");
                }
            } else {
                for (Map<String, String> m : maps) {
                    String srcF = m.get("srcField");
                    String dstF = m.get("dstField");
                    if (StrUtil.isBlank(srcF)) continue;
                    if (dstSeen.contains(dstF)) continue;
                    dstSeen.add(dstF);
                    String out = StrUtil.isBlank(dstF) ? srcF : dstF;
                    cols.add(alias + "." + q + srcF + q + " AS " + q + out + q);
                    midCols.add(out);
                }
            }
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", cols));
        sql.append(" FROM ").append(quoteTable(srcSchema, srcs.get(0).getStr("srcTable"), src)).append(" t0");
        for (int i = 1; i < srcs.size(); i++) {
            cn.hutool.json.JSONObject item = srcs.get(i);
            String jt = item.getStr("joinType", "JOIN");
            boolean left = "LEFT_JOIN".equalsIgnoreCase(jt) || "LEFT JOIN".equalsIgnoreCase(jt);
            String joinField = item.getStr("joinField", "");
            String joinRef = item.getStr("joinRef", "");
            sql.append(left ? " LEFT JOIN " : " JOIN ")
               .append(quoteTable(srcSchema, item.getStr("srcTable"), src)).append(" t").append(i);
            if (StrUtil.isNotBlank(joinRef) && StrUtil.isNotBlank(joinField)) {
                String[] refs = joinRef.split(",");
                String[] flds = joinField.split(",");
                boolean firstOn = true;
                for (int ki = 0; ki < Math.min(refs.length, flds.length); ki++) {
                    String rf = refs[ki].trim();
                    String ff = flds[ki].trim();
                    if (StrUtil.isBlank(rf) || StrUtil.isBlank(ff)) continue;
                    sql.append(firstOn ? " ON " : " AND ");
                    sql.append(" t0.").append(q).append(rf).append(q)
                       .append(" = t").append(i).append(".").append(q).append(ff).append(q);
                    firstOn = false;
                }
            }
        }
        String where = srcs.get(0).getStr("srcWhere", "");
        if (StrUtil.isNotBlank(where)) {
            sql.append(" WHERE ").append(where);
        }
        DatabaseMeta srcDb = toDatabaseMeta("src_db", src, srcSchema);
        tm.addDatabase(srcDb);
        TableInputMeta tim = new TableInputMeta();
        tim.setDatabaseMeta(srcDb);
        tim.setSQL(sql.toString());
        tim.setRowLimit("0");
        StepMeta input = new StepMeta("TableInput", "源表JOIN", tim);
        input.setLocation(60, 80);
        input.drawStep();
        tm.addStep(input);
        return input;
    }

    /** 跨库 JOIN: 各源 TableInput(含关联键) → MergeJoin 链 → SelectValues(去 __k 残留) */
    private StepMeta buildJoinCrossDbStep(TransMeta tm, EtlJob job, List<cn.hutool.json.JSONObject> srcs,
                                          Map<String, List<Map<String, String>>> byTable, LinkedHashSet<String> midCols) throws Exception {
        StepMeta prev = null;
        String[] mainKeys = null;
        LinkedHashSet<String> dstSeen = new LinkedHashSet<>();
        String mainJoinField = "";
        if (srcs.size() > 1) {
            mainJoinField = StrUtil.blankToDefault(srcs.get(1).getStr("joinField", ""), srcs.get(0).getStr("joinRef", ""));
        } else if (!srcs.isEmpty()) {
            mainJoinField = srcs.get(0).getStr("joinRef", "");
        }
        for (int i = 0; i < srcs.size(); i++) {
            cn.hutool.json.JSONObject item = srcs.get(i);
            Long cid = item.getLong("srcConnId", job.getSrcConnId());
            if (cid == null) cid = job.getSrcConnId();
            DbConn src = dbConnService.getById(cid);
            if (src == null) {
                throw new RuntimeException("源数据源不存在: " + cid);
            }
            String sch = StrUtil.blankToDefault(item.getStr("srcSchema", ""), src.getDatabaseName());
            String tbl = item.getStr("srcTable");
            String q = "MYSQL".equalsIgnoreCase(src.getDbType()) ? "`" : "\"";
            DatabaseMeta srcDb = toDatabaseMeta("src_db_" + i, src, sch);
            tm.addDatabase(srcDb);
            List<String> cols = new ArrayList<>();
            List<Map<String, String>> maps = byTable.get(tbl);
            if (maps != null) {
                for (Map<String, String> m : maps) {
                    String srcF = m.get("srcField");
                    String dstF = m.get("dstField");
                    if (StrUtil.isBlank(srcF)) continue;
                    if (dstSeen.contains(dstF)) continue;
                    dstSeen.add(dstF);
                    String out = StrUtil.isBlank(dstF) ? srcF : dstF;
                    cols.add(q + srcF + q + " AS " + q + out + q);
                    midCols.add(out);
                }
            } else if (dstSeen.isEmpty()) {
                cols.add("*");
                dstSeen.add("*");
            }
            String joinFieldOrRef = i == 0 ? mainJoinField : item.getStr("joinRef", "");
            List<String> keyAliases = new ArrayList<>();
            List<String> keySrcCols = new ArrayList<>();
            if (StrUtil.isNotBlank(joinFieldOrRef)) {
                String[] parts = joinFieldOrRef.split(",");
                for (int j = 0; j < parts.length; j++) {
                    String col = parts[j].trim();
                    if (StrUtil.isBlank(col)) continue;
                    String alias = "__k" + i + "_" + j;
                    cols.add(q + col + q + " AS " + q + alias + q);
                    keyAliases.add(alias);
                    keySrcCols.add(col);
                }
            }
            if (i == 0) {
                mainKeys = keyAliases.toArray(new String[0]);
            }
            String where = i == 0 ? item.getStr("srcWhere", "") : "";
            StringBuilder sql = new StringBuilder("SELECT ");
            sql.append(String.join(", ", cols));
            sql.append(" FROM ").append(quoteTable(sch, tbl, src));
            if (StrUtil.isNotBlank(where)) {
                sql.append(" WHERE ").append(where);
            }
            if (!keySrcCols.isEmpty()) {
                sql.append(" ORDER BY ").append(String.join(", ", keySrcCols));
            }
            TableInputMeta tim = new TableInputMeta();
            tim.setDatabaseMeta(srcDb);
            tim.setSQL(sql.toString());
            tim.setRowLimit("0");
            StepMeta input = new StepMeta("TableInput", "源表输入" + (i + 1), tim);
            input.setLocation(60, 80 + i * 140);
            input.drawStep();
            tm.addStep(input);
            if (i == 0) {
                prev = input;
            } else {
                cn.hutool.json.JSONObject it = srcs.get(i);
                String jt = it.getStr("joinType", "JOIN");
                boolean left = "LEFT_JOIN".equalsIgnoreCase(jt) || "LEFT JOIN".equalsIgnoreCase(jt);
                MergeJoinMeta mjm = new MergeJoinMeta();
                mjm.setJoinType(left ? "LEFT OUTER" : "INNER");
                mjm.setKeyFields1(mainKeys == null ? new String[0] : mainKeys);
                mjm.setKeyFields2(keyAliases.toArray(new String[0]));
                org.pentaho.di.trans.step.StepIOMetaInterface io = mjm.getStepIOMeta();
                java.util.List<org.pentaho.di.trans.step.errorhandling.StreamInterface> infos = io.getInfoStreams();
                if (!infos.isEmpty()) infos.get(0).setStepMeta(prev);
                if (infos.size() > 1) infos.get(1).setStepMeta(input);
                StepMeta mj = new StepMeta("MergeJoin", "Merge Join " + i, mjm);
                mj.setLocation(280 + (i - 1) * 220, 80 + (i - 1) * 20);
                mj.drawStep();
                tm.addStep(mj);
                tm.addTransHop(new TransHopMeta(prev, mj));
                tm.addTransHop(new TransHopMeta(input, mj));
                prev = mj;
            }
        }
        for (StepMeta sm : tm.getSteps()) {
            if (sm.getStepMetaInterface() instanceof MergeJoinMeta) {
                MergeJoinMeta mjm2 = (MergeJoinMeta) sm.getStepMetaInterface();
                List<StepMeta> prevs = tm.findPreviousSteps(sm);
                org.pentaho.di.trans.step.StepIOMetaInterface io2 = mjm2.getStepIOMeta();
                java.util.List<org.pentaho.di.trans.step.errorhandling.StreamInterface> infos2 = io2.getInfoStreams();
                for (int k = 0; k < prevs.size() && k < infos2.size(); k++) {
                    infos2.get(k).setStepMeta(prevs.get(k));
                }
            }
        }
        List<String> targetCols = new ArrayList<>(midCols);
        if (!targetCols.isEmpty()) {
            SelectValuesMeta svm = new SelectValuesMeta();
            svm.setSelectName(targetCols.toArray(new String[0]));
            svm.setSelectingAndSortingUnspecifiedFields(false);
            StepMeta sv = new StepMeta("SelectValues", "字段选择", svm);
            sv.setLocation(700, 80);
            sv.drawStep();
            tm.addStep(sv);
            tm.addTransHop(new TransHopMeta(prev, sv));
            prev = sv;
        }
        return prev;
    }

    /** 追加清洗/质量规则步骤(仅真实可执行类型): 去重复→UniqueRows, 去空值/非空校验→FilterRows */
    private StepMeta appendFilterSteps(TransMeta tm, StepMeta prev, List<cn.hutool.json.JSONObject> rules,
                                       LinkedHashSet<String> midCols, String label) {
        if (rules.isEmpty()) {
            return prev;
        }
        int idx = 0;
        for (cn.hutool.json.JSONObject r : rules) {
            String type = r.getStr("ruleType", "");
            String config = r.getStr("ruleConfig", "");
            if ("去重复".equals(type) || "去重".equals(type) || "DEDUP".equalsIgnoreCase(type) || "DISTINCT".equalsIgnoreCase(type)) {
                String dedupField = extractFieldFromConfig(config, midCols);
                UniqueRowsMeta urm = new UniqueRowsMeta();
                urm.setRejectDuplicateRow(true);
                urm.setCompareFields(StrUtil.isBlank(dedupField) ? new String[0] : new String[]{dedupField});
                urm.setCaseInsensitive(StrUtil.isBlank(dedupField) ? new boolean[0] : new boolean[]{true});
                StepMeta ur = new StepMeta("UniqueRows", label + "去重复" + (idx + 1), urm);
                ur.setLocation(prev.getLocation().x + 220, 80 + idx * 60);
                ur.drawStep();
                tm.addStep(ur);
                tm.addTransHop(new TransHopMeta(prev, ur));
                prev = ur;
                idx++;
            } else if ("去空值".equals(type) || "NOT_NULL".equalsIgnoreCase(type) || "非空校验".equals(type) || "非空".equals(type)) {
                String field = extractFieldFromConfig(config, midCols);
                if (StrUtil.isNotBlank(field)) {
                    Condition cond = new Condition();
                    cond.setLeftValuename(field);
                    cond.setFunction(Condition.FUNC_NOT_NULL);
                    FilterRowsMeta frm = new FilterRowsMeta();
                    frm.setCondition(cond);
                    StepMeta fr = new StepMeta("FilterRows", label + "非空校验" + (idx + 1), frm);
                    fr.setLocation(prev.getLocation().x + 220, 80 + idx * 60);
                    fr.drawStep();
                    tm.addStep(fr);
                    tm.addTransHop(new TransHopMeta(prev, fr));
                    prev = fr;
                    idx++;
                }
            } else if ("枚举校验".equals(type) || "ENUM".equalsIgnoreCase(type)) {
                String field = extractFieldFromConfig(config, midCols);
                String list = ruleCfg(config, "list", "");
                if (StrUtil.isBlank(list)) {
                    list = ruleCfg(config, "values", "");
                }
                if (StrUtil.isNotBlank(field) && StrUtil.isNotBlank(list)) {
                    java.util.List<String> ev = new java.util.ArrayList<>();
                    String trimmed = list.trim();
                    if (trimmed.startsWith("[")) {
                        try {
                            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(trimmed);
                            for (Object o : arr) {
                                if (o != null) ev.add(String.valueOf(o));
                            }
                        } catch (Exception ignored) {
                            ev.clear();
                        }
                    }
                    if (ev.isEmpty()) {
                        for (String v : list.split(",")) {
                            String t = v.trim();
                            if (!t.isEmpty()) ev.add(t);
                        }
                    }
                    // 枚举校验: 根条件为复合(OR), 每个枚举值一个 = 子条件(显式 OPERATOR_OR)
                    Condition root = new Condition();
                    for (String v : ev) {
                        String t = v.trim();
                        if (t.isEmpty()) continue;
                        Condition c = new Condition();
                        c.setLeftValuename(field);
                        c.setFunction(Condition.FUNC_EQUAL);
                        c.setRightExact(new ValueMetaAndData(new ValueMeta("v", ValueMetaInterface.TYPE_STRING), t));
                        c.setOperator(Condition.OPERATOR_OR);
                        root.addCondition(c);
                    }
                    if (root.nrConditions() > 0) {
                        FilterRowsMeta frm = new FilterRowsMeta();
                        frm.setCondition(root);
                        StepMeta fr = new StepMeta("FilterRows", label + "枚举校验" + (idx + 1), frm);
                        fr.setLocation(prev.getLocation().x + 220, 80 + idx * 60);
                        fr.drawStep();
                        tm.addStep(fr);
                        tm.addTransHop(new TransHopMeta(prev, fr));
                        prev = fr;
                        idx++;
                    }
                }
            } else if ("格式规范化".equals(type) || "NORMALIZE".equalsIgnoreCase(type)) {
                String field = extractFieldFromConfig(config, midCols);
                if (StrUtil.isNotBlank(field)) {
                    String action = StrUtil.blankToDefault(ruleCfg(config, "action", ""), "upper").toLowerCase();
                    String[] trim = new String[]{StringOperationsMeta.trimTypeCode[StringOperationsMeta.TRIM_NONE]};
                    String[] lu = new String[]{StringOperationsMeta.lowerUpperCode[StringOperationsMeta.LOWER_UPPER_NONE]};
                    if ("lower".equals(action) || "小写".equals(action)) {
                        lu = new String[]{StringOperationsMeta.lowerUpperCode[StringOperationsMeta.LOWER_UPPER_LOWER]};
                    } else if ("trim".equals(action)) {
                        trim = new String[]{StringOperationsMeta.trimTypeCode[StringOperationsMeta.TRIM_BOTH]};
                    } else if ("upper".equals(action) || "大写".equals(action)) {
                        lu = new String[]{StringOperationsMeta.lowerUpperCode[StringOperationsMeta.LOWER_UPPER_UPPER]};
                    } else {
                        lu = new String[]{StringOperationsMeta.lowerUpperCode[StringOperationsMeta.LOWER_UPPER_UPPER]};
                    }
                    StringOperationsMeta som = new StringOperationsMeta();
                    som.setFieldInStream(new String[]{field});
                    som.setFieldOutStream(new String[]{field});
                    som.setTrimType(trim);
                    som.setLowerUpper(lu);
                    // 其余操作数组必须同长度设置, 否则执行时 NPE(padding_type 等为 null)
                    som.setPaddingType(new String[]{StringOperationsMeta.paddingCode[StringOperationsMeta.PADDING_NONE]});
                    som.setPadLen(new String[]{""});
                    som.setPadChar(new String[]{""});
                    som.setInitCap(new String[]{StringOperationsMeta.initCapCode[StringOperationsMeta.INIT_CAP_NO]});
                    som.setMaskXML(new String[]{StringOperationsMeta.maskXMLCode[StringOperationsMeta.MASK_NONE]});
                    som.setDigits(new String[]{StringOperationsMeta.digitsCode[StringOperationsMeta.DIGITS_NONE]});
                    som.setRemoveSpecialCharacters(new String[]{StringOperationsMeta.removeSpecialCharactersCode[StringOperationsMeta.REMOVE_SPECIAL_CHARACTERS_NONE]});
                    StepMeta so = new StepMeta("StringOperations", label + "格式规范化" + (idx + 1), som);
                    so.setLocation(prev.getLocation().x + 220, 80 + idx * 60);
                    so.drawStep();
                    tm.addStep(so);
                    tm.addTransHop(new TransHopMeta(prev, so));
                    prev = so;
                    idx++;
                }
            } else if ("单位换算".equals(type) || "UNIT_CONVERT".equalsIgnoreCase(type)) {
                String field = extractFieldFromConfig(config, midCols);
                if (StrUtil.isNotBlank(field)) {
                    String factorStr = StrUtil.blankToDefault(ruleCfg(config, "factor", "1"), "1");
                    double factor;
                    try {
                        factor = Double.parseDouble(factorStr.trim());
                    } catch (Exception ignored) {
                        factor = 1.0;
                    }
                    CalculatorMetaFunction cmf = new CalculatorMetaFunction();
                    cmf.setFieldName(field);
                    cmf.setCalcType(CalculatorMetaFunction.CALC_MULTIPLY);
                    cmf.setFieldA(String.valueOf(factor));
                    cmf.setFieldB("");
                    cmf.setValueType(ValueMetaInterface.TYPE_NUMBER);
                    cmf.setValueLength(-1);
                    cmf.setValuePrecision(-1);
                    CalculatorMeta cm = new CalculatorMeta();
                    cm.setCalculation(new CalculatorMetaFunction[]{cmf});
                    StepMeta ca = new StepMeta("Calculator", label + "单位换算" + (idx + 1), cm);
                    ca.setLocation(prev.getLocation().x + 220, 80 + idx * 60);
                    ca.drawStep();
                    tm.addStep(ca);
                    tm.addTransHop(new TransHopMeta(prev, ca));
                    prev = ca;
                    idx++;
                }
            } else if ("默认值填充".equals(type) || "DEFAULT_VALUE".equalsIgnoreCase(type)) {
                String field = extractFieldFromConfig(config, midCols);
                String dv = ruleCfg(config, "defaultValue", ruleCfg(config, "value", ""));
                if (StrUtil.isNotBlank(field) && StrUtil.isNotBlank(dv)) {
                    IfNullMeta inm = new IfNullMeta();
                    IfNullMeta.Fields fld = new IfNullMeta.Fields();
                    fld.setFieldName(field);
                    fld.setReplaceValue(dv);
                    fld.setEmptyString(true);
                    inm.setFields(new IfNullMeta.Fields[]{fld});
                    // valueTypes 必须同长度设置, 否则执行时 NPE(this.valueTypes is null)
                    IfNullMeta.ValueTypes vt = new IfNullMeta.ValueTypes();
                    vt.setTypeName("String");
                    vt.setTypereplaceValue(dv);
                    inm.setValueTypes(new IfNullMeta.ValueTypes[]{vt});
                    inm.setSelectFields(true);
                    inm.setSelectValuesType(true);
                    StepMeta in = new StepMeta("IfNull", label + "默认值填充" + (idx + 1), inm);
                    in.setLocation(prev.getLocation().x + 220, 80 + idx * 60);
                    in.drawStep();
                    tm.addStep(in);
                    tm.addTransHop(new TransHopMeta(prev, in));
                    prev = in;
                    idx++;
                }
            } else if ("范围校验".equals(type) || "RANGE".equalsIgnoreCase(type)) {
                String field = extractFieldFromConfig(config, midCols);
                String min = ruleCfg(config, "min", "");
                String max = ruleCfg(config, "max", "");
                if (StrUtil.isNotBlank(field) && (StrUtil.isNotBlank(min) || StrUtil.isNotBlank(max))) {
                    Condition root = new Condition();
                    if (StrUtil.isNotBlank(min)) {
                        Condition lo = new Condition();
                        lo.setLeftValuename(field);
                        lo.setFunction(Condition.FUNC_LARGER_EQUAL);
                        try {
                            lo.setRightExact(new ValueMetaAndData(new ValueMeta("v", ValueMetaInterface.TYPE_NUMBER), Double.valueOf(min.trim())));
                        } catch (Exception ignored) {
                            lo.setRightExact(new ValueMetaAndData(new ValueMeta("v", ValueMetaInterface.TYPE_STRING), min.trim()));
                        }
                        lo.setOperator(Condition.OPERATOR_AND);
                        root.addCondition(lo);
                    }
                    if (StrUtil.isNotBlank(max)) {
                        Condition hi = new Condition();
                        hi.setLeftValuename(field);
                        hi.setFunction(Condition.FUNC_SMALLER_EQUAL);
                        try {
                            hi.setRightExact(new ValueMetaAndData(new ValueMeta("v", ValueMetaInterface.TYPE_NUMBER), Double.valueOf(max.trim())));
                        } catch (Exception ignored) {
                            hi.setRightExact(new ValueMetaAndData(new ValueMeta("v", ValueMetaInterface.TYPE_STRING), max.trim()));
                        }
                        hi.setOperator(Condition.OPERATOR_AND);
                        root.addCondition(hi);
                    }
                    if (root.nrConditions() > 0) {
                        FilterRowsMeta frm = new FilterRowsMeta();
                        frm.setCondition(root);
                        StepMeta fr = new StepMeta("FilterRows", label + "范围校验" + (idx + 1), frm);
                        fr.setLocation(prev.getLocation().x + 220, 80 + idx * 60);
                        fr.drawStep();
                        tm.addStep(fr);
                        tm.addTransHop(new TransHopMeta(prev, fr));
                        prev = fr;
                        idx++;
                    }
                }
            } else if ("格式校验".equals(type) || "REGEXP".equalsIgnoreCase(type) || "FORMAT".equalsIgnoreCase(type)) {
                String field = extractFieldFromConfig(config, midCols);
                String pattern = ruleCfg(config, "pattern", "");
                if (StrUtil.isNotBlank(field) && StrUtil.isNotBlank(pattern)) {
                    Condition cond = new Condition();
                    cond.setLeftValuename(field);
                    cond.setFunction(Condition.FUNC_REGEXP);
                    cond.setRightExact(new ValueMetaAndData(new ValueMeta("v", ValueMetaInterface.TYPE_STRING), pattern));
                    FilterRowsMeta frm = new FilterRowsMeta();
                    frm.setCondition(cond);
                    StepMeta fr = new StepMeta("FilterRows", label + "格式校验" + (idx + 1), frm);
                    fr.setLocation(prev.getLocation().x + 220, 80 + idx * 60);
                    fr.drawStep();
                    tm.addStep(fr);
                    tm.addTransHop(new TransHopMeta(prev, fr));
                    prev = fr;
                    idx++;
                }
            } else if ("关联完整性".equals(type) || "FK".equalsIgnoreCase(type)) {
                String field = extractFieldFromConfig(config, midCols);
                if (StrUtil.isNotBlank(field)) {
                    Condition cond = new Condition();
                    cond.setLeftValuename(field);
                    cond.setFunction(Condition.FUNC_NOT_NULL);
                    FilterRowsMeta frm = new FilterRowsMeta();
                    frm.setCondition(cond);
                    StepMeta fr = new StepMeta("FilterRows", label + "关联完整性" + (idx + 1), frm);
                    fr.setLocation(prev.getLocation().x + 220, 80 + idx * 60);
                    fr.drawStep();
                    tm.addStep(fr);
                    tm.addTransHop(new TransHopMeta(prev, fr));
                    prev = fr;
                    idx++;
                }
            } else if ("SQL 清洗".equals(type) || "SQL_CLEAN".equalsIgnoreCase(type)
                    || "自定义 SQL".equals(type) || "CUSTOM_SQL".equalsIgnoreCase(type)) {
                log.warn("清洗/质量规则[{}] 在 {} 源(CSV 步骤流)下不执行: 仅数据库表源支持 SQL 清洗/自定义 SQL", type, label);
            } else if ("字段映射".equals(type) || "MAPPING".equalsIgnoreCase(type)) {
                log.warn("清洗规则[字段映射] 由表单字段映射配置生效, 清洗规则步骤中不重复执行");
            }
        }
        return prev;
    }

    /** 从规则配置中提取字段名: 支持 {"field":"name"} 或 裸字段名 */
    private String extractFieldFromConfig(String config, LinkedHashSet<String> midCols) {
        if (StrUtil.isBlank(config)) {
            return midCols.isEmpty() ? "" : midCols.iterator().next();
        }
        String c = config.trim();
        try {
            if (c.startsWith("{")) {
                cn.hutool.json.JSONObject o = cn.hutool.json.JSONUtil.parseObj(c);
                String f = o.getStr("field", "");
                if (StrUtil.isNotBlank(f)) return f;
                String col = o.getStr("column", "");
                if (StrUtil.isNotBlank(col)) return col;
            }
        } catch (Exception ignored) {
            // ignore
        }
        return c;
    }

    /** 段2映射: SelectValues 保留映射后的目标列 */
    private StepMeta appendSelectValues(TransMeta tm, StepMeta prev, List<Map<String, String>> dstMaps) {
        List<String> outCols = new ArrayList<>();
        for (Map<String, String> m : dstMaps) {
            String dstF = m.get("dstField");
            if (StrUtil.isNotBlank(dstF) && !outCols.contains(dstF)) {
                outCols.add(dstF);
            }
        }
        if (outCols.isEmpty()) {
            return prev;
        }
        SelectValuesMeta svm = new SelectValuesMeta();
        svm.setSelectName(outCols.toArray(new String[0]));
        svm.setSelectingAndSortingUnspecifiedFields(false);
        StepMeta sv = new StepMeta("SelectValues", "目标字段映射", svm);
        sv.setLocation(prev.getLocation().x + 220, 80);
        sv.drawStep();
        tm.addStep(sv);
        tm.addTransHop(new TransHopMeta(prev, sv));
        return sv;
    }

    /**
     * 同库 JOIN: 单条 JOIN SQL(支持多关联字段, 逗号分隔)
     */
    private String buildTransXmlJoinSameDb(EtlJob job, List<cn.hutool.json.JSONObject> srcs, DbConn src, String srcSchema) throws Exception {
        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (dst == null) {
            throw new RuntimeException("数据源不存在: src=" + job.getSrcConnId() + ", dst=" + job.getDstConnId());
        }
        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
        String q = "MYSQL".equalsIgnoreCase(src.getDbType()) ? "`" : "\"";
        Map<String, List<Map<String, String>>> byTable = parseMappingGroup(job.getFieldMapping());
        // 目标列去重: 每个 dstField 只输出第一个提供它的源表, 避免多表同名字段在 Kettle 流中被重命名(category_id_1)导致 TableOutput 列不匹配
        List<String> cols = new ArrayList<>();
        LinkedHashSet<String> dstSeen = new LinkedHashSet<>();
        for (int i = 0; i < srcs.size(); i++) {
            String t = srcs.get(i).getStr("srcTable");
            String alias = "t" + i;
            List<Map<String, String>> maps = byTable.get(t);
            if (maps == null || maps.isEmpty()) {
                if (dstSeen.isEmpty()) {
                    cols.add(alias + ".*");
                    dstSeen.add("*");
                }
            } else {
                for (Map<String, String> m : maps) {
                    String srcF = m.get("srcField");
                    String dstF = m.get("dstField");
                    if (StrUtil.isBlank(srcF)) continue;
                    if (dstSeen.contains(dstF)) continue;
                    dstSeen.add(dstF);
                    cols.add(alias + "." + q + srcF + q + " AS " + q + dstF + q);
                }
            }
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", cols));
        sql.append(" FROM ").append(quoteTable(srcSchema, srcs.get(0).getStr("srcTable"), src)).append(" t0");
        for (int i = 1; i < srcs.size(); i++) {
            cn.hutool.json.JSONObject item = srcs.get(i);
            String jt = item.getStr("joinType", "JOIN");
            boolean left = "LEFT_JOIN".equalsIgnoreCase(jt) || "LEFT JOIN".equalsIgnoreCase(jt);
            String joinField = item.getStr("joinField", "");
            String joinRef = item.getStr("joinRef", "");
            sql.append(left ? " LEFT JOIN " : " JOIN ")
               .append(quoteTable(srcSchema, item.getStr("srcTable"), src)).append(" t").append(i);
            if (StrUtil.isNotBlank(joinRef) && StrUtil.isNotBlank(joinField)) {
                String[] refs = joinRef.split(",");
                String[] flds = joinField.split(",");
                boolean firstOn = true;
                for (int ki = 0; ki < Math.min(refs.length, flds.length); ki++) {
                    String rf = refs[ki].trim();
                    String ff = flds[ki].trim();
                    if (StrUtil.isBlank(rf) || StrUtil.isBlank(ff)) continue;
                    sql.append(firstOn ? " ON " : " AND ");
                    sql.append(" t0.").append(q).append(rf).append(q)
                       .append(" = t").append(i).append(".").append(q).append(ff).append(q);
                    firstOn = false;
                }
            }
        }
        String where = srcs.get(0).getStr("srcWhere", "");
        if (StrUtil.isNotBlank(where)) {
            sql.append(" WHERE ").append(where);
        }
        return buildTransXmlWithSql(job, src, srcSchema, dst, dstSchema, sql.toString());
    }

    /**
     * 跨库 JOIN: 各源独立 TableInput(含排序) + Merge Join 串联 + 表输出
     */
    private String buildTransXmlJoinCrossDb(EtlJob job, List<cn.hutool.json.JSONObject> srcs) throws Exception {
        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (dst == null) {
            throw new RuntimeException("目标数据源不存在: dst=" + job.getDstConnId());
        }
        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
        DatabaseMeta dstDb = toDatabaseMeta("dst_db", dst, dstSchema);

        TransMeta tm = new TransMeta();
        tm.setName("etl_" + StrUtil.nullToEmpty(job.getJobName()).replaceAll("\\s+", "_") + "_" + System.nanoTime());
        tm.addDatabase(dstDb);

        Map<String, List<Map<String, String>>> byTable = parseMappingGroup(job.getFieldMapping());
        List<StepMeta> prevInputs = new ArrayList<>();
        String[] mainKeys = null;
        StepMeta prev = null;
        LinkedHashSet<String> dstSeen = new LinkedHashSet<>();
        // 主表侧关联列: 取第一个副表(srcs[1])的 joinField(主表字段, 逗号分隔多值); i==0 的 joinRef 仅作兜底
        String mainJoinField = "";
        if (srcs.size() > 1) {
            mainJoinField = StrUtil.blankToDefault(srcs.get(1).getStr("joinField", ""), srcs.get(0).getStr("joinRef", ""));
        } else if (!srcs.isEmpty()) {
            mainJoinField = srcs.get(0).getStr("joinRef", "");
        }
        for (int i = 0; i < srcs.size(); i++) {
            cn.hutool.json.JSONObject item = srcs.get(i);
            Long cid = item.getLong("srcConnId", job.getSrcConnId());
            if (cid == null) cid = job.getSrcConnId();
            DbConn src = dbConnService.getById(cid);
            if (src == null) {
                throw new RuntimeException("源数据源不存在: " + cid);
            }
            String sch = StrUtil.blankToDefault(item.getStr("srcSchema", ""), src.getDatabaseName());
            String tbl = item.getStr("srcTable");
            String q = "MYSQL".equalsIgnoreCase(src.getDbType()) ? "`" : "\"";
            DatabaseMeta srcDb = toDatabaseMeta("src_db_" + i, src, sch);
            tm.addDatabase(srcDb);

            // SELECT 列: 映射列 AS dstF(全链路目标列去重, 避免 MergeJoin 后同名列重命名) + 关联字段列 AS __ki_j
            List<String> cols = new ArrayList<>();
            List<Map<String, String>> maps = byTable.get(tbl);
            if (maps != null) {
                for (Map<String, String> m : maps) {
                    String srcF = m.get("srcField");
                    String dstF = m.get("dstField");
                    if (StrUtil.isBlank(srcF)) continue;
                    if (dstSeen.contains(dstF)) continue;
                    dstSeen.add(dstF);
                    cols.add(q + srcF + q + " AS " + q + dstF + q);
                }
            } else if (dstSeen.isEmpty()) {
                cols.add("*");
                dstSeen.add("*");
            }
            // i==0 主表用 mainJoinField(主表侧列), i>0 副表用各自 joinRef(本表侧列, 逗号分隔多值)
            String joinFieldOrRef = i == 0 ? mainJoinField : item.getStr("joinRef", "");
            List<String> keyAliases = new ArrayList<>();
            List<String> keySrcCols = new ArrayList<>();
            if (StrUtil.isNotBlank(joinFieldOrRef)) {
                String[] parts = joinFieldOrRef.split(",");
                for (int j = 0; j < parts.length; j++) {
                    String col = parts[j].trim();
                    if (StrUtil.isBlank(col)) continue;
                    String alias = "__k" + i + "_" + j;
                    cols.add(q + col + q + " AS " + q + alias + q);
                    keyAliases.add(alias);
                    keySrcCols.add(col);
                }
            }
            if (i == 0) {
                mainKeys = keyAliases.toArray(new String[0]);
            }
            String where = i == 0 ? item.getStr("srcWhere", "") : "";
            StringBuilder sql = new StringBuilder("SELECT ");
            sql.append(String.join(", ", cols));
            sql.append(" FROM ").append(quoteTable(sch, tbl, src));
            if (StrUtil.isNotBlank(where)) {
                sql.append(" WHERE ").append(where);
            }
            if (!keySrcCols.isEmpty()) {
                sql.append(" ORDER BY ").append(String.join(", ", keySrcCols));
            }
            TableInputMeta tim = new TableInputMeta();
            tim.setDatabaseMeta(srcDb);
            tim.setSQL(sql.toString());
            tim.setRowLimit("0");
            StepMeta input = new StepMeta("TableInput", "源表输入" + (i + 1), tim);
            input.setLocation(60, 60 + i * 160);
            input.drawStep();
            tm.addStep(input);
            if (i == 0) {
                prev = input;
                prevInputs.add(input);
            } else {
                // Merge Join: prev(左) + input(右)
                cn.hutool.json.JSONObject it = srcs.get(i);
                String jt = it.getStr("joinType", "JOIN");
                boolean left = "LEFT_JOIN".equalsIgnoreCase(jt) || "LEFT JOIN".equalsIgnoreCase(jt);
                MergeJoinMeta mjm = new MergeJoinMeta();
                mjm.setJoinType(left ? "LEFT OUTER" : "INNER");
                mjm.setKeyFields1(mainKeys == null ? new String[0] : mainKeys);
                mjm.setKeyFields2(keyAliases.toArray(new String[0]));

                org.pentaho.di.trans.step.StepIOMetaInterface io = mjm.getStepIOMeta();
                java.util.List<org.pentaho.di.trans.step.errorhandling.StreamInterface> infos = io.getInfoStreams();
                if (!infos.isEmpty()) infos.get(0).setStepMeta(prev);
                if (infos.size() > 1) infos.get(1).setStepMeta(input);
                StepMeta mj = new StepMeta("MergeJoin", "Merge Join " + i, mjm);
                mj.setLocation(280 + (i - 1) * 220, 60 + (i - 1) * 20);
                mj.drawStep();
                tm.addStep(mj);
                tm.addTransHop(new TransHopMeta(prev, mj));
                tm.addTransHop(new TransHopMeta(input, mj));
                prev = mj;
            }
        }

        // 有关联列(__k*)残留时, 用 SelectValues 只保留目标列, 避免 TableOutput 遇到 __k 列报错
        List<String> targetCols = new ArrayList<>(dstSeen);
        targetCols.remove("*");
        if (!targetCols.isEmpty()) {
            SelectValuesMeta svm = new SelectValuesMeta();
            svm.setSelectName(targetCols.toArray(new String[0]));
            svm.setSelectingAndSortingUnspecifiedFields(false);
            StepMeta sv = new StepMeta("SelectValues", "字段选择", svm);
            sv.setLocation(760, 60);
            sv.drawStep();
            tm.addStep(sv);
            tm.addTransHop(new TransHopMeta(prev, sv));
            prev = sv;
        }
        TableOutputMeta tom = new TableOutputMeta();
        tom.setDatabaseMeta(dstDb);
        tom.setTablename(job.getDstTable());
        tom.setCommitSize(job.getBatchSize() == null ? 1024 : job.getBatchSize());
        tom.setTruncateTable("TRUNCATE".equalsIgnoreCase(job.getDstWriteMode()) || "REPLACE".equalsIgnoreCase(job.getDstWriteMode()));
        StepMeta output = new StepMeta("TableOutput", "TableOutput", tom);
        output.setLocation(920, 80);
        output.drawStep();
        tm.addStep(output);
        tm.addTransHop(new TransHopMeta(prev, output));
        // MergeJoin 的 step1/step2 由 info streams 决定, 按 hop 前驱顺序手动设置(与 keys_1/keys_2 对应)
        for (StepMeta sm : tm.getSteps()) {
            if (sm.getStepMetaInterface() instanceof MergeJoinMeta) {
                MergeJoinMeta mjm2 = (MergeJoinMeta) sm.getStepMetaInterface();
                List<StepMeta> prevs = tm.findPreviousSteps(sm);
                org.pentaho.di.trans.step.StepIOMetaInterface io2 = mjm2.getStepIOMeta();
                java.util.List<org.pentaho.di.trans.step.errorhandling.StreamInterface> infos2 = io2.getInfoStreams();
                for (int k = 0; k < prevs.size() && k < infos2.size(); k++) {
                    infos2.get(k).setStepMeta(prevs.get(k));
                }
            }
        }
        return XMLHandler.getXMLHeader() + tm.getXML();
    }

    /**
     * 单条 SQL 的 Kettle 转换(同库 JOIN 用)
     */
    private String buildTransXmlWithSql(EtlJob job, DbConn src, String srcSchema, DbConn dst, String dstSchema, String sql) throws Exception {
        TransMeta tm = new TransMeta();
        tm.setName("etl_" + StrUtil.nullToEmpty(job.getJobName()).replaceAll("\\s+", "_") + "_" + System.nanoTime());
        DatabaseMeta srcDb = toDatabaseMeta("src_db", src, srcSchema);
        DatabaseMeta dstDb = toDatabaseMeta("dst_db", dst, dstSchema);
        tm.addDatabase(srcDb);
        tm.addDatabase(dstDb);

        TableInputMeta tim = new TableInputMeta();
        tim.setDatabaseMeta(srcDb);
        tim.setSQL(sql);
        tim.setRowLimit("0");
        StepMeta input = new StepMeta("TableInput", "TableInput", tim);
        input.setLocation(80, 80);
        input.drawStep();
        tm.addStep(input);

        TableOutputMeta tom = new TableOutputMeta();
        tom.setDatabaseMeta(dstDb);
        tom.setTablename(job.getDstTable());
        tom.setCommitSize(job.getBatchSize() == null ? 1024 : job.getBatchSize());
        tom.setTruncateTable("TRUNCATE".equalsIgnoreCase(job.getDstWriteMode()) || "REPLACE".equalsIgnoreCase(job.getDstWriteMode()));
        StepMeta output = new StepMeta("TableOutput", "TableOutput", tom);
        output.setLocation(320, 80);
        output.drawStep();
        tm.addStep(output);

        tm.addTransHop(new TransHopMeta(input, output));
        return XMLHandler.getXMLHeader() + tm.getXML();
    }


    /**
     * 解析分组字段映射 [{srcTable, maps:[{srcField,dstField}]}] → 该源表的目标列列表(全量并集, 缺失列 srcField 为空)
     * 无映射返回 null(走 SELECT *)
     */
    private List<Map<String, String>> parseFieldMappingFor(String fieldMapping, String srcTable) {
        if (StrUtil.isBlank(fieldMapping)) {
            return null;
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(fieldMapping);
            // 目标列并集(保序)
            LinkedHashSet<String> dstSet = new LinkedHashSet<>();
            Map<String, Map<String, String>> byTable = new LinkedHashMap<>();
            for (Object o : arr) {
                cn.hutool.json.JSONObject g = (cn.hutool.json.JSONObject) o;
                String t = g.getStr("srcTable", "");
                cn.hutool.json.JSONArray maps = g.getJSONArray("maps");
                if (maps == null) continue;
                for (Object mo : maps) {
                    cn.hutool.json.JSONObject m = (cn.hutool.json.JSONObject) mo;
                    String srcF = m.getStr("srcField", "");
                    String dstF = m.getStr("dstField", "");
                    if (StrUtil.isBlank(dstF)) continue;
                    dstSet.add(dstF);
                    if (StrUtil.isNotBlank(srcF)) {
                        byTable.computeIfAbsent(t, k -> new LinkedHashMap<>()).put(dstF, srcF);
                    }
                }
            }
            if (dstSet.isEmpty()) return null;
            Map<String, String> tblMap = byTable.getOrDefault(srcTable, new LinkedHashMap<>());
            List<Map<String, String>> out = new ArrayList<>();
            for (String dstF : dstSet) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("srcField", tblMap.getOrDefault(dstF, ""));
                m.put("dstField", dstF);
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 统计 srcList 中的有效源表数量
     */
    private int countSrcList(EtlJob job) {
        if (StrUtil.isBlank(job.getSrcList())) {
            return 0;
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(job.getSrcList());
            return (int) arr.stream().filter(o -> o instanceof cn.hutool.json.JSONObject
                && StrUtil.isNotBlank(((cn.hutool.json.JSONObject) o).getStr("srcTable"))).count();
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 可视化设计器 ====================

    private String buildTransXmlFromDesign(KettleDesignReq req) throws Exception {
        List<KettleDesignReq.DesignNode> ins = req.getNodes().stream()
            .filter(n -> "tableInput".equals(n.getType())).collect(java.util.stream.Collectors.toList());
        KettleDesignReq.DesignNode out = req.getNodes().stream()
            .filter(n -> "tableOutput".equals(n.getType())).findFirst().orElse(null);
        if (ins.isEmpty() || out == null) {
            throw new RuntimeException("转换设计至少需要一个「表输入」节点和一个「表输出」节点");
        }
        if (ins.size() > 1) {
            return buildTransMultiXmlFromDesign(req, ins, out);
        }
        EtlJob job = new EtlJob();
        job.setJobName(req.getJobName());
        job.setSrcConnId(longProp(ins.get(0), "srcConnId"));
        job.setSrcSchema(strProp(ins.get(0), "srcSchema"));
        job.setSrcTable(strProp(ins.get(0), "srcTable"));
        job.setSrcWhere(strProp(ins.get(0), "srcWhere"));
        job.setDstConnId(longProp(out, "dstConnId"));
        job.setDstSchema(strProp(out, "dstSchema"));
        job.setDstTable(strProp(out, "dstTable"));
        job.setDstWriteMode(strProp(out, "writeMode"));
        job.setBatchSize(intProp(out, "batchSize"));
        return buildTransXml(job);
    }

    /**
     * 多源表 → 单目标表
     * 实现: 单个表输入步骤, SQL 为各源 SELECT 的 UNION ALL 连接(要求各源字段结构一致)
     */
    private String buildTransMultiXmlFromDesign(KettleDesignReq req,
                                                List<KettleDesignReq.DesignNode> ins,
                                                KettleDesignReq.DesignNode out) throws Exception {
        return buildTransMultiXmlFromDesign(req, ins, out, null);
    }

    /**
     * 多源合并(支持字段映射列组合):
     * - 无映射: SELECT * UNION ALL(要求各源列一致)
     * - 有映射(分组 [{srcTable, maps:[{srcField,dstField}]}]): 每源 SELECT srcField AS dstField, 缺失目标列补 NULL AS dst
     */
    private String buildTransMultiXmlFromDesign(KettleDesignReq req,
                                                List<KettleDesignReq.DesignNode> ins,
                                                KettleDesignReq.DesignNode out,
                                                String fieldMapping) throws Exception {
        DbConn dst = dbConnService.getById(longProp(out, "dstConnId"));
        if (dst == null) {
            throw new RuntimeException("表输出节点未选择目标数据源");
        }
        String dstSchema = StrUtil.blankToDefault(strProp(out, "dstSchema"), dst.getDatabaseName());
        DatabaseMeta dstDb = toDatabaseMeta("dst_db", dst, dstSchema);

        TransMeta tm = new TransMeta();
        tm.setName("etl_" + StrUtil.nullToEmpty(req.getJobName()).replaceAll("\\s+", "_") + "_" + System.nanoTime());
        tm.addDatabase(dstDb);

        // 各源表独立 TableInput(支持不同数据源/库) + SortedMerge 多流合并(UNION ALL 语义, 跨库合并)
        List<StepMeta> inSteps = new ArrayList<>();
        Map<String, DatabaseMeta> addedDbs = new LinkedHashMap<>();
        String sortField = null;
        int i = 0;
        for (KettleDesignReq.DesignNode in : ins) {
            DbConn src = dbConnService.getById(longProp(in, "srcConnId"));
            if (src == null) {
                throw new RuntimeException("表输入节点[" + in.getId() + "] 未选择源数据源");
            }
            String srcSchema = StrUtil.blankToDefault(strProp(in, "srcSchema"), src.getDatabaseName());
            String srcTable = strProp(in, "srcTable");
            if (StrUtil.isBlank(srcTable)) {
                throw new RuntimeException("表输入节点[" + in.getId() + "] 未填写源表");
            }
            String dbName = "src_db_" + i;
            DatabaseMeta srcDb = addedDbs.get(dbName);
            if (srcDb == null) {
                srcDb = toDatabaseMeta(dbName, src, srcSchema);
                tm.addDatabase(srcDb);
                addedDbs.put(dbName, srcDb);
            }
            String where = strProp(in, "srcWhere");
            String srcTbl = quoteTable(srcSchema, srcTable, src);
            String q = "MYSQL".equalsIgnoreCase(src.getDbType()) ? "`" : "\"";
            // 字段映射列组合: 按分组映射生成列, 无映射保持 SELECT *
            List<Map<String, String>> maps = parseFieldMappingFor(fieldMapping, srcTable);
            if (maps != null && !maps.isEmpty() && sortField == null) {
                sortField = maps.get(0).get("dstField");
            }
            String sql;
            if (maps == null) {
                sql = "SELECT * FROM " + srcTbl + (StrUtil.isBlank(where) ? "" : " WHERE " + where);
            } else {
                StringBuilder sb = new StringBuilder("SELECT ");
                for (int mi = 0; mi < maps.size(); mi++) {
                    Map<String, String> m = maps.get(mi);
                    String srcF = m.get("srcField");
                    String dstF = m.get("dstField");
                    if (mi > 0) sb.append(", ");
                    if (StrUtil.isBlank(srcF)) {
                        sb.append("NULL AS ").append(q).append(dstF).append(q);
                    } else {
                        sb.append(q).append(srcF).append(q).append(" AS ").append(q).append(dstF).append(q);
                    }
                }
                sb.append(" FROM ").append(srcTbl);
                if (StrUtil.isNotBlank(where)) sb.append(" WHERE ").append(where);
                sql = sb.toString();
            }
            TableInputMeta tim = new TableInputMeta();
            tim.setDatabaseMeta(srcDb);
            tim.setSQL(sql);
            tim.setRowLimit("0");
            StepMeta input = new StepMeta("TableInput", "源表输入" + (i + 1), tim);
            input.setLocation(60, 60 + i * 80);
            input.drawStep();
            tm.addStep(input);
            inSteps.add(input);
            i++;
        }
        // SortedMerge 多流合并(UNION ALL 语义; 排序字段取第一个映射目标列, 各源列名一致)
        SortedMergeMeta smm = new SortedMergeMeta();
        if (sortField != null) {
            smm.setFieldName(new String[]{sortField});
            smm.setAscending(new boolean[]{true});
        }
        StepMeta merge = new StepMeta("SortedMerge", "多源合并", smm);
        merge.setLocation(280, 60);
        merge.drawStep();
        tm.addStep(merge);
        for (StepMeta inStep : inSteps) {
            tm.addTransHop(new TransHopMeta(inStep, merge));
        }

        TableOutputMeta tom = new TableOutputMeta();
        tom.setDatabaseMeta(dstDb);
        tom.setTablename(strProp(out, "dstTable"));
        Integer bs = intProp(out, "batchSize");
        tom.setCommitSize(bs == null || bs <= 0 ? 1024 : bs);
        tom.setTruncateTable("TRUNCATE".equalsIgnoreCase(strProp(out, "writeMode")) || "REPLACE".equalsIgnoreCase(strProp(out, "writeMode")));
        StepMeta output = new StepMeta("TableOutput", "TableOutput", tom);
        output.setLocation(460, 60);
        output.drawStep();
        tm.addStep(output);

        tm.addTransHop(new TransHopMeta(merge, output));
        return XMLHandler.getXMLHeader() + tm.getXML();
    }

    private String buildJobXmlFromDesign(KettleDesignReq req) throws Exception {
        JobMeta jm = new JobMeta();
        jm.setName("etl_job_" + StrUtil.nullToEmpty(req.getJobName()).replaceAll("\\s+", "_") + "_" + System.nanoTime());

        List<KettleDesignReq.DesignNode> ordered = orderNodes(req);
        Map<String, JobEntryCopy> copies = new LinkedHashMap<>();
        Map<String, DatabaseMeta> addedDbs = new LinkedHashMap<>();
        int idx = 0;
        for (KettleDesignReq.DesignNode node : ordered) {
            idx++;
            String type = StrUtil.nullToEmpty(node.getType());
            if (!"sql".equals(type)) {
                throw new RuntimeException("作业设计仅支持「SQL 执行」节点(数据同步请使用转换类型), 节点[" + node.getId() + "] 类型=" + type);
            }
            DbConn conn = dbConnService.getById(longProp(node, "connId"));
            if (conn == null) {
                throw new RuntimeException("SQL 节点[" + node.getId() + "] 未选择数据源");
            }
            String sql = strProp(node, "sql");
            if (StrUtil.isBlank(sql)) {
                throw new RuntimeException("SQL 节点[" + node.getId() + "] 未填写 SQL 语句");
            }
            String dbName = "db_" + node.getId();
            DatabaseMeta dbMeta = addedDbs.get(dbName);
            if (dbMeta == null) {
                dbMeta = toDatabaseMeta(dbName, conn, strProp(node, "schema"));
                jm.addDatabase(dbMeta);
                addedDbs.put(dbName, dbMeta);
            }
            JobEntrySQL jes = new JobEntrySQL();
            jes.setName("SQL-" + idx + "-" + StrUtil.nullToEmpty(strProp(node, "name")));
            jes.setDatabase(dbMeta);
            jes.setSQL(sql);
            JobEntryCopy jec = new JobEntryCopy(jes);
            jec.setLocation(node.getX() == null ? 80 + idx * 20 : node.getX(), node.getY() == null ? 80 : node.getY());
            jec.setDrawn(true);
            jm.addJobEntry(jec);
            copies.put(node.getId(), jec);
        }
        if (copies.isEmpty()) {
            throw new RuntimeException("作业设计至少需要一个「SQL 执行」节点");
        }
        // Start 入口作业项(Kettle 作业必须以 Start 为起点)
        JobEntrySpecial startEntry = new JobEntrySpecial("START", true, false);
        JobEntryCopy startCopy = new JobEntryCopy(startEntry);
        startCopy.setLocation(20, 80);
        startCopy.setDrawn(true);
        jm.addJobEntry(startCopy);
        // Start -> 第一个 SQL
        jm.addJobHop(new org.pentaho.di.job.JobHopMeta(startCopy, copies.get(ordered.get(0).getId())));
        // 作业项顺序连线(默认串行)
        for (int i = 0; i + 1 < ordered.size(); i++) {
            JobEntryCopy from = copies.get(ordered.get(i).getId());
            JobEntryCopy to = copies.get(ordered.get(i + 1).getId());
            if (from != null && to != null) {
                jm.addJobHop(new org.pentaho.di.job.JobHopMeta(from, to));
            }
        }
        return XMLHandler.getXMLHeader() + jm.getXML();
    }

    /**
     * 按连线拓扑排序节点(简易: 有 edges 时按边序, 无则按数组序)
     */
    private List<KettleDesignReq.DesignNode> orderNodes(KettleDesignReq req) {
        List<KettleDesignReq.DesignNode> result = new ArrayList<>();
        if (req.getEdges() != null && !req.getEdges().isEmpty()) {
            Map<String, KettleDesignReq.DesignNode> byId = new LinkedHashMap<>();
            for (KettleDesignReq.DesignNode n : req.getNodes()) {
                byId.put(n.getId(), n);
            }
            for (KettleDesignReq.DesignEdge e : req.getEdges()) {
                if (e.getFromNodeId() != null && byId.containsKey(e.getFromNodeId()) && !result.contains(byId.get(e.getFromNodeId()))) {
                    result.add(byId.get(e.getFromNodeId()));
                }
                if (e.getToNodeId() != null && byId.containsKey(e.getToNodeId()) && !result.contains(byId.get(e.getToNodeId()))) {
                    result.add(byId.get(e.getToNodeId()));
                }
            }
            for (KettleDesignReq.DesignNode n : req.getNodes()) {
                if (!result.contains(n)) {
                    result.add(n);
                }
            }
            return result;
        }
        return new ArrayList<>(req.getNodes());
    }

    // ==================== 私有工具 ====================

    private void cleanupTemp(File f) {
        if (f != null && f.exists()) {
            try {
                f.delete();
                if (f.getParentFile() != null) {
                    f.getParentFile().delete();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 统计实际写入行数: 优先取 TableOutput 步骤输出行数, 兜底取 Result 汇总
     */
    private long collectWrittenRows(Trans trans, Result result) {
        try {
            if (trans.getSteps() != null) {
                for (org.pentaho.di.trans.step.StepMetaDataCombi combi : trans.getSteps()) {
                    if (combi != null && combi.step != null && combi.stepMeta != null) {
                        String sid = null;
                        try {
                            sid = combi.stepMeta.getStepID();
                        } catch (Exception ignored) {
                        }
                        String nm = combi.stepMeta.getName();
                        if ("TableOutput".equals(sid) || "TableOutput".equals(nm) || (nm != null && nm.contains("输出"))) {
                            return combi.step.getLinesOutput();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取 TableOutput 行数失败: {}", e.getMessage());
        }
        return result == null ? 0 : result.getNrLinesOutput();
    }

    /**
     * 从 Kettle 日志缓存读取执行日志(转换/作业通用)
     */
    private String collectLogText(String channelId) {
        try {
            if (StrUtil.isBlank(channelId)) {
                return "";
            }
            return KettleLogStore.getAppender().getBuffer(channelId, false, 0, KettleLogStore.getLastBufferLineNr()).toString();
        } catch (Exception e) {
            log.warn("读取 Kettle 日志失败: {}", e.getMessage());
            return "";
        }
    }

    private DatabaseMeta toDatabaseMeta(String name, DbConn conn, String schema) {
        DatabaseMeta dm = new DatabaseMeta();
        dm.setName(name);
        dm.setDatabaseType(kettleType(conn.getDbType()));
        dm.setAccessType(DatabaseMeta.TYPE_ACCESS_NATIVE);
        dm.setHostname(conn.getHost());
        dm.setDBPort(String.valueOf(conn.getPort()));
        dm.setDBName(StrUtil.isBlank(schema) ? conn.getDatabaseName() : schema);
        dm.setUsername(conn.getUsername());
        dm.setPassword(DbConnCrypto.decrypt(conn.getPassword()));
        return dm;
    }

    private String kettleType(String dbType) {
        if (dbType == null) {
            return "MYSQL";
        }
        return switch (dbType.toUpperCase()) {
            case "SQLSERVER" -> "MSSQL";
            case "POSTGRESQL" -> "POSTGRESQL";
            case "ORACLE" -> "ORACLE";
            case "DM", "DAMENG" -> "GENERIC";
            default -> "MYSQL";
        };
    }

    private String quoteTable(String schema, String table, DbConn conn) {
        String q = conn != null && "MYSQL".equalsIgnoreCase(conn.getDbType()) ? "`" : "\"";
        String s = StrUtil.isBlank(schema) ? "" : q + schema + q + ".";
        return s + q + table + q;
    }

    private String quoteIdent(String name, DbConn conn) {
        String q = conn != null && "MYSQL".equalsIgnoreCase(conn.getDbType()) ? "`" : "\"";
        return q + name + q;
    }

    private String suffixWhere(EtlJob job) {
        if (StrUtil.isBlank(job.getSrcWhere())) {
            return "";
        }
        return " WHERE " + job.getSrcWhere();
    }

    /** 从 Job 日志解析"目标表输出"步骤写入行数 */
    private long parseJobWrittenRows(String logText) {
        if (StrUtil.isBlank(logText)) {
            return 0L;
        }
        String[] lines = logText.split("\\R");
        long total = 0L;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("W=(\\d+)");
        for (String l : lines) {
            if (l.contains("目标表输出") || l.contains("TableOutput")) {
                java.util.regex.Matcher m = p.matcher(l);
                if (m.find()) {
                    total += Long.parseLong(m.group(1));
                }
            }
        }
        return total;
    }

    private String tailError(String logText) {
        if (StrUtil.isBlank(logText)) {
            return "";
        }
        String[] lines = logText.split("\\R");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = lines.length - 1; i >= 0 && count < 15; i--) {
            String l = lines[i];
            if (l.contains("ERROR") || l.contains("Exception") || l.contains("错误") || l.contains("失败")) {
                sb.insert(0, l + "\n");
                count++;
            }
        }
        return count == 0 ? StrUtil.sub(logText, Math.max(0, logText.length() - 1000), logText.length()) : sb.toString();
    }

    // ---- 节点属性读取 ----

    private Long longProp(KettleDesignReq.DesignNode n, String key) {
        Object v = n.getProps() == null ? null : n.getProps().get(key);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intProp(KettleDesignReq.DesignNode n, String key) {
        Object v = n.getProps() == null ? null : n.getProps().get(key);
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String strProp(KettleDesignReq.DesignNode n, String key) {
        Object v = n.getProps() == null ? null : n.getProps().get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
