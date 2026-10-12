package org.dromara.common.web.core;

import org.dromara.common.core.domain.R;

/**
 * 控制器基类（提供 RuoYi BaseController 的常用辅助方法）。
 *
 * @author doubao
 */
public abstract class BaseController {

    /**
     * 根据操作结果构造 R（对齐 RuoYi toAjax 语义）。
     */
    protected R<Void> toAjax(boolean result) {
        return result ? R.ok() : R.fail("操作失败");
    }

    protected R<Void> toAjax(int rows) {
        return rows > 0 ? R.ok() : R.fail("操作失败");
    }
}
