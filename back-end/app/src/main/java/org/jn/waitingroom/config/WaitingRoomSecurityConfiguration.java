/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomSecurityConfiguration.java
 * @description Backend API 인증 모드에 따라 HTTP 보안 경계와 운영 정보를 구성합니다.
 */
package org.jn.waitingroom.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth2가 켜진 경우 Backend API에만 JWT scope를 요구합니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WaitingRoomSecurityConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(WaitingRoomSecurityConfiguration.class);
    private final WaitingRoomProperties properties;

    public WaitingRoomSecurityConfiguration(WaitingRoomProperties properties) {
        this.properties = properties;
    }

    /**
     * Backend POST API와 데모 reset에 각각 필요한 scope를 적용합니다.
     */
    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "waiting-room.security.oauth2", name = "enabled", havingValue = "true")
    SecurityFilterChain backendApiSecurity(HttpSecurity http) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        Map<RequestMatcher, String> scopes = new LinkedHashMap<>();
        scopes.put(paths.matcher(HttpMethod.POST, "/api/v1/waiting-requests"), "waiting:create");
        scopes.put(paths.matcher(HttpMethod.POST, "/api/v1/demo/reset"), "waiting:create");
        scopes.put(paths.matcher(HttpMethod.POST, "/api/v1/waiting-requests/{id}/access-tokens"), "waiting:token:issue");
        scopes.put(paths.matcher(HttpMethod.POST, "/api/v1/waiting-requests/{id}/enter"), "waiting:enter");
        scopes.put(paths.matcher(HttpMethod.POST, "/api/v1/waiting-requests/{id}/complete"), "waiting:complete");
        scopes.put(paths.matcher(HttpMethod.POST, "/api/v1/waiting-requests/{id}/cancel"), "waiting:cancel");
        var scopeAuthorities = new JwtGrantedAuthoritiesConverter();
        scopeAuthorities.setAuthoritiesClaimName("scope");
        var authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(jwt ->
                jwt.getClaims().get("scope") instanceof String
                        ? scopeAuthorities.convert(jwt) : List.of());
        return http.securityMatcher("/api/v1/waiting-requests", "/api/v1/waiting-requests/**", "/api/v1/demo/reset")
                .headers(headers -> headers.referrerPolicy(referrer ->
                        referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> {
                    // 접근 검사와 오류 Header가 동일한 endpoint-scope 계약을 사용합니다.
                    scopes.forEach((matcher, scope) -> authorize.requestMatchers(matcher).hasAuthority("SCOPE_" + scope));
                    authorize.anyRequest().denyAll();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))
                        .authenticationEntryPoint((request, response, exception) -> {
                            // Decoder의 상세 실패 이유와 만료시각을 외부 challenge에 내보내지 않습니다.
                            response.setStatus(401);
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                                    exception instanceof InsufficientAuthenticationException
                                            ? "Bearer" : "Bearer error=\"invalid_token\"");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            String scope = scopes.entrySet().stream().filter(entry -> entry.getKey().matches(request))
                                    .map(Map.Entry::getValue).findFirst().orElse("");
                            response.setStatus(403);
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                                    "Bearer error=\"insufficient_scope\", scope=\"" + scope + "\"");
                        }))
                .build();
    }

    /**
     * Browser, 정적 Front와 demo token 발급은 인증 없이 통과시킵니다.
     */
    @Bean
    @Order(2)
    SecurityFilterChain otherRequests(HttpSecurity http) throws Exception {
        if (!properties.security().oauth2().enabled()) {
            logger.warn("OAuth2 is disabled; Backend API authentication is UPSTREAM_MANAGED.");
        }
        // Browser 세션 API는 Controller의 same-origin 계약으로 보호하며 별도 CSRF 상태를 만들지 않습니다.
        // OAuth2 OFF에서는 Controller가 없는 reset 경로의 404가 CSRF 403으로 바뀌지 않게 합니다.
        return http.headers(headers -> headers.referrerPolicy(referrer ->
                        referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/api/v1/waiting-requests", "/api/v1/waiting-requests/**",
                        "/api/v1/waiting-session", "/api/v1/demo/token", "/api/v1/demo/reset"))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }

    /**
     * RS256 서명과 issuer, audience, 필수 exp 및 존재하는 nbf의 유효시간을 검사합니다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "waiting-room.security.oauth2", name = "enabled", havingValue = "true")
    @Conditional(ExternalJwtCondition.class)
    JwtDecoder waitingRoomJwtDecoder(Environment environment) {
        String issuer = environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        List<String> audiences = Binder.get(environment)
                .bind("spring.security.oauth2.resourceserver.jwt.audiences", Bindable.listOf(String.class))
                .orElse(List.of());
        var decoder = NimbusJwtDecoder.withIssuerLocation(issuer)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        var audienceValidator = new JwtClaimValidator<List<String>>("aud",
                tokenAudiences -> tokenAudiences != null && tokenAudiences.stream().anyMatch(audiences::contains));
        var expirationRequired = new JwtClaimValidator<>("exp", value -> value != null);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, expirationRequired));
        return decoder;
    }

    /** 데모 임시 decoder가 활성화된 경우에만 외부 issuer decoder 생성을 생략합니다. */
    static class ExternalJwtCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            return !(environment.acceptsProfiles(Profiles.of("demo"))
                    && environment.getProperty("waiting-room.demo.jwt-enabled", Boolean.class, false));
        }
    }

    @Bean
    InfoContributor backendAuthenticationInfo() {
        String mode = properties.security().oauth2().enabled()
                ? "OAUTH2_RESOURCE_SERVER" : "UPSTREAM_MANAGED";
        return builder -> builder.withDetail("backendAuthentication", mode);
    }
}
