package org.dromara.common.core.exception;

import java.io.Serial;

/**
 * 业务异常（与 RuoYi ServiceException 行为对齐：消息会原样透传给前端）。
 *
 * @author doubao
 */
public class ServiceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
