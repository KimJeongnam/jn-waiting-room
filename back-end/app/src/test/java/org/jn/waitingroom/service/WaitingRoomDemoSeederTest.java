/**
 * @author jeongnam
 * @since 2026-09-28
 * @file WaitingRoomDemoSeederTest.java
 * @description 실제 Redis에서 데모 초기 활성 사용자와 대기 사용자의 상태를 검증합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.config.WaitingRoomDemoProperties;
import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.domain.WaitingRequestStatus;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class WaitingRoomDemoSeederTest {
    private static final String SERVICE_ID = "reservation-service";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisWaitingRoomStore store;

    @BeforeAll
    static void setUpStore() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        store = new RedisWaitingRoomStore(redis, new ObjectMapper());
    }

    @AfterAll
    static void closeConnectionFactory() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void clearRedis() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void seedsActiveUsersWithRandomExpiryAndLeavesWaitingUsersWaiting() {
        var seeder = new WaitingRoomDemoSeeder(
                store,
                redis,
                waitingRoomProperties(),
                new WaitingRoomDemoProperties(
                        true,
                        SERVICE_ID,
                        2,
                        3,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(5)
                )
        );
        long beforeSeed = System.currentTimeMillis();

        seeder.seed();

        long afterSeed = System.currentTimeMillis();
        assertEquals(2, redis.opsForZSet().size("active-slots:{reservation-service}"));
        assertEquals(3, redis.opsForZSet().size("waiting:{reservation-service}"));
        for (int index = 1; index <= 2; index++) {
            String requestId = "demo-active-%04d".formatted(index);
            assertEquals(WaitingRequestStatus.ENTERED, store.find(SERVICE_ID, requestId).status());
            Double expiry = redis.opsForZSet().score("active-slots:{reservation-service}", requestId);
            assertNotNull(expiry);
            assertTrue(expiry >= beforeSeed + Duration.ofMinutes(1).toMillis());
            assertTrue(expiry <= afterSeed + Duration.ofMinutes(5).toMillis());
        }
        for (int index = 1; index <= 3; index++) {
            String requestId = "demo-waiting-%05d".formatted(index);
            assertEquals(WaitingRequestStatus.WAITING, store.find(SERVICE_ID, requestId).status());
        }

        redis.opsForZSet().remove("active-slots:{reservation-service}", "demo-active-0001");
        seeder.seed();

        assertEquals(2, redis.opsForZSet().size("active-slots:{reservation-service}"));
        assertEquals(3, redis.opsForZSet().size("waiting:{reservation-service}"));
        assertEquals(WaitingRequestStatus.ENTERED, store.find(SERVICE_ID, "demo-active-0001").status());
    }

    @Test
    void seedResetsExistingRuntimeMembershipsBeforeCreatingTheDemoState() {
        store.register(
                SERVICE_ID,
                "existing-user",
                "sha256:existing-user",
                "service-entry",
                Duration.ofDays(1)
        );
        store.admitNext(
                SERVICE_ID,
                2,
                Duration.ofMinutes(2),
                Duration.ofMinutes(20),
                Duration.ofHours(23),
                Duration.ofHours(1)
        );
        var seeder = new WaitingRoomDemoSeeder(
                store,
                redis,
                waitingRoomProperties(),
                new WaitingRoomDemoProperties(
                        true,
                        SERVICE_ID,
                        2,
                        3,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(5)
                )
        );

        seeder.seed();

        assertEquals(2, redis.opsForZSet().size("active-slots:{reservation-service}"));
        assertEquals(3, redis.opsForZSet().size("waiting:{reservation-service}"));
        assertNull(redis.opsForZSet().rank("active-slots:{reservation-service}", "existing-user"));
        assertEquals(WaitingRequestStatus.ENTERED, store.find(SERVICE_ID, "demo-active-0001").status());
        assertEquals(WaitingRequestStatus.ENTERED, store.find(SERVICE_ID, "demo-active-0002").status());
    }

    private WaitingRoomProperties waitingRoomProperties() {
        var service = new WaitingRoomProperties.ServiceProperties(
                2,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                10,
                Duration.ofMinutes(1),
                0.0,
                Duration.ofDays(1),
                Duration.ofHours(23),
                Map.of("service-entry", "https://service.example/entry")
        );
        return new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of(SERVICE_ID, service)
        );
    }
}
