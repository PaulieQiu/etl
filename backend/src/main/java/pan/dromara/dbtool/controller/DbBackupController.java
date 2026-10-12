package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.DbOperationConfirmationService;
import pan.dromara.dbtool.service.IDbBackupService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.domain.R;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 数据库备份/恢复 控制器(P4)
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/backup")
public class DbBackupController {

    private final IDbBackupService backupService;
    private final IDbMetaService metaService;
    private final DbOperationConfirmationService confirmationService;

    public DbBackupController(IDbBackupService backupService, IDbMetaService metaService,
                              DbOperationConfirmationService confirmationService) {
        this.backupService = backupService;
        this.metaService = metaService;
        this.confirmationService = confirmationService;
    }

    /** 启动备份任务 */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/dump")
    public R<Long> dump(@RequestBody Map<String, Object> body) {
        Long connId = body == null || body.get("connId") == null ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String schema = body == null ? null : String.valueOf(body.get("schema"));
        return R.ok(backupService.startDump(connId, schema, StpUtil.getLoginIdAsString()));
    }

    /** 恢复确认凭证(一次性) */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/confirmation")
    public R<String> confirmation(@RequestBody Map<String, Object> body) {
        Long connId = body == null || body.get("connId") == null
            ? null : Long.valueOf(String.valueOf(body.get("connId")));
        if (connId == null) {
            return R.fail("connId 不能为空");
        }
        DbAccessPolicy.requireWritable(metaService.getConn(connId));
        String payload = body.get("payload") == null ? "" : String.valueOf(body.get("payload"));
        return R.ok(confirmationService.issue(connId, payload, StpUtil.getLoginIdAsString()));
    }

    /** 启动恢复任务 */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/restore")
    public R<Long> restore(@RequestBody Map<String, Object> body) {
        Long connId = body == null || body.get("connId") == null ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String schema = body == null ? null : String.valueOf(body.get("schema"));
        String filePath = body == null ? null : String.valueOf(body.get("filePath"));
        String token = body == null || body.get("confirmationToken") == null
            ? null : String.valueOf(body.get("confirmationToken"));
        return R.ok(backupService.startRestore(connId, schema, filePath, StpUtil.getLoginIdAsString(), token));
    }

    /** 任务列表 */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/jobs")
    public R<Map<String, Object>> jobs(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "10") Integer pageSize,
                                       @RequestParam(required = false) Long connId) {
        return R.ok(backupService.listJobs(pageNum, pageSize, connId, StpUtil.getLoginIdAsString()));
    }

    /** 取消任务 */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/cancel/{id}")
    public R<Boolean> cancel(@PathVariable Long id) {
        return R.ok(backupService.cancelJob(id, StpUtil.getLoginIdAsString()));
    }

    /** 下载备份文件 */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/download/{id}")
    public ResponseEntity<?> download(@PathVariable Long id) {
        Map<String, Object> job = backupService.getJob(id, StpUtil.getLoginIdAsString());
        String filePath = job.get("filePath") == null ? null : String.valueOf(job.get("filePath"));
        if (filePath == null || !Files.isRegularFile(Paths.get(filePath))) {
            return ResponseEntity.badRequest().body("备份文件不存在");
        }
        try {
            Path path = Paths.get(filePath);
            File file = path.toFile();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/sql"));
            headers.setContentDispositionFormData("attachment", file.getName());
            return ResponseEntity.ok()
                .headers(headers)
                .contentLength(file.length())
                .body(new InputStreamResource(new FileInputStream(file)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("文件读取失败: " + e.getMessage());
        }
    }
}
