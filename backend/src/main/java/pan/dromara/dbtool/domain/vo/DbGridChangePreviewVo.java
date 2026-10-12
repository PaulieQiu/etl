package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * Reviewable preview of prepared result-grid updates.
 */
@Data
public class DbGridChangePreviewVo {

    private String confirmationToken;
    private Integer changeCount;
    private List<String> statements;
}
