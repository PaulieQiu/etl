package org.dromara.common.log.annotation;

import org.dromara.common.log.enums.BusinessType;

import java.lang.annotation.*;

/**
 * 操作日志注解（独立运行时仅做标记，不强制接入日志切面；保留以兼容原 Controller 代码）。
 *
 * @author doubao
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    String title() default "";

    BusinessType businessType() default BusinessType.OTHER;

    boolean isSaveRequestData() default true;

    boolean isSaveResponseData() default true;
}
