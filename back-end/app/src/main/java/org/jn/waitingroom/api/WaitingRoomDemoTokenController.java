/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomDemoTokenController.java
 * @description 데모 페이지에서만 사용할 임시 Backend Access Token을 발급합니다.
 */
package org.jn.waitingroom.api;

import org.jn.waitingroom.config.WaitingRoomDemoProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 명시적으로 활성화한 demo 실행에만 무인증 발급 endpoint를 노출합니다. */
@RestController
@Profile("demo")
@ConditionalOnProperty(prefix = "waiting-room.demo", name = "jwt-enabled", havingValue = "true")
public class WaitingRoomDemoTokenController {
    private final JwtEncoder encoder;
    private final WaitingRoomDemoProperties properties;

    /** 메모리 서명기와 데모 전용 발급 정책을 주입받습니다. */
    public WaitingRoomDemoTokenController(JwtEncoder encoder, WaitingRoomDemoProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /** JWT와 만료시각만 반환하고 HTTP 캐시에 토큰을 저장하지 않도록 지정합니다. */
    @PostMapping("/api/v1/demo/token")
    public ResponseEntity<DemoTokenResponse> token() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(properties.jwtTtl());
        var claims = JwtClaimsSet.builder().issuer(properties.jwtIssuer())
                .audience(List.of(properties.jwtAudience())).issuedAt(now).notBefore(now).expiresAt(expiresAt)
                .claim("client_id", properties.jwtClientId())
                .claim("scope", "waiting:create waiting:token:issue waiting:enter waiting:complete waiting:cancel")
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DemoTokenResponse(token, expiresAt));
    }

    /**
     * 브라우저 메모리에서 사용할 데모 인증 응답입니다.
     * @param accessToken 임시 RS256 Access Token
     * @param expiresAt Access Token 만료시각
     */
    public record DemoTokenResponse(String accessToken, Instant expiresAt) {
        /** MVC 응답 TRACE 로그에 토큰 원문이 기록되지 않도록 가립니다. */
        @Override
        public String toString() {
            return "DemoTokenResponse[accessToken=[REDACTED], expiresAt=" + expiresAt + "]";
        }
    }
}
