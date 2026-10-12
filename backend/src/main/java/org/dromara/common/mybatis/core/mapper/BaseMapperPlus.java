package org.dromara.common.mybatis.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.io.Serializable;
import java.util.Collection;

/**
 * 与 RuoYi BaseMapperPlus 对齐的轻量 Mapper 基类。
 *
 * @param <T> 实体类型
 * @param <V> VO 类型（保留以兼容 RuoYi 签名，实际不强制使用）
 * @author doubao
 */
public interface BaseMapperPlus<T, V> extends BaseMapper<T> {

    /**
     * 批量逻辑/物理删除（对齐 RuoYi deleteByIds 语义）。
     */
    default int deleteByIds(@Param("ids") Collection<? extends Serializable> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Serializable id : ids) {
            n += this.deleteById(id);
        }
        return n;
    }
}
