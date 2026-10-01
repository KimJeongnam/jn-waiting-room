/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomSecurityEnabledTest.java
 * @description OAuth2 ON의 Backend API 인증과 scope를 HTTP 수준에서 검증합니다.
 */
package org.jn.waitingroom.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로컬 JWK issuer가 서명한 토큰으로 실제 SecurityFilterChain을 확인합니다.
 */
@SpringBootTest(classes = WaitingRoomSecurityConfigurationTest.TestApplication.class, properties = {
        "spring.docker.compose.enabled=false",
        "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=100",
        "WAITING_ROOM_RESERVATION_ENTRY_URL=https://service.example/entry",
        "WAITING_ROOM_OAUTH2_ENABLED=true"
})
@AutoConfigureMockMvc
@DisplayName("OAuth 리소스 서버 보안 테스트")
class WaitingRoomSecurityEnabledTest {
    private static final KeyPair KEY_PAIR = keyPair();
    private static final HttpServer ISSUER = issuer();
    private static final String ISSUER_URI = "http://127.0.0.1:" + ISSUER.getAddress().getPort();

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void oauth2Properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER_URI);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences[0]", () -> "waiting-room-api");
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.stop(0);
    }

    /** 검증 목적: Bearer 토큰이 없으면 401 챌린지를 반환한다. */
    @DisplayName("Bearer 토큰이 없으면 401 챌린지를 반환한다")
    @Test
    void missingBearerTokenGets401Challenge() throws Exception {
        mvc.perform(post("/api/v1/waiting-requests"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    /** 검증 목적: 클라이언트 ID가 없거나 문자열이 아니면 401 챌린지를 반환한다. */
    @DisplayName("클라이언트 ID가 없거나 문자열이 아니면 401 챌린지를 반환한다")
    @Test
    void missingOrNonStringClientIdGets401BearerChallenge() throws Exception {
        assertInvalidClientId(token("waiting:create",
                claims -> claims.claim("client_id", null).subject("CaseSensitiveClient"), KEY_PAIR.getPrivate()));
        assertInvalidClientId(token("waiting:create",
                claims -> claims.claim("client_id", 42).claim("azp", "CaseSensitiveClient"), KEY_PAIR.getPrivate()));
    }

    /** 검증 목적: 설정된 클라이언트 ID는 소유권 검사를 통과한다. */
    @DisplayName("설정된 클라이언트 ID는 소유권 검사를 통과한다")
    @Test
    void configuredClientIdPassesOwnershipBoundary() throws Exception {
        mvc.perform(post("/api/v1/waiting-requests")
                        .queryParam("serviceId", "reservation-service")
                        .header("Authorization", "Bearer " + token("waiting:create",
                                claims -> claims.claim("client_id", "reservation-service-client"),
                                KEY_PAIR.getPrivate())))
                .andExpect(status().isNoContent());
    }

    /** 검증 목적: 필요한 scope가 없으면 403 챌린지를 반환한다. */
    @DisplayName("필요한 scope가 없으면 403 챌린지를 반환한다")
    @Test
    void insufficientScopeGets403BearerChallenge() throws Exception {
        mvc.perform(post("/api/v1/waiting-requests")
                        .header("Authorization", "Bearer " + token("waiting:enter")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.containsString("error=\"insufficient_scope\"")));
    }

    /** 검증 목적: 백엔드 작업마다 전용 scope가 필요하다. */
    @DisplayName("백엔드 작업마다 전용 scope가 필요하다")
    @Test
    void eachBackendActionRequiresItsOwnScope() throws Exception {
        String[][] actions = {
                {"/api/v1/waiting-requests", "waiting:create"},
                {"/api/v1/waiting-requests/request-1/access-tokens", "waiting:token:issue"},
                {"/api/v1/waiting-requests/request-1/enter", "waiting:enter"},
                {"/api/v1/waiting-requests/request-1/complete", "waiting:complete"},
                {"/api/v1/waiting-requests/request-1/cancel", "waiting:cancel"}
        };
        for (String[] action : actions) {
            mvc.perform(post(action[0]).header("Authorization", "Bearer " + token(action[1])))
                    .andExpect(status().isNoContent());
            mvc.perform(post(action[0]).header("Authorization", "Bearer " + token("unrelated")))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string("WWW-Authenticate",
                            "Bearer error=\"insufficient_scope\", scope=\"" + action[1] + "\""));
        }
    }

    /** Backend API 경계의 미등록 method와 하위 경로는 인증 여부와 관계없이 통과시키지 않습니다. */
    @DisplayName("등록되지 않은 백엔드 요청은 기본 거부한다")
    @Test
    void unregisteredBackendRequestsFailClosed() throws Exception {
        for (String path : java.util.List.of(
                "/api/v1/waiting-requests",
                "/api/v1/waiting-requests/request-1/unknown")) {
            var request = path.endsWith("unknown") ? post(path) : get(path);
            mvc.perform(request)
                    .andExpect(status().isUnauthorized());

            request = path.endsWith("unknown") ? post(path) : get(path);
            mvc.perform(request.header("Authorization", "Bearer " + token("waiting:create")))
                    .andExpect(status().isForbidden());
        }
    }

    /** 검증 목적: 브라우저와 데모 경로는 JWT 없이 접근할 수 있다. */
    @DisplayName("브라우저와 데모 경로는 JWT 없이 접근할 수 있다")
    @Test
    void browserAndDemoRoutesDoNotRequireJwt() throws Exception {
        mvc.perform(get("/api/v1/waiting-session").header("Authorization", "Bearer invalid"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/app/waiting"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(post("/api/v1/demo/token"))
                .andExpect(status().isNoContent());
    }

    /** 검증 목적: 활성 보안 모드를 리소스 서버로 보고한다. */
    @DisplayName("활성 보안 모드를 리소스 서버로 보고한다")
    @Test
    void reportsResourceServerMode() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backendAuthentication").value("OAUTH2_RESOURCE_SERVER"));
    }

    /** 검증 목적: audience가 다른 토큰은 거절한다. */
    @DisplayName("audience가 다른 토큰은 거절한다")
    @Test
    void rejectsWrongAudience() throws Exception {
        assertInvalidToken(token("waiting:create", claims -> claims.audience("other-api"), KEY_PAIR.getPrivate()));
    }

    /** 검증 목적: issuer가 다른 토큰은 거절한다. */
    @DisplayName("issuer가 다른 토큰은 거절한다")
    @Test
    void rejectsWrongIssuer() throws Exception {
        assertInvalidToken(token("waiting:create", claims -> claims.issuer("http://other-issuer.example"),
                KEY_PAIR.getPrivate()));
    }

    /** 검증 목적: 만료된 토큰은 거절한다. */
    @DisplayName("만료된 토큰은 거절한다")
    @Test
    void rejectsExpiredToken() throws Exception {
        assertInvalidToken(token("waiting:create", claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(120))),
                KEY_PAIR.getPrivate()));
    }

    /** 검증 목적: 만료 시각이 없는 토큰은 거절한다. */
    @DisplayName("만료 시각이 없는 토큰은 거절한다")
    @Test
    void rejectsTokenWithoutExpiration() throws Exception {
        assertInvalidToken(token("waiting:create", claims -> claims.expirationTime(null), KEY_PAIR.getPrivate()));
    }

    /** 검증 목적: 유효 시작 시각 이전의 토큰은 거절한다. */
    @DisplayName("유효 시작 시각 이전의 토큰은 거절한다")
    @Test
    void rejectsTokenBeforeNotBeforeTime() throws Exception {
        assertInvalidToken(token("waiting:create", claims -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(120))),
                KEY_PAIR.getPrivate()));
    }

    /** 검증 목적: 유효 시작 시각이 없는 토큰은 허용한다. */
    @DisplayName("유효 시작 시각이 없는 토큰은 허용한다")
    @Test
    void acceptsTokenWithoutNotBeforeTime() throws Exception {
        mvc.perform(post("/api/v1/waiting-requests")
                        .header("Authorization", "Bearer " + token("waiting:create",
                                claims -> claims.notBeforeTime(null), KEY_PAIR.getPrivate())))
                .andExpect(status().isNoContent());
    }

    /** 검증 목적: 서명이 잘못된 토큰은 거절한다. */
    @DisplayName("서명이 잘못된 토큰은 거절한다")
    @Test
    void rejectsInvalidSignature() throws Exception {
        assertInvalidToken(token("waiting:create", claims -> { }, keyPair().getPrivate()));
    }

    /** 검증 목적: HS256 알고리즘 토큰은 거절한다. */
    @DisplayName("HS256 알고리즘 토큰은 거절한다")
    @Test
    void rejectsHs256Algorithm() throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER_URI)
                .audience("waiting-room-api")
                .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .claim("scope", "waiting:create")
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(new byte[32]));
        assertInvalidToken(jwt.serialize());
    }

    /** 검증 목적: scope 클레임이 문자열일 때만 권한을 부여한다. */
    @DisplayName("scope 클레임이 문자열일 때만 권한을 부여한다")
    @Test
    void onlyStringScopeClaimGrantsAuthority() throws Exception {
        mvc.perform(post("/api/v1/waiting-requests")
                        .header("Authorization", "Bearer " + token("waiting:create",
                                claims -> claims.claim("scope", java.util.List.of("waiting:create")),
                                KEY_PAIR.getPrivate())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/waiting-requests")
                        .header("Authorization", "Bearer " + token("waiting:create",
                                claims -> claims.claim("scope", null).claim("scp", java.util.List.of("waiting:create")),
                                KEY_PAIR.getPrivate())))
                .andExpect(status().isForbidden());
    }

    private void assertInvalidToken(String token) throws Exception {
        mvc.perform(post("/api/v1/waiting-requests")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
    }

    private void assertInvalidClientId(String token) throws Exception {
        mvc.perform(post("/api/v1/waiting-requests")
                        .queryParam("serviceId", "reservation-service")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.startsWith("Bearer")));
    }

    private static String token(String scope) throws Exception {
        return token(scope, claims -> { }, KEY_PAIR.getPrivate());
    }

    private static String token(String scope, Consumer<JWTClaimsSet.Builder> change, PrivateKey signer) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER_URI)
                .audience("waiting-room-api")
                .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(10)))
                .claim("client_id", "CaseSensitiveClient")
                .claim("scope", scope);
        change.accept(claims);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                claims.build());
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    private static KeyPair keyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static HttpServer issuer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String metadata = "{\"issuer\":\"" + base + "\",\"jwks_uri\":\"" + base + "/jwks\"}";
            var jwk = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) KEY_PAIR.getPublic())
                    .keyID("test-key")
                    .build();
            server.createContext("/.well-known/openid-configuration",
                    exchange -> respond(exchange, metadata));
            server.createContext("/jwks", exchange -> respond(exchange,
                    "{\"keys\":[" + jwk.toPublicJWK().toJSONString() + "]}"));
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
