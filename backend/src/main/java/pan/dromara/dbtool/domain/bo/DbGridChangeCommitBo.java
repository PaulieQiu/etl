package pan.dromara.dbtool.domain.bo;

import lombok.Data;

/**
 * Commit request containing a reviewed grid change batch and its approval token.
 */
@Data
public class DbGridChangeCommitBo {

    private DbGridChangeBo changes;
    private String confirmationToken;
}
