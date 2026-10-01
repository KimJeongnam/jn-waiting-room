/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomDemoSecurityConfigurationTest.java
 * @description 데모 JWT의 활성 조건과 실제 Bearer API 경계를 검증합니다.
 */
package org.jn.waitingroom.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.jn.waitingroom.api.WaitingRoomController;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.jn.waitingroom.service.WaitingClientAuthorization;
import org.jn.waitingroom.service.WaitingRoomService;
import org.jn.waitingroom.service.WaitingRoomRecoveryCoordinator;
import org.jn.waitingroom.service.RedisOperationalSafety;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.web.FilterChainProxy;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** 실제 필터와 Controller를 통과하는 데모 인증 계약입니다. */
@Testcontainers
@DisplayName("데모 JWT 보안 구성 테스트")
class WaitingRoomDemoSecurityConfigurationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestApplication.class)
            .withPropertyValues("spring.docker.compose.enabled=false",
                    "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=100",
                    "WAITING_ROOM_RESERVATION_ENTRY_URL=https://service.example/demo/admitted",
                    "WAITING_ROOM_OAUTH2_ENABLED=false");

    /** 검증 목적: 데모 JWT는 필수 클레임을 서명하고 등록 입장 완료를 허용한다. */
    @DisplayName("데모 JWT는 필수 클레임을 서명하고 등록 입장 완료를 허용한다")
    @Test
    void demoJwtSignsRequiredClaimsAndAllowsCreateEnterComplete() {
        runner.withPropertyValues("spring.profiles.active=demo", "waiting-room.demo.jwt-enabled=true",
                "waiting-room.security.oauth2.enabled=true", "spring.data.redis.host=" + REDIS.getHost(),
                "spring.data.redis.port=" + REDIS.getMappedPort(6379)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class).hasSingleBean(JwtEncoder.class);
            // ContextRunner는 Boot ApplicationRunner를 실행하지 않으므로 최초 복구를 명시합니다.
            context.getBean(WaitingRoomRecoveryCoordinator.class).run(null);
            assertThat(((RSAPublicKey) context.getBean(KeyPair.class).getPublic()).getModulus().bitLength()).isGreaterThanOrEqualTo(2048);
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            Logger logger = (Logger) LoggerFactory.getLogger("org.springframework.web");
            Level previousLevel = logger.getLevel();
            boolean previousAdditive = logger.isAdditive();
            var appender = new ListAppender<ILoggingEvent>();
            appender.setContext(logger.getLoggerContext());
            appender.start();
            logger.addAppender(appender);
            logger.setAdditive(false);
            logger.setLevel(Level.TRACE);
            tools.jackson.databind.JsonNode json;
            try {
                var response = mvc.perform(post("/api/v1/demo/token")).andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
                json = new ObjectMapper().readTree(response.getContentAsString());
                String issued = json.get("accessToken").asText();
                assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().contains("Writing"));
                assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(issued));
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(previousLevel);
                logger.setAdditive(previousAdditive);
                appender.stop();
            }
            assertThat(json.size()).isEqualTo(2);
            String token = json.get("accessToken").asText();
            var jwt = context.getBean(JwtDecoder.class).decode(token);
            assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
            assertThat(jwt.getClaimAsString("iss")).isEqualTo("jn-waiting-room-demo");
            assertThat(jwt.getAudience()).containsExactly("waiting-room-api");
            assertThat(jwt.getClaimAsString("client_id")).isEqualTo("demo-reservation-client");
            assertThat(jwt.getClaimAsString("scope")).isEqualTo("waiting:create waiting:token:issue waiting:enter waiting:complete waiting:cancel");
            assertThat(jwt.getNotBefore()).isEqualTo(jwt.getIssuedAt());
            assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(12));
            assertThat(json.get("expiresAt").asText()).isEqualTo(jwt.getExpiresAt().toString());
            mvc.perform(post("/api/v1/waiting-requests").header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "demo-flow").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"serviceId\":\"reservation-service\",\"redirectTargetId\":\"service-entry\"}"))
                    .andExpect(status().isCreated());
            context.getBean(RedisWaitingRoomStore.class).admitNext("reservation-service", 100, Duration.ofMinutes(2),
                    Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1));
            for (String action : new String[]{"enter", "complete"}) {
                mvc.perform(post("/api/v1/waiting-requests/demo-flow/" + action)
                                .queryParam("serviceId", "reservation-service").header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENTERED"));
            }
            assertThat(context.getBean(RedisWaitingRoomStore.class).find("reservation-service", "demo-flow").completedAt()).isNotNull();
        });
    }

    /** 검증 목적: 애플리케이션 재시작은 데모 키를 교체하고 이전 토큰을 거절한다. */
    @DisplayName("애플리케이션 재시작은 데모 키를 교체하고 이전 토큰을 거절한다")
    @Test
    void restartingContextReplacesTheKeyAndRejectsThePreviousToken() {
        var firstToken = new java.util.concurrent.atomic.AtomicReference<String>();
        var demo = runner.withPropertyValues("spring.profiles.active=demo", "waiting-room.demo.jwt-enabled=true");
        demo.run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
            var response = mvc.perform(post("/api/v1/demo/token")).andExpect(status().isOk()).andReturn().getResponse();
            firstToken.set(new ObjectMapper().readTree(response.getContentAsString()).get("accessToken").asText());
        });
        demo.run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(JwtDecoder.class).decode(firstToken.get())).isInstanceOf(JwtException.class);
        });
    }

    /** 검증 목적: 데모 프로필 또는 옵션이 없으면 키를 만들지 않고 발급 API는 404를 반환한다. */
    @DisplayName("데모 프로필 또는 옵션이 없으면 키를 만들지 않고 발급 API는 404를 반환한다")
    @Test
    void missingProfileOrFlagCreatesNoDemoKeyAndEndpointIs404() {
        for (String[] properties : new String[][]{
                {"spring.profiles.active=demo", "waiting-room.demo.jwt-enabled=false"},
                {"waiting-room.demo.jwt-enabled=true"}}) {
            runner.withPropertyValues(properties).run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(KeyPair.class).doesNotHaveBean(JwtEncoder.class);
                var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
                mvc.perform(post("/api/v1/demo/token")).andExpect(status().isNotFound());
            });
        }
    }

    /** 검증 목적: 운영 프로필과 데모 프로필은 동시에 시작할 수 없다. */
    @DisplayName("운영 프로필과 데모 프로필은 동시에 시작할 수 없다")
    @Test
    void productionAndDemoProfilesCannotStartTogether() {
        runner.withPropertyValues("spring.profiles.active=prod,demo").run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties({WaitingRoomProperties.class, WaitingRoomDemoProperties.class})
    @ComponentScan(basePackages = {"org.jn.waitingroom.config", "org.jn.waitingroom.api"}, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                    "org.jn.waitingroom.config.WaitingRoom(SecurityConfiguration|DemoSecurityConfiguration|ConfigurationValidation)",
                    "org.jn.waitingroom.api.WaitingRoomDemoTokenController"}))
    @Import({WaitingRoomController.class, WaitingRoomService.class, RedisWaitingRoomStore.class, WaitingClientAuthorization.class,
            WaitingRoomRecoveryCoordinator.class, WaitingRoomReadinessHealthIndicator.class, RedisOperationalSafety.class})
    static class TestApplication { }
}
