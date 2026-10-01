/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomRecoveryCoordinatorTest.java
 * @description 초기 차단, 전체 서비스 복구 및 동시 복구의 상태 전이를 검증합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Redis 복구 조정 테스트")
class WaitingRoomRecoveryCoordinatorTest {
    /** 검증 목적: 초기 상태에서는 요청을 차단한다. */
    @DisplayName("초기 상태에서는 요청을 차단한다")
    @Test
    void startsUnavailableAndRejectsRequests() {
        var coordinator = new WaitingRoomRecoveryCoordinator(mock(RedisWaitingRoomStore.class), properties(), listenerProvider(), mock(RedisOperationalSafety.class));

        assertFalse(coordinator.isAvailable());
        assertThrows(WaitingRoomRecoveryCoordinator.UnavailableException.class, coordinator::requireAvailable);
    }

    /** 검증 목적: 모든 서비스를 복구한 뒤에만 요청을 허용한다. */
    @DisplayName("모든 서비스를 복구한 뒤에만 요청을 허용한다")
    @Test
    void becomesAvailableOnlyAfterBootstrappingEveryService() {
        var store = mock(RedisWaitingRoomStore.class);
        var properties = properties();
        var coordinator = new WaitingRoomRecoveryCoordinator(store, properties, listenerProvider(), mock(RedisOperationalSafety.class));
        doAnswer(call -> {
            assertFalse(coordinator.isAvailable());
            return new RedisWaitingRoomStore.RecoveryBootstrap(false, 0);
        }).when(store).bootstrapRecovery(anyString(), anyString(), any(), any(), any());

        coordinator.recoverIfNeeded();

        assertTrue(coordinator.isAvailable());
        assertDoesNotThrow(coordinator::requireAvailable);
        var recoveryIds = org.mockito.ArgumentCaptor.forClass(String.class);
        for (String serviceId : properties.services().keySet()) {
            verify(store).bootstrapRecovery(eq(serviceId), recoveryIds.capture(),
                    eq(Duration.ofMinutes(20)), eq(Duration.ofMinutes(10)), eq(Duration.ofMinutes(20)));
        }
        assertFalse(recoveryIds.getValue().isBlank());
        assertEquals(recoveryIds.getAllValues().getFirst(), recoveryIds.getAllValues().getLast());
        coordinator.recoverIfNeeded();
        verifyNoMoreInteractions(store);
    }

    /** 검증 목적: 일부 서비스 복구 실패 후 재시도는 모든 서비스를 다시 검사한다. */
    @DisplayName("일부 서비스 복구 실패 후 재시도는 모든 서비스를 다시 검사한다")
    @Test
    void partialFailureRemainsUnavailableAndLaterProbeRetriesEveryService() {
        var store = mock(RedisWaitingRoomStore.class);
        var coordinator = new WaitingRoomRecoveryCoordinator(store, properties(), listenerProvider(), mock(RedisOperationalSafety.class));
        when(store.bootstrapRecovery(eq("second-service"), anyString(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("offline"))
                .thenReturn(new RedisWaitingRoomStore.RecoveryBootstrap(false, 0));

        assertDoesNotThrow(coordinator::recoverIfNeeded);
        assertFalse(coordinator.isAvailable());
        coordinator.recoverIfNeeded();

        assertTrue(coordinator.isAvailable());
        verify(store, times(2)).bootstrapRecovery(eq("reservation-service"), anyString(), any(), any(), any());
    }

    /** 검증 목적: 스크립트 실패는 복구 검사 밖으로 예외를 전파하지 않는다. */
    @DisplayName("스크립트 실패는 복구 검사 밖으로 예외를 전파하지 않는다")
    @Test
    void scriptFailureDoesNotEscapeTheProbe() {
        var store = mock(RedisWaitingRoomStore.class);
        var coordinator = new WaitingRoomRecoveryCoordinator(store, properties(), listenerProvider(), mock(RedisOperationalSafety.class));
        when(store.bootstrapRecovery(anyString(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("invalid script response"));

        assertDoesNotThrow(coordinator::recoverIfNeeded);
        assertFalse(coordinator.isAvailable());
    }

    /** 지연 중인 Redis 호출과 겹친 probe는 두 번째 bootstrap pass를 시작하지 않습니다. */
    @DisplayName("동시 복구 검사는 겹치지 않고 실패 시 게이트를 닫는다")
    @Test
    void concurrentProbeDoesNotOverlapAndConcurrentFailureKeepsTheGateClosed() throws Exception {
        var store = mock(RedisWaitingRoomStore.class);
        var coordinator = new WaitingRoomRecoveryCoordinator(store, properties(), listenerProvider(), mock(RedisOperationalSafety.class));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(store.bootstrapRecovery(eq("reservation-service"), anyString(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new RedisWaitingRoomStore.RecoveryBootstrap(false, 0);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(coordinator::recoverIfNeeded);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                executor.submit(coordinator::recoverIfNeeded).get(1, TimeUnit.SECONDS);
                coordinator.markRedisUnavailable();
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }

        assertFalse(coordinator.isAvailable());
        verify(store).bootstrapRecovery(eq("reservation-service"), anyString(), any(), any(), any());
        coordinator.recoverIfNeeded();
        assertTrue(coordinator.isAvailable());
    }

    /** 검증 목적: 스프링 실행기 순서는 일반 실행기 전에 복구를 완료한다. */
    @DisplayName("스프링 실행기 순서는 일반 실행기 전에 복구를 완료한다")
    @Test
    void springRunnerOrderingRecoversBeforeOrdinaryRunners() throws Exception {
        var coordinator = new WaitingRoomRecoveryCoordinator(mock(RedisWaitingRoomStore.class), properties(), listenerProvider(), mock(RedisOperationalSafety.class));
        ApplicationRunner ordinaryRunner = args -> coordinator.requireAvailable();
        var runners = new ArrayList<ApplicationRunner>(List.of(ordinaryRunner, coordinator));
        AnnotationAwareOrderComparator.sort(runners);

        assertSame(coordinator, runners.getFirst());
        for (ApplicationRunner runner : runners) {
            runner.run(null);
        }
        assertTrue(coordinator.isAvailable());
    }

    /** 검증 목적: 정기 복구 검사는 최초 실행기 시도가 끝나기를 기다린다. */
    @DisplayName("정기 복구 검사는 최초 실행기 시도가 끝나기를 기다린다")
    @Test
    void scheduledProbeWaitsForTheInitialRunnerAttempt() {
        var store = mock(RedisWaitingRoomStore.class);
        var coordinator = new WaitingRoomRecoveryCoordinator(store, properties(), listenerProvider(), mock(RedisOperationalSafety.class));

        coordinator.probeRecovery();
        verifyNoInteractions(store);
        coordinator.run(null);
        assertTrue(coordinator.isAvailable());
        coordinator.markRedisUnavailable();
        coordinator.probeRecovery();
        assertTrue(coordinator.isAvailable());
        verify(store, times(2)).bootstrapRecovery(eq("reservation-service"), anyString(), any(), any(), any());
    }

    /** 안전성 조회가 진행 중이면 bootstrap이 끝나도 업무 gate는 열리지 않습니다. */
    @DisplayName("복구는 메모리 점검이 끝날 때까지 게이트를 열지 않는다")
    @Test
    void recoveryWaitsForMemoryCheckBeforeReopeningTheGate() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var properties = properties();
        var safety = new RedisOperationalSafety(true, properties, new RedisOperationalSafety.Probe() {
            @Override
            public java.util.Properties maxmemoryPolicies() {
                var policies = new java.util.Properties();
                policies.setProperty("maxmemory-policy", "noeviction");
                return policies;
            }

            @Override
            public java.util.Properties memoryInfo() {
                if (block.get()) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException exception) { throw new RuntimeException(exception); }
                }
                var memory = new java.util.Properties();
                memory.setProperty("used_memory", "100");
                memory.setProperty("maxmemory", "1000");
                return memory;
            }
        });
        safety.refresh();
        var store = mock(RedisWaitingRoomStore.class);
        var coordinator = new WaitingRoomRecoveryCoordinator(store, properties, listenerProvider(), safety);
        coordinator.recoverIfNeeded();
        coordinator.markRedisUnavailable();
        block.set(true);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var recovering = executor.submit(coordinator::recoverIfNeeded);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(coordinator.isAvailable());
                assertEquals("OUT_OF_SERVICE", new org.jn.waitingroom.config.WaitingRoomReadinessHealthIndicator(
                        coordinator, safety).health().getStatus().getCode());
                var service = new WaitingRoomService(store, properties, coordinator, safety);
                assertThrows(WaitingRoomRecoveryCoordinator.UnavailableException.class,
                        () -> service.register("reservation-service", "blocked-during-check", "service-entry"));
            } finally {
                release.countDown();
            }
            recovering.get(5, TimeUnit.SECONDS);
        }
        assertTrue(coordinator.isAvailable());
        assertTrue(safety.isReady());
    }

    /** 검증 목적: INFO 실패 시 신규 등록 안전성은 닫지만 기존 신청 게이트는 허용한다. */
    @DisplayName("INFO 실패 시 신규 등록 안전성은 닫지만 기존 신청 게이트는 허용한다")
    @Test
    void recoveryWithInfoFailureKeepsSafetyClosedButAllowsExistingRequestGate() {
        var properties = properties();
        var probe = new RedisOperationalSafetyTest.FixtureProbe();
        var safety = new RedisOperationalSafety(true, properties, probe);
        safety.refresh();
        var coordinator = new WaitingRoomRecoveryCoordinator(mock(RedisWaitingRoomStore.class), properties, listenerProvider(), safety);
        coordinator.recoverIfNeeded();
        coordinator.markRedisUnavailable();
        probe.infoFailure = new RedisConnectionFailureException("offline INFO");

        coordinator.recoverIfNeeded();

        assertTrue(coordinator.isAvailable());
        assertFalse(safety.isReady());
        assertThrows(RedisOperationalSafety.CapacityUnavailableException.class, safety::requireRegistrationCapacity);
        probe.infoFailure = null;
        safety.refresh();
        assertTrue(safety.isReady());
    }

    static WaitingRoomProperties properties() {
        var service = new WaitingRoomProperties.ServiceProperties(1, Duration.ofMinutes(20),
                Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofMinutes(5), 10,
                Duration.ofMinutes(1), 0, Duration.ofDays(1), Duration.ofHours(23),
                Map.of("service-entry", "https://service.example/entry"));
        var services = new LinkedHashMap<String, WaitingRoomProperties.ServiceProperties>();
        services.put("reservation-service", service);
        services.put("second-service", service);
        return new WaitingRoomProperties(Duration.ofSeconds(1), Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofHours(1), Duration.ofSeconds(5),
                100, 100, 100, 300, services);
    }

    /** 검증 목적: 복구 성공은 리스너를 시작하고 Redis 실패는 리스너를 중단한다. */
    @DisplayName("복구 성공은 리스너를 시작하고 Redis 실패는 리스너를 중단한다")
    @Test
    void successfulRecoveryStartsTheListenerAndRedisFailureStopsIt() {
        var container = mock(org.springframework.data.redis.listener.RedisMessageListenerContainer.class);
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("listener", container);
        var coordinator = new WaitingRoomRecoveryCoordinator(mock(RedisWaitingRoomStore.class), properties(),
factory.getBeanProvider(org.springframework.data.redis.listener.RedisMessageListenerContainer.class), mock(RedisOperationalSafety.class));

        coordinator.recoverIfNeeded();
        assertTrue(coordinator.isAvailable());
        verify(container).start();
        coordinator.markRedisUnavailable();
        assertFalse(coordinator.isAvailable());
        verify(container).stop();
    }

    /** 검증 목적: 리스너 시작 실패는 요청을 차단하고 재시도할 수 있다. */
    @DisplayName("리스너 시작 실패는 요청을 차단하고 재시도할 수 있다")
    @Test
    void listenerStartupFailureRemainsUnavailableAndCanRetry() {
        var container = mock(org.springframework.data.redis.listener.RedisMessageListenerContainer.class);
        doThrow(new RedisConnectionFailureException("listener offline")).doNothing().when(container).start();
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("listener", container);
        var coordinator = new WaitingRoomRecoveryCoordinator(mock(RedisWaitingRoomStore.class), properties(),
factory.getBeanProvider(org.springframework.data.redis.listener.RedisMessageListenerContainer.class), mock(RedisOperationalSafety.class));

        assertDoesNotThrow(coordinator::recoverIfNeeded);
        assertFalse(coordinator.isAvailable());
        verify(container).stop();
        coordinator.recoverIfNeeded();
        assertTrue(coordinator.isAvailable());
        verify(container, times(2)).start();
    }

    /** 검증 목적: 복구는 이전 리스너 종료가 완료되기를 기다린다. */
    @DisplayName("복구는 이전 리스너 종료가 완료되기를 기다린다")
    @Test
    void recoveryWaitsUntilThePreviousListenerStopCompletes() throws Exception {
        var container = mock(org.springframework.data.redis.listener.RedisMessageListenerContainer.class);
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("listener", container);
        var coordinator = new WaitingRoomRecoveryCoordinator(mock(RedisWaitingRoomStore.class), properties(),
factory.getBeanProvider(org.springframework.data.redis.listener.RedisMessageListenerContainer.class), mock(RedisOperationalSafety.class));
        coordinator.recoverIfNeeded();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(container).stop();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stopping = executor.submit(coordinator::markRedisUnavailable);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                coordinator.recoverIfNeeded();
                assertFalse(coordinator.isAvailable());
                verify(container).start();
            } finally {
                release.countDown();
            }
            stopping.get(5, TimeUnit.SECONDS);
        }
        coordinator.recoverIfNeeded();
        assertTrue(coordinator.isAvailable());
        verify(container, times(2)).start();
    }

    static org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.listener.RedisMessageListenerContainer>
    listenerProvider() {
        return new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(org.springframework.data.redis.listener.RedisMessageListenerContainer.class);
    }
}
