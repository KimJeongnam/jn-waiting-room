/**
 * @author jeongnam
 * @since 2026-09-21
 * @file WaitingRoomPropertiesTest.java
 * @description Waiting Room 설정의 기본값과 환경변수 바인딩을 검증합니다.
 */
package org.jn.waitingroom.config;

import org.jn.waitingroom.domain.WaitingRequestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaitingRoomPropertiesTest {
    @Test
    void bindsDefaultValuesFromApplicationYaml() throws IOException {
        var properties = bind(Map.of(
                "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS", 100,
                "WAITING_ROOM_RESERVATION_ENTRY_URL", "https://service.example/entry"
        ));
        var service = properties.services().get("reservation-service");

        assertEquals(Duration.ofSeconds(1), properties.schedulerInterval());
        assertEquals(Duration.ofSeconds(30), properties.maxRetryAfter());
        assertEquals(Duration.ofSeconds(1), properties.retryAfterSafetyMargin());
        assertEquals(Duration.ofHours(1), properties.quarantineTtl());
        assertEquals(Duration.ofSeconds(5), properties.maintenanceLeaseTimeout());
        assertEquals(100, properties.maxAdmissionsPerRun());
        assertEquals(100, properties.maxActiveExpirationsPerRun());
        assertEquals(100, properties.maxWaitingExpirationsPerRun());
        assertEquals(300, properties.maxCandidatesScannedPerRun());
        assertEquals(Duration.ofMinutes(20), service.heartbeatTimeout());
        assertEquals(Duration.ofMinutes(2), service.admissionTimeout());
        assertEquals(Duration.ofMinutes(30), service.maxSessionDuration());
        assertEquals(Duration.ofMinutes(5), service.etaWindow());
        assertEquals(10, service.etaMinSamples());
        assertEquals(Duration.ofMinutes(1), service.etaMinObservation());
        assertEquals(0.0, service.etaInitialReleaseRatePerSecond());
        assertEquals(Duration.ofHours(24), service.requestTtl());
        assertEquals(Duration.ofHours(23), service.maxWaitDuration());
        assertEquals(100, service.maxConcurrentUsers());
        assertEquals("https://service.example/entry", service.redirects().get("service-entry"));
    }

    @Test
    void environmentVariablesOverrideDefaultValues() throws IOException {
        var properties = bind(Map.ofEntries(
                Map.entry("WAITING_ROOM_MAX_RETRY_AFTER", "PT10S"),
                Map.entry("WAITING_ROOM_RETRY_AFTER_SAFETY_MARGIN", "PT2S"),
                Map.entry("WAITING_ROOM_QUARANTINE_TTL", "PT3H"),
                Map.entry("WAITING_ROOM_RESERVATION_HEARTBEAT_TIMEOUT", "PT45S"),
                Map.entry("WAITING_ROOM_RESERVATION_ETA_WINDOW", "PT10M"),
                Map.entry("WAITING_ROOM_RESERVATION_ETA_MIN_SAMPLES", 20),
                Map.entry("WAITING_ROOM_RESERVATION_ETA_MIN_OBSERVATION", "PT2M"),
                Map.entry("WAITING_ROOM_RESERVATION_ETA_INITIAL_RELEASE_RATE_PER_SECOND", 55.6),
                Map.entry("WAITING_ROOM_RESERVATION_REQUEST_TTL", "PT48H"),
                Map.entry("WAITING_ROOM_RESERVATION_MAX_WAIT_DURATION", "PT12H"),
                Map.entry("WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS", 250),
                Map.entry("WAITING_ROOM_RESERVATION_ENTRY_URL", "https://other.example/entry")
        ));
        var service = properties.services().get("reservation-service");

        assertEquals(Duration.ofSeconds(10), properties.maxRetryAfter());
        assertEquals(Duration.ofSeconds(2), properties.retryAfterSafetyMargin());
        assertEquals(Duration.ofHours(3), properties.quarantineTtl());
        assertEquals(Duration.ofSeconds(45), service.heartbeatTimeout());
        assertEquals(Duration.ofMinutes(10), service.etaWindow());
        assertEquals(20, service.etaMinSamples());
        assertEquals(Duration.ofMinutes(2), service.etaMinObservation());
        assertEquals(55.6, service.etaInitialReleaseRatePerSecond());
        assertEquals(Duration.ofHours(48), service.requestTtl());
        assertEquals(Duration.ofHours(12), service.maxWaitDuration());
        assertEquals(250, service.maxConcurrentUsers());
        assertEquals("https://other.example/entry", service.redirects().get("service-entry"));
    }

    @Test
    void exposesOnlyTheApprovedRequestStatuses() {
        var names = Stream.of(WaitingRequestStatus.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of("WAITING", "ADMITTED", "ENTERED", "EXPIRED", "CANCELLED"), names);
    }

    @Test
    void rejectsUnsafeSchedulerAndBatchSettings() {
        var service = validService();

        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(1),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", service)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", service)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofMinutes(20),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", service)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(20),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", service)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                0,
                100,
                100,
                300,
                Map.of("reservation-service", service)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                99,
                Map.of("reservation-service", service)
        ));
    }

    @Test
    void rejectsUnsafeServiceTimeoutsAndCapacity() {
        assertThrows(IllegalArgumentException.class, () -> service(
                0,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofDays(1),
                Duration.ofHours(23)
        ));
        assertThrows(IllegalArgumentException.class, () -> service(
                100,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofHours(23),
                Duration.ofHours(23)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofMinutes(5),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", validService())
        ));
    }

    @Test
    void rejectsInvalidServiceIdsAndRedirectTargets() {
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of("Reservation_Service", validService())
        ));

        var redirects = new HashMap<String, String>();
        redirects.put("service-entry", "/relative-entry");
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties.ServiceProperties(
                100,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                10,
                Duration.ofMinutes(1),
                0.0,
                Duration.ofDays(1),
                Duration.ofHours(23),
                redirects
        ));
    }

    @Test
    void rejectsAnEtaObservationLongerThanItsWindow() {
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties.ServiceProperties(
                100,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofSeconds(30),
                10,
                Duration.ofMinutes(1),
                0.0,
                Duration.ofDays(1),
                Duration.ofHours(23),
                Map.of("service-entry", "https://service.example/entry")
        ));
    }

    @Test
    void rejectsInvalidInitialEtaReleaseRates() {
        assertThrows(IllegalArgumentException.class, () -> serviceWithInitialReleaseRate(-0.1));
        assertThrows(IllegalArgumentException.class, () -> serviceWithInitialReleaseRate(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> serviceWithInitialReleaseRate(Double.POSITIVE_INFINITY));
    }

    private WaitingRoomProperties bind(Map<String, Object> environmentVariables) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("testEnvironment", environmentVariables)
        );

        var yamlSources = new YamlPropertySourceLoader().load(
                "application.yml",
                new ClassPathResource("application.yml")
        );
        yamlSources.forEach(environment.getPropertySources()::addLast);

        return Binder.get(environment)
                .bind("waiting-room", WaitingRoomProperties.class)
                .orElseThrow(() -> new IllegalStateException("waiting-room 설정을 바인딩할 수 없습니다."));
    }

    private WaitingRoomProperties.ServiceProperties validService() {
        return service(
                100,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofDays(1),
                Duration.ofHours(23)
        );
    }

    private WaitingRoomProperties.ServiceProperties service(
            int maxConcurrentUsers,
            Duration heartbeatTimeout,
            Duration admissionTimeout,
            Duration maxSessionDuration,
            Duration requestTtl,
            Duration maxWaitDuration
    ) {
        return new WaitingRoomProperties.ServiceProperties(
                maxConcurrentUsers,
                heartbeatTimeout,
                admissionTimeout,
                maxSessionDuration,
                Duration.ofMinutes(5),
                10,
                Duration.ofMinutes(1),
                0.0,
                requestTtl,
                maxWaitDuration,
                Map.of("service-entry", "https://service.example/entry")
        );
    }

    private WaitingRoomProperties.ServiceProperties serviceWithInitialReleaseRate(double initialReleaseRate) {
        return new WaitingRoomProperties.ServiceProperties(
                100,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                10,
                Duration.ofMinutes(1),
                initialReleaseRate,
                Duration.ofDays(1),
                Duration.ofHours(23),
                Map.of("service-entry", "https://service.example/entry")
        );
    }
}
