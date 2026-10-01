/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomReadinessHealthIndicatorTest.java
 * @description 복구 coordinator의 가용 상태만 readiness에 비밀 정보 없이 반영하는지 검증합니다.
 */
package org.jn.waitingroom.config;

import org.jn.waitingroom.service.WaitingRoomRecoveryCoordinator;
import org.jn.waitingroom.service.RedisOperationalSafety;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("대기열 준비 상태 테스트")
class WaitingRoomReadinessHealthIndicatorTest {
    /** 검증 목적: 복구 전에는 준비되지 않고 복구 후에는 준비 상태가 된다. */
    @DisplayName("복구 전에는 준비되지 않고 복구 후에는 준비 상태가 된다")
    @Test
    void readinessIsOutOfServiceUntilRecoveryAndUpAfterRecovery() {
        var coordinator = mock(WaitingRoomRecoveryCoordinator.class);
        var safety = mock(RedisOperationalSafety.class);
        when(safety.isReady()).thenReturn(true);
        var indicator = new WaitingRoomReadinessHealthIndicator(coordinator, safety);

        assertEquals("OUT_OF_SERVICE", indicator.health().getStatus().getCode());
        assertEquals(java.util.Map.of("recoveryReady", false, "redisSafetyReady", true), indicator.health().getDetails());
        when(coordinator.isAvailable()).thenReturn(true);
        assertEquals("UP", indicator.health().getStatus().getCode());
        assertEquals(java.util.Map.of("recoveryReady", true, "redisSafetyReady", true), indicator.health().getDetails());
        when(safety.isReady()).thenReturn(false);
        assertEquals("OUT_OF_SERVICE", indicator.health().getStatus().getCode());
        assertEquals(java.util.Map.of("recoveryReady", true, "redisSafetyReady", false), indicator.health().getDetails());
    }
}
