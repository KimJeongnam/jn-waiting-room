/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomSecurityConfigurationTest.java
 * @description Backend API 인증 모드의 HTTP 경계와 JWT 검증을 확인합니다.
 */
package org.jn.waitingroom.config;

import org.jn.waitingroom.service.WaitingClientAuthorization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 MVC 필터 체인을 통해 OAuth2 OFF의 무인증 동작을 확인합니다.
 */
@SpringBootTest(classes = WaitingRoomSecurityConfigurationTest.TestApplication.class, properties = {
        "spring.docker.compose.enabled=false",
        "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=100",
        "WAITING_ROOM_RESERVATION_ENTRY_URL=https://service.example/entry",
        "WAITING_ROOM_OAUTH2_ENABLED=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=waiting-room-api"
})
@AutoConfigureMockMvc
@DisplayName("OAuth 비활성 보안 구성 테스트")
class WaitingRoomSecurityConfigurationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    /** 검증 목적: OAuth를 끄면 JWT 없이 백엔드 API에 접근하고 디코더를 만들지 않는다. */
    @DisplayName("OAuth를 끄면 JWT 없이 백엔드 API에 접근하고 디코더를 만들지 않는다")
    @Test
    void oauth2OffAllowsBackendApiWithoutJwtAndCreatesNoDecoder() throws Exception {
        assertTrue(context.getBeansOfType(JwtDecoder.class).isEmpty());
        mvc.perform(post("/api/v1/waiting-requests"))
                .andExpect(status().isNoContent());
    }

    /** 검증 목적: OAuth를 끄면 외부 보안 관리 모드를 보고한다. */
    @DisplayName("OAuth를 끄면 외부 보안 관리 모드를 보고한다")
    @Test
    void oauth2OffPublishesUpstreamManagedMode() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backendAuthentication").value("UPSTREAM_MANAGED"));
    }

    /** Browser와 정적 요청 모두 Referrer가 외부로 전달되지 않도록 안내합니다. */
    @DisplayName("브라우저와 정적 파일 응답은 리퍼러 정보를 전송하지 않는다")
    @Test
    void browserAndStaticResponsesDoNotSendReferrers() throws Exception {
        for (String path : java.util.List.of("/app/waiting", "/api/v1/waiting-session", "/assets/missing.js")) {
            mvc.perform(get(path)).andExpect(header().string("Referrer-Policy", "no-referrer"));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(WaitingRoomProperties.class)
    @ComponentScan(basePackages = "org.jn.waitingroom.config", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "org.jn.waitingroom.config.WaitingRoomSecurityConfiguration"))
    @Import({TestEndpoints.class, WaitingClientAuthorization.class})
    static class TestApplication {
    }

    @RestController
    static class TestEndpoints {
        private final WaitingClientAuthorization authorization;

        TestEndpoints(WaitingClientAuthorization authorization) {
            this.authorization = authorization;
        }

        @PostMapping("/api/v1/waiting-requests")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void create(@RequestParam(required = false) String serviceId, Authentication authentication) {
            if (serviceId != null) {
                authorization.requireServiceOwnership(authentication, serviceId);
            }
        }

        @PostMapping({
                "/api/v1/waiting-requests/{id}/access-tokens",
                "/api/v1/waiting-requests/{id}/enter",
                "/api/v1/waiting-requests/{id}/complete",
                "/api/v1/waiting-requests/{id}/cancel",
                "/api/v1/waiting-requests/{id}/unknown",
                "/api/v1/demo/token"
        })
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void action() {
        }

        @GetMapping("/api/v1/waiting-requests")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void unregisteredMethod() {
        }

        @GetMapping({"/api/v1/waiting-session", "/app/waiting"})
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void browser() {
        }
    }
}
