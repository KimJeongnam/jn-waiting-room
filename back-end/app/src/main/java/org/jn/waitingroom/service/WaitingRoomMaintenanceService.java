/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomMaintenanceService.java
 * @description 서비스별 만료 요청을 정리하고 빈 활성 슬롯에 다음 대기 요청을 배정합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pub/Sub 알림과 주기 실행이 공유하는 서비스 단위 유지보수 작업입니다.
 */
@Service
public class WaitingRoomMaintenanceService {
    private static final String SLOT_RELEASED_CHANNEL_PREFIX = "waiting-room:slot-released:{";
    private static final Logger LOGGER = LoggerFactory.getLogger(WaitingRoomMaintenanceService.class);

    private final RedisWaitingRoomStore store;
    private final WaitingRoomProperties properties;
    private final WaitingRoomRecoveryCoordinator coordinator;
    private final String instanceId;
    private volatile boolean applicationReady;

    /**
     * Redis 저장소와 외부 설정을 주입하고 현재 프로세스의 lease 소유자 ID를 생성합니다.
     *
     * @param store Redis 대기 상태 저장소
     * @param properties Waiting Room 설정
     * @param coordinator Redis 복구 완료 gate
     */
    @Autowired
    public WaitingRoomMaintenanceService(RedisWaitingRoomStore store, WaitingRoomProperties properties,
                                         WaitingRoomRecoveryCoordinator coordinator) {
        this(store, properties, coordinator, UUID.randomUUID().toString());
    }

    /**
     * 테스트에서 고정된 lease 소유자 ID를 사용할 수 있게 합니다.
     *
     * @param store Redis 대기 상태 저장소
     * @param properties Waiting Room 설정
     * @param coordinator Redis 복구 완료 gate
     * @param instanceId 현재 프로세스의 lease 소유자 식별자
     */
    WaitingRoomMaintenanceService(
            RedisWaitingRoomStore store,
            WaitingRoomProperties properties,
            WaitingRoomRecoveryCoordinator coordinator,
            String instanceId
    ) {
        this.store = Objects.requireNonNull(store);
        this.properties = Objects.requireNonNull(properties);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.instanceId = Objects.requireNonNull(instanceId);
    }

    /**
     * 설정된 주기마다 모든 대상 서비스의 누락된 만료와 입장 처리를 보정합니다.
     */
    @Scheduled(fixedDelayString = "${waiting-room.scheduler-interval:PT1S}")
    public void maintainAll() {
        if (!applicationReady || !coordinator.isAvailable()) {
            return;
        }
        for (String serviceId : properties.services().keySet()) {
            try {
                maintain(serviceId);
            } catch (RuntimeException exception) {
                coordinator.markRedisUnavailable();
                // 스케줄러 스레드를 유지하고 다음 복구 probe가 가용 상태를 회복하도록 합니다.
                LOGGER.error("Waiting Room 유지보수에 실패했습니다. serviceId={}", serviceId, exception);
            }
        }
    }

    /**
     * 서비스별 lease 안에서 만료 정리 후 빈 슬롯만큼 다음 요청을 입장 허용합니다.
     *
     * @param serviceId 유지보수를 실행할 대상 서비스 식별자
     */
    public void maintain(String serviceId) {
        if (!coordinator.isAvailable()) {
            return;
        }
        var service = properties.services().get(serviceId);
        if (service == null) {
            return;
        }
        try {
            maintainUnderLease(serviceId, service);
        } catch (RuntimeException exception) {
            // Pub/Sub와 scheduler 모두 동일하게 장애를 기록하고 다음 recovery probe를 기다립니다.
            coordinator.markRedisUnavailable();
            LOGGER.error("Waiting Room 유지보수에 실패했습니다. serviceId={}", serviceId, exception);
        }
    }

    private void maintainUnderLease(String serviceId, WaitingRoomProperties.ServiceProperties service) {
        // 동일 인스턴스의 scheduler/Pub/Sub 재획득도 이전 pass와 구분해 ABA를 막습니다.
        String leaseToken = instanceId + ":" + UUID.randomUUID();
        if (!store.tryAcquireMaintenanceLease(
                serviceId,
                leaseToken,
                properties.maintenanceLeaseTimeout()
        )) {
            return;
        }

        try {
            expireActiveSlots(serviceId);
            expireWaitingRequests(serviceId, service);
            admitWaitingRequests(serviceId, service);
            // lease 소유자가 정상 작업을 마친 경우에만 공유 복구 상태를 갱신합니다.
            if (!store.recordMaintenanceHeartbeat(serviceId, leaseToken, properties.maintenanceHealthTimeout())) {
                throw new IllegalStateException("유지보수 lease 소유권이 만료되어 heartbeat를 기록할 수 없습니다.");
            }
        } finally {
            store.releaseMaintenanceLease(serviceId, leaseToken);
        }
    }

    /**
     * 활성 슬롯 반환 Pub/Sub 채널에서 서비스 ID를 읽어 즉시 유지보수를 시도합니다.
     *
     * @param channel Redis가 전달한 슬롯 반환 채널명
     */
    public void onSlotReleased(String channel) {
        if (!applicationReady || !coordinator.isAvailable()
                || !channel.startsWith(SLOT_RELEASED_CHANNEL_PREFIX)
                || !channel.endsWith("}")) {
            return;
        }
        String serviceId = channel.substring(SLOT_RELEASED_CHANNEL_PREFIX.length(), channel.length() - 1);
        maintain(serviceId);
    }

    /** ApplicationRunner가 모두 끝난 뒤 자동 유지보수 처리를 허용합니다. */
    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        applicationReady = true;
    }

    private void expireActiveSlots(String serviceId) {
        List<String> candidates = store.activeExpirationCandidates(
                serviceId,
                properties.maxActiveExpirationsPerRun()
        );
        for (String reservationRequestId : candidates) {
            var result = store.expireActiveIfDue(
                    serviceId,
                    reservationRequestId,
                    properties.quarantineTtl(),
                    properties.services().get(serviceId).etaWindow()
            );
            ensureStoreIsHealthy(result);
            if (result.type() == RedisWaitingRoomStore.ResultType.RETRY) {
                break;
            }
        }
    }

    private void expireWaitingRequests(
            String serviceId,
            WaitingRoomProperties.ServiceProperties service
    ) {
        int scanLimit = properties.maxCandidatesScannedPerRun();
        var heartbeatCandidates = store.heartbeatExpirationCandidates(serviceId, scanLimit);
        var waitingCandidates = store.waitingExpirationCandidates(serviceId, scanLimit);
        List<String> candidates = interleaveDistinct(heartbeatCandidates, waitingCandidates, scanLimit);

        int expiredCount = 0;
        for (String reservationRequestId : candidates) {
            var result = store.expireWaitingIfDue(
                    serviceId,
                    reservationRequestId,
                    service.heartbeatTimeout(),
                    service.maxWaitDuration(),
                    properties.quarantineTtl()
            );
            ensureStoreIsHealthy(result);
            if (result.type() == RedisWaitingRoomStore.ResultType.APPLIED
                    && ++expiredCount >= properties.maxWaitingExpirationsPerRun()) {
                break;
            }
        }
    }

    private void admitWaitingRequests(
            String serviceId,
            WaitingRoomProperties.ServiceProperties service
    ) {
        int admitted = 0;
        int scanned = 0;
        while (admitted < properties.maxAdmissionsPerRun()
                && scanned++ < properties.maxCandidatesScannedPerRun()) {
            var result = store.admitNext(
                    serviceId,
                    service.maxConcurrentUsers(),
                    service.admissionTimeout(),
                    service.heartbeatTimeout(),
                    service.maxWaitDuration(),
                    properties.quarantineTtl()
            );
            ensureStoreIsHealthy(result);
            if (result.type() == RedisWaitingRoomStore.ResultType.APPLIED) {
                admitted++;
            } else if (result.type() != RedisWaitingRoomStore.ResultType.CLEANED) {
                break;
            }
        }
    }

    private static List<String> interleaveDistinct(
            List<String> first,
            List<String> second,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }
        var candidates = new LinkedHashSet<String>();
        int longest = Math.max(first.size(), second.size());
        for (int index = 0; index < longest && candidates.size() < limit; index++) {
            if (index < first.size()) {
                candidates.add(first.get(index));
            }
            if (index < second.size() && candidates.size() < limit) {
                candidates.add(second.get(index));
            }
        }
        return new ArrayList<>(candidates);
    }

    private static void ensureStoreIsHealthy(RedisWaitingRoomStore.WriteResult result) {
        if (result.type() == RedisWaitingRoomStore.ResultType.STORE_ERROR) {
            throw new IllegalStateException("Redis 대기 요청 상태가 올바르지 않습니다.");
        }
    }
}
