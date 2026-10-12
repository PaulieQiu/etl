package pan.dromara.etl.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Kettle 可视化设计请求体
 * 前端画布节点/连线 → 后端生成 Kettle 转换/作业 XML
 *
 * @author doubao
 */
@Data
public class KettleDesignReq implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Kettle 任务类型: TRANS 转换 / JOB 作业
     */
    private String kettleType;

    /**
     * 任务名称(用于 XML name)
     */
    private String jobName;

    /**
     * 画布节点列表
     */
    private List<DesignNode> nodes;

    /**
     * 画布连线列表 (fromNodeId -> toNodeId, 串行顺序)
     */
    private List<DesignEdge> edges;

    @Data
    public static class DesignNode implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 节点ID
         */
        private String id;

        /**
         * 节点类型: tableInput / tableOutput / sql / trans
         */
        private String type;

        /**
         * 画布坐标
         */
        private Integer x;
        private Integer y;

        /**
         * 节点属性
         */
        private Map<String, Object> props;
    }

    @Data
    public static class DesignEdge implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String id;
        private String fromNodeId;
        private String toNodeId;
    }
}
