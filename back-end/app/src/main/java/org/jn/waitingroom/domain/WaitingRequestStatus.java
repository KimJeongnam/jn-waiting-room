/**
 * @author jeongnam
 * @since 2026-09-21
 * @file WaitingRequestStatus.java
 * @description 대기 신청의 생명주기 상태를 정의합니다.
 */
package org.jn.waitingroom.domain;

/**
 * 대기 신청이 가질 수 있는 상태입니다.
 */
public enum WaitingRequestStatus {
    /** 대기열에서 입장 순서를 기다리는 상태입니다. */
    WAITING,

    /** 활성 슬롯을 배정받아 대상 서비스에 입장할 수 있는 상태입니다. */
    ADMITTED,

    /** 대상 서비스가 실제 입장을 확인한 상태입니다. */
    ENTERED,

    /** 대기 또는 활성 슬롯의 제한시간이 만료된 상태입니다. */
    EXPIRED,

    /** 대상 서비스가 입장 전에 요청을 취소한 상태입니다. */
    CANCELLED
}
