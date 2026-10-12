package org.dromara.common.core.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果包装(独立版轻量实现, 与 RuoYi 保持一致)
 *
 * @author doubao
 */
@Data
public class PageResult<T> implements Serializable {

    private List<T> rows;
    private long total;

    public static <T> PageResult<T> build(List<T> rows, long total) {
        PageResult<T> r = new PageResult<>();
        r.setRows(rows);
        r.setTotal(total);
        return r;
    }
}
