/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingClientAuthorization.java
 * @description 인증된 Backend client가 대상 서비스를 호출할 수 있는지 확인합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.config.WaitingRoomProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * HTTP 요청의 인증 주체와 client별 서비스 설정을 비교합니다.
 */
@Component
public class WaitingClientAuthorization {
    private final WaitingRoomProperties properties;

    /**
     * 서비스 소유권 설정을 주입받습니다.
     *
     * @param properties Waiting Room 설정
     */
    public WaitingClientAuthorization(WaitingRoomProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    /**
     * OAuth2 ON에서 JWT client_id의 서비스 소유권을 검사합니다.
     * OFF에서는 기존 업무 서비스 검증을 호출자에게 맡깁니다.
     *
     * @param authentication HTTP 요청의 인증 주체
     * @param serviceId 대상 서비스 식별자
     */
    public void requireServiceOwnership(Authentication authentication, String serviceId) {
        if (!properties.security().oauth2().enabled()) {
            return;
        }
        var identity = WaitingClientIdentity.from(authentication);
        var client = properties.security().clients().get(identity.clientId());
        if (client == null || !client.services().contains(serviceId)) {
            // 다른 client의 서비스와 존재하지 않는 요청을 같은 외부 계약으로 처리합니다.
            throw new NoSuchElementException("대기 신청을 찾을 수 없습니다.");
        }
    }
}
