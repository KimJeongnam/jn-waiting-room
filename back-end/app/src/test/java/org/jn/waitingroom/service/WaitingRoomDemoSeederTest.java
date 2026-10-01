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
import org.junit.jupiter.api.DisplayName;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@Testcontainers
@DisplayName("데모 데이터 초기화 테스트")
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

    /** 검증 목적: 활성 테스트 사용자는 무작위 만료시간을 갖고 대기 사용자는 대기 상태를 유지한다. */
    @DisplayName("활성 테스트 사용자는 무작위 만료시간을 갖고 대기 사용자는 대기 상태를 유지한다")
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

    /** 검증 목적: 데모 데이터 생성 전에 기존 런타임 멤버십을 초기화한다. */
    @DisplayName("데모 데이터 생성 전에 기존 런타임 멤버십을 초기화한다")
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

    /** 다른 유지보수 또는 reset의 lease를 보유하면 기존 Redis 상태를 변경하지 않습니다. */
    @DisplayName("유지보수 임대가 점유되면 Redis를 변경하지 않고 초기화를 거절한다")
    @Test
    void heldMaintenanceLeasePreventsResetWithoutMutatingRedis() {
        store.register(SERVICE_ID, "existing-user", "sha256:existing-user", "service-entry", Duration.ofDays(1));
        String stateBefore = redis.opsForValue().get("waiting-request:{reservation-service}:existing-user");
        assertTrue(store.tryAcquireMaintenanceLease(SERVICE_ID, "other-reset", Duration.ofMinutes(20)));
        var seeder = new WaitingRoomDemoSeeder(store, redis, waitingRoomProperties(),
                new WaitingRoomDemoProperties(true, SERVICE_ID, 2, 3, Duration.ofMinutes(1), Duration.ofMinutes(5)));

        assertThrows(IllegalStateException.class, seeder::seed);

        assertEquals("other-reset", redis.opsForValue().get("maintenance-lease:{reservation-service}"));
        assertEquals(stateBefore, redis.opsForValue().get("waiting-request:{reservation-service}:existing-user"));
        assertEquals(0L, redis.opsForZSet().rank("waiting:{reservation-service}", "existing-user"));
        assertNull(store.find(SERVICE_ID, "demo-active-0001"));
        store.releaseMaintenanceLease(SERVICE_ID, "other-reset");
        seeder.seed();
        assertNull(redis.opsForValue().get("maintenance-lease:{reservation-service}"));
        assertEquals(2, redis.opsForZSet().size("active-slots:{reservation-service}"));
    }

    /** seed 도중 실패해도 획득한 lease를 해제하여 다음 유지보수를 허용합니다. */
    @DisplayName("데모 데이터 생성 실패 시 유지보수 임대를 해제한다")
    @Test
    void failingSeedReleasesItsMaintenanceLease() {
        var observedStore = spy(store);
        doAnswer(call -> {
            assertNotNull(redis.opsForValue().get("maintenance-lease:{reservation-service}"));
            Long remainingLease = redis.getExpire("maintenance-lease:{reservation-service}", java.util.concurrent.TimeUnit.MILLISECONDS);
            assertTrue(remainingLease > 0 && remainingLease <= waitingRoomProperties().heartbeatExpirationRecoveryGrace().toMillis());
            throw new IllegalArgumentException("seed failure");
        }).when(observedStore).register(anyString(), anyString(), anyString(), anyString(), any());
        var seeder = new WaitingRoomDemoSeeder(observedStore, redis, waitingRoomProperties(),
                new WaitingRoomDemoProperties(true, SERVICE_ID, 1, 0, Duration.ofMinutes(1), Duration.ofMinutes(5)));

        assertThrows(IllegalArgumentException.class, seeder::seed);

        assertNull(redis.opsForValue().get("maintenance-lease:{reservation-service}"));
        assertTrue(store.tryAcquireMaintenanceLease(SERVICE_ID, "next-pass", Duration.ofSeconds(5)));
    }

    /** 첫 reset이 실제 등록 중인 동안 두 번째 reset은 같은 lease에서 거부됩니다. */
    @DisplayName("데모 생성 중에는 임대를 유지하고 동시 초기화를 거절한다")
    @Test
    void resetKeepsItsLeaseDuringSeedingAndRejectsConcurrentReset() {
        var demo = new WaitingRoomDemoProperties(true, SERVICE_ID, 1, 0, Duration.ofMinutes(1), Duration.ofMinutes(5));
        var concurrentSeeder = new WaitingRoomDemoSeeder(store, redis, waitingRoomProperties(), demo);
        var observedStore = spy(store);
        doAnswer(call -> {
            String leaseToken = redis.opsForValue().get("maintenance-lease:{reservation-service}");
            assertNotNull(leaseToken);
            assertThrows(IllegalStateException.class, concurrentSeeder::seed);
            assertEquals(leaseToken, redis.opsForValue().get("maintenance-lease:{reservation-service}"));
            return call.callRealMethod();
        }).when(observedStore).register(anyString(), anyString(), anyString(), anyString(), any());

        new WaitingRoomDemoSeeder(observedStore, redis, waitingRoomProperties(), demo).seed();

        assertNull(redis.opsForValue().get("maintenance-lease:{reservation-service}"));
        assertEquals(1, redis.opsForZSet().size("active-slots:{reservation-service}"));
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
