package pan.dromara.etl.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pan.dromara.etl.util.EtlFileBridge;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ETL 文件管理(Excel 源/目标文件 上传/下载/列表)
 * <p>统一文件目录由 EtlFileBridge.fileDir() 管理(系统属性 etl.file.dir 可覆盖),
 * 任务 Excel 路径支持绝对路径或相对文件名(相对统一目录)。</p>
 *
 * @author doubao
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/etl/file")
public class EtlFileController extends BaseController {

    /**
     * 上传 Excel/CSV 文件到统一目录
     */
    @SaCheckPermission("etl:job:list")
    @PostMapping("/upload")
    public R<java.util.Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return R.fail("请选择要上传的文件");
        }
        String original = file.getOriginalFilename();
        String name = original == null ? "file_" + System.currentTimeMillis() : original;
        // 防路径穿越: 仅保留文件名
        name = new File(name).getName();
        File dir = new File(EtlFileBridge.fileDir());
        if (!dir.exists() && !dir.mkdirs()) {
            return R.fail("无法创建统一文件目录: " + dir.getAbsolutePath());
        }
        File target = new File(dir, name);
        // 不使用 transferTo(Jetty multipart 在不同环境可能不落盘), 手动流式写入
        try (java.io.InputStream in = file.getInputStream();
             java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("name", name);
        data.put("size", target.length());
        data.put("path", EtlFileBridge.resolveFile(name));
        return R.ok(data);
    }

    /**
     * 统一目录文件列表
     */
    @SaCheckPermission("etl:job:list")
    @GetMapping("/list")
    public R<List<java.util.Map<String, Object>>> list() {
        File dir = new File(EtlFileBridge.fileDir());
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                if (f.isFile()) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("name", f.getName());
                    m.put("size", f.length());
                    m.put("lastModified", f.lastModified());
                    out.add(m);
                }
            }
        }
        return R.ok(out);
    }

    /**
     * 读取 Excel/CSV 工作表信息(名称/总行数/总列数/前 30 行预览, 供工作表下拉与表头列名映射)
     */
    @SaCheckPermission("etl:job:list")
    @GetMapping("/sheets")
    public R<List<java.util.Map<String, Object>>> sheets(@RequestParam("path") String path) {
        if (StrUtil.isBlank(path)) {
            return R.fail("缺少文件路径参数 path");
        }
        File f = new File(EtlFileBridge.resolveFile(path.trim()));
        if (!f.exists() || !f.isFile()) {
            return R.fail("文件不存在: " + path);
        }
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        String lower = f.getName().toLowerCase();
        try {
            if (lower.endsWith(".csv")) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("name", f.getName());
                m.put("rows", 0);
                m.put("cols", 0);
                List<List<String>> preview = new ArrayList<>();
                int rows = 0, cols = 0;
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (rows < 30) {
                            String[] cells = line.split(",", -1);
                            List<String> row = new ArrayList<>();
                            for (String c : cells) {
                                row.add(c == null ? "" : c.trim());
                            }
                            cols = Math.max(cols, row.size());
                            preview.add(row);
                        }
                        rows++;
                    }
                }
                m.put("rows", rows);
                m.put("cols", cols);
                m.put("preview", preview);
                out.add(m);
            } else {
                try (Workbook wb = WorkbookFactory.create(f)) {
                    for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                        Sheet sh = wb.getSheetAt(i);
                        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("name", sh.getSheetName());
                        int lastRow = sh.getLastRowNum();
                        int rowCount = lastRow + 1;
                        int maxCol = 0;
                        List<List<String>> preview = new ArrayList<>();
                        int previewRows = Math.min(lastRow, 29);
                        for (int r = 0; r <= previewRows; r++) {
                            Row row = sh.getRow(r);
                            List<String> cells = new ArrayList<>();
                            int cellsEnd = row == null ? 0 : row.getLastCellNum();
                            maxCol = Math.max(maxCol, cellsEnd);
                            for (int c = 0; c < cellsEnd; c++) {
                                var cell = row.getCell(c);
                                cells.add(cell == null ? "" : String.valueOf(cell).trim());
                            }
                            preview.add(cells);
                        }
                        m.put("rows", rowCount);
                        m.put("cols", maxCol);
                        m.put("preview", preview);
                        out.add(m);
                    }
                }
            }
        } catch (Exception e) {
            return R.fail("读取工作表失败: " + e.getMessage());
        }
        return R.ok(out);
    }

    /**
     * 下载统一目录文件
     */
    @SaCheckPermission("etl:job:list")
    @GetMapping("/download")
    public void download(@RequestParam("name") String name, HttpServletResponse response) throws Exception {
        File f = new File(EtlFileBridge.resolveFile(new File(name).getName()));
        if (!f.exists() || !f.isFile()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("文件不存在: " + name);
            return;
        }
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition",
            "attachment; filename=" + java.net.URLEncoder.encode(f.getName(), StandardCharsets.UTF_8));
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f));
             OutputStream os = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        }
    }
}
