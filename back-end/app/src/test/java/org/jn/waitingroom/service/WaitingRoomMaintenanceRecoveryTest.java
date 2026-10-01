/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomMaintenanceRecoveryTest.java
 * @description 복구 gate와 lease 소유자만 기록하는 heartbeat 및 장애 차단을 검증합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("유지보수 작업의 복구 안전성 테스트")
class WaitingRoomMaintenanceRecoveryTest {
    /** 검증 목적: 복구 조정기가 중단되면 스케줄러와 PubSub 및 직접 유지보수를 막는다. */
    @DisplayName("복구 조정기가 중단되면 스케줄러와 PubSub 및 직접 유지보수를 막는다")
    @Test
    void unavailableCoordinatorBlocksScheduledPubSubAndDirectMaintenance() {
        var store = mock(RedisWaitingRoomStore.class);
        var properties = WaitingRoomRecoveryCoordinatorTest.properties();
var coordinator = new WaitingRoomRecoveryCoordinator(store, properties, WaitingRoomRecoveryCoordinatorTest.listenerProvider(), mock(RedisOperationalSafety.class));
        var maintenance = new WaitingRoomMaintenanceService(store, properties, coordinator, "test-instance");
        maintenance.onApplicationReady();

        maintenance.maintainAll();
        maintenance.onSlotReleased("waiting-room:slot-released:{reservation-service}");
        maintenance.maintain("reservation-service");

        verifyNoInteractions(store);
    }

    /** 검증 목적: 임대 소유자는 작업 후 임대 해제 전에 하트비트를 기록한다. */
    @DisplayName("임대 소유자는 작업 후 임대 해제 전에 하트비트를 기록한다")
    @Test
    void leaseOwnerRecordsHeartbeatAfterWorkAndBeforeRelease() {
        var store = healthyStore();
        var properties = WaitingRoomRecoveryCoordinatorTest.properties();
var coordinator = new WaitingRoomRecoveryCoordinator(store, properties, WaitingRoomRecoveryCoordinatorTest.listenerProvider(), mock(RedisOperationalSafety.class));
        coordinator.recoverIfNeeded();
        clearInvocations(store);
        var maintenance = new WaitingRoomMaintenanceService(store, properties, coordinator, "test-instance");

        maintenance.maintain("reservation-service");

        var order = inOrder(store);
        var leaseToken = org.mockito.ArgumentCaptor.forClass(String.class);
        order.verify(store).tryAcquireMaintenanceLease(eq("reservation-service"), leaseToken.capture(),
                eq(properties.maintenanceLeaseTimeout()));
        assertTrue(leaseToken.getValue().startsWith("test-instance:"));
        order.verify(store).admitNext(eq("reservation-service"), anyInt(), any(), any(), any(), any());
        order.verify(store).recordMaintenanceHeartbeat("reservation-service", leaseToken.getValue(), properties.maintenanceHealthTimeout());
        order.verify(store).releaseMaintenanceLease("reservation-service", leaseToken.getValue());
        assertTrue(coordinator.isAvailable());
    }

    /** 검증 목적: 임대가 없는 인스턴스는 하트비트를 기록하지 않는다. */
    @DisplayName("임대가 없는 인스턴스는 하트비트를 기록하지 않는다")
    @Test
    void instanceWithoutLeaseDoesNotRecordHeartbeat() {
        var store = healthyStore();
        when(store.tryAcquireMaintenanceLease(anyString(), anyString(), any())).thenReturn(false);
        var properties = WaitingRoomRecoveryCoordinatorTest.properties();
var coordinator = new WaitingRoomRecoveryCoordinator(store, properties, WaitingRoomRecoveryCoordinatorTest.listenerProvider(), mock(RedisOperationalSafety.class));
        coordinator.recoverIfNeeded();
        clearInvocations(store);

        new WaitingRoomMaintenanceService(store, properties, coordinator, "test-instance").maintain("reservation-service");

        verify(store, never()).recordMaintenanceHeartbeat(anyString(), anyString(), any());
        verify(store, never()).releaseMaintenanceLease(anyString(), anyString());
    }

    /** 검증 목적: 스케줄 작업 실패는 예외 전파 없이 가용성 게이트를 닫는다. */
    @DisplayName("스케줄 작업 실패는 예외 전파 없이 가용성 게이트를 닫는다")
    @Test
    void scheduledFailureClosesTheGateWithoutEscaping() {
        var store = healthyStore();
        var properties = WaitingRoomRecoveryCoordinatorTest.properties();
var coordinator = new WaitingRoomRecoveryCoordinator(store, properties, WaitingRoomRecoveryCoordinatorTest.listenerProvider(), mock(RedisOperationalSafety.class));
        coordinator.recoverIfNeeded();
        when(store.tryAcquireMaintenanceLease(anyString(), anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("offline"));
        var maintenance = new WaitingRoomMaintenanceService(store, properties, coordinator, "test-instance");
        maintenance.onApplicationReady();

        assertDoesNotThrow(maintenance::maintainAll);
        assertFalse(coordinator.isAvailable());
    }

    /** 검증 목적: PubSub 하트비트 실패는 예외 전파 없이 게이트를 닫고 임대를 해제한다. */
    @DisplayName("PubSub 하트비트 실패는 예외 전파 없이 게이트를 닫고 임대를 해제한다")
    @Test
    void pubSubHeartbeatFailureClosesTheGateAndReleasesLeaseWithoutEscaping() {
        var store = healthyStore();
        var properties = WaitingRoomRecoveryCoordinatorTest.properties();
var coordinator = new WaitingRoomRecoveryCoordinator(store, properties, WaitingRoomRecoveryCoordinatorTest.listenerProvider(), mock(RedisOperationalSafety.class));
        coordinator.recoverIfNeeded();
        when(store.recordMaintenanceHeartbeat(anyString(), anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("offline"));
        var maintenance = new WaitingRoomMaintenanceService(store, properties, coordinator, "test-instance");
        maintenance.onApplicationReady();

        assertDoesNotThrow(() -> maintenance.onSlotReleased("waiting-room:slot-released:{reservation-service}"));
        assertFalse(coordinator.isAvailable());
        verify(store).releaseMaintenanceLease(eq("reservation-service"), anyString());
    }

    /** 검증 목적: 하트비트 전에 임대를 잃으면 예외 전파 없이 게이트를 닫는다. */
    @DisplayName("하트비트 전에 임대를 잃으면 예외 전파 없이 게이트를 닫는다")
    @Test
    void losingTheLeaseBeforeHeartbeatClosesTheGateWithoutEscaping() {
        var store = healthyStore();
        var properties = WaitingRoomRecoveryCoordinatorTest.properties();
var coordinator = new WaitingRoomRecoveryCoordinator(store, properties, WaitingRoomRecoveryCoordinatorTest.listenerProvider(), mock(RedisOperationalSafety.class));
        coordinator.recoverIfNeeded();
        when(store.recordMaintenanceHeartbeat(anyString(), anyString(), any())).thenReturn(false);
        var maintenance = new WaitingRoomMaintenanceService(store, properties, coordinator, "test-instance");

        assertDoesNotThrow(() -> maintenance.maintain("reservation-service"));
        assertFalse(coordinator.isAvailable());
        verify(store).releaseMaintenanceLease(eq("reservation-service"), anyString());
    }

    private RedisWaitingRoomStore healthyStore() {
        var store = mock(RedisWaitingRoomStore.class);
        when(store.tryAcquireMaintenanceLease(anyString(), anyString(), any())).thenReturn(true);
        when(store.recordMaintenanceHeartbeat(anyString(), anyString(), any())).thenReturn(true);
        when(store.admitNext(anyString(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new RedisWaitingRoomStore.WriteResult(RedisWaitingRoomStore.ResultType.NOT_FOUND, null));
        return store;
    }
}
