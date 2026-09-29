/**
 * @author jeongnam
 * @since 2026-09-21
 * @file RedisWaitingRoomStoreTest.java
 * @description 실제 Redis에서 대기 상태와 Sorted Set의 원자 변경을 검증합니다.
 */
package org.jn.waitingroom.redis;

import org.jn.waitingroom.domain.WaitingRequestStatus;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class RedisWaitingRoomStoreTest {
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
    void registerIsIdempotentAndRejectsAnotherPayloadForTheSameRequestId() {
        var created = store.register(
                SERVICE_ID,
                "reservation-1",
                "sha256:first",
                "service-entry",
                Duration.ofDays(1)
        );
        var replayed = store.register(
                SERVICE_ID,
                "reservation-1",
                "sha256:first",
                "service-entry",
                Duration.ofDays(1)
        );
        var conflict = store.register(
                SERVICE_ID,
                "reservation-1",
                "sha256:other",
                "service-entry",
                Duration.ofDays(1)
        );

        assertEquals(RedisWaitingRoomStore.ResultType.CREATED, created.type());
        assertEquals(RedisWaitingRoomStore.ResultType.REPLAYED, replayed.type());
        assertEquals(RedisWaitingRoomStore.ResultType.CONFLICT, conflict.type());
        assertEquals(WaitingRequestStatus.WAITING, created.state().status());
        assertEquals(1, redis.opsForZSet().size("waiting:{reservation-service}"));
        assertEquals(1, redis.opsForZSet().size("waiting-heartbeat:{reservation-service}"));

        var ttl = redis.getExpire("waiting-request:{reservation-service}:reservation-1");
        assertTrue(ttl > Duration.ofHours(23).toSeconds());
        assertTrue(ttl <= Duration.ofDays(1).toSeconds());
    }

    @Test
    void admissionNeverExceedsTheConfiguredCapacity() {
        store.register(SERVICE_ID, "reservation-1", "sha256:1", "service-entry", Duration.ofDays(1));
        store.register(SERVICE_ID, "reservation-2", "sha256:2", "service-entry", Duration.ofDays(1));

        var admitted = store.admitNext(
                SERVICE_ID, 1, Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)
        );
        var full = store.admitNext(
                SERVICE_ID, 1, Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)
        );

        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, admitted.type());
        assertEquals("reservation-1", admitted.state().reservationRequestId());
        assertEquals(WaitingRequestStatus.ADMITTED, admitted.state().status());
        assertEquals(RedisWaitingRoomStore.ResultType.FULL, full.type());
        assertEquals(1, redis.opsForZSet().size("active-slots:{reservation-service}"));
        assertEquals(1, redis.opsForZSet().size("waiting:{reservation-service}"));
        assertEquals(1, redis.opsForZSet().size("waiting-heartbeat:{reservation-service}"));
    }

    @Test
    void enterIsIdempotentWithoutExtendingTheActiveSlotAndCompleteReturnsIt() {
        store.register(SERVICE_ID, "reservation-1", "sha256:1", "service-entry", Duration.ofDays(1));
        store.admitNext(
                SERVICE_ID, 1, Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)
        );

        var entered = store.enter(
                SERVICE_ID,
                "reservation-1",
                Duration.ofMinutes(30),
                Duration.ofMinutes(5)
        );
        var firstExpiry = redis.opsForZSet().score(
                "active-slots:{reservation-service}",
                "reservation-1"
        );
        var replayed = store.enter(
                SERVICE_ID,
                "reservation-1",
                Duration.ofHours(1),
                Duration.ofMinutes(5)
        );
        var secondExpiry = redis.opsForZSet().score(
                "active-slots:{reservation-service}",
                "reservation-1"
        );
        var completed = store.complete(SERVICE_ID, "reservation-1", Duration.ofMinutes(5));
        var completedAgain = store.complete(SERVICE_ID, "reservation-1", Duration.ofMinutes(5));

        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, entered.type());
        assertEquals(RedisWaitingRoomStore.ResultType.REPLAYED, replayed.type());
        assertEquals(firstExpiry, secondExpiry);
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, completed.type());
        assertEquals(RedisWaitingRoomStore.ResultType.REPLAYED, completedAgain.type());
        assertNotNull(completed.state().completedAt());
        assertEquals(0, redis.opsForZSet().size("active-slots:{reservation-service}"));
        assertEquals(1, redis.opsForZSet().size("slot-release-events:{reservation-service}"));
        long releaseEventsTtl = redis.getExpire("slot-release-events:{reservation-service}");
        assertTrue(releaseEventsTtl > Duration.ofMinutes(9).toSeconds());
        assertTrue(releaseEventsTtl <= Duration.ofMinutes(10).toSeconds());
    }

    @Test
    void completeAcceptsAnEnteredRequestAfterItsSessionSlotExpired() throws InterruptedException {
        store.register(SERVICE_ID, "reservation-1", "sha256:1", "service-entry", Duration.ofDays(1));
        store.admitNext(
                SERVICE_ID, 1, Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)
        );
        store.enter(SERVICE_ID, "reservation-1", Duration.ofMillis(10), Duration.ofMinutes(5));
        Thread.sleep(30);
        store.expireActiveIfDue(
                SERVICE_ID,
                "reservation-1",
                Duration.ofHours(1),
                Duration.ofMinutes(5)
        );

        var completed = store.complete(SERVICE_ID, "reservation-1", Duration.ofMinutes(5));
        var completedAgain = store.complete(SERVICE_ID, "reservation-1", Duration.ofMinutes(5));

        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, completed.type());
        assertEquals(RedisWaitingRoomStore.ResultType.REPLAYED, completedAgain.type());
        assertEquals(WaitingRequestStatus.EXPIRED, completed.state().status());
        assertNotNull(completed.state().completedAt());
        assertEquals(1, redis.opsForZSet().size("slot-release-events:{reservation-service}"));
    }

    @Test
    void transitionsDistinguishMissingRequestsFromStateConflicts() {
        assertEquals(
                RedisWaitingRoomStore.ResultType.NOT_FOUND,
                store.enter(SERVICE_ID, "missing", Duration.ofMinutes(30), Duration.ofMinutes(5)).type()
        );
        assertEquals(
                RedisWaitingRoomStore.ResultType.NOT_FOUND,
                store.complete(SERVICE_ID, "missing", Duration.ofMinutes(5)).type()
        );
        assertEquals(
                RedisWaitingRoomStore.ResultType.NOT_FOUND,
                store.cancel(SERVICE_ID, "missing", Duration.ofMinutes(5)).type()
        );
    }

    @Test
    void enterExpiresAnAdmissionWhoseSlotAlreadyPassed() throws InterruptedException {
        store.register(SERVICE_ID, "reservation-1", "sha256:1", "service-entry", Duration.ofDays(1));
        store.admitNext(
                SERVICE_ID, 1, Duration.ofMillis(10), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)
        );
        Thread.sleep(30);

        var result = store.enter(
                SERVICE_ID,
                "reservation-1",
                Duration.ofMinutes(30),
                Duration.ofMinutes(5)
        );

        assertEquals("ADMISSION_EXPIRED", result.type().name());
        assertEquals(WaitingRequestStatus.EXPIRED, result.state().status());
        assertEquals("ADMISSION_TIMEOUT", result.state().expirationReason());
        assertEquals(0, redis.opsForZSet().size("active-slots:{reservation-service}"));
        assertEquals(1, redis.opsForZSet().size("slot-release-events:{reservation-service}"));
    }

    @Test
    void cancelRemovesWaitingOrAdmittedMembership() {
        store.register(SERVICE_ID, "waiting-request", "sha256:1", "service-entry", Duration.ofDays(1));
        var waitingCancelled = store.cancel(SERVICE_ID, "waiting-request", Duration.ofMinutes(5));

        store.register(SERVICE_ID, "admitted-request", "sha256:2", "service-entry", Duration.ofDays(1));
        store.admitNext(
                SERVICE_ID, 1, Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)
        );
        var admittedCancelled = store.cancel(SERVICE_ID, "admitted-request", Duration.ofMinutes(5));

        assertEquals(WaitingRequestStatus.CANCELLED, waitingCancelled.state().status());
        assertEquals(WaitingRequestStatus.CANCELLED, admittedCancelled.state().status());
        assertEquals(0, redis.opsForZSet().size("waiting:{reservation-service}"));
        assertEquals(0, redis.opsForZSet().size("waiting-heartbeat:{reservation-service}"));
        assertEquals(0, redis.opsForZSet().size("active-slots:{reservation-service}"));
        assertEquals(1, redis.opsForZSet().size("slot-release-events:{reservation-service}"));
    }

    @Test
    void pollUsesSchedulerBatchesWhenAvailableSlotsDoNotRequireARelease() {
        store.register(SERVICE_ID, "reservation-1", "sha256:1", "service-entry", Duration.ofDays(1));
        store.register(SERVICE_ID, "reservation-2", "sha256:2", "service-entry", Duration.ofDays(1));
        store.register(SERVICE_ID, "reservation-3", "sha256:3", "service-entry", Duration.ofDays(1));

        var snapshot = poll("reservation-3", 10, 2, 10);

        assertEquals(3, snapshot.position());
        assertEquals(2, snapshot.estimatedWaitSeconds());
        assertEquals(1_000, snapshot.delayMs());
    }

    @Test
    void pollOmitsEtaWhenAReleaseIsRequiredButSamplesAreInsufficient() {
        store.register(SERVICE_ID, "active", "sha256:active", "service-entry", Duration.ofDays(1));
        store.admitNext(
                SERVICE_ID, 1, Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)
        );
        store.register(SERVICE_ID, "waiting", "sha256:waiting", "service-entry", Duration.ofDays(1));

        var snapshot = poll("waiting", 1, 100, 10);

        assertNull(snapshot.estimatedWaitSeconds());
        assertEquals(15_000, snapshot.delayMs());
    }

    @Test
    void pollUsesInitialReleaseRateWhenReleaseSamplesAreInsufficient() {
        store.register(SERVICE_ID, "active", "sha256:active", "service-entry", Duration.ofDays(1));
        store.admitNext(
                SERVICE_ID, 1, Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)
        );
        store.register(SERVICE_ID, "waiting", "sha256:waiting", "service-entry", Duration.ofDays(1));

        var snapshot = poll("waiting", 1, 100, 10, 0.5);

        assertEquals(2, snapshot.estimatedWaitSeconds());
        assertEquals(1_000, snapshot.delayMs());
    }

    @Test
    void pollUsesRecentReleaseRateWhenAReleaseIsRequired() {
        store.register(SERVICE_ID, "active", "sha256:active", "service-entry", Duration.ofDays(1));
        store.admitNext(
                SERVICE_ID, 1, Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)
        );
        store.register(SERVICE_ID, "waiting", "sha256:waiting", "service-entry", Duration.ofDays(1));
        long baseTime = System.currentTimeMillis() - Duration.ofSeconds(59).toMillis();
        for (int index = 0; index < 10; index++) {
            redis.opsForZSet().add(
                    "slot-release-events:{reservation-service}",
                    "released-" + index,
                    baseTime + index
            );
        }

        var snapshot = poll("waiting", 1, 100, 10);

        assertEquals(6, snapshot.estimatedWaitSeconds());
        assertEquals(1_000, snapshot.delayMs());
    }

    private RedisWaitingRoomStore.WaitingSnapshot poll(
            String reservationRequestId,
            int maxConcurrentUsers,
            int maxAdmissionsPerRun,
            int etaMinSamples
    ) {
        return poll(reservationRequestId, maxConcurrentUsers, maxAdmissionsPerRun, etaMinSamples, 0.0);
    }

    private RedisWaitingRoomStore.WaitingSnapshot poll(
            String reservationRequestId,
            int maxConcurrentUsers,
            int maxAdmissionsPerRun,
            int etaMinSamples,
            double etaInitialReleaseRatePerSecond
    ) {
        return store.poll(
                SERVICE_ID,
                reservationRequestId,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                maxConcurrentUsers,
                Duration.ofMinutes(20),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                etaMinSamples,
                Duration.ofMinutes(1),
                etaInitialReleaseRatePerSecond,
                maxAdmissionsPerRun,
                Duration.ofSeconds(1)
        );
    }
}
