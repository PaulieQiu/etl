package pan.dromara.dbtool;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ETL 数据集成独立启动类。
 *
 * <p>本项目基于 RuoYi-Vue-Plus 的 {@code ruoyi-etl} + {@code ruoyi-dbtool} 模块抽取，
 * 通过内置轻量 shim（{@code org.dromara.common.*}）替代对 RuoYi 全家桶的硬依赖，可独立启动。</p>
 *
 * @author doubao
 */
@SpringBootApplication
@MapperScan({"pan.dromara.dbtool.mapper", "pan.dromara.etl.mapper"})
public class DbToolApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbToolApplication.class, args);
    }
}
