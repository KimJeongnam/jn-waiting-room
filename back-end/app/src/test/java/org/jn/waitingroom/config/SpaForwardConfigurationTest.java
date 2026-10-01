package org.jn.waitingroom.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPA 진입점만 forward하고 API와 누락된 파일의 404 계약을 유지합니다. */
@SpringBootTest(classes = SpaForwardConfigurationTest.TestApplication.class,
        properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("SPA 경로 전달 테스트")
class SpaForwardConfigurationTest {
    @Autowired
    private MockMvc mvc;

    /** 검증 목적: 쿼리 문자열과 무관하게 대기 페이지 진입을 index로 전달한다. */
    @DisplayName("쿼리 문자열과 무관하게 대기 페이지 진입을 index로 전달한다")
    @Test
    void waitingEntryForwardsToIndexRegardlessOfQuery() throws Exception {
        mvc.perform(get("/waiting").queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    /** 검증 목적: 없는 API 경로는 404로 유지한다. */
    @DisplayName("없는 API 경로는 404로 유지한다")
    @Test
    void missingApiRemainsNotFound() throws Exception {
        mvc.perform(get("/api/unknown")).andExpect(status().isNotFound());
    }

    /** 검증 목적: 없는 정적 파일 경로는 404로 유지한다. */
    @DisplayName("없는 정적 파일 경로는 404로 유지한다")
    @Test
    void missingStaticFileRemainsNotFound() throws Exception {
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "org.jn.waitingroom.config", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "org.jn.waitingroom.config.SpaForwardConfiguration"))
    static class TestApplication {
    }
}
