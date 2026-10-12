package pan.dromara.dbtool.domain.bo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * A batch of row changes requested by the result grid.
 */
@Data
public class DbGridChangeBo {

    private Long connId;
    private String schema;
    private String table;
    private String sourceSql;
    private List<RowChange> changes;

    @Data
    public static class RowChange {
        private String operation;
        private Map<String, Object> values;
        private Map<String, Object> originalValues;
    }
}
