package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.service.IDbExportService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.domain.R;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 大结果导出 控制器(P2)
 * <p>POST /download 返回二进制流(前端 fetch blob 下载), 避免长 SQL 进入 URL。</p>
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/export")
public class DbExportController {

    private final IDbExportService exportService;

    public DbExportController(IDbExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 导出预览: 列信息 + 估算行数
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        Long connId = body == null ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String sql = body == null ? null : String.valueOf(body.get("sql"));
        if (connId == null || StrUtil.isBlank(sql)) {
            return R.fail("connId 与 sql 不能为空");
        }
        return R.ok(exportService.preview(connId, sql));
    }

    /**
     * 流式导出下载(CSV 带 UTF-8 BOM 兼容 Excel)
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@RequestBody Map<String, Object> body) {
        Long connId = body == null ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String sql = body == null ? null : String.valueOf(body.get("sql"));
        String format = body == null ? null : String.valueOf(body.get("format"));
        long maxRows = body == null || body.get("maxRows") == null ? 100000L : Long.parseLong(String.valueOf(body.get("maxRows")));
        String finalFormat = StrUtil.blankToDefault(format, "csv");
        if (connId == null || StrUtil.isBlank(sql)) {
            return ResponseEntity.badRequest().build();
        }
        String ext = "json".equals(finalFormat) ? "json" : "sql".equals(finalFormat) ? "sql" : "csv";
        String fileName = "export_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "." + ext;
        StreamingResponseBody stream = outputStream -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            if ("csv".equals(finalFormat)) {
                writer.write("\uFEFF");
            }
            exportService.exportTo(connId, sql, finalFormat, maxRows, writer);
            writer.flush();
        };
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(stream);
    }

}
