package com.footpred.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：跨域与前端路由回退。
 *
 * <p>前端打进 {@code static/} 后与后端同源，正常情况下不触发跨域。但有两种
 * 场景需要 CORS：
 * <ul>
 *   <li>前端在设置里把「数据连接」指向另一个地址（例如开发机指向虚拟机）；</li>
 *   <li>用 Live Server 等工具单独跑 {@code dist/} 调试。</li>
 * </ul>
 *
 * <p>前端用 hash 路由（{@code #/dashboard}），服务端不需要为每个页面注册映射；
 * 但直接访问 {@code /} 时要落到 {@code index.html}，这一点由 Spring Boot 的
 * 静态资源默认行为覆盖，无需额外配置。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // allowedOriginPatterns 而非 allowedOrigins：后者不允许通配符与
                // credentials 同时使用，演示时经常需要从任意主机访问。
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 访问根路径时显式转发到前端入口，避免在部分容器配置下落到 404。
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
