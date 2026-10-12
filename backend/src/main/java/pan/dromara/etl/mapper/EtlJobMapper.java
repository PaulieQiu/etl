package pan.dromara.etl.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import pan.dromara.etl.domain.EtlJob;

/**
 * ETL任务 数据层
 *
 * @author doubao
 */
@Mapper
public interface EtlJobMapper extends BaseMapperPlus<EtlJob, EtlJob> {
}
