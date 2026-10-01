/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomSecuritySessionTest.java
 * @description 실제 Redis와 HTTP 보안 필터를 통해 Browser 세션 경계를 검증합니다.
 */
package org.jn.waitingroom.config;

import jakarta.servlet.http.Cookie;
import org.jn.waitingroom.api.WaitingRoomController;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.jn.waitingroom.service.WaitingClientAuthorization;
import org.jn.waitingroom.service.WaitingRoomService;
import org.jn.waitingroom.service.WaitingRoomRecoveryCoordinator;
import org.jn.waitingroom.service.RedisOperationalSafety;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OAuth2 OFF에서도 실제 Browser Controller의 세션 검증은 필수입니다. */
@Testcontainers
@SpringBootTest(classes = WaitingRoomSecuritySessionTest.TestApplication.class, properties = {
        "spring.docker.compose.enabled=false",
        "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=100",
        "WAITING_ROOM_RESERVATION_ENTRY_URL=https://service.example/entry",
        "WAITING_ROOM_OAUTH2_ENABLED=false"
})
@AutoConfigureMockMvc
@DisplayName("브라우저 세션 보안 테스트")
class WaitingRoomSecuritySessionTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    WaitingRoomService service;

    /** 검증 목적: 세션이 없으면 Bearer 헤더가 있어도 Bearer 챌린지 없이 401을 반환한다. */
    @DisplayName("세션이 없으면 Bearer 헤더가 있어도 Bearer 챌린지 없이 401을 반환한다")
    @Test
    void missingSessionIs401WithoutBearerChallengeEvenWithBearerHeader() throws Exception {
        mvc.perform(get("/api/v1/waiting-session")
                        .header("Authorization", "Bearer invalid")
                        .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WAITING_SESSION_INVALID"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    /** 검증 목적: 토큰 교환과 상태 조회는 보안 필터 체인 전체에서 세션만 사용한다. */
    @DisplayName("토큰 교환과 상태 조회는 보안 필터 체인 전체에서 세션만 사용한다")
    @Test
    void browserExchangeAndPollingUseOnlyTheSessionAcrossTheSecurityChain() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String url = service.register("reservation-service", requestId, "service-entry").waitingUrl();
        String body = "{\"serviceId\":\"reservation-service\",\"token\":\""
                + url.substring(url.indexOf("#token=") + 7) + "\"}";
        mvc.perform(post("/api/v1/waiting-session").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String setCookie = mvc.perform(post("/api/v1/waiting-session")
                        .header("Authorization", "Bearer invalid")
                        .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin")
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getHeader("Set-Cookie");
        Cookie cookie = new Cookie("__Host-waiting_session", setCookie.split(";", 2)[0].split("=", 2)[1]);
        mvc.perform(get("/api/v1/waiting-session").cookie(cookie)
                        .header("Authorization", "Bearer invalid")
                        .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin")
                        .header("Origin", "http://localhost"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.reservationRequestId").value(requestId));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(WaitingRoomProperties.class)
    @Import({WaitingRoomSecurityConfiguration.class, WaitingClientAuthorization.class,
            WaitingRoomController.class, WaitingRoomService.class, RedisWaitingRoomStore.class,
            WaitingRoomRecoveryCoordinator.class, WaitingRoomReadinessHealthIndicator.class, RedisOperationalSafety.class})
    static class TestApplication {
    }
}
