/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingClientIdentity.java
 * @description 인증된 Backend client의 식별자와 scope를 서비스에서 사용할 값으로 제공합니다.
 */
package org.jn.waitingroom.service;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JWT의 대소문자를 보존하는 client_id와 scope 집합입니다.
 *
 * @param clientId Backend client의 정확한 식별자
 * @param scopes JWT scope 문자열에서 읽은 권한 이름
 */
public record WaitingClientIdentity(String clientId, Set<String> scopes) {
    public WaitingClientIdentity {
        if (clientId == null || clientId.isBlank()) {
            throw new BadCredentialsException("JWT client_id가 필요합니다.");
        }
        scopes = Set.copyOf(scopes);
    }

    /**
     * 인증된 JWT 외의 주체와 잘못된 client_id를 인증 실패로 처리합니다.
     * 비문자열 scope는 권한을 부여하지 않습니다.
     */
    public static WaitingClientIdentity from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new BadCredentialsException("인증된 JWT가 필요합니다.");
        }
        var jwt = jwtAuthentication.getToken();
        Object clientId = jwt.getClaims().get("client_id");
        if (!(clientId instanceof String id) || id.isBlank()) {
            throw new BadCredentialsException("JWT client_id가 필요합니다.");
        }
        Object scope = jwt.getClaims().get("scope");
        Set<String> scopes = scope instanceof String text && !text.isBlank()
                ? Arrays.stream(text.trim().split("\\s+"))
                        .collect(Collectors.toUnmodifiableSet())
                : Set.of();
        return new WaitingClientIdentity(id, scopes);
    }
}
