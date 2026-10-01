/**
 * @author jeongnam
 * @since 2026-09-30
 * @file RedisOperationalSafetyRedisTest.java
 * @description 실제 CONFIG/INFO 조회와 메모리 차단 중 기존 요청 처리를 검증합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DisplayName("실제 Redis 운영 안전성 테스트")
class RedisOperationalSafetyRedisTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withCommand("redis-server", "--maxmemory", "64mb", "--maxmemory-policy", "noeviction")
            .withExposedPorts(6379);
    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void reset() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
            connection.serverCommands().setConfig("maxmemory-policy", "noeviction");
            connection.serverCommands().setConfig("maxmemory", "67108864");
        }
    }

    /** 검증 목적: 실제 Redis 설정과 정보로 정책과 한도 누락을 감지하고 복구한다. */
    @DisplayName("실제 Redis 설정과 정보로 정책과 한도 누락을 감지하고 복구한다")
    @Test
    void realConfigAndInfoDetectPolicyAndMissingLimitAndRecover() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var safety = new RedisOperationalSafety(redis, RedisOperationalSafetyTest.properties(false, 0), environment);
        safety.refresh();
        assertTrue(safety.isReady());
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().setConfig("maxmemory-policy", "allkeys-lru");
            safety.refresh();
            assertFalse(safety.isReady());
            connection.serverCommands().setConfig("maxmemory-policy", "noeviction");
            connection.serverCommands().setConfig("maxmemory", "0");
            safety.refresh();
            assertFalse(safety.isReady());
            connection.serverCommands().setConfig("maxmemory", "67108864");
            safety.refresh();
            assertTrue(safety.isReady());
        }
    }

    /** 검증 목적: ACL이 CONFIG를 거부해도 명시적 확인과 INFO 조회가 필요하다. */
    @DisplayName("ACL이 CONFIG를 거부해도 명시적 확인과 INFO 조회가 필요하다")
    @Test
    void actualAclConfigDenialUsesAttestationButStillRequiresInfo() throws Exception {
        assertEquals(0, REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "safety-check", "on",
                ">test-password", "~*", "+@all", "-config").getExitCode());
        var settings = new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        settings.setUsername("safety-check");
        settings.setPassword("test-password");
        var factory = new LettuceConnectionFactory(settings);
        factory.afterPropertiesSet();
        try {
            var restrictedRedis = new StringRedisTemplate(factory);
            restrictedRedis.afterPropertiesSet();
            var environment = new MockEnvironment();
            environment.setActiveProfiles("prod");
            var unattested = new RedisOperationalSafety(restrictedRedis,
                    RedisOperationalSafetyTest.properties(false, 0), environment);
            unattested.refresh();
            assertFalse(unattested.isReady());
            var attested = new RedisOperationalSafety(restrictedRedis,
                    RedisOperationalSafetyTest.properties(true, 0), environment);
            attested.refresh();
            assertTrue(attested.isReady());
            assertEquals(0, REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "safety-check", "-info").getExitCode());
            attested.refresh();
            assertFalse(attested.isReady());
        } finally {
            factory.destroy();
            REDIS.execInContainer("redis-cli", "ACL", "DELUSER", "safety-check");
        }
    }

    /** 검증 목적: 메모리 차단 시 신규 등록은 거절하고 기존 신청 작업은 허용한다. */
    @DisplayName("메모리 차단 시 신규 등록은 거절하고 기존 신청 작업은 허용한다")
    @Test
    void memoryBlockRejectsNewRegistrationAndAllowsEveryExistingRequestOperation() {
        var properties = RedisOperationalSafetyTest.properties(false, 0);
        var store = new RedisWaitingRoomStore(redis, new ObjectMapper());
        var probe = new RedisOperationalSafetyTest.FixtureProbe();
        var safety = new RedisOperationalSafety(true, properties, probe);
        var coordinator = new WaitingRoomRecoveryCoordinator(store, properties,
                WaitingRoomRecoveryCoordinatorTest.listenerProvider(), safety);
        coordinator.recoverIfNeeded();
        safety.refresh();
        var service = new WaitingRoomService(store, properties, coordinator, safety);
        var first = service.register("reservation-service", "existing-1", "service-entry");
        service.register("reservation-service", "existing-2", "service-entry");
        probe.used = "900";
        safety.refresh();
        assertTrue(safety.isReady());
        assertThrows(RedisOperationalSafety.CapacityUnavailableException.class,
                () -> service.register("reservation-service", "new-registration", "service-entry"));
        assertNull(redis.opsForValue().get("waiting-request:{reservation-service}:new-registration"));
        String token = first.waitingUrl().substring(first.waitingUrl().indexOf("#token=") + 7);
        String cookie = service.exchangeToken("reservation-service", token);
        assertEquals("existing-1", service.poll(cookie).reservationRequestId());
        assertNotNull(service.issueToken("reservation-service", "existing-1").waitingUrl());
        store.admitNext("reservation-service", 1, java.time.Duration.ofMinutes(2),
                java.time.Duration.ofMinutes(20), java.time.Duration.ofHours(23), java.time.Duration.ofHours(1));
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, service.enter("reservation-service", "existing-1").resultType());
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, service.complete("reservation-service", "existing-1").resultType());
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, service.cancel("reservation-service", "existing-2").resultType());
        assertTrue(coordinator.isAvailable());
        probe.used = "899";
        safety.refresh();
        assertEquals(RedisWaitingRoomStore.ResultType.CREATED,
                service.register("reservation-service", "new-registration", "service-entry").resultType());
    }

    /** 재연결 후 정책 변경은 이전 NORMAL 상태로 등록을 재개하기 전에 확인합니다. */
    @DisplayName("복구 시 정책을 다시 확인한 뒤 등록과 준비 상태를 연다")
    @Test
    void recoveryRechecksPolicyBeforeReopeningRegistrationAndReadiness() {
        var properties = RedisOperationalSafetyTest.properties(false, 0);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var safety = new RedisOperationalSafety(redis, properties, environment);
        safety.refresh();
        var store = new RedisWaitingRoomStore(redis, new ObjectMapper());
        var coordinator = new WaitingRoomRecoveryCoordinator(store, properties,
                WaitingRoomRecoveryCoordinatorTest.listenerProvider(), safety);
        coordinator.recoverIfNeeded();
        var service = new WaitingRoomService(store, properties, coordinator, safety);
        service.register("reservation-service", "before-recovery", "service-entry");
        coordinator.markRedisUnavailable();
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().setConfig("maxmemory-policy", "allkeys-lru");
        }

        coordinator.recoverIfNeeded();

        assertTrue(coordinator.isAvailable());
        assertFalse(safety.isReady());
        assertEquals("OUT_OF_SERVICE", new org.jn.waitingroom.config.WaitingRoomReadinessHealthIndicator(
                coordinator, safety).health().getStatus().getCode());
        assertThrows(RedisOperationalSafety.CapacityUnavailableException.class,
                () -> service.register("reservation-service", "unsafe-registration", "service-entry"));
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED,
                service.cancel("reservation-service", "before-recovery").resultType());
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().setConfig("maxmemory-policy", "noeviction");
        }
        coordinator.markRedisUnavailable();
        coordinator.recoverIfNeeded();
        assertTrue(safety.isReady());
        assertEquals(RedisWaitingRoomStore.ResultType.CREATED,
                service.register("reservation-service", "safe-registration", "service-entry").resultType());
    }
}
