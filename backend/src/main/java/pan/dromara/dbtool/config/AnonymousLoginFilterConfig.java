package pan.dromara.dbtool.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 独立运行模式：未登录请求自动以 userId=1 登录，避免 401。
 * <p>接入生产环境请删除此 Filter，改为真实登录流程。</p>
 *
 * @author doubao
 */
@Configuration
public class AnonymousLoginFilterConfig {

    @Bean
    public FilterRegistrationBean<Filter> anonymousLoginFilter() {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletRequest req = (HttpServletRequest) request;
                String path = req.getRequestURI();
                // 静态资源/错误页放行
                if (path == null || path.startsWith("/error") || path.startsWith("/favicon")) {
                    chain.doFilter(request, response);
                    return;
                }
                try {
                    if (!StpUtil.isLogin()) {
                        StpUtil.login(1L);
                    }
                } catch (Exception ignored) {
                }
                chain.doFilter(request, response);
            }
        });
        bean.addUrlPatterns("/*");
        bean.setOrder(-100);
        return bean;
    }
}
