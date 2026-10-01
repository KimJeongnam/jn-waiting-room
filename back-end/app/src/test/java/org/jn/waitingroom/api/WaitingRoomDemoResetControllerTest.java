/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomDemoResetControllerTest.java
 * @description 실제 Redis와 보안 필터에서 데모 reset의 활성 조건과 동기 응답을 검증합니다.
 */
package org.jn.waitingroom.api;

import org.jn.waitingroom.config.WaitingRoomDemoProperties;
import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** reset 요청을 완료할 때만 실제 데모 데이터가 생성되어야 합니다. */
@Testcontainers
@DisplayName("데모 초기화 API 테스트")
class WaitingRoomDemoResetControllerTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestApplication.class)
            .withPropertyValues("spring.docker.compose.enabled=false",
                    "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=1",
                    "WAITING_ROOM_RESERVATION_ENTRY_URL=https://service.example/entry",
                    "waiting-room.security.oauth2.enabled=true", "waiting-room.demo.jwt-enabled=true",
                    "waiting-room.demo.active-users=1", "waiting-room.demo.waiting-users=2");

    /** 응답 전에 초기 상태를 완성하고 반복 reset에도 동일한 인원만 생성합니다. */
    @DisplayName("데모 초기화 요청은 Redis 테스트 데이터를 동기적으로 생성하고 204를 반환한다")
    @Test
    void demoResetSynchronouslySeedsRedisAndReturns204() {
        runner.withPropertyValues("spring.profiles.active=demo", "waiting-room.demo.seed-enabled=true",
                "spring.data.redis.host=" + REDIS.getHost(), "spring.data.redis.port=" + REDIS.getMappedPort(6379))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var redis = context.getBean(StringRedisTemplate.class);
                    try (var connection = redis.getConnectionFactory().getConnection()) {
                        connection.serverCommands().flushDb();
                    }
                    var mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean(FilterChainProxy.class)).build();
                    var tokenResponse = mvc.perform(post("/api/v1/demo/token")).andExpect(status().isOk())
                            .andReturn().getResponse();
                    String token = new ObjectMapper().readTree(tokenResponse.getContentAsString())
                            .get("accessToken").asText();
                    mvc.perform(post("/api/v1/demo/reset")).andExpect(status().isUnauthorized());
                    mvc.perform(post("/api/v1/demo/reset").header("Authorization", "Bearer invalid-token"))
                            .andExpect(status().isUnauthorized());
                    var claims = JwtClaimsSet.builder().issuer("jn-waiting-room-demo")
                            .audience(List.of("waiting-room-api")).issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60)).claim("scope", "waiting:enter").build();
                    String insufficientToken = context.getBean(JwtEncoder.class).encode(JwtEncoderParameters.from(
                            JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
                    mvc.perform(post("/api/v1/demo/reset").header("Authorization", "Bearer " + insufficientToken))
                            .andExpect(status().isForbidden());
                    assertThat(redis.opsForValue().get("waiting-request:{reservation-service}:demo-active-0001"))
                            .isNull();
                    for (int attempt = 0; attempt < 2; attempt++) {
                        mvc.perform(post("/api/v1/demo/reset").header("Authorization", "Bearer " + token))
                                .andExpect(status().isNoContent())
                                .andExpect(content().string(""));
                        assertThat(redis.opsForZSet().size("active-slots:{reservation-service}")).isEqualTo(1L);
                        assertThat(redis.opsForZSet().size("waiting:{reservation-service}")).isEqualTo(2L);
                        assertThat(redis.opsForValue().get("waiting-request:{reservation-service}:demo-active-0001"))
                                .isNotNull();
                        assertThat(redis.opsForValue().get("waiting-request:{reservation-service}:demo-waiting-00002"))
                                .isNotNull();
                    }
                });
    }

    /** 운영 profile 또는 seed 비활성 설정에서는 HTTP 경로 자체를 노출하지 않습니다. */
    @DisplayName("데모 프로필과 초기화 옵션이 모두 없으면 초기화 API는 404를 반환한다")
    @Test
    void resetIs404WithoutBothDemoProfileAndSeedFlag() {
        for (String[] properties : new String[][]{
                {"spring.profiles.active=demo", "waiting-room.demo.seed-enabled=false",
                        "waiting-room.security.oauth2.enabled=false"},
                {"spring.profiles.active=prod", "waiting-room.demo.seed-enabled=true",
                        "waiting-room.security.oauth2.enabled=false", "waiting-room.demo.jwt-enabled=false"},
                {"waiting-room.demo.seed-enabled=true", "waiting-room.security.oauth2.enabled=false",
                        "waiting-room.demo.jwt-enabled=false"},
                {"spring.profiles.active=demo", "waiting-room.demo.seed-enabled=true",
                        "waiting-room.security.oauth2.enabled=false"}}) {
            runner.withPropertyValues(properties).run(context -> {
                assertThat(context).hasNotFailed();
                var mvc = MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(context.getBean(FilterChainProxy.class)).build();
                mvc.perform(post("/api/v1/demo/reset")).andExpect(status().isNotFound());
            });
        }
    }

    /** 필요한 실제 Controller, Seeder와 보안 설정만 자동 구성과 함께 로드합니다. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties({WaitingRoomProperties.class, WaitingRoomDemoProperties.class})
    @ComponentScan(basePackages = "org.jn.waitingroom", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                    "org.jn.waitingroom.config.WaitingRoomSecurityConfiguration",
                    "org.jn.waitingroom.config.WaitingRoomDemoSecurityConfiguration",
                    "org.jn.waitingroom.api.WaitingRoomDemoTokenController",
                    "org.jn.waitingroom.api.WaitingRoomDemoResetController",
                    "org.jn.waitingroom.service.WaitingRoomDemoSeeder"}))
    @Import(RedisWaitingRoomStore.class)
    static class TestApplication { }
}
