/**
 * @author jeongnam
 * @since 2026-09-30
 * @file RedisOperationalSafetyTest.java
 * @description 운영 Redis 정책과 메모리 경계에서 등록·readiness 상태를 검증합니다.
 */
package org.jn.waitingroom.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jn.waitingroom.config.WaitingRoomProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

@DisplayName("Redis 운영 안전성 정책 테스트")
class RedisOperationalSafetyTest {
    /** 검증 목적: 운영 외 환경은 항상 준비 상태이며 Redis를 조회하지 않는다. */
    @DisplayName("운영 외 환경은 항상 준비 상태이며 Redis를 조회하지 않는다")
    @Test
    void nonProductionIsAlwaysReadyAndNeverQueriesRedis() {
        var probe = new FixtureProbe();
        probe.policyFailure = new AssertionError("non-prod Redis access");
        var safety = new RedisOperationalSafety(false, properties(false, 0), probe);
        safety.refresh();
        safety.run(null);
        assertTrue(safety.isReady());
        assertDoesNotThrow(safety::requireRegistrationCapacity);
        assertEquals(0, probe.policyReads);
        assertEquals(0, probe.infoReads);
    }

    /** 검증 목적: 운영 환경은 차단 상태로 시작하고 복구 후 실행기를 수행한다. */
    @DisplayName("운영 환경은 차단 상태로 시작하고 복구 후 실행기를 수행한다")
    @Test
    void productionStartsClosedAndRunnerRunsAfterRecovery() {
        var safety = safety(new FixtureProbe());
        assertFalse(safety.isReady());
        assertThrows(RedisOperationalSafety.CapacityUnavailableException.class, safety::requireRegistrationCapacity);
        assertTrue(safety.getOrder() > org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        safety.run(null);
        assertTrue(safety.isReady());
    }

    /** 검증 목적: 최초 점검 실패 후 재시도는 기동 완료를 기다렸다가 복구한다. */
    @DisplayName("최초 점검 실패 후 재시도는 기동 완료를 기다렸다가 복구한다")
    @Test
    void scheduledRetryWaitsForStartupAndRecoversAfterFailedInitialRefresh() {
        var probe = new FixtureProbe();
        probe.infoFailure = new RedisConnectionFailureException("offline");
        var safety = safety(probe);
        safety.retrySafetyCheck();
        assertEquals(0, probe.policyReads);
        safety.run(null);
        assertClosed(safety);
        probe.infoFailure = null;
        safety.retrySafetyCheck();
        assertTrue(safety.isReady());
    }

    /** 검증 목적: noeviction 정책은 대소문자와 무관하게 허용하고 다른 정책은 거절한다. */
    @DisplayName("noeviction 정책은 대소문자와 무관하게 허용하고 다른 정책은 거절한다")
    @Test
    void acceptsNoevictionCaseInsensitivelyAndRejectsOtherPolicies() {
        var probe = new FixtureProbe();
        var safety = safety(probe);
        probe.policy = "NoEvIcTiOn";
        safety.refresh();
        assertTrue(safety.isReady());
        assertDoesNotThrow(safety::requireRegistrationCapacity);
        probe.policy = "allkeys-lru";
        safety.refresh();
        assertClosed(safety);
        probe.policy = "noeviction";
        safety.refresh();
        assertTrue(safety.isReady());
    }

    /** 검증 목적: CONFIG 접근 거부 시 명시적 확인과 성공한 INFO 조회가 필요하다. */
    @DisplayName("CONFIG 접근 거부 시 명시적 확인과 성공한 INFO 조회가 필요하다")
    @Test
    void deniedConfigRequiresExplicitAttestationAndSuccessfulInfo() {
        var probe = new FixtureProbe();
        probe.policyFailure = new RedisOperationalSafety.PolicyAccessDeniedException();
        var unattested = safety(probe);
        unattested.refresh();
        assertClosed(unattested);
        var attested = new RedisOperationalSafety(true, properties(true, 0), probe);
        attested.refresh();
        assertTrue(attested.isReady());
        assertEquals(1, probe.infoReads);
        probe.infoFailure = new RedisConnectionFailureException("secret connection text");
        attested.refresh();
        assertClosed(attested);
    }

    /** 검증 목적: 명시적 확인은 연결 실패나 잘못된 정책을 가리지 못한다. */
    @DisplayName("명시적 확인은 연결 실패나 잘못된 정책을 가리지 못한다")
    @Test
    void attestationNeverMasksAConnectionFailureOrInvalidPolicy() {
        var probe = new FixtureProbe();
        var safety = new RedisOperationalSafety(true, properties(true, 0), probe);
        probe.policyFailure = new RedisConnectionFailureException("offline");
        assertDoesNotThrow(safety::refresh);
        assertClosed(safety);
        probe.policyFailure = null;
        probe.policy = "volatile-lru";
        safety.refresh();
        assertClosed(safety);
    }

    /** 검증 목적: 최대 메모리 누락 또는 0은 양수인 대체 한도에서만 허용한다. */
    @DisplayName("최대 메모리 누락 또는 0은 양수인 대체 한도에서만 허용한다")
    @Test
    void missingOrZeroMaxmemoryUsesPositiveConfiguredFallbackOnly() {
        for (String limit : new String[]{null, "0"}) {
            var probe = new FixtureProbe();
            probe.limit = limit;
            var safety = new RedisOperationalSafety(true, properties(false, 1000), probe);
            safety.refresh();
            assertTrue(safety.isReady());
            var unknown = safety(probe);
            unknown.refresh();
            assertClosed(unknown);
        }
    }

    /** 검증 목적: 메모리 정보가 잘못되거나 없으면 준비 상태를 닫고 재시도할 수 있다. */
    @DisplayName("메모리 정보가 잘못되거나 없으면 준비 상태를 닫고 재시도할 수 있다")
    @Test
    void malformedNegativeOrMissingMemoryKeepsReadinessClosedAndRefreshRetryable() {
        for (String used : new String[]{null, "bad", "-1", "9223372036854775808"}) {
            var probe = new FixtureProbe();
            var safety = safety(probe);
            probe.used = used;
            assertDoesNotThrow(safety::refresh);
            assertClosed(safety);
            probe.used = "100";
            safety.refresh();
            assertTrue(safety.isReady());
        }
        for (String limit : new String[]{"bad", "-1"}) {
            var probe = new FixtureProbe();
            probe.limit = limit;
            var safety = new RedisOperationalSafety(true, properties(false, 1000), probe);
            safety.refresh();
            assertClosed(safety);
        }
    }

    /** 검증 목적: 경고와 차단 경계에서 신규 등록만 차단하고 복구한다. */
    @DisplayName("경고와 차단 경계에서 신규 등록만 차단하고 복구한다")
    @Test
    void exactWarningAndBlockBoundariesOnlyRejectRegistrationAtBlockAndRecover() {
        var probe = new FixtureProbe();
        var safety = safety(probe);
        var logger = (Logger) LoggerFactory.getLogger(RedisOperationalSafety.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            probe.used = "799";
            safety.refresh();
            assertDoesNotThrow(safety::requireRegistrationCapacity);
            assertEquals(0, appender.list.size());
            probe.used = "800";
            safety.refresh();
            safety.refresh();
            assertTrue(safety.isReady());
            assertDoesNotThrow(safety::requireRegistrationCapacity);
            assertEquals(1, appender.list.size());
            probe.used = "900";
            safety.refresh();
            safety.refresh();
            assertTrue(safety.isReady());
            assertThrows(RedisOperationalSafety.CapacityUnavailableException.class, safety::requireRegistrationCapacity);
            assertEquals(2, appender.list.size());
            probe.used = "899";
            safety.refresh();
            assertDoesNotThrow(safety::requireRegistrationCapacity);
            probe.used = "700";
            safety.refresh();
            probe.used = "800";
            safety.refresh();
            assertEquals(4, appender.list.size());
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 검증 목적: 매우 큰 메모리 값에서도 비율 계산은 오버플로하지 않는다. */
    @DisplayName("매우 큰 메모리 값에서도 비율 계산은 오버플로하지 않는다")
    @Test
    void memoryDivisionDoesNotOverflowAtLongMaxValue() {
        var probe = new FixtureProbe();
        probe.used = "4611686018427387903";
        probe.limit = "9223372036854775807";
        var safety = safety(probe);
        safety.refresh();
        assertTrue(safety.isReady());
        assertDoesNotThrow(safety::requireRegistrationCapacity);
        probe.used = probe.limit;
        safety.refresh();
        assertTrue(safety.isReady());
        assertThrows(RedisOperationalSafety.CapacityUnavailableException.class, safety::requireRegistrationCapacity);
    }

    /** 검증 목적: 클러스터는 모든 노드의 정책과 가장 높은 메모리 상태를 사용한다. */
    @DisplayName("클러스터는 모든 노드의 정책과 가장 높은 메모리 상태를 사용한다")
    @Test
    void clusterUsesEveryNodesPolicyAndHighestMemoryState() {
        var config = clusterConfig();
        var memory = clusterMemory("100", "1600");
        memory.setProperty("node-b.maxmemory", "2000");
        var safety = clusterSafety(config, memory, 0);
        safety.refresh();
        assertTrue(safety.isReady());
        assertDoesNotThrow(safety::requireRegistrationCapacity);

        memory.setProperty("node-b.used_memory", "1800");
        safety.refresh();
        assertTrue(safety.isReady());
        assertThrows(RedisOperationalSafety.CapacityUnavailableException.class, safety::requireRegistrationCapacity);

        config.setProperty("node-a.maxmemory-policy", "allkeys-lru");
        safety.refresh();
        assertClosed(safety);
    }

    /** 검증 목적: 클러스터 노드 정보가 없거나 잘못되거나 짝이 맞지 않으면 거절한다. */
    @DisplayName("클러스터 노드 정보가 없거나 잘못되거나 짝이 맞지 않으면 거절한다")
    @Test
    void clusterRejectsMissingInvalidOrUnpairedNodeData() {
        for (String key : new String[]{"node-a.maxmemory-policy", "node-b.used_memory"}) {
            var config = clusterConfig();
            var memory = clusterMemory("100", "100");
            config.remove(key);
            memory.remove(key);
            var safety = clusterSafety(config, memory, 0);
            safety.refresh();
            assertClosed(safety);
        }
        for (String value : new String[]{"bad", "-1"}) {
            var memory = clusterMemory("100", value);
            var safety = clusterSafety(clusterConfig(), memory, 1000);
            safety.refresh();
            assertClosed(safety);
        }
        var memory = clusterMemory("100", "100");
        memory.setProperty("node-c.used_memory", "100");
        memory.setProperty("node-c.maxmemory", "1000");
        var safety = clusterSafety(clusterConfig(), memory, 0);
        safety.refresh();
        assertClosed(safety);
    }

    /** 검증 목적: 클러스터 대체 메모리 한도는 노드마다 적용한다. */
    @DisplayName("클러스터 대체 메모리 한도는 노드마다 적용한다")
    @Test
    void clusterFallbackMemoryLimitAppliesToEachNode() {
        for (String limit : new String[]{null, "0"}) {
            var memory = clusterMemory("100", "900");
            if (limit == null) memory.remove("node-b.maxmemory");
            else memory.setProperty("node-b.maxmemory", limit);
            var safety = clusterSafety(clusterConfig(), memory, 1000);
            safety.refresh();
            assertTrue(safety.isReady());
            assertThrows(RedisOperationalSafety.CapacityUnavailableException.class, safety::requireRegistrationCapacity);
        }
    }

    private Properties clusterConfig() {
        var config = new Properties();
        config.setProperty("node-a.maxmemory-policy", "NoEvIcTiOn");
        config.setProperty("node-b.maxmemory-policy", "noeviction");
        return config;
    }

    private Properties clusterMemory(String firstUsed, String secondUsed) {
        var memory = new Properties();
        memory.setProperty("node-a.used_memory", firstUsed);
        memory.setProperty("node-a.maxmemory", "1000");
        memory.setProperty("node-b.used_memory", secondUsed);
        memory.setProperty("node-b.maxmemory", "1000");
        return memory;
    }

    /** Spring Redis가 반환하는 원래 CONFIG/INFO Properties를 실제 생성자 경계에 제공합니다. */
    private RedisOperationalSafety clusterSafety(Properties config, Properties memory, long fallbackBytes) {
        var redis = mock(StringRedisTemplate.class);
        // refresh마다 CONFIG/INFO가 다시 실행되므로 마지막 응답을 고정하지 않습니다.
        org.mockito.Mockito.doAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            var connection = mock(org.springframework.data.redis.connection.RedisConnection.class);
            var commands = mock(org.springframework.data.redis.connection.RedisServerCommands.class);
            org.mockito.Mockito.when(connection.serverCommands()).thenReturn(commands);
            org.mockito.Mockito.when(commands.getConfig("maxmemory-policy")).thenReturn(config);
            org.mockito.Mockito.when(commands.info("memory")).thenReturn(memory);
            return callback.doInRedis(connection);
        }).when(redis).execute(any(RedisCallback.class));
        return new RedisOperationalSafety(redis, properties(false, fallbackBytes),
                new MockEnvironment().withProperty("spring.profiles.active", "prod"));
    }

    static WaitingRoomProperties properties(boolean attested, long fallbackBytes) {
        var p = WaitingRoomRecoveryCoordinatorTest.properties();
        return new WaitingRoomProperties(p.schedulerInterval(), p.maxRetryAfter(), p.retryAfterSafetyMargin(),
                p.quarantineTtl(), p.maintenanceLeaseTimeout(), p.maxAdmissionsPerRun(),
                p.maxActiveExpirationsPerRun(), p.maxWaitingExpirationsPerRun(), p.maxCandidatesScannedPerRun(),
                p.services(), p.security(), p.heartbeatExpirationRecoveryGrace(), p.maintenanceHealthTimeout(),
                java.time.Duration.ofSeconds(5), 0.80, 0.90, attested, fallbackBytes);
    }

    private RedisOperationalSafety safety(FixtureProbe probe) {
        return new RedisOperationalSafety(true, properties(false, 0), probe);
    }

    private void assertClosed(RedisOperationalSafety safety) {
        assertFalse(safety.isReady());
        assertThrows(RedisOperationalSafety.CapacityUnavailableException.class, safety::requireRegistrationCapacity);
    }

    /** 정확한 메모리 값과 접근 거부만 제공하며 Spring 내부 객체를 모방하지 않습니다. */
    static final class FixtureProbe implements RedisOperationalSafety.Probe {
        String policy = "noeviction";
        String used = "100";
        String limit = "1000";
        RuntimeException infoFailure;
        Throwable policyFailure;
        int policyReads;
        int infoReads;

        @Override
        public Properties maxmemoryPolicies() {
            policyReads++;
            if (policyFailure instanceof RuntimeException exception) throw exception;
            if (policyFailure instanceof Error error) throw error;
            var config = new Properties();
            if (policy != null) config.setProperty("maxmemory-policy", policy);
            return config;
        }

        @Override
        public Properties memoryInfo() {
            infoReads++;
            if (infoFailure != null) throw infoFailure;
            var info = new Properties();
            if (used != null) info.setProperty("used_memory", used);
            if (limit != null) info.setProperty("maxmemory", limit);
            return info;
        }
    }
}
