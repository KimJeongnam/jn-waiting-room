# Estimated Wait Time Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 활성 슬롯 반환 속도와 스케줄러 입장 처리량을 이용해 WAITING 응답에 신뢰 가능한 예상 대기시간과 적응형 Polling 주기를 반환합니다.

**Architecture:** Redis Lua가 Redis 서버 시각을 기준으로 대기 순번, 활성 슬롯 수, 최근 슬롯 반환 표본을 같은 원자 연산에서 읽고 ETA를 계산합니다. 슬롯을 실제로 반환한 상태 전이만 서비스별 Sorted Set에 표본을 남기며, Java 서비스는 계산 결과를 HTTP 응답으로 전달합니다.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Data Redis, Redis 8 Lua, JUnit 5, Testcontainers

## Global Constraints

- ETA는 WAITING에서 ADMITTED까지의 시간이며 서비스 이용 완료시간을 포함하지 않습니다.
- 슬롯 반환 표본 키는 `slot-release-events:{serviceId}`이고 보존시간은 `etaWindow * 2`입니다.
- Redis 시각과 `[nowMs - etaWindowMs, nowMs]` 구간만 사용하며 경계 표본을 포함합니다.
- 슬롯이 실제로 제거된 경우에만 `reservationRequestId`를 표본으로 기록해 중복 완료를 배제합니다.
- ETA와 `nextPollAfterMs`는 동일한 Redis Lua 실행에서 결정합니다.
- 기본값은 `eta-window=PT5M`, `eta-min-samples=10`, `eta-min-observation=PT1M`입니다.
- ETA가 60초 미만이면 1초, 60분 미만이면 10초, 그 이상 또는 계산 불가이면 15초를 기준으로 ±10% jitter를 적용합니다.
- 현재 작업 트리의 기존 변경을 보존하고, 별도 승인 전에는 Git 커밋을 만들지 않습니다.
- 수정 파일은 `.editorconfig`에 맞춰 LF로 저장하고 공개 타입과 설정 필드에 JavaDoc을 유지합니다.

---

### Task 1: ETA 설정 계약

**Files:**
- Modify: `back-end/app/src/main/java/org/jn/waitingroom/config/WaitingRoomProperties.java`
- Modify: `back-end/app/src/main/resources/application.yml`
- Test: `back-end/app/src/test/java/org/jn/waitingroom/config/WaitingRoomPropertiesTest.java`

**Interfaces:**
- Produces: `ServiceProperties.etaWindow(): Duration`, `etaMinSamples(): int`, `etaMinObservation(): Duration`
- Validation: 양의 값이며 `etaMinObservation <= etaWindow`

- [x] **Step 1: 설정 바인딩과 검증 실패 테스트를 추가합니다.**

```java
assertEquals(Duration.ofMinutes(5), service.etaWindow());
assertEquals(10, service.etaMinSamples());
assertEquals(Duration.ofMinutes(1), service.etaMinObservation());
assertThrows(IllegalArgumentException.class, () -> serviceWithEta(Duration.ofSeconds(30), 10, Duration.ofMinutes(1)));
```

- [x] **Step 2: 설정 테스트를 실행해 새 접근자가 없어 실패하는지 확인합니다.**

Run: `./gradlew :app:test --tests org.jn.waitingroom.config.WaitingRoomPropertiesTest`

- [x] **Step 3: `ServiceProperties` 필드·JavaDoc·검증과 환경변수 기본값을 구현합니다.**

```yaml
eta-window: ${WAITING_ROOM_RESERVATION_ETA_WINDOW:PT5M}
eta-min-samples: ${WAITING_ROOM_RESERVATION_ETA_MIN_SAMPLES:10}
eta-min-observation: ${WAITING_ROOM_RESERVATION_ETA_MIN_OBSERVATION:PT1M}
```

- [x] **Step 4: 설정 테스트 통과를 확인합니다.**

Run: `./gradlew :app:test --tests org.jn.waitingroom.config.WaitingRoomPropertiesTest`

### Task 2: 슬롯 반환 표본의 단일 기록

**Files:**
- Modify: `back-end/app/src/main/java/org/jn/waitingroom/redis/RedisWaitingRoomStore.java`
- Modify: `back-end/app/src/main/java/org/jn/waitingroom/service/WaitingRoomService.java`
- Modify: `back-end/app/src/main/java/org/jn/waitingroom/service/WaitingRoomMaintenanceService.java`
- Test: `back-end/app/src/test/java/org/jn/waitingroom/redis/RedisWaitingRoomStoreTest.java`

**Interfaces:**
- Consumes: `ServiceProperties.etaWindow()`
- Produces: `slot-release-events:{serviceId}` ZSET, score=`Redis TIME epoch ms`, member=`reservationRequestId`
- Changes: `enter`, `complete`, `cancel`, `expireActiveIfDue`가 `Duration etaWindow`을 받습니다.

- [x] **Step 1: 완료 재호출과 만료 후 지연 완료가 표본을 중복 생성하지 않는 테스트를 추가합니다.**

```java
assertEquals(1L, redis.opsForZSet().size("slot-release-events:{reservation-service}"));
assertEquals(Duration.ofMinutes(10).toSeconds(), redis.getExpire("slot-release-events:{reservation-service}"));
```

- [x] **Step 2: Redis 저장소 테스트를 실행해 표본 키가 없어 실패하는지 확인합니다.**

Run: `./gradlew :app:test --tests org.jn.waitingroom.redis.RedisWaitingRoomStoreTest`

- [x] **Step 3: 각 Lua 전이에서 `ZREM active-slots` 결과가 1인 경우에만 표본을 기록합니다.**

```lua
local released = redis.call('ZREM', KEYS[activeSlotsIndex], reservationRequestId)
if released == 1 then
  redis.call('ZADD', KEYS[releaseEventsIndex], now, reservationRequestId)
  redis.call('ZREMRANGEBYSCORE', KEYS[releaseEventsIndex], '-inf', '(' .. (now - etaWindowMs))
  redis.call('PEXPIRE', KEYS[releaseEventsIndex], etaWindowMs * 2)
end
```

- [x] **Step 4: Java 서비스와 유지보수 호출부에 `etaWindow`을 전달하고 관련 테스트 생성자를 갱신합니다.**

- [x] **Step 5: Redis 저장소와 서비스 테스트 통과를 확인합니다.**

Run: `./gradlew :app:test --tests org.jn.waitingroom.redis.RedisWaitingRoomStoreTest --tests org.jn.waitingroom.service.WaitingRoomMaintenanceServiceTest`

### Task 3: 원자적 ETA와 Polling 주기 계산

**Files:**
- Modify: `back-end/app/src/main/java/org/jn/waitingroom/redis/RedisWaitingRoomStore.java`
- Modify: `back-end/app/src/main/java/org/jn/waitingroom/service/WaitingRoomService.java`
- Test: `back-end/app/src/test/java/org/jn/waitingroom/redis/RedisWaitingRoomStoreTest.java`

**Interfaces:**
- Produces: `WaitingSnapshot.estimatedWaitSeconds(): Long`
- Inputs: short/medium/long poll delays, capacity, heartbeat policy, ETA policy, `maxAdmissionsPerRun`, `schedulerInterval`

- [x] **Step 1: 세 가지 핵심 계산 테스트를 추가합니다.**

```java
// 여유 슬롯이 있으면 스케줄러 배치 지연만 반환한다.
assertEquals(2L, snapshot.estimatedWaitSeconds());
// 슬롯 반환이 필요하지만 표본이 부족하면 계산하지 않는다.
assertNull(snapshot.estimatedWaitSeconds());
// 최소 표본이 있으면 max(배치 지연, 슬롯 반환 지연)을 올림해 반환한다.
assertTrue(snapshot.estimatedWaitSeconds() >= 6L);
```

- [x] **Step 2: Redis 저장소 테스트를 실행해 ETA 필드가 없어 실패하는지 확인합니다.**

Run: `./gradlew :app:test --tests org.jn.waitingroom.redis.RedisWaitingRoomStoreTest`

- [x] **Step 3: Poll Lua에 release-event 키를 추가하고 Redis TIME으로 순번·용량·관측기간·반환률을 계산합니다.**

```lua
local availableSlots = math.max(0, maxConcurrentUsers - activeSlotCount)
local requiredReleases = math.max(0, position - availableSlots)
local batchDelay = math.ceil(position / maxAdmissionsPerRun) * schedulerIntervalSeconds
local observationSeconds = math.max(etaMinObservationSeconds, math.min(etaWindowSeconds, (nowMs - oldestSampleAt) / 1000))
local releaseDelay = requiredReleases / (releaseSampleCount / observationSeconds)
local estimatedWaitSeconds = math.ceil(math.max(batchDelay, releaseDelay))
```

- [x] **Step 4: ETA 경계에 맞춰 short/medium/long jitter 값을 Lua 안에서 선택하고 여섯 번째 응답값으로 반환합니다.**

```lua
if estimatedWaitSeconds and estimatedWaitSeconds < 60 then
  selectedPollDelay = shortPollDelayMs
elseif estimatedWaitSeconds and estimatedWaitSeconds < 3600 then
  selectedPollDelay = mediumPollDelayMs
else
  selectedPollDelay = longPollDelayMs
end
```

- [x] **Step 5: `WaitingSnapshot` 파싱과 JavaDoc을 확장하고 저장소 테스트 통과를 확인합니다.**

Run: `./gradlew :app:test --tests org.jn.waitingroom.redis.RedisWaitingRoomStoreTest`

### Task 4: HTTP 상태조회 응답 연결

**Files:**
- Modify: `back-end/app/src/main/java/org/jn/waitingroom/service/WaitingRoomService.java`
- Test: `back-end/app/src/test/java/org/jn/waitingroom/api/WaitingRoomControllerTest.java`
- Test: `back-end/app/src/test/java/org/jn/waitingroom/service/PollingPolicyTest.java`

**Interfaces:**
- Consumes: `WaitingSnapshot.estimatedWaitSeconds()`
- Produces: 기존 `GET /api/v1/services/{serviceId}/waiting-requests/{reservationRequestId}`의 `estimatedWaitSeconds`와 `nextPollAfterMs`

- [x] **Step 1: 계산 가능·불가능 응답과 60초/3600초 Polling 경계를 검증하는 테스트를 추가합니다.**

```java
assertThat(response.getBody().estimatedWaitSeconds()).isNotNull();
assertThat(response.getBody().nextPollAfterMs()).isBetween(900L, 1_100L);
```

- [x] **Step 2: API 테스트를 실행해 현재 `estimatedWaitSeconds=null` 고정값 때문에 실패하는지 확인합니다.**

Run: `./gradlew :app:test --tests org.jn.waitingroom.api.WaitingRoomControllerTest --tests org.jn.waitingroom.service.PollingPolicyTest`

- [x] **Step 3: `WaitingRoomService.poll`에서 세 jitter 값을 만들고 설정·스케줄러 처리량을 저장소에 전달한 뒤 snapshot ETA를 응답에 복사합니다.**

```java
Long estimatedWaitSeconds = snapshot.estimatedWaitSeconds();
return new WaitingStatus(requestId, state.status(), snapshot.position(), snapshot.waitingCount(), estimatedWaitSeconds, nextPollAfterMs, redirectUrl, state.expirationReason());
```

- [x] **Step 4: API와 Polling 정책 테스트 통과를 확인합니다.**

Run: `./gradlew :app:test --tests org.jn.waitingroom.api.WaitingRoomControllerTest --tests org.jn.waitingroom.service.PollingPolicyTest`

### Task 5: 회귀 검증과 문서 일치 확인

**Files:**
- Verify: `docs/waiting-room-architecture.md`
- Verify: `docs/superpowers/specs/2026-09-28-estimated-wait-time-design.md`
- Verify: all modified source and test files

**Interfaces:**
- Consumes: Tasks 1-4의 설정, 표본 기록, 계산, API 계약
- Produces: 빌드·테스트·문서가 같은 동작을 설명한다는 검증 증거

- [x] **Step 1: 백엔드 전체 테스트를 실행합니다.**

Run: `./gradlew :app:test`
Expected: 모든 테스트 PASS

- [x] **Step 2: 백엔드 빌드를 실행합니다.**

Run: `./gradlew :app:build`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: 변경 파일의 LF와 diff whitespace 오류를 확인합니다.**

Run: `git diff --check`
Expected: 출력 없음

- [x] **Step 4: 계획과 설계 문서의 식별자·기본값·수식이 구현과 일치하는지 검토하고 변경 내역을 사용자에게 보고합니다.**

Run: `git diff -- back-end/app/src/main/java back-end/app/src/main/resources/application.yml back-end/app/src/test docs/waiting-room-architecture.md docs/superpowers/specs/2026-09-28-estimated-wait-time-design.md`
