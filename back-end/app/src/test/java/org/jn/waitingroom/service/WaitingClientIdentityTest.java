/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingClientIdentityTest.java
 * @description JWT client_id와 scope를 서비스 호출자 식별자로 읽는 규칙을 검증합니다.
 */
package org.jn.waitingroom.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("JWT 클라이언트 식별 테스트")
class WaitingClientIdentityTest {
    /** 검증 목적: 클라이언트 ID와 scope의 대소문자를 보존한다. */
    @DisplayName("클라이언트 ID와 scope의 대소문자를 보존한다")
    @Test
    void preservesClientIdAndScopeCase() {
        var identity = WaitingClientIdentity.from(jwtAuthentication(Map.of(
                "client_id", "CaseSensitiveClient",
                "scope", "waiting:create Waiting:Enter waiting:create"
        )));

        assertEquals("CaseSensitiveClient", identity.clientId());
        assertEquals(Set.of("waiting:create", "Waiting:Enter"), identity.scopes());
    }

    /** 검증 목적: 클라이언트 ID가 없거나 문자열이 아니면 거절한다. */
    @DisplayName("클라이언트 ID가 없거나 문자열이 아니면 거절한다")
    @Test
    void rejectsMissingOrNonStringClientId() {
        assertThrows(AuthenticationException.class,
                () -> WaitingClientIdentity.from(jwtAuthentication(Map.of("scope", "waiting:create"))));
        assertThrows(AuthenticationException.class,
                () -> WaitingClientIdentity.from(jwtAuthentication(Map.of("client_id", 42))));
    }

    /** 검증 목적: scope 클레임이 문자열이 아니거나 JWT 인증이 아니면 권한을 부여하지 않는다. */
    @DisplayName("scope 클레임이 문자열이 아니거나 JWT 인증이 아니면 권한을 부여하지 않는다")
    @Test
    void doesNotGrantScopeForNonStringClaimOrNonJwtAuthentication() {
        var identity = WaitingClientIdentity.from(jwtAuthentication(Map.of(
                "client_id", "Client",
                "scope", java.util.List.of("waiting:create")
        )));
        assertEquals(Set.of(), identity.scopes());
        assertThrows(AuthenticationException.class,
                () -> WaitingClientIdentity.from(new UsernamePasswordAuthenticationToken("Client", "secret")));
    }

    private JwtAuthenticationToken jwtAuthentication(Map<String, Object> claims) {
        var jwt = new Jwt("test-token", Instant.now().minusSeconds(10), Instant.now().plusSeconds(120),
                Map.of("alg", "RS256"), claims);
        return new JwtAuthenticationToken(jwt, java.util.List.of());
    }
}
