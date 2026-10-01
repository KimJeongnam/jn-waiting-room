/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomDemoDefaultsTest.java
 * @description 기본 demo 실행의 설정 바인딩과 기존 환경변수 override를 검증합니다.
 */
package org.jn.waitingroom.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 별도의 인원·서비스 설정 없이도 launcher의 demo 플래그만으로 바인딩할 수 있어야 합니다. */
@DisplayName("데모 기본 설정 테스트")
class WaitingRoomDemoDefaultsTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("spring.profiles.active=demo", "WAITING_ROOM_DEMO_SEED_ENABLED=true",
                    "WAITING_ROOM_DEMO_JWT_ENABLED=true", "WAITING_ROOM_OAUTH2_ENABLED=true");

    /** launcher 기본 실행은 유효한 인원과 데모 입장 URL을 가져야 합니다. */
    @DisplayName("데모 실행 옵션만으로 추가 환경 변수 없이 유효한 기본값을 바인딩한다")
    @Test
    void demoLauncherFlagsBindValidDefaultsWithoutExtraEnvironmentVariables() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var demo = context.getBean(WaitingRoomDemoProperties.class);
            var properties = context.getBean(WaitingRoomProperties.class);
            var service = properties.services().get("reservation-service");
            assertThat(demo.seedEnabled()).isTrue();
            assertThat(demo.jwtEnabled()).isTrue();
            assertThat(properties.security().oauth2().enabled()).isTrue();
            assertThat(demo.activeUsers()).isEqualTo(5000);
            assertThat(demo.waitingUsers()).isEqualTo(10000);
            assertThat(demo.activeExpiryMin()).isEqualTo(Duration.ofSeconds(30));
            assertThat(demo.activeExpiryMax()).isEqualTo(Duration.ofSeconds(150));
            assertThat(service.maxConcurrentUsers()).isEqualTo(5000);
            assertThat(properties.maxAdmissionsPerRun()).isEqualTo(60);
            assertThat(service.admissionTimeout()).isEqualTo(Duration.ofMinutes(1));
            assertThat(service.etaInitialReleaseRatePerSecond()).isEqualTo(55.6);
            assertThat(service.etaMinSamples()).isEqualTo(5000);
            assertThat(service.redirects().get("service-entry")).isEqualTo("http://localhost:5173/demo/admitted");
        });
    }

    /** 기존 환경변수는 profile의 데모 기본값보다 우선합니다. */
    @DisplayName("명시된 환경 변수는 데모 기본값보다 우선한다")
    @Test
    void existingEnvironmentVariablesOverrideDemoDefaults() {
        runner.withPropertyValues("WAITING_ROOM_DEMO_ACTIVE_USERS=2", "WAITING_ROOM_DEMO_WAITING_USERS=3",
                "WAITING_ROOM_DEMO_ACTIVE_EXPIRY_MIN=PT10S", "WAITING_ROOM_DEMO_ACTIVE_EXPIRY_MAX=PT20S",
                "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=4", "WAITING_ROOM_MAX_ADMISSIONS_PER_RUN=5",
                "WAITING_ROOM_RESERVATION_ADMISSION_TIMEOUT=PT30S",
                "WAITING_ROOM_RESERVATION_ETA_INITIAL_RELEASE_RATE_PER_SECOND=2.5",
                "WAITING_ROOM_RESERVATION_ETA_MIN_SAMPLES=6",
                "WAITING_ROOM_RESERVATION_ENTRY_URL=http://localhost:6173/demo/admitted").run(context -> {
            assertThat(context).hasNotFailed();
            var demo = context.getBean(WaitingRoomDemoProperties.class);
            var properties = context.getBean(WaitingRoomProperties.class);
            var service = properties.services().get("reservation-service");
            assertThat(demo.activeUsers()).isEqualTo(2);
            assertThat(demo.waitingUsers()).isEqualTo(3);
            assertThat(demo.activeExpiryMin()).isEqualTo(Duration.ofSeconds(10));
            assertThat(demo.activeExpiryMax()).isEqualTo(Duration.ofSeconds(20));
            assertThat(service.maxConcurrentUsers()).isEqualTo(4);
            assertThat(properties.maxAdmissionsPerRun()).isEqualTo(5);
            assertThat(service.admissionTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(service.etaInitialReleaseRatePerSecond()).isEqualTo(2.5);
            assertThat(service.etaMinSamples()).isEqualTo(6);
            assertThat(service.redirects().get("service-entry")).isEqualTo("http://localhost:6173/demo/admitted");
        });
    }

    /** 실제 설정 객체와 기동 검증기를 로드합니다. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({WaitingRoomProperties.class, WaitingRoomDemoProperties.class})
    @Import(WaitingRoomConfigurationValidation.class)
    static class TestConfiguration { }
}
