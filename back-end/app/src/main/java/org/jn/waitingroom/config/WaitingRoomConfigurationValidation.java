/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomConfigurationValidation.java
 * @description 인증 모드와 실행 프로필의 필수 설정을 기동 시 검증합니다.
 */
package org.jn.waitingroom.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.List;
import java.net.URI;

/**
 * Spring 컨텍스트가 시작될 때 운영 인증 설정과 데모 프로필의 충돌을 확인합니다.
 */
@Component
public class WaitingRoomConfigurationValidation {
    /**
     * 활성화한 인증 모드에 필요한 JWT 설정과 프로필 조합을 검증합니다.
     *
     * @param environment Spring 프로필과 Resource Server JWT 설정
     * @param properties Waiting Room 인증 모드 설정
     * @param demoProperties 데모 JWT의 client와 대상 서비스 설정
     */
    public WaitingRoomConfigurationValidation(
            Environment environment,
            WaitingRoomProperties properties,
            WaitingRoomDemoProperties demoProperties
    ) {
        if (environment.acceptsProfiles(Profiles.of("prod"))
                && environment.acceptsProfiles(Profiles.of("demo"))) {
            throw new IllegalArgumentException("prod와 demo 프로필을 동시에 활성화할 수 없습니다.");
        }
        // 개발용 localhost HTTP는 유지하되 운영 callback은 TLS가 보장된 URL만 허용합니다.
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            properties.services().values().forEach(service -> service.redirects().values().forEach(url -> {
                if (!"https".equalsIgnoreCase(URI.create(url).getScheme())) {
                    throw new IllegalArgumentException("prod 프로필의 redirect URL은 HTTPS여야 합니다.");
                }
            }));
        }
        if (environment.acceptsProfiles(Profiles.of("demo")) && demoProperties.jwtEnabled()) {
            var client = properties.security().clients().get(demoProperties.jwtClientId());
            if (client == null || !client.services().contains(demoProperties.serviceId())) {
                throw new IllegalArgumentException("데모 JWT clientId는 데모 serviceId에 접근할 수 있어야 합니다.");
            }
            // 임시 공개키 decoder는 외부 issuer discovery 설정을 사용하지 않습니다.
            return;
        }
        if (!properties.security().oauth2().enabled()) {
            return;
        }
        String issuerUri = environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        List<String> audiences = Binder.get(environment)
                .bind("spring.security.oauth2.resourceserver.jwt.audiences", Bindable.listOf(String.class))
                .orElse(List.of());
        if (issuerUri == null || issuerUri.isBlank() || audiences.isEmpty()
                || audiences.stream().anyMatch(audience -> audience == null || audience.isBlank())) {
            throw new IllegalArgumentException("OAuth2 활성화 시 issuer-uri와 audiences가 필수입니다.");
        }
    }
}
