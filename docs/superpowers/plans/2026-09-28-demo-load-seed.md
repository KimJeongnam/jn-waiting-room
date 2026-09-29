# Demo Load Seed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 여유 슬롯 대상의 1초 Polling과 데모 전용 활성 1,000명·대기 10,000명 시드를 구현한다.

**Architecture:** Redis Lua가 활성 슬롯 수와 대기 순번을 원자적으로 비교해 빠른 Polling 여부를 반환한다. `demo` 프로필에서만 동작하는 시작 초기화기가 기존 Redis 상태 전이를 재사용해 테스트 상태를 구성한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Data Redis, JUnit 5, Testcontainers

## Global Constraints

- 랜덤 1~5분 만료는 최초 활성 테스트 사용자에게만 적용한다.
- 실제 브라우저 요청에는 기존 만료 설정을 유지한다.
- 운영 프로필에서는 데모 시드를 실행하지 않는다.
- 새로운 외부 의존성을 추가하지 않는다.

---

### Task 1: 여유 슬롯 대상 빠른 Polling

**Files:**
- Modify: `back-end/app/src/main/java/org/jn/waitingroom/redis/RedisWaitingRoomStore.java`
- Modify: `back-end/app/src/main/java/org/jn/waitingroom/service/WaitingRoomService.java`
- Test: `back-end/app/src/test/java/org/jn/waitingroom/redis/RedisWaitingRoomStoreTest.java`
- Test: `back-end/app/src/test/java/org/jn/waitingroom/api/WaitingRoomControllerTest.java`

**Interfaces:**
- `RedisWaitingRoomStore.poll(...)`은 빠른 주기와 `maxConcurrentUsers`를 입력받는다.
- Redis Lua가 활성 슬롯 수와 순번으로 실제 다음 조회 주기를 선택해 기존 `WaitingSnapshot.delayMs`로 반환한다.

- [ ] 여유 슬롯 안쪽과 바깥쪽 순번을 구분하는 실패 테스트를 작성한다.
- [ ] 테스트가 기존 장기 주기 때문에 실패하는지 확인한다.
- [ ] Polling Lua와 서비스 주기 선택을 최소 변경한다.
- [ ] Redis 및 Controller 테스트를 통과시킨다.

### Task 2: 데모 전용 초기 상태

**Files:**
- Create: `back-end/app/src/main/java/org/jn/waitingroom/config/WaitingRoomDemoProperties.java`
- Create: `back-end/app/src/main/java/org/jn/waitingroom/service/WaitingRoomDemoSeeder.java`
- Create: `back-end/app/src/test/java/org/jn/waitingroom/service/WaitingRoomDemoSeederTest.java`
- Modify: `back-end/app/src/main/resources/application.yml`
- Modify: `.run/Backend Debug.run.xml`

**Interfaces:**
- `WaitingRoomDemoProperties`는 활성·대기 인원과 활성 슬롯 만료 범위를 제공한다.
- `WaitingRoomDemoSeeder`는 `demo` 프로필과 `seed-enabled=true`에서만 실행한다.
- 재실행 시 고정된 데모 테스트 ID만 삭제한 뒤 초기 상태를 복구한다.

- [ ] 소규모 시드로 활성/대기 수와 만료 범위를 검증하는 실패 테스트를 작성한다.
- [ ] 테스트가 시드 컴포넌트 부재로 실패하는지 확인한다.
- [ ] 기존 store 상태 전이를 재사용하는 최소 초기화기를 구현한다.
- [ ] 데모 실행 설정을 1,000/10,000/1~5분으로 지정한다.
- [ ] 시드 테스트와 전체 백엔드 테스트를 통과시킨다.

### Task 3: 실행 검증

**Files:**
- Modify: `docs/waiting-room-architecture.md`

- [ ] 데모 전용 설정과 운영 격리 조건을 문서에 기록한다.
- [ ] 백엔드·프런트·Compose 검증을 실행한다.
- [ ] 데모를 재기동하고 Redis에서 활성 1,000명·대기 10,000명을 확인한다.
- [ ] 실제 신청의 빠른 Polling과 테스트 사용자 랜덤 만료 범위를 확인한다.
