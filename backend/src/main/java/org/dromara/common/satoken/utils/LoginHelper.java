package org.dromara.common.satoken.utils;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 登录用户助手（独立运行时统一从 Sa-Token 取当前登录用户 ID）。
 *
 * @author doubao
 */
public class LoginHelper {

    public static Long getUserId() {
        try {
            Object id = StpUtil.getLoginId();
            if (id instanceof Long l) {
                return l;
            }
            if (id instanceof Number n) {
                return n.longValue();
            }
            return Long.valueOf(String.valueOf(id));
        } catch (Exception e) {
            // 未登录或匿名访问时回退到 1L（与默认管理员对齐）
            return 1L;
        }
    }

    public static String getUsername() {
        try {
            return StpUtil.getLoginIdAsString();
        } catch (Exception e) {
            return "anonymous";
        }
    }
}
