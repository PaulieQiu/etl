package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.service.IDbMetaService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据迁移 & Excel 导入 控制器
 * <p>跨库(MySQL/PostgreSQL/Oracle/DM/SQLServer)表数据迁移; Excel 多工作表导入到指定库(新建表/追加已有表)</p>
 *
 * @author doubao
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dbtool/transfer")
public class DataTransferController {

    private final IDbMetaService metaService;

    /**
     * 跨库表数据迁移(含表结构同步 + 数据复制)
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/migrate")
    public R<Map<String, Object>> migrate(@RequestBody Map<String, Object> body) {
        Long srcConnId = Long.valueOf(String.valueOf(body.get("srcConnId")));
        Long dstConnId = Long.valueOf(String.valueOf(body.get("dstConnId")));
        String srcSchema = (String) body.get("srcSchema");
        String srcTable = (String) body.get("srcTable");
        String dstSchema = (String) body.get("dstSchema");
        String dstTable = (String) body.get("dstTable");
        Boolean withStructure = (Boolean) body.get("withStructure");
        Boolean withData = (Boolean) body.get("withData");
        Boolean dropTarget = (Boolean) body.get("dropTarget");
        List<Map<String, String>> fieldMappings = null;
        Object fm = body.get("fieldMappings");
        if (fm instanceof List) {
            fieldMappings = new ArrayList<>();
            for (Object o : (List<?>) fm) {
                if (o instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    Map<String, String> mm = new LinkedHashMap<>();
                    Object s = m.get("src");
                    Object d = m.get("dst");
                    mm.put("src", s == null ? null : String.valueOf(s));
                    mm.put("dst", d == null ? null : String.valueOf(d));
                    fieldMappings.add(mm);
                }
            }
        }
        return R.ok(metaService.migrateTable(srcConnId, srcSchema, srcTable,
            dstConnId, dstSchema, dstTable, withStructure, withData, dropTarget, fieldMappings));
    }

    /**
     * 解析 Excel 工作表结构(sheet 名/行数/前 3 行预览), 供导入界面配置
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/excel-sheets")
    public R<List<Map<String, Object>>> excelSheets(@RequestParam("file") MultipartFile file) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            DataFormatter fmt = new DataFormatter();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet s = wb.getSheetAt(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sheetName", s.getSheetName());
                m.put("rows", s.getLastRowNum() + 1);
                List<List<Object>> preview = new ArrayList<>();
                for (int r = 0; r < Math.min(s.getLastRowNum() + 1, 3); r++) {
                    Row row = s.getRow(r);
                    List<Object> vals = new ArrayList<>();
                    if (row != null) {
                        for (int c = 0; c < row.getLastCellNum(); c++) {
                            Cell cell = row.getCell(c);
                            vals.add(cell == null ? null : fmt.formatCellValue(cell));
                        }
                    }
                    preview.add(vals);
                }
                m.put("preview", preview);
                list.add(m);
            }
        } catch (Exception e) {
            throw new ServiceException("Excel 解析失败: " + e.getMessage());
        }
        return R.ok(list);
    }

    /**
     * Excel 多工作表导入
     * <p>multipart: file + params(JSON: {connId, schema, sheets:[{sheetName, tableName, mode:create|append}]})</p>
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/excel-import")
    public R<Map<String, Object>> excelImport(@RequestParam("file") MultipartFile file,
                                              @RequestParam("params") String params) {
        cn.hutool.json.JSONObject p = JSONUtil.parseObj(params);
        Long connId = Long.valueOf(p.getStr("connId"));
        String schema = p.getStr("schema");
        cn.hutool.json.JSONArray arr = p.getJSONArray("sheets");
        List<Map<String, Object>> sheets = new ArrayList<>();
        if (arr != null) {
            for (Object o : arr) {
                sheets.add((Map<String, Object>) o);
            }
        }
        return R.ok(metaService.importExcel(connId, schema, sheets, file));
    }
}
