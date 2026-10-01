package org.jn.waitingroom.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 대기 페이지의 직접 접근만 SPA로 연결하고 API와 정적 파일의 404를 보존합니다. */
@Configuration(proxyBeanMethods = false)
public class SpaForwardConfiguration implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/waiting").setViewName("forward:/index.html");
    }
}
