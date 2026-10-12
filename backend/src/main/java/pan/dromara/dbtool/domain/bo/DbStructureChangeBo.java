package pan.dromara.dbtool.domain.bo;

import lombok.Data;

/**
 * A structure operation requiring a short-lived, single-use approval.
 */
@Data
public class DbStructureChangeBo {

    private Long connId;
    private String operation;
    private String schema;
    private String table;
    private String columnName;
    private String confirmationToken;
}
