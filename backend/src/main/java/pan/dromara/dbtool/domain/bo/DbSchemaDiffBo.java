package pan.dromara.dbtool.domain.bo;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Source and target schema selection for a metadata comparison.
 */
@Data
public class DbSchemaDiffBo {

    @NotNull(message = "源连接不能为空")
    private Long sourceConnId;
    @NotNull(message = "目标连接不能为空")
    private Long targetConnId;
    @NotBlank(message = "源数据库不能为空")
    private String sourceSchema;
    @NotBlank(message = "目标数据库不能为空")
    private String targetSchema;
}
