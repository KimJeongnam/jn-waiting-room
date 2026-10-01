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
import org.jn.waitingroom.domain.WaitingSecret;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@DisplayName("Redis 대기열 저장소 테스트")
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

    /** 검증 목적: 초기 기동은 복구 장벽을 만들지 않고 오래된 장벽을 제거한다. */
    @DisplayName("초기 기동은 복구 장벽을 만들지 않고 오래된 장벽을 제거한다")
    @Test
    void recoveryBootstrapDoesNotCreateColdStartBarrierAndRemovesStaleBarrier() {
        redis.opsForValue().set("heartbeat-expiry-barrier:{reservation-service}", "{}");
        var result = bootstrap("first");
        assertEquals(false, result.barrierActive());
        assertNull(redis.opsForValue().get("heartbeat-expiry-barrier:{reservation-service}"));
    }

    /** 검증 목적: 복구 시작은 장벽 하나만 만들고 만료 시간을 연장하지 않는다. */
    @DisplayName("복구 시작은 장벽 하나만 만들고 만료 시간을 연장하지 않는다")
    @Test
    void recoveryBootstrapCreatesOneBarrierAndDoesNotExtendItsExpiry() {
        store.register(SERVICE_ID, "waiting", "payload", "service-entry", Duration.ofDays(1));
        var first = bootstrap("first");
        String encoded = redis.opsForValue().get("heartbeat-expiry-barrier:{reservation-service}");
        var barrier = new ObjectMapper().readTree(encoded);
        assertEquals("first", barrier.get("recoveryId").asString());
        assertEquals(60_000, barrier.get("expiresAt").asLong() - barrier.get("recoveredAt").asLong());
        assertTrue(redis.getExpire("heartbeat-expiry-barrier:{reservation-service}", TimeUnit.MILLISECONDS) > 69_000);
        var second = bootstrap("second");
        assertEquals(true, first.barrierActive());
        assertEquals(first, second);
        assertEquals(encoded, redis.opsForValue().get("heartbeat-expiry-barrier:{reservation-service}"));
    }

    /** 검증 목적: 동시 복구 시작은 하나의 장벽으로 수렴한다. */
    @DisplayName("동시 복구 시작은 하나의 장벽으로 수렴한다")
    @Test
    void concurrentRecoveryBootstrapsConvergeOnOneBarrier() {
        store.register(SERVICE_ID, "waiting", "payload", "service-entry", Duration.ofDays(1));
        var attempts = IntStream.range(0, 12).mapToObj(index -> CompletableFuture.supplyAsync(() ->
                bootstrap("instance-" + index))).toList();
        assertEquals(1, attempts.stream().map(CompletableFuture::join).distinct().count());
    }

    /** 검증 목적: 정상 유지보수는 복구 장벽을 막고 하트비트에 TTL을 둔다. */
    @DisplayName("정상 유지보수는 복구 장벽을 막고 하트비트에 TTL을 둔다")
    @Test
    void healthyMaintenancePreventsRecoveryBarrierAndHeartbeatHasTtl() {
        store.register(SERVICE_ID, "waiting", "payload", "service-entry", Duration.ofDays(1));
        assertTrue(store.tryAcquireMaintenanceLease(SERVICE_ID, "healthy-pass", Duration.ofSeconds(5)));
        assertTrue(store.recordMaintenanceHeartbeat(SERVICE_ID, "healthy-pass", Duration.ofSeconds(30)));
        long heartbeat = Long.parseLong(redis.opsForValue().get("maintenance-heartbeat:{reservation-service}"));
        assertTrue(Math.abs(System.currentTimeMillis() - heartbeat) < 5_000);
        long ttl = redis.getExpire("maintenance-heartbeat:{reservation-service}", TimeUnit.MILLISECONDS);
        assertTrue(ttl > 29_000 && ttl <= 30_000);
        var result = bootstrap("healthy");
        assertEquals(false, result.barrierActive());
        assertNull(redis.opsForValue().get("heartbeat-expiry-barrier:{reservation-service}"));
    }

    /** 만료된 pass는 새 lease의 heartbeat를 기록하거나 새 lease를 해제할 수 없습니다. */
    @DisplayName("만료된 임대는 하트비트를 쓰거나 후임 임대를 해제할 수 없다")
    @Test
    void expiredLeaseCannotRecordHeartbeatOrReleaseAReplacementLease() {
        String leaseKey = "maintenance-lease:{reservation-service}";
        assertTrue(store.tryAcquireMaintenanceLease(SERVICE_ID, "instance:old-pass", Duration.ofSeconds(5)));
        redis.expire(leaseKey, Duration.ZERO);
        assertTrue(store.tryAcquireMaintenanceLease(SERVICE_ID, "instance:new-pass", Duration.ofSeconds(5)));

        org.junit.jupiter.api.Assertions.assertFalse(
                store.recordMaintenanceHeartbeat(SERVICE_ID, "instance:old-pass", Duration.ofSeconds(30)));
        store.releaseMaintenanceLease(SERVICE_ID, "instance:old-pass");

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertNull(redis.opsForValue().get("maintenance-heartbeat:{reservation-service}")),
                () -> assertEquals("instance:new-pass", redis.opsForValue().get(leaseKey)));
    }

    /** 검증 목적: 후임 임대가 없어도 만료된 임대는 하트비트를 쓸 수 없다. */
    @DisplayName("후임 임대가 없어도 만료된 임대는 하트비트를 쓸 수 없다")
    @Test
    void expiredLeaseWithoutReplacementCannotRecordAHeartbeat() {
        assertTrue(store.tryAcquireMaintenanceLease(SERVICE_ID, "expired-pass", Duration.ofSeconds(5)));
        redis.expire("maintenance-lease:{reservation-service}", Duration.ZERO);

        org.junit.jupiter.api.Assertions.assertFalse(
                store.recordMaintenanceHeartbeat(SERVICE_ID, "expired-pass", Duration.ofSeconds(30)));
        assertNull(redis.opsForValue().get("maintenance-heartbeat:{reservation-service}"));
    }

    /** 검증 목적: 활성 복구 장벽 안에서는 오래된 대기 하트비트를 갱신할 수 있다. */
    @DisplayName("활성 복구 장벽 안에서는 오래된 대기 하트비트를 갱신할 수 있다")
    @Test
    void activeRecoveryBarrierLetsPollingRefreshOldHeartbeat() {
        oldHeartbeat("waiting");
        setBarrier(System.currentTimeMillis() + 60_000);
        var snapshot = poll("waiting", 100, 100, 10);
        assertEquals(WaitingRequestStatus.WAITING, snapshot.state().status());
        assertTrue(redis.opsForZSet().score("waiting-heartbeat:{reservation-service}", "waiting")
                > System.currentTimeMillis() - 5_000);
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, store.admitNext(SERVICE_ID, 100,
                Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)).type());
    }

    /** 검증 목적: 유지보수가 없고 장벽이 만료되면 복구 시작이 새 장벽을 만든다. */
    @DisplayName("유지보수가 없고 장벽이 만료되면 복구 시작이 새 장벽을 만든다")
    @Test
    void recoveryBootstrapReplacesExpiredBarrierWhenMaintenanceIsAbsent() {
        store.register(SERVICE_ID, "waiting", "payload", "service-entry", Duration.ofDays(1));
        long expiresAt = System.currentTimeMillis() - 1_000;
        setBarrier(expiresAt);
        var result = bootstrap("later");
        assertTrue(result.barrierActive());
        assertTrue(result.barrierExpiresAtEpochMs() > System.currentTimeMillis());
        assertEquals("later", new ObjectMapper().readTree(redis.opsForValue().get(
                "heartbeat-expiry-barrier:{reservation-service}")).get("recoveryId").asString());
    }

    /** 검증 목적: 키 TTL이 남아 있어도 하트비트 시각을 확인해 복구 장벽을 만든다. */
    @DisplayName("키 TTL이 남아 있어도 하트비트 시각을 확인해 복구 장벽을 만든다")
    @Test
    void recoveryBootstrapChecksHeartbeatAgeEvenWhileTheKeyTtlRemains() {
        store.register(SERVICE_ID, "waiting", "payload", "service-entry", Duration.ofDays(1));
        redis.opsForValue().set("maintenance-heartbeat:{reservation-service}",
                Long.toString(System.currentTimeMillis() - Duration.ofMinutes(21).toMillis()), Duration.ofHours(1));
        assertTrue(bootstrap("stale-maintenance").barrierActive());
    }

    /** 같은 score 후보에서도 ID 사전순이 아닌 실제 등록 순서가 유지되어야 합니다. */
    @DisplayName("신규 등록은 사전순이 아닌 마지막 점수 뒤에 추가한다")
    @Test
    void registrationAppendsAfterTheLastScoreInsteadOfUsingMemberLexicalOrder() {
        store.register(SERVICE_ID, "z-first", "payload", "service-entry", Duration.ofDays(1));
        double sharedTime = System.currentTimeMillis() + 1_000;
        redis.opsForZSet().add("waiting:{reservation-service}", "z-first", sharedTime);
        store.register(SERVICE_ID, "a-second", "payload", "service-entry", Duration.ofDays(1));
        store.register(SERVICE_ID, "0-third", "payload", "service-entry", Duration.ofDays(1));
        assertEquals(java.util.List.of("z-first", "a-second", "0-third"),
                new java.util.ArrayList<>(redis.opsForZSet().range("waiting:{reservation-service}", 0, -1)));
        assertTrue(redis.opsForZSet().score("waiting:{reservation-service}", "a-second") > sharedTime);
        assertTrue(redis.opsForZSet().score("waiting:{reservation-service}", "0-third")
                > redis.opsForZSet().score("waiting:{reservation-service}", "a-second"));
    }

    /** 검증 목적: 만료된 장벽은 하트비트 만료로부터 조회를 보호하지 못한다. */
    @DisplayName("만료된 장벽은 하트비트 만료로부터 조회를 보호하지 못한다")
    @Test
    void expiredBarrierDoesNotProtectPollingFromHeartbeatTimeout() {
        store.register(SERVICE_ID, "waiting", "payload", "service-entry", Duration.ofDays(1));
        consume(store.find(SERVICE_ID, "waiting").currentWaitingTokenHash(), WaitingSecret.hash("test-session"));
        redis.opsForZSet().add("waiting-heartbeat:{reservation-service}", "waiting",
                System.currentTimeMillis() - Duration.ofHours(1).toMillis());
        setBarrier(System.currentTimeMillis() - 1_000);
        var snapshot = poll("waiting", 100, 100, 10);
        assertEquals(WaitingRequestStatus.EXPIRED, snapshot.state().status());
        assertEquals("HEARTBEAT_TIMEOUT", snapshot.state().expirationReason());
    }

    /** 검증 목적: 복구 장벽은 하트비트 만료를 미루지만 최대 대기시간은 적용한다. */
    @DisplayName("복구 장벽은 하트비트 만료를 미루지만 최대 대기시간은 적용한다")
    @Test
    void activeRecoveryBarrierDefersHeartbeatExpiryButStillExpiresMaxWait() {
        oldHeartbeat("waiting");
        setBarrier(System.currentTimeMillis() + 60_000);
        assertEquals(RedisWaitingRoomStore.ResultType.RETRY, expireRecoveryWaiting().type());
        redis.opsForZSet().add("waiting:{reservation-service}", "waiting", System.currentTimeMillis() - Duration.ofDays(1).toMillis());
        var expired = expireRecoveryWaiting();
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, expired.type());
        assertEquals("MAX_WAIT_DURATION", expired.state().expirationReason());
    }

    /** 검증 목적: 장벽 정리용 TTL만 남으면 하트비트 만료를 미루지 않는다. */
    @DisplayName("장벽 정리용 TTL만 남으면 하트비트 만료를 미루지 않는다")
    @Test
    void barrierCleanupTtlDoesNotDeferHeartbeatExpiryAfterJsonExpiry() {
        oldHeartbeat("waiting");
        setBarrier(System.currentTimeMillis() - 1_000);
        assertEquals("HEARTBEAT_TIMEOUT", expireRecoveryWaiting().state().expirationReason());
    }

    /** 검증 목적: 복구 장벽 동안 오래된 하트비트의 입장을 멈추고 FIFO를 유지한다. */
    @DisplayName("복구 장벽 동안 오래된 하트비트의 입장을 멈추고 FIFO를 유지한다")
    @Test
    void activeRecoveryBarrierStopsAdmissionAtOldHeartbeatWithoutBreakingFifo() {
        oldHeartbeat("waiting");
        store.register(SERVICE_ID, "later", "payload", "service-entry", Duration.ofDays(1));
        setBarrier(System.currentTimeMillis() + 60_000);
        assertEquals(RedisWaitingRoomStore.ResultType.RETRY, expireRecoveryWaiting().type());
        assertEquals(RedisWaitingRoomStore.ResultType.RETRY, store.admitNext(SERVICE_ID, 100,
                Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1)).type());
        assertEquals(WaitingRequestStatus.WAITING, store.find(SERVICE_ID, "later").status());
        assertEquals("waiting", redis.opsForZSet().range("waiting:{reservation-service}", 0, 0).iterator().next());
    }

    private RedisWaitingRoomStore.RecoveryBootstrap bootstrap(String recoveryId) {
        return store.bootstrapRecovery(SERVICE_ID, recoveryId, Duration.ofMinutes(1), Duration.ofSeconds(10), Duration.ofMinutes(20));
    }

    private void oldHeartbeat(String id) {
        store.register(SERVICE_ID, id, "payload", "service-entry", Duration.ofDays(1));
        redis.opsForZSet().add("waiting-heartbeat:{reservation-service}", id, System.currentTimeMillis() - Duration.ofHours(1).toMillis());
    }

    private void setBarrier(long expiresAt) {
        redis.opsForValue().set("heartbeat-expiry-barrier:{reservation-service}",
                "{\"recoveryId\":\"recovery\",\"recoveredAt\":0,\"expiresAt\":" + expiresAt + "}", Duration.ofMinutes(5));
    }

    private RedisWaitingRoomStore.WriteResult expireRecoveryWaiting() {
        return store.expireWaitingIfDue(SERVICE_ID, "waiting", Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1));
    }

    /** 검증 목적: 동시 토큰 교환은 신청 TTL 안에서 세션 하나만 만든다. */
    @DisplayName("동시 토큰 교환은 신청 TTL 안에서 세션 하나만 만든다")
    @Test
    void concurrentConsumptionCreatesExactlyOneSessionWithinTheRequestTtl() {
        String tokenHash = WaitingSecret.hash("token");
        store.register(SERVICE_ID, "secure", "payload", "service-entry", Duration.ofMinutes(1),
                tokenHash, Duration.ofSeconds(30));
        var attempts = IntStream.range(0, 12).mapToObj(index -> CompletableFuture.supplyAsync(() ->
                consume(tokenHash, WaitingSecret.hash("session-" + index)))).toList();
        long applied = attempts.stream().map(CompletableFuture::join)
                .filter(result -> result.type() == RedisWaitingRoomStore.ResultType.APPLIED).count();
        assertEquals(1, applied);
        assertNull(redis.opsForValue().get("waiting-access:{reservation-service}:" + tokenHash));
        var sessions = redis.keys("waiting-session:{reservation-service}:*");
        assertEquals(1, sessions.size());
        long sessionTtl = redis.getExpire(sessions.iterator().next(), TimeUnit.MILLISECONDS);
        long requestTtl = redis.getExpire("waiting-request:{reservation-service}:secure", TimeUnit.MILLISECONDS);
        assertTrue(sessionTtl > 0 && sessionTtl <= requestTtl + 10);
        assertNull(store.find(SERVICE_ID, "secure").currentWaitingTokenHash());
    }

    /** 검증 목적: 동시 토큰 재발급 후에는 최신 토큰과 버전만 남는다. */
    @DisplayName("동시 토큰 재발급 후에는 최신 토큰과 버전만 남는다")
    @Test
    void concurrentReissuesLeaveOnlyTheLatestTokenAndVersion() {
        store.register(SERVICE_ID, "secure", "payload", "service-entry", Duration.ofMinutes(1));
        var attempts = IntStream.range(0, 8).mapToObj(index -> CompletableFuture.supplyAsync(() ->
                issue("secure", WaitingSecret.hash("token-" + index)))).toList();
        attempts.forEach(future -> assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, future.join().type()));
        var state = store.find(SERVICE_ID, "secure");
        assertEquals(9, state.waitingSessionVersion());
        var tokens = redis.keys("waiting-access:{reservation-service}:*");
        assertEquals(java.util.Set.of("waiting-access:{reservation-service}:" + state.currentWaitingTokenHash()), tokens);
        for (int index = 0; index < 8; index++) {
            String hash = WaitingSecret.hash("token-" + index);
            assertEquals(hash.equals(state.currentWaitingTokenHash())
                            ? RedisWaitingRoomStore.ResultType.APPLIED : RedisWaitingRoomStore.ResultType.INVALID_SESSION,
                    consume(hash, WaitingSecret.hash("session")).type());
        }
    }

    /** 검증 목적: 만료된 토큰은 교환할 수 없다. */
    @DisplayName("만료된 토큰은 교환할 수 없다")
    @Test
    void expiredTokenCannotBeConsumed() throws InterruptedException {
        String tokenHash = WaitingSecret.hash("expiring-token");
        store.register(SERVICE_ID, "secure", "payload", "service-entry", Duration.ofMinutes(1),
                tokenHash, Duration.ofMillis(10));
        Thread.sleep(30);
        assertEquals(RedisWaitingRoomStore.ResultType.INVALID_SESSION,
                consume(tokenHash, WaitingSecret.hash("session")).type());
        assertTrue(redis.keys("waiting-session:{reservation-service}:*").isEmpty());
    }

    /** 검증 목적: 토큰 재발급은 만료 대상을 정리하고 종료 상태를 되살리지 않는다. */
    @DisplayName("토큰 재발급은 만료 대상을 정리하고 종료 상태를 되살리지 않는다")
    @Test
    void reissueExpiresDueRequestsAndDoesNotReviveTerminalStates() {
        for (String reason : java.util.List.of("MAX_WAIT_DURATION", "HEARTBEAT_TIMEOUT")) {
            String id = reason.toLowerCase();
            store.register(SERVICE_ID, id, "payload", "service-entry", Duration.ofDays(1));
            String key = reason.equals("MAX_WAIT_DURATION")
                    ? "waiting:{reservation-service}" : "waiting-heartbeat:{reservation-service}";
            redis.opsForZSet().add(key, id, System.currentTimeMillis() - Duration.ofDays(2).toMillis());
            assertEquals(RedisWaitingRoomStore.ResultType.CONFLICT, issue(id, WaitingSecret.hash(id)).type());
            assertEquals(WaitingRequestStatus.EXPIRED, store.find(SERVICE_ID, id).status());
            assertEquals(reason, store.find(SERVICE_ID, id).expirationReason());
            assertNull(redis.opsForZSet().score("waiting:{reservation-service}", id));
            assertNull(redis.opsForZSet().score("waiting-heartbeat:{reservation-service}", id));
        }
        store.register(SERVICE_ID, "admitted", "payload", "service-entry", Duration.ofDays(1));
        store.admitNext(SERVICE_ID, 1, Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1));
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, issue("admitted", WaitingSecret.hash("allowed")).type());
        redis.opsForZSet().add("active-slots:{reservation-service}", "admitted", System.currentTimeMillis() - 1);
        assertEquals(RedisWaitingRoomStore.ResultType.ADMISSION_EXPIRED,
                issue("admitted", WaitingSecret.hash("expired")).type());
        assertEquals("ADMISSION_TIMEOUT", store.find(SERVICE_ID, "admitted").expirationReason());
        assertEquals(1, redis.opsForZSet().size("slot-release-events:{reservation-service}"));

        store.register(SERVICE_ID, "cancelled", "payload", "service-entry", Duration.ofDays(1));
        store.cancel(SERVICE_ID, "cancelled", Duration.ofMinutes(5));
        store.register(SERVICE_ID, "entered", "payload", "service-entry", Duration.ofDays(1));
        store.admitNext(SERVICE_ID, 1, Duration.ofMinutes(2), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofHours(1));
        store.enter(SERVICE_ID, "entered", Duration.ofMinutes(30), Duration.ofMinutes(5));
        for (String id : java.util.List.of("cancelled", "entered", "admitted")) {
            var previous = store.find(SERVICE_ID, id);
            assertEquals(RedisWaitingRoomStore.ResultType.CONFLICT, issue(id, WaitingSecret.hash("denied")).type());
            assertEquals(previous, store.find(SERVICE_ID, id));
        }
    }

    private RedisWaitingRoomStore.WriteResult issue(String id, String tokenHash) {
        return store.issueToken(SERVICE_ID, id, tokenHash, Duration.ofMinutes(5), Duration.ofMinutes(20),
                Duration.ofHours(23), Duration.ofMinutes(5));
    }

    private RedisWaitingRoomStore.WriteResult consume(String tokenHash, String sessionHash) {
        return store.consumeToken(SERVICE_ID, tokenHash, sessionHash, Duration.ofMinutes(20),
                Duration.ofHours(23), Duration.ofMinutes(5));
    }

    /** 복구 유예는 token 재발급에서도 heartbeat 만료만 늦춥니다. */
    @DisplayName("복구 장벽 동안 오래된 하트비트의 토큰 재발급을 허용한다")
    @Test
    void recoveryBarrierAllowsTokenReissueForOldHeartbeat() {
        oldHeartbeat("recovery-token");
        setBarrier(System.currentTimeMillis() + 60_000);
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED,
                issue("recovery-token", WaitingSecret.hash("renewed")).type());
        assertEquals(WaitingRequestStatus.WAITING, store.find(SERVICE_ID, "recovery-token").status());
    }

    /** 검증 목적: 복구 중에도 최대 대기시간이 지나면 토큰 재발급은 만료 처리한다. */
    @DisplayName("복구 중에도 최대 대기시간이 지나면 토큰 재발급은 만료 처리한다")
    @Test
    void tokenReissueStillExpiresMaxWaitDuringRecovery() {
        oldHeartbeat("max-wait-token");
        redis.opsForZSet().add("waiting:{reservation-service}", "max-wait-token",
                System.currentTimeMillis() - Duration.ofDays(1).toMillis());
        setBarrier(System.currentTimeMillis() + 60_000);
        assertEquals(RedisWaitingRoomStore.ResultType.CONFLICT,
                issue("max-wait-token", WaitingSecret.hash("renewed")).type());
        assertEquals("MAX_WAIT_DURATION", store.find(SERVICE_ID, "max-wait-token").expirationReason());
    }

    /** Scheduler 처리 전에도 token 교환이 이미 만료된 상태를 허용하면 안 됩니다. */
    @DisplayName("토큰 교환은 대기 하트비트 만료 시 세션을 만들지 않는다")
    @Test
    void tokenExchangeExpiresWaitingHeartbeatWithoutCreatingSession() {
        oldHeartbeat("expired-exchange");
        var state = store.find(SERVICE_ID, "expired-exchange");
        assertEquals(RedisWaitingRoomStore.ResultType.INVALID_SESSION,
                consume(state.currentWaitingTokenHash(), WaitingSecret.hash("expired-session")).type());
        assertEquals("HEARTBEAT_TIMEOUT", store.find(SERVICE_ID, "expired-exchange").expirationReason());
        assertNull(redis.opsForValue().get("waiting-session:{reservation-service}:" + WaitingSecret.hash("expired-session")));
        assertNull(redis.opsForZSet().score("waiting:{reservation-service}", "expired-exchange"));
        assertNull(redis.opsForValue().get("waiting-access:{reservation-service}:" + state.currentWaitingTokenHash()));
    }

    /** 검증 목적: 복구 중에도 최대 대기시간이 지나면 토큰 교환은 만료 처리한다. */
    @DisplayName("복구 중에도 최대 대기시간이 지나면 토큰 교환은 만료 처리한다")
    @Test
    void tokenExchangeStillExpiresMaxWaitDuringRecovery() {
        oldHeartbeat("max-wait-exchange");
        redis.opsForZSet().add("waiting:{reservation-service}", "max-wait-exchange",
                System.currentTimeMillis() - Duration.ofDays(1).toMillis());
        setBarrier(System.currentTimeMillis() + 60_000);
        assertEquals(RedisWaitingRoomStore.ResultType.INVALID_SESSION,
                consume(store.find(SERVICE_ID, "max-wait-exchange").currentWaitingTokenHash(),
                        WaitingSecret.hash("max-wait-session")).type());
        assertEquals("MAX_WAIT_DURATION", store.find(SERVICE_ID, "max-wait-exchange").expirationReason());
    }

    /** 검증 목적: 오래된 하트비트의 토큰 교환은 활성 복구 장벽에서만 허용한다. */
    @DisplayName("오래된 하트비트의 토큰 교환은 활성 복구 장벽에서만 허용한다")
    @Test
    void tokenExchangeAllowsOldHeartbeatOnlyDuringActiveRecoveryBarrier() {
        oldHeartbeat("recovery-exchange");
        setBarrier(System.currentTimeMillis() + 60_000);
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED,
                consume(store.find(SERVICE_ID, "recovery-exchange").currentWaitingTokenHash(),
                        WaitingSecret.hash("recovery-session")).type());
        oldHeartbeat("expired-barrier-exchange");
        setBarrier(System.currentTimeMillis() - 1_000);
        assertEquals(RedisWaitingRoomStore.ResultType.INVALID_SESSION,
                consume(store.find(SERVICE_ID, "expired-barrier-exchange").currentWaitingTokenHash(),
                        WaitingSecret.hash("expired-barrier-session")).type());
        assertEquals("HEARTBEAT_TIMEOUT", store.find(SERVICE_ID, "expired-barrier-exchange").expirationReason());
    }

    /** 검증 목적: 토큰 교환은 만료된 입장 슬롯을 정리하고 슬롯 반환을 기록한다. */
    @DisplayName("토큰 교환은 만료된 입장 슬롯을 정리하고 슬롯 반환을 기록한다")
    @Test
    void tokenExchangeExpiresAdmittedSlotAndRecordsRelease() {
        store.register(SERVICE_ID, "expired-admission-exchange", "payload", "service-entry", Duration.ofDays(1));
        store.admitNext(SERVICE_ID, 100, Duration.ofMinutes(2), Duration.ofMinutes(20),
                Duration.ofHours(23), Duration.ofHours(1));
        redis.opsForZSet().add("active-slots:{reservation-service}", "expired-admission-exchange",
                System.currentTimeMillis() - 1_000);
        assertEquals(RedisWaitingRoomStore.ResultType.INVALID_SESSION,
                consume(store.find(SERVICE_ID, "expired-admission-exchange").currentWaitingTokenHash(),
                        WaitingSecret.hash("expired-admission-session")).type());
        assertEquals("ADMISSION_TIMEOUT", store.find(SERVICE_ID, "expired-admission-exchange").expirationReason());
        assertNull(redis.opsForZSet().score("active-slots:{reservation-service}", "expired-admission-exchange"));
        assertNotNull(redis.opsForZSet().score("slot-release-events:{reservation-service}", "expired-admission-exchange"));
    }

    /** 검증 목적: 입장 완료 신청은 토큰 교환을 거절한다. */
    @DisplayName("입장 완료 신청은 토큰 교환을 거절한다")
    @Test
    void tokenExchangeRejectsEnteredRequest() {
        store.register(SERVICE_ID, "entered-exchange", "payload", "service-entry", Duration.ofDays(1));
        store.admitNext(SERVICE_ID, 100, Duration.ofMinutes(2), Duration.ofMinutes(20),
                Duration.ofHours(23), Duration.ofHours(1));
        store.enter(SERVICE_ID, "entered-exchange", Duration.ofMinutes(30), Duration.ofMinutes(5));
        assertEquals(RedisWaitingRoomStore.ResultType.INVALID_SESSION,
                consume(store.find(SERVICE_ID, "entered-exchange").currentWaitingTokenHash(),
                        WaitingSecret.hash("entered-session")).type());
    }

    /** JSON null을 포함한 이전 상태도 재발급과 새 세션 교환이 완료되어야 합니다. */
    @DisplayName("토큰 재발급은 JSON null 인증 값을 정규화한다")
    @Test
    void reissueNormalizesExplicitJsonNullCredentials() throws Exception {
        store.register(SERVICE_ID, "legacy", "payload", "service-entry", Duration.ofMinutes(1));
        String key = "waiting-request:{reservation-service}:legacy";
        String original = redis.opsForValue().get(key);
        var state = new ObjectMapper().readTree(original).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) state).putNull("waitingSessionVersion")
                .putNull("currentWaitingTokenHash").putNull("currentWaitingSessionHash");
        redis.opsForValue().set(key, state.toString(), Duration.ofMinutes(1));
        String tokenHash = WaitingSecret.hash("replacement");
        var attempt = CompletableFuture.supplyAsync(() -> issue("legacy", tokenHash));
        try {
            var result = attempt.get(2, TimeUnit.SECONDS);
            assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, result.type());
            assertEquals(1, result.state().waitingSessionVersion());
            assertEquals(RedisWaitingRoomStore.ResultType.APPLIED,
                    consume(tokenHash, WaitingSecret.hash("session")).type());
        } finally {
            // RED에서도 반복 중인 요청을 종료하여 Redis 컨테이너에 작업을 남기지 않습니다.
            if (!attempt.isDone()) {
                redis.opsForValue().set(key, original, Duration.ofMinutes(1));
                attempt.get(2, TimeUnit.SECONDS);
            }
        }
    }

    /** 매 사전 조회 뒤의 경쟁을 재현해 요청이 정해진 횟수 안에서 종료되는지 검증합니다. */
    @DisplayName("토큰 버전이 계속 바뀌면 저장소 오류를 반환한다")
    @Test
    void reissueReturnsStoreErrorWhenVersionKeepsChanging() {
        store.register(SERVICE_ID, "contended", "payload", "service-entry", Duration.ofMinutes(1));
        var reads = new AtomicInteger();
        var racingStore = new RedisWaitingRoomStore(redis, new ObjectMapper()) {
            @Override
            public org.jn.waitingroom.domain.WaitingRequestState find(String serviceId, String requestId) {
                var state = super.find(serviceId, requestId);
                // 실제 Redis 변경을 조회와 Lua 사이에 넣어 CAS 경쟁을 결정적으로 재현합니다.
                if (reads.incrementAndGet() <= 20) {
                    String key = "waiting-request:{reservation-service}:contended";
                    var updated = (tools.jackson.databind.node.ObjectNode)
                            new ObjectMapper().readTree(redis.opsForValue().get(key));
                    updated.put("waitingSessionVersion", state.waitingSessionVersion() + 1);
                    redis.opsForValue().set(key, updated.toString(), Duration.ofMinutes(1));
                }
                return state;
            }
        };

        var result = racingStore.issueToken(SERVICE_ID, "contended", WaitingSecret.hash("contended-token"),
                Duration.ofMinutes(5), Duration.ofMinutes(20), Duration.ofHours(23), Duration.ofMinutes(5));

        assertEquals(RedisWaitingRoomStore.ResultType.STORE_ERROR, result.type());
        assertEquals(8, reads.get());
    }

    /** 검증 목적: 신청 등록은 멱등적이며 같은 ID의 다른 본문은 거절한다. */
    @DisplayName("신청 등록은 멱등적이며 같은 ID의 다른 본문은 거절한다")
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

    /** 검증 목적: 입장은 설정된 수용 인원을 넘지 않는다. */
    @DisplayName("입장은 설정된 수용 인원을 넘지 않는다")
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

    /** 검증 목적: 입장 재요청은 슬롯을 연장하지 않고 완료는 슬롯을 반환한다. */
    @DisplayName("입장 재요청은 슬롯을 연장하지 않고 완료는 슬롯을 반환한다")
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

    /** 검증 목적: 세션 슬롯 만료 후에도 입장 완료 신청을 완료 처리할 수 있다. */
    @DisplayName("세션 슬롯 만료 후에도 입장 완료 신청을 완료 처리할 수 있다")
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

    /** 검증 목적: 상태 전이는 신청 없음과 상태 충돌을 구분한다. */
    @DisplayName("상태 전이는 신청 없음과 상태 충돌을 구분한다")
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

    /** 검증 목적: 이미 만료된 입장 슬롯에 대한 입장 요청은 신청을 만료한다. */
    @DisplayName("이미 만료된 입장 슬롯에 대한 입장 요청은 신청을 만료한다")
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

    /** 검증 목적: 취소는 대기 또는 입장 허용 멤버십을 제거한다. */
    @DisplayName("취소는 대기 또는 입장 허용 멤버십을 제거한다")
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

    /** 검증 목적: 가용 슬롯에 반환이 불필요하면 예상 시간에 스케줄러 배치를 사용한다. */
    @DisplayName("가용 슬롯에 반환이 불필요하면 예상 시간에 스케줄러 배치를 사용한다")
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

    /** 검증 목적: 반환이 필요하고 관측값이 부족하면 예상 시간을 표시하지 않는다. */
    @DisplayName("반환이 필요하고 관측값이 부족하면 예상 시간을 표시하지 않는다")
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

    /** 검증 목적: 반환 관측값이 부족하면 초기 해제 속도로 예상 시간을 계산한다. */
    @DisplayName("반환 관측값이 부족하면 초기 해제 속도로 예상 시간을 계산한다")
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

    /** 검증 목적: 반환이 필요하면 최근 해제 속도로 예상 시간을 계산한다. */
    @DisplayName("반환이 필요하면 최근 해제 속도로 예상 시간을 계산한다")
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
        String sessionHash = org.jn.waitingroom.domain.WaitingSecret.hash("test-session");
        var state = store.find(SERVICE_ID, reservationRequestId);
        if (state.currentWaitingSessionHash() == null) consume(state.currentWaitingTokenHash(), sessionHash);
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
                Duration.ofSeconds(1),
                sessionHash
        );
    }
}
