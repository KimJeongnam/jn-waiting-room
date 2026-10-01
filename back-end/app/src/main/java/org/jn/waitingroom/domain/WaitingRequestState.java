/**
 * @author jeongnam
 * @since 2026-09-21
 * @file WaitingRequestState.java
 * @description Redis에 저장되는 대기 신청의 현재 상태를 정의합니다.
 */
package org.jn.waitingroom.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 요청 상태 Key에 JSON으로 저장되는 대기 신청 정보입니다.
 *
 * @param reservationRequestId 전체 대기 흐름에서 사용하는 단일 요청 식별자
 * @param serviceId Waiting Room이 보호하는 대상 서비스 식별자
 * @param status 현재 대기 신청 상태
 * @param redirectTargetId 입장 허용 후 이동할 서버 등록 대상 식별자
 * @param payloadFingerprint 멱등 요청의 payload 변경 여부를 확인하는 지문
 * @param createdAt 요청이 Redis에 최초 등록된 Unix epoch milliseconds
 * @param admittedAt 활성 슬롯을 배정받은 Unix epoch milliseconds
 * @param enteredAt 대상 서비스가 실제 입장을 확인한 Unix epoch milliseconds
 * @param completedAt 대상 서비스 이용이 정상 완료된 Unix epoch milliseconds
 * @param expiredAt 대기 또는 활성 슬롯이 만료된 Unix epoch milliseconds
 * @param expirationReason 요청이 만료된 원인
 * @param nextPollAllowedAt 기존 내부 조회의 허용 시각 (Browser 조회 제한은 세션에 저장)
 * @param currentWaitingTokenHash 현재 일회용 접근 토큰의 SHA-256 해시
 * @param currentWaitingSessionHash 현재 Browser 세션의 SHA-256 해시
 * @param waitingSessionVersion 재발급 시 증가하는 접근 자격 버전
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaitingRequestState(
        String reservationRequestId,
        String serviceId,
        WaitingRequestStatus status,
        String redirectTargetId,
        String payloadFingerprint,
        long createdAt,
        Long admittedAt,
        Long enteredAt,
        Long completedAt,
        Long expiredAt,
        String expirationReason,
        Long nextPollAllowedAt,
        String currentWaitingTokenHash,
        String currentWaitingSessionHash,
        Long waitingSessionVersion
) {
}
