/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomDemoSecurityConfiguration.java
 * @description demo 실행에서만 메모리 RSA 키와 JWT 서명 및 검증기를 제공합니다.
 */
package org.jn.waitingroom.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/** demo 프로필과 명시적 JWT 활성화가 모두 있을 때만 임시 서명 키를 생성합니다. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
@ConditionalOnProperty(prefix = "waiting-room.demo", name = "jwt-enabled", havingValue = "true")
public class WaitingRoomDemoSecurityConfiguration {
    /** 재시작마다 새 키를 생성하며 키를 파일, 설정 또는 로그로 내보내지 않습니다. */
    @Bean
    KeyPair demoJwtKeyPair() throws NoSuchAlgorithmException {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** 메모리의 개인키로만 RS256 토큰을 서명합니다. */
    @Bean
    JwtEncoder demoJwtEncoder(KeyPair demoJwtKeyPair) {
        var key = new RSAKey.Builder((RSAPublicKey) demoJwtKeyPair.getPublic())
                .privateKey((RSAPrivateKey) demoJwtKeyPair.getPrivate()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    /** 임시 공개키에 더해 발급자, 대상과 유효시간을 검증합니다. */
    @Bean
    JwtDecoder demoJwtDecoder(KeyPair demoJwtKeyPair, WaitingRoomDemoProperties properties) {
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) demoJwtKeyPair.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var audience = new JwtClaimValidator<List<String>>("aud",
                values -> values != null && values.contains(properties.jwtAudience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwtIssuer()), audience,
                new JwtClaimValidator<>("exp", value -> value != null)));
        return decoder;
    }
}
