package pan.dromara.etl.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import pan.dromara.etl.domain.EtlJobLog;

/**
 * ETL任务执行日志 数据层
 *
 * @author doubao
 */
@Mapper
public interface EtlJobLogMapper extends BaseMapperPlus<EtlJobLog, EtlJobLog> {
}
