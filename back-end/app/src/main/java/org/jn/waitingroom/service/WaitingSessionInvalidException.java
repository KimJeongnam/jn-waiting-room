/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingSessionInvalidException.java
 * @description Browser 접근 자격 인증 실패를 Backend Bearer 인증과 구분합니다.
 */
package org.jn.waitingroom.service;

/** 비밀값을 메시지에 포함하지 않는 Browser 인증 실패입니다. */
public class WaitingSessionInvalidException extends RuntimeException {
    public WaitingSessionInvalidException() {
        super("유효한 대기 세션이 필요합니다.");
    }
}
