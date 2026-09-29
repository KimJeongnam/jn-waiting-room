/**
 * @author jeongnam
 * @since 2026-09-28
 * @file WaitingRoomDemoProperties.java
 * @description 개발 및 데모 실행에서만 사용하는 초기 테스트 사용자 설정을 정의합니다.
 */
package org.jn.waitingroom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 데모 시작 시 생성할 테스트 사용자 수와 최초 활성 슬롯 만료 범위입니다.
 *
 * @param seedEnabled 데모 테스트 사용자 생성 여부
 * @param serviceId 테스트 사용자를 생성할 대상 서비스 ID
 * @param activeUsers 최초 ENTERED 상태로 생성할 테스트 사용자 수
 * @param waitingUsers 최초 WAITING 상태로 생성할 테스트 사용자 수
 * @param activeExpiryMin 테스트 활성 슬롯의 최소 유지시간
 * @param activeExpiryMax 테스트 활성 슬롯의 최대 유지시간
 */
@ConfigurationProperties("waiting-room.demo")
public record WaitingRoomDemoProperties(
        boolean seedEnabled,
        String serviceId,
        int activeUsers,
        int waitingUsers,
        Duration activeExpiryMin,
        Duration activeExpiryMax
) {
    /** 활성화된 데모 시드 설정의 값 범위를 검증합니다. */
    public WaitingRoomDemoProperties {
        if (seedEnabled) {
            if (serviceId == null || serviceId.isBlank()) {
                throw new IllegalArgumentException("데모 시드 serviceId는 필수입니다.");
            }
            if (activeUsers < 0 || waitingUsers < 0 || activeUsers + waitingUsers == 0) {
                throw new IllegalArgumentException("데모 테스트 사용자 수는 0 이상이며 합계는 1 이상이어야 합니다.");
            }
            if (activeExpiryMin == null || activeExpiryMax == null
                    || activeExpiryMin.isZero() || activeExpiryMin.isNegative()
                    || activeExpiryMax.compareTo(activeExpiryMin) < 0) {
                throw new IllegalArgumentException("데모 활성 슬롯 만료 범위가 올바르지 않습니다.");
            }
        }
    }
}
