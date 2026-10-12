package pan.dromara.dbtool.core;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 数据库类型信息
 *
 * @author doubao
 */
@Data
@NoArgsConstructor
public class DbTypeInfo {

    /**
     * 能力清单(P0 能力矩阵, 由 DbTypeRegistry.capabilitiesOf 推断, 不参与注册构造器):
     * connect/query/browse/edit/ddl/diff/dialect/verified
     */
    private List<String> capabilities;

    /**
     * 全参构造器(与注册调用保持一致, 12 参)
     */
    public DbTypeInfo(String code, String name, String category, Boolean hot, String icon, Integer defaultPort,
                      String driverClass, String artifact, String defaultVersion, List<String> versions,
                      String urlTemplate, Boolean builtin) {
        this.code = code;
        this.name = name;
        this.category = category;
        this.hot = hot;
        this.icon = icon;
        this.defaultPort = defaultPort;
        this.driverClass = driverClass;
        this.artifact = artifact;
        this.defaultVersion = defaultVersion;
        this.versions = versions;
        this.urlTemplate = urlTemplate;
        this.builtin = builtin;
    }

    /**
     * 类型编码(大写, 如 MYSQL)
     */
    private String code;

    /**
     * 显示名称(如 MySQL)
     */
    private String name;

    /**
     * 分类: SQL/NoSQL/分析型/时间序列/嵌入式/大数据/全文搜索/图数据库
     */
    private String category;

    /**
     * 是否热门
     */
    private Boolean hot;

    /**
     * 图标关键字(前端映射 SVG)
     */
    private String icon;

    /**
     * 默认端口
     */
    private Integer defaultPort;

    /**
     * 驱动类名
     */
    private String driverClass;

    /**
     * Maven 坐标(自动下载用), 如 com.mysql:mysql-connector-j
     */
    private String artifact;

    /**
     * 默认驱动版本
     */
    private String defaultVersion;

    /**
     * 支持的版本列表(供前端选择)
     */
    private List<String> versions;

    /**
     * JDBC URL 模板 {host} {port} {db}
     */
    private String urlTemplate;

    /**
     * 驱动是否内置在应用 classpath 中
     */
    private Boolean builtin;

    /**
     * 构建 JDBC URL
     *
     * @param host   主机
     * @param port   端口
     * @param db     数据库名(可为空)
     * @param params 扩展参数(可选, 追加到 url)
     */
    public String buildUrl(String host, int port, String db, String params) {
        String url = urlTemplate
            .replace("{host}", host)
            .replace("{port}", String.valueOf(port))
            .replace("{db}", StrUtil.blankToDefault(db, ""));
        if (StrUtil.isNotBlank(params)) {
            String sep = url.contains("?") ? "&" : "?";
            url = url + sep + params.trim().replaceAll("^[?&]+", "");
        }
        return url;
    }
}
