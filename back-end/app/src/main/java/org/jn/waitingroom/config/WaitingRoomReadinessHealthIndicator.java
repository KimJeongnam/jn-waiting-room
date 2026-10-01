/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomReadinessHealthIndicator.java
 * @description Redis 복구와 운영 안전 정책의 로컬 상태를 readiness에 반영합니다.
 */
package org.jn.waitingroom.config;

import org.jn.waitingroom.service.WaitingRoomRecoveryCoordinator;
import org.jn.waitingroom.service.RedisOperationalSafety;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 내부 오류나 Redis 주소를 노출하지 않는 Waiting Room 업무 readiness입니다. */
@Component("waitingRoomReadiness")
public class WaitingRoomReadinessHealthIndicator implements HealthIndicator {
    private final WaitingRoomRecoveryCoordinator coordinator;
    private final RedisOperationalSafety redisSafety;

    /** API gate와 같은 로컬 복구·운영 안전 상태를 주입받습니다. */
    public WaitingRoomReadinessHealthIndicator(WaitingRoomRecoveryCoordinator coordinator, RedisOperationalSafety redisSafety) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.redisSafety = Objects.requireNonNull(redisSafety);
    }

    @Override
    public Health health() {
        boolean recoveryReady = coordinator.isAvailable();
        boolean redisSafetyReady = redisSafety.isReady();
        return (recoveryReady && redisSafetyReady ? Health.up() : Health.outOfService())
                .withDetail("recoveryReady", recoveryReady)
                .withDetail("redisSafetyReady", redisSafetyReady)
                .build();
    }
}
