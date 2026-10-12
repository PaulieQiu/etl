package pan.dromara.dbtool.core;

import pan.dromara.dbtool.domain.bo.DbStructureChangeBo;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.dromara.common.core.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Creates canonical approval payloads for destructive structure operations.
 */
public final class DbStructureChangeConfirmation {

    private DbStructureChangeConfirmation() {
    }

    public static String canonicalPayload(DbStructureChangeBo request) {
        if (request == null || request.getConnId() == null || StrUtil.isBlank(request.getTable())) {
            throw new ServiceException("结构变更确认请求不完整");
        }
        String operation = StrUtil.nullToEmpty(request.getOperation()).toUpperCase(Locale.ROOT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation);
        payload.put("schema", StrUtil.nullToEmpty(request.getSchema()));
        payload.put("table", request.getTable());
        if ("DROP_COLUMN".equals(operation)) {
            if (StrUtil.isBlank(request.getColumnName())) {
                throw new ServiceException("删除列确认请求缺少列名");
            }
            payload.put("columnName", request.getColumnName());
        } else if (!"DROP_TABLE".equals(operation)) {
            throw new ServiceException("不支持该结构变更确认操作");
        }
        return "STRUCTURE:" + JSONUtil.toJsonStr(payload);
    }
}
