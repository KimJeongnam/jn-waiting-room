/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomRecoveryStartupTest.java
 * @description 전체 애플리케이션의 데모 기동 및 Redis 장애 기동과 readiness를 검증합니다.
 */
package org.jn.waitingroom.config;

import org.jn.waitingroom.WaitingRoomApplication;
import org.jn.waitingroom.service.WaitingRoomRecoveryCoordinator;
import org.jn.waitingroom.service.RedisOperationalSafety;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DisplayName("애플리케이션 시작과 복구 테스트")
class WaitingRoomRecoveryStartupTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withCommand("redis-server", "--maxmemory", "64mb", "--maxmemory-policy", "noeviction")
            .withExposedPorts(6379);

    /** 검증 목적: 정상 Redis 환경의 운영 모드는 두 준비 상태가 모두 정상으로 시작한다. */
    @DisplayName("정상 Redis 환경의 운영 모드는 두 준비 상태가 모두 정상으로 시작한다")
    @Test
    void healthyProductionStartsWithBothReadinessComponentsReady() throws Exception {
        try (var context = SpringApplication.run(WaitingRoomApplication.class,
                arguments(REDIS.getMappedPort(6379), "--spring.profiles.active=prod"))) {
            assertTrue(context.getBean(WaitingRoomRecoveryCoordinator.class).isAvailable());
            assertTrue(context.getBean(RedisOperationalSafety.class).isReady());
            var response = get(context, "/actuator/health/readiness");
            assertEquals(200, response.statusCode());
            var health = new ObjectMapper().readTree(response.body());
            assertEquals("UP", health.get("status").asText());
            var details = health.get("components").get("waitingRoomReadiness").get("details");
            assertTrue(details.get("recoveryReady").asBoolean());
            assertTrue(details.get("redisSafetyReady").asBoolean());
        }
    }

    /** 검증 목적: 정상 Redis 환경의 데모 모드는 자동 초기화하지 않고 요청 후 데이터를 만든다. */
    @DisplayName("정상 Redis 환경의 데모 모드는 자동 초기화하지 않고 요청 후 데이터를 만든다")
    @Test
    void healthyDemoStartsWithoutSeedAndResetCreatesDemoStateAfterRecovery() throws Exception {
        var connectionFactory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        try {
            try (var redisConnection = connectionFactory.getConnection()) {
                redisConnection.serverCommands().flushDb();
            }
        } finally {
            connectionFactory.destroy();
        }
        try (var context = SpringApplication.run(WaitingRoomApplication.class,
                arguments(REDIS.getMappedPort(6379), "--spring.profiles.active=demo",
                        "--waiting-room.security.oauth2.enabled=true", "--waiting-room.demo.jwt-enabled=true",
                        "--waiting-room.demo.seed-enabled=true", "--waiting-room.demo.active-users=1",
                        "--waiting-room.demo.waiting-users=1"))) {
            assertTrue(context.getBean(WaitingRoomRecoveryCoordinator.class).isAvailable());
            assertTrue(context.getBean("waitingRoomRedisMessageListenerContainer", RedisMessageListenerContainer.class).isRunning());
            var redis = context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
            assertNull(redis.opsForValue().get("waiting-request:{reservation-service}:demo-active-0001"));
            assertNull(redis.opsForValue().get("waiting-request:{reservation-service}:demo-waiting-00001"));
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var tokenResponse = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/demo/token"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, tokenResponse.statusCode());
            String token = new ObjectMapper().readTree(tokenResponse.body()).get("accessToken").asText();
            var reset = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/demo/reset"))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, reset.statusCode());
            assertNotNull(redis.opsForValue().get("waiting-request:{reservation-service}:demo-active-0001"));
            assertNotNull(redis.opsForValue().get("waiting-request:{reservation-service}:demo-waiting-00001"));
            var response = get(context, "/actuator/health/readiness");
            assertEquals(200, response.statusCode());
            var health = new ObjectMapper().readTree(response.body());
            assertEquals("UP", health.get("status").asText());
            assertEquals("UP", health.get("components").get("redis").get("status").asText());
            assertEquals("UP", health.get("components").get("waitingRoomReadiness").get("status").asText());
            assertEquals(200, get(context, "/actuator/info").statusCode());
        }
    }

    /** Redis 연결 실패가 Spring lifecycle을 종료시키면 이 테스트는 context 생성에서 실패합니다. */
    @DisplayName("Redis 중단 시 서버는 살아 있고 준비 상태와 API는 제한된 장애 응답을 낸다")
    @Test
    void offlineRedisKeepsTheServerAliveWithReadinessDownAndBoundedApi503() throws Exception {
        int offlinePort;
        try (var socket = new ServerSocket(0)) {
            offlinePort = socket.getLocalPort();
        }
        try (var context = SpringApplication.run(WaitingRoomApplication.class,
                arguments(offlinePort, "--spring.profiles.active=prod"))) {
            assertFalse(context.getBean(WaitingRoomRecoveryCoordinator.class).isAvailable());
            assertFalse(context.getBean(RedisOperationalSafety.class).isReady());
            assertFalse(context.getBean("waitingRoomRedisMessageListenerContainer", RedisMessageListenerContainer.class).isRunning());
            var readiness = get(context, "/actuator/health/readiness");
            assertEquals(503, readiness.statusCode());
            var health = new ObjectMapper().readTree(readiness.body());
            assertEquals("OUT_OF_SERVICE", health.get("components").get("waitingRoomReadiness").get("status").asText());
            assertEquals("DOWN", health.get("components").get("redis").get("status").asText());
            assertEquals(200, get(context, "/actuator/health/liveness").statusCode());
            assertEquals(200, get(context, "/actuator/info").statusCode());
            var api = get(context, "/api/v1/waiting-session");
            assertEquals(503, api.statusCode());
            assertEquals("1", api.headers().firstValue("Retry-After").orElseThrow());
            var error = new ObjectMapper().readTree(api.body());
            assertEquals("SERVICE_UNAVAILABLE", error.get("code").asText());
            assertTrue(error.get("retryable").asBoolean());
        }
    }

    private String[] arguments(int redisPort, String... additional) {
        var arguments = new java.util.ArrayList<>(java.util.List.of(
                "--server.port=0", "--spring.docker.compose.enabled=false",
                "--spring.data.redis.host=localhost", "--spring.data.redis.port=" + redisPort,
                "--spring.data.redis.connect-timeout=1s", "--spring.data.redis.timeout=1s",
                "--waiting-room.services.reservation-service.max-concurrent-users=1",
                "--waiting-room.services.reservation-service.redirects.service-entry=https://service.example/entry",
                "--management.endpoint.health.show-components=always",
                "--management.endpoint.health.show-details=always"));
        if (java.util.Arrays.stream(additional).noneMatch(argument ->
                argument.startsWith("--waiting-room.security.oauth2.enabled="))) {
            arguments.add("--waiting-room.security.oauth2.enabled=false");
        }
        arguments.addAll(java.util.List.of(additional));
        return arguments.toArray(String[]::new);
    }

    private HttpResponse<String> get(org.springframework.context.ConfigurableApplicationContext context, String path)
            throws Exception {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
