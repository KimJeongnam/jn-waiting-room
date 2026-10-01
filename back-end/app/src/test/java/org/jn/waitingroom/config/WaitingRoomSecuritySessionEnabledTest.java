/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomSecuritySessionEnabledTest.java
 * @description OAuth2 ON에서 실제 세션 API와 재발급 권한을 통합 검증합니다.
 */
package org.jn.waitingroom.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 서명 검증 자체는 기존 issuer 테스트에서 검증하고 여기서는 scope/소유권/세션 분리를 확인합니다. */
@SpringBootTest(classes = WaitingRoomSecuritySessionTest.TestApplication.class, properties = {
        "spring.docker.compose.enabled=false",
        "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=100",
        "WAITING_ROOM_RESERVATION_ENTRY_URL=https://service.example/entry",
        "WAITING_ROOM_OAUTH2_ENABLED=true"
})
@DisplayName("세션 재발급 권한 테스트")
class WaitingRoomSecuritySessionEnabledTest extends WaitingRoomSecuritySessionTest {
    @MockitoBean
    JwtDecoder decoder;

    /** 검증 목적: 실제 컨트롤러의 세션 재발급은 scope와 신청 소유권을 모두 요구한다. */
    @DisplayName("실제 컨트롤러의 세션 재발급은 scope와 신청 소유권을 모두 요구한다")
    @Test
    void reissueRequiresItsScopeAndOwnershipThroughTheActualController() throws Exception {
        String id = UUID.randomUUID().toString();
        service.register("reservation-service", id, "service-entry");
        when(decoder.decode("wrong-scope")).thenReturn(jwt("waiting:create", "reservation-service-client"));
        when(decoder.decode("foreign-client")).thenReturn(jwt("waiting:token:issue", "foreign-client"));
        when(decoder.decode("owner")).thenReturn(jwt("waiting:token:issue", "reservation-service-client"));
        mvc.perform(post("/api/v1/waiting-requests/{id}/access-tokens", id)
                        .queryParam("serviceId", "reservation-service")
                        .header("Authorization", "Bearer wrong-scope"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/waiting-requests/{id}/access-tokens", id)
                        .queryParam("serviceId", "reservation-service")
                        .header("Authorization", "Bearer foreign-client"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/v1/waiting-requests/{id}/access-tokens", id)
                        .queryParam("serviceId", "reservation-service")
                        .header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.waitingUrl").isNotEmpty());
    }

    private Jwt jwt(String scope, String clientId) {
        return Jwt.withTokenValue("test-token").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("scope", scope).claim("client_id", clientId).build();
    }
}
