package pan.dromara.dbtool.service;

import java.util.List;
import java.util.Map;

/**
 * 连接配置导入服务(P4)
 * <p>评估导入 DBeaver connections.xml 或通用 JSON 连接配置, 提供预览、冲突处理与字段映射;
 * 密码默认不导入, 敏感凭据需在导入后手动填写。</p>
 *
 * @author doubao
 */
public interface IDbConnImportService {

    /**
     * 解析导入文件内容, 返回候选连接列表(只读评估)
     *
     * @param content  文件内容文本
     * @param fileName 文件名(用于格式识别)
     * @return {format, candidates:[{connName,dbType,host,port,databaseName,username,passwordPresent,issues[]}]}
     */
    Map<String, Object> preview(String content, String fileName);

    /**
     * 按选择项导入(创建连接)
     *
     * @param candidates 勾选的候选连接(至少含 connName/dbType/host/port/databaseName/username)
     * @return {imported:[...], skipped:[{connName,reason}]}
     */
    Map<String, Object> apply(List<Map<String, Object>> candidates);
}
