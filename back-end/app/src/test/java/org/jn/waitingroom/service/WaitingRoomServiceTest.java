/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomServiceTest.java
 * @description 복구 전에는 모든 Redis 업무 진입점이 저장소 접근 전에 차단되는지 검증합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@DisplayName("대기열 서비스 접근 제어 테스트")
class WaitingRoomServiceTest {
    /** 검증 목적: 모든 Redis 작업은 서비스 가용성 검사를 먼저 수행한다. */
    @DisplayName("모든 Redis 작업은 서비스 가용성 검사를 먼저 수행한다")
    @Test
    void everyRedisOperationStartsWithTheAvailabilityGate() {
        var store = mock(RedisWaitingRoomStore.class);
        var properties = WaitingRoomRecoveryCoordinatorTest.properties();
        var safety = new RedisOperationalSafety(true, properties, new RedisOperationalSafetyTest.FixtureProbe());
        var coordinator = new WaitingRoomRecoveryCoordinator(store, properties, WaitingRoomRecoveryCoordinatorTest.listenerProvider(), safety);
        var service = new WaitingRoomService(store, properties, coordinator, safety);
        List<Runnable> operations = List.of(
                () -> service.register(null, null, null),
                () -> service.poll(null),
                () -> service.exchangeToken(null, null),
                () -> service.issueToken(null, null),
                () -> service.enter(null, null),
                () -> service.complete(null, null),
                () -> service.cancel(null, null));

        for (Runnable operation : operations) {
            assertThrows(WaitingRoomRecoveryCoordinator.UnavailableException.class, operation::run);
        }
        verifyNoInteractions(store);
    }

    /** 입장 후 재조회에서도 저장된 redirectTargetId를 사용하고 polling을 재개하지 않습니다. */
    @DisplayName("입장 완료 상태 조회는 저장된 이동 주소를 반환한다")
    @Test
    void enteredPollReturnsTheStoredRedirectTarget() {
        var store = mock(RedisWaitingRoomStore.class);
        var properties = WaitingRoomRecoveryCoordinatorTest.properties();
        var coordinator = mock(WaitingRoomRecoveryCoordinator.class);
        var service = new WaitingRoomService(store, properties, coordinator, mock(RedisOperationalSafety.class));
        String secret = "A".repeat(43);
        String sessionHash = org.jn.waitingroom.domain.WaitingSecret.hash(secret);
        when(store.findSession("reservation-service", sessionHash))
                .thenReturn(new RedisWaitingRoomStore.Credential("reservation-service", "entered-request", 1, 0L));
        var state = new org.jn.waitingroom.domain.WaitingRequestState("entered-request", "reservation-service",
                org.jn.waitingroom.domain.WaitingRequestStatus.ENTERED, "service-entry", "payload",
                0, 1L, 2L, null, null, null, null, null, sessionHash, 1L);
        when(store.poll(anyString(), anyString(), any(), any(), any(), anyInt(), any(), any(), any(),
                any(), anyInt(), any(), anyDouble(), anyInt(), any(), anyString()))
                .thenReturn(new RedisWaitingRoomStore.WaitingSnapshot(
                        RedisWaitingRoomStore.ResultType.APPLIED, state, null, 0, null, null));

        var result = service.poll("reservation-service." + secret);

        assertEquals("https://service.example/entry?reservationRequestId=entered-request", result.redirectUrl());
        assertNull(result.nextPollAfterMs());
    }
}
