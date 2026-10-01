# Waiting Room 핵심 기능 구조

- 문서 대상: Waiting Room을 연동·배포·운영하는 개발자
- 대상: 서비스 A와 Waiting Room 개발자
- 범위: 대기 등록, Waiting 세션, 상태 Polling, 동시 이용자 수 기반 입장, 서비스 완료에 따른 슬롯 반환
- 핵심 원칙: 하나의 요청은 전체 업무 흐름에서 `reservationRequestId` 하나로 식별한다.
- Browser 제약: 한 Browser에서는 Waiting origin의 고정 쿠키로 활성 Waiting 세션 하나만 유지한다.
- 제외: Redis 장애로 인한 데이터 유실 복구, 장기 이력 저장, 자동 용량 증감

## 1. 목적과 전제

시스템은 다음 두 서비스로 구성한다.

- **서비스 A - 대상 서비스**: 예매 신청부터 실제 서비스 이용과 완료까지 담당한다.
- **서비스 B - Waiting Room**: 대기 순서, 입장 허용 상태와 동시 이용 슬롯을 관리한다.

서비스 A Backend가 전역에서 유일한 `reservationRequestId`를 생성한다. 이 값은 다음 용도로 동일하게 사용한다.

- HTTP `Idempotency-Key`
- Redis Sorted Set member
- 상태 전이 식별자
- 서비스 간 전달 식별자

별도의 대기열 ID, 입장 ID 또는 완료 ID는 만들지 않는다. 다만 Browser가 Waiting 세션을 안전하게 시작하기 위한 짧은 수명의 일회용 `waitingToken`은 보안 자격으로 별도 발급한다. `waitingToken`은 업무 식별자가 아니다.

Waiting Room은 초당 요청 수가 아니라 **서비스 A가 동시에 수용할 수 있는 최대 이용자 수**를 기준으로 입장을 허용한다. 요청이 `ADMITTED`가 되는 순간 슬롯을 점유하고, 서비스가 완료되거나 슬롯 만료시간을 넘을 때까지 유지한다.

## 2. 전체 아키텍처

다이어그램은 OAuth2를 활성화한 배포를 나타낸다. OAuth2를 비활성화한 `UPSTREAM_MANAGED` 모드에서는 Access Token 단계가 API Gateway 또는 private network의 접근 통제로 대체된다.

```mermaid
flowchart LR
    User[사용자 Browser]
    AuthorizationServer[외부 Authorization Server]

    subgraph ServiceA[서비스 A - 대상 서비스]
        ReservationPage[예매 신청 페이지]
        ServicePage[서비스 이용 페이지]
        ServiceBackend[서비스 A Backend]
    end

    subgraph ServiceB[서비스 B - Waiting Room]
        WaitingPage[Waiting Front]
        WaitingApi[Waiting API]
        Scheduler[Spring Scheduler]
        Subscriber[슬롯 해제 Subscriber]
    end

    Redis[(Redis<br/>대기·heartbeat·활성 슬롯 ZSET<br/>+ 요청 상태 + Waiting token + Pub/Sub)]

    User --> ReservationPage
    ReservationPage -->|예매 신청| ServiceBackend
    ServiceBackend -->|Access Token 확보| AuthorizationServer
    ServiceBackend -->|Bearer Token + reservationRequestId로 대기 등록| WaitingApi
    WaitingApi --> Redis
    WaitingApi -->|waitingToken이 포함된 waitingUrl| ServiceBackend
    ServiceBackend -->|waitingUrl| ReservationPage
    ReservationPage -->|브라우저 이동| WaitingPage
    WaitingPage -->|token 소비와 Waiting 세션 생성| WaitingApi

    WaitingPage -->|상태 Polling + heartbeat| WaitingApi
    WaitingApi -->|WAITING: 순번과 다음 조회 주기| WaitingPage
    WaitingApi -->|ADMITTED: 등록된 redirectUrl| WaitingPage
    WaitingPage -->|window.location.replace| ServicePage

    ServicePage -->|서비스 진입| ServiceBackend
    ServiceBackend -->|Bearer Token으로 ENTERED 처리| WaitingApi
    ServiceBackend -->|Bearer Token으로 서비스 완료 처리| WaitingApi

    Scheduler --> Redis
    Subscriber --> Redis
    Redis -. slot-released .-> Subscriber
```

Waiting 서버가 Browser를 직접 redirect하지 않는다. Waiting Front가 상태를 Polling하다가 `ADMITTED` 응답을 받으면 `window.location.replace(redirectUrl)`로 서비스 A에 이동한다.

이미 입장한 세션을 다시 조회하여 `ENTERED`를 받는 경우에도 저장된 `redirectTargetId`의 동일한 `redirectUrl`로 복귀하며 Polling을 재개하지 않는다.

서비스 A는 Browser가 돌아오면 자신이 보관한 `reservationRequestId`와 사용자 또는 세션의 소유 관계를 확인한다. 이후 서비스 A Backend가 Waiting API에 입장 처리를 요청하여 `ADMITTED`를 `ENTERED`로 변경한다.

## 3. 구성요소 책임

| 구성요소 | 책임 |
|---|---|
| 서비스 A 예매 신청 페이지 | 신청 요청, 발급받은 `waitingUrl`로 이동 |
| 서비스 A Backend | `reservationRequestId` 생성, 외부 Access Token 확보, 대기 등록, 서비스 진입 확인, 완료·취소 통보 |
| 서비스 A 이용 페이지 | 입장 허용된 사용자의 실제 서비스 이용 |
| Waiting Front | 일회용 token 교환, 상태 Polling, heartbeat, 순번 표시, 서비스 A로 이동 |
| Waiting API | OAuth2 Resource Server 검증, 서비스 소유권 확인, 멱등 등록, Waiting 세션, 상태 조회, 입장·완료·취소 처리 |
| Spring Scheduler | 이탈·만료 요청 정리와 빈 슬롯 보충을 설정된 `schedulerInterval` 주기로 재조정 |
| 슬롯 해제 Subscriber | 내부 `slot-released` 신호를 받아 빈 슬롯을 즉시 보충 |
| Redis | 대기 순서, heartbeat, 활성 슬롯, 요청 상태와 내부 Pub/Sub 제공 |

Scheduler와 Subscriber는 Waiting API와 같은 Spring Boot 애플리케이션에 둔다. 별도 Worker나 외부 메시지 브로커는 추가하지 않는다.

## 4. 상태 모델

```mermaid
stateDiagram-v2
    [*] --> WAITING: 대기 등록
    WAITING --> ADMITTED: 빈 슬롯 확보
    WAITING --> EXPIRED: heartbeat 또는 최대 대기시간 초과
    WAITING --> CANCELLED: 서비스 A가 취소
    ADMITTED --> ENTERED: 서비스 A가 입장 확인
    ADMITTED --> EXPIRED: 입장 유효시간 초과
    ADMITTED --> CANCELLED: 서비스 A가 취소
    ENTERED --> EXPIRED: 최대 서비스 이용시간 초과
    ENTERED --> ENTERED: 정상 완료, 슬롯만 반환
```

| 상태 | 의미 | 활성 슬롯 점유 |
|---|---|---|
| `WAITING` | 대기열에서 순서를 기다리는 상태 | 아니요 |
| `ADMITTED` | 입장이 허용됐지만 서비스 A가 아직 입장을 확인하지 않은 상태 | 예 |
| `ENTERED` | 서비스 A가 실제 진입을 확인한 상태 | 완료 전까지 예 |
| `EXPIRED` | heartbeat, 대기시간 또는 슬롯 유효시간이 만료된 상태 | 아니요 |
| `CANCELLED` | 서비스 A가 대기 또는 입장 허용을 취소한 상태 | 아니요 |

`ENTERED` 이후에는 `CANCELLED`로 전이하지 않는다. 정상 완료는 `completedAt`을 기록한 뒤 활성 슬롯만 반환한다. 완료 후에도 상태는 `ENTERED`이지만 서비스 이용은 종료되며, 활성 이용 여부는 `completedAt == null`과 활성 슬롯 membership을 함께 확인한다.

## 5. Redis 데이터 구조

모든 업무 Key는 `{serviceId}` hash tag를 사용한다. Standalone Redis와 Redis Cluster에서 같은 Key 규칙을 사용하며, Cluster에서는 같은 서비스의 다중 Key Lua가 한 hash slot에서 실행된다. 서로 다른 `serviceId`는 분산할 수 있지만 단일 인기 서비스의 Key와 Lua 부하는 여러 shard로 나뉘지 않는다.

### 5.1 대기열

```text
Key    waiting:{serviceId}
Type   Sorted Set
Score  requestedAt의 Unix epoch milliseconds
Member reservationRequestId
```

- `ZADD NX`로 중복 등록을 막는다.
- 순번은 `ZRANK + 1`로 계산한다.
- 등록 score는 `max(현재 Unix epoch milliseconds, 마지막 score + 0.001)`로 부여하여 동일 시각에도 member 사전순이 아닌 등록 순서를 유지한다. score의 단위는 ms이며 동점마다 0.001ms씩 증가한다. 극단적 동시 등록에서는 이 증가분이 누적될 수 있으며 score를 사용하는 최대 대기시간 판정에도 반영된다. 요청 `createdAt`과 heartbeat에는 실제 현재 시각을 기록한다.
- `ADMITTED`, `EXPIRED`, `CANCELLED`가 되면 제거한다.

### 5.2 Waiting heartbeat

```text
Key    waiting-heartbeat:{serviceId}
Type   Sorted Set
Score  lastPolledAt의 Unix epoch milliseconds
Member reservationRequestId
```

- 대기 등록 시 `requestedAt`을 최초 heartbeat score로 추가한다.
- 정상 상태 Polling마다 현재 시각으로 score를 갱신한다.
- `lastPolledAt + heartbeatTimeout`이 현재 시각보다 작거나 같으면 이탈 요청으로 처리한다.
- `WAITING` 상태가 끝나면 제거한다.

### 5.3 활성 슬롯

```text
Key    active-slots:{serviceId}
Type   Sorted Set
Score  slotExpiresAt의 Unix epoch milliseconds
Member reservationRequestId
```

- `WAITING → ADMITTED` 시 추가하고 `admissionTimeout`을 만료시간으로 사용한다.
- `ADMITTED → ENTERED` 시 score를 `now + maxSessionDuration`으로 갱신한다.
- 정상 완료, 취소 또는 만료 시 제거한다.
- 가용 슬롯은 `max(0, maxConcurrentUsers - ZCARD(active-slots:{serviceId}))`로 계산한다.

#### 5.3.1 예상 대기시간용 슬롯 반환 이력

```text
Key    slot-release-events:{serviceId}
Type   Sorted Set
Score  slotReleasedAt의 Unix epoch milliseconds
Member reservationRequestId
TTL    etaWindow의 2배
```

- 예상 대기시간은 서비스 이용 완료시간이 아니라 현재 요청이 `ADMITTED`가 될 때까지의 시간이다. 유지보수 batch 제한과 다음 Scheduler 보정 실행까지의 지연도 포함한다.
- 정상 완료, `SESSION_TIMEOUT`, `ADMISSION_TIMEOUT`, `ADMITTED` 취소와 고아 활성 슬롯 정리에서 `active-slots` member가 실제로 제거된 경우만 반환 이벤트로 기록한다.
- 각 상태 변경 Lua는 `ZREM active-slots`의 결과가 `1`일 때만 같은 원자 처리 안에서 `ZADD slot-release-events`를 실행한다. 중복 완료나 이미 제거된 슬롯은 표본을 추가하지 않는다.
- `nowMs`는 각 Lua가 Redis `TIME`으로 얻은 millisecond 값이다. 최근 표본 범위는 `[nowMs - etaWindowMs, nowMs]`로 양 끝을 포함하고, 정리 시 cutoff보다 작은 score만 제거한다.
- 이벤트 기록 시 오래된 member를 제거하고 Key TTL을 `etaWindow * 2`로 갱신한다. Polling은 TTL을 갱신하지 않으므로 새 반환이 없으면 마지막 기록 후 자연 만료된다.
- Key는 다른 서비스 상태와 같은 `{serviceId}` hash tag를 사용한다. 각 Lua가 접근하는 모든 Key는 호출자가 `KEYS[]`로 전달하며 Lua 내부에서 Key 이름을 조합하지 않는다.
- Lua는 명령 사이의 interleaving을 막지만 runtime 오류 시 이전 쓰기를 rollback하지 않는다. 반환 이력 기록에 실패하면 이력 Key 삭제를 시도한다. 이후 ETA는 남은 표본과 초기 반환률 설정에 따라 계산하므로 운영자는 Redis 오류와 메모리 여유를 함께 확인한다.

### 5.4 요청 상태

```text
Key    waiting-request:{serviceId}:{reservationRequestId}
Type   String(JSON)
TTL    생성 시점부터 requestTtl
```

```json
{
  "reservationRequestId": "reservation-123",
  "serviceId": "reservation-service",
  "status": "WAITING",
  "redirectTargetId": "service-entry",
  "payloadFingerprint": "sha256:...",
  "currentWaitingTokenHash": "sha256:...",
  "waitingSessionVersion": 1,
  "createdAt": 1790000000000
}
```

- 재등록 시 `serviceId`와 payload fingerprint가 같으면 현재 상태를 반환한다.
- 동일한 Idempotency-Key에 다른 payload가 들어오면 `409 Conflict`를 반환한다.
- 상태 변경은 기존 TTL을 유지한다.
- 상태 시각은 Unix epoch milliseconds로 저장하며 값이 없는 필드는 생략한다.
- 상태 Key TTL은 전체 요청 생명주기보다 길어야 한다.

### 5.5 Waiting 접근 token

```text
Key    waiting-access:{serviceId}:{tokenHash}
Type   String(JSON)
TTL    waiting-token-ttl (기본 5분)

Value
{
  "serviceId": "reservation-service",
  "reservationRequestId": "reservation-123",
  "version": 1
}
```

- CSPRNG로 생성한 고엔트로피 token의 검증용 hash만 Key에 사용한다.
- Browser가 보낸 `serviceId`는 Key 조회 범위를 정하는 데만 사용하고, token에 저장된 값과 일치하는지 다시 검증한다.
- 최초 Waiting 페이지 접근에서 원자적으로 한 번만 소비한다.
- 요청 상태의 `currentWaitingTokenHash`와 일치하는 최신 token만 허용한다. 새 token을 발급하면 이전 token Key를 삭제한다.
- 소비 성공 시 서버가 `HttpOnly` Waiting 세션 쿠키를 발급한다.
- token은 URL fragment로 전달하고 Waiting Front가 읽은 즉시 `history.replaceState()`로 제거한다. fragment는 HTTP 요청·Referer·서버 로그에 전송되지 않으며 이후 Polling에는 사용하지 않는다.

### 5.6 Waiting 세션

```text
Key    waiting-session:{serviceId}:{sessionHash}
Type   String(JSON)
TTL    요청 상태 Key의 남은 TTL 이내

Value
{
  "serviceId": "reservation-service",
  "reservationRequestId": "reservation-123",
  "version": 1,
  "nextPollAllowedAt": 0
}
```

- 쿠키는 공개 routing 값인 `serviceId`와 CSPRNG로 생성한 세션 secret으로 구성한다.
- Redis에는 세션 secret 원문 대신 hash를 사용한다.
- 쿠키의 `serviceId`는 Key 조회 범위만 정하며, 세션 레코드의 값과 일치해야 한다.
- 세션 생성과 token 소비는 같은 `{serviceId}` hash slot에서 원자적으로 처리한다.
- Polling은 세션 hash와 `waitingSessionVersion`이 요청 상태의 현재 값과 모두 일치할 때만 허용한다.
- 서비스 A는 자신의 로그인 또는 익명 Browser 세션별로 현재 `reservationRequestId` 하나를 저장한다. 이 값의 생성·교체는 DB transaction, compare-and-set 또는 unique constraint로 직렬화하고 승자 한 요청만 Waiting Room에 등록한다.
- Waiting origin의 `__Host-waiting_session` 쿠키는 Browser 전체에서 하나이므로 다른 서비스 A를 포함해 가장 최근 Waiting 세션만 Browser에서 유지한다. 같은 서비스 A가 소유한 기존 요청은 새 요청 전에 취소하고, 서비스 간 조정이 불가능한 이전 요청은 cookie 교체 후 heartbeat timeout으로 정리한다.

### 5.7 내부 슬롯 해제 채널

```text
Channel waiting-room:slot-released:{serviceId}
Payload reservationRequestId
```

Pub/Sub은 빈 슬롯을 빠르게 보충하기 위한 내부 신호일 뿐 상태 저장소가 아니다. 메시지가 유실돼도 Scheduler가 Redis 상태를 기준으로 설정된 `schedulerInterval` 안에 다시 조정한다. 외부 서비스에는 Redis와 Pub/Sub을 노출하지 않는다.

### 5.8 유지보수 실행 lease

```text
Key    maintenance-lease:{serviceId}
Type   String(instanceId + 실행별 고유 token)
TTL    한 번의 유지보수 실행 제한시간
```

- Scheduler와 Pub/Sub Subscriber는 실행 전에 `SET ... NX PX`로 서비스별 lease를 획득한다.
- lease를 획득한 한 인스턴스만 만료 정리와 빈 슬롯 보충 Lua를 실행한다.
- 각 유지보수 pass는 고유 token을 사용하며, 정상 종료 시 현재 lease token과 일치할 때만 유지보수 heartbeat를 기록한다. 만료되거나 다른 실행이 획득한 lease로는 성공 기록을 갱신하지 않는다.
- 정상 종료 시 단일 compare-and-delete Lua로 소유자가 일치할 때만 lease를 삭제하고, 인스턴스 중단 시에는 TTL로 해제한다.
- Pub/Sub 신호가 여러 인스턴스에 전달돼도 같은 서비스의 중복 실행을 억제한다.

### 5.9 heartbeat 만료 복구 barrier

```text
Key    maintenance-heartbeat:{serviceId}
Type   String(마지막 유지보수 성공 시각)
TTL    maintenanceHealthTimeout

Key    heartbeat-expiry-barrier:{serviceId}
Type   String(JSON)
TTL    heartbeatExpirationRecoveryGrace + cleanupMargin

Value
{
  "recoveryId": "01J8RECOVERY...",
  "recoveredAt": 1790000000000,
  "expiresAt": 1790001200000
}
```

- lease를 획득한 인스턴스는 유지보수 성공 후 `maintenance-heartbeat`를 갱신한다.
- 각 인스턴스는 시작 또는 Redis 연결 단절 시 readiness를 닫고 Polling과 상태 변경 요청에 `503`을 반환한다.
- Redis 연결이 복구되면 bootstrap Lua가 서비스별 대기열을 확인한다. 대기열이 비어 있는 cold start는 barrier를 삭제하고 바로 복구를 마친다.
- 대기열이 있고 기존 barrier가 활성이라면 원래 `expiresAt`을 재사용하고 연장하지 않는다.
- 활성 barrier가 없으면 `maintenance-heartbeat` 값의 epoch ms를 읽어 `now - lastMaintenanceAt < 해당 서비스 heartbeatTimeout`일 때만 정상으로 간주하며 barrier를 만들지 않는다. 기록이 없거나 오래됐으면 새 `recoveryId`, `recoveredAt`과 `now + heartbeatExpirationRecoveryGrace`를 기록한다. 논리 만료된 barrier는 정리 TTL이 남아 있어도 교체할 수 있다. 다른 인스턴스가 늦게 복구돼도 활성 barrier를 연장하지 않는다.
- 모든 서비스 bootstrap과 Pub/Sub 구독 시작이 성공하면 복구 gate를 열어 Polling, Scheduler와 상태 변경 요청을 허용한다. HTTP readiness는 운영 Redis 안전성 확인도 함께 요구한다. 구독은 bootstrap 이후에 시작하므로 Redis가 없는 상태에서도 서버가 기동하고 복구를 재시도한다.
- Polling과 유지보수 Lua는 이 시각 전까지 `HEARTBEAT_TIMEOUT` 만료를 적용하지 않는다.
- `maxWaitDuration`, `ADMISSION_TIMEOUT`, `SESSION_TIMEOUT`은 복구 barrier와 관계없이 적용한다.

### 5.10 손상 상태 격리

```text
Key    waiting-quarantine:{serviceId}:{reservationRequestId}:{detectedAt}
Type   String(손상된 원문)
TTL    quarantineTtl
```

- 상태 JSON decode에 실패하면 원문을 격리 Key에 저장해 운영 확인 근거를 남긴다.
- 같은 Lua에서 요청 상태를 최소한의 `EXPIRED`, `STATE_CORRUPTED` JSON으로 교체하되 기존 요청 상태 Key의 남은 TTL을 유지한다.
- 격리 Key는 자동 복구에 사용하지 않으며 개인정보와 token 원문을 포함하지 않도록 상태 payload 자체를 제한한다.

## 6. 사용자 흐름

```mermaid
flowchart TD
    Start([서비스 A 예매 신청 페이지])
    Apply[예매 신청]
    Register[서비스 A Backend가<br/>reservationRequestId 생성 및 대기 등록]
    Token[Waiting token 소비<br/>세션 쿠키 발급]
    Poll{상태 Polling + heartbeat}
    Display[순번·예상 대기시간 표시]
    Redirect[window.location.replace<br/>서비스 A 이용 페이지로 이동]
    Enter[서비스 A Backend가<br/>ENTERED 처리]
    Use[서비스 이용]
    Complete[완료 통보 및 슬롯 반환]
    Expired([만료 안내])
    Cancelled([취소 안내])

    Start --> Apply --> Register --> Token --> Poll
    Poll -->|WAITING| Display --> Poll
    Poll -->|ADMITTED| Redirect --> Enter --> Use --> Complete
    Poll -->|EXPIRED| Expired
    Poll -->|CANCELLED| Cancelled
```

## 7. 전체 시퀀스

```mermaid
sequenceDiagram
    autonumber

    actor User as 사용자
    participant AFront as 서비스 A Front
    participant ABackend as 서비스 A Backend
    participant Auth as 외부 Authorization Server
    participant WFront as Waiting Front
    participant WApi as Waiting API
    participant Redis
    participant Internal as Scheduler / Subscriber

    User->>AFront: 예매 신청
    AFront->>ABackend: 신청 요청
    ABackend->>ABackend: reservationRequestId 생성 및 사용자와 연결
    ABackend->>Auth: Access Token 요청
    Auth-->>ABackend: JWT Access Token
    ABackend->>WApi: Bearer Token으로 대기 등록<br/>Idempotency-Key = reservationRequestId
    WApi->>WApi: 서명·issuer·audience·exp·scope·소유권 검증
    WApi->>Redis: 요청·대기열·token 원자 생성
    WApi-->>ABackend: WAITING, waitingUrl(serviceId, token)
    ABackend-->>AFront: waitingUrl
    AFront-->>WFront: 브라우저 이동
    WFront->>WApi: waitingToken 교환
    WApi->>Redis: token 원자 소비
    WApi-->>WFront: Waiting 세션 쿠키

    loop status = WAITING
        WFront->>WApi: 세션 기반 상태 Polling
        WApi->>Redis: heartbeat 갱신 및 상태·순번 조회
        WApi-->>WFront: position, estimatedWaitSeconds, nextPollAfterMs
    end

    Internal->>Redis: 제한된 배치로 WAITING → ADMITTED
    WFront->>WApi: 상태 Polling
    WApi-->>WFront: ADMITTED, 등록된 redirectUrl
    WFront-->>AFront: window.location.replace(redirectUrl)

    AFront->>ABackend: 서비스 진입
    ABackend->>ABackend: 사용자와 reservationRequestId 소유 관계 확인
    ABackend->>WApi: Bearer Token으로 ENTERED 처리
    alt Access Token 만료
        WApi-->>ABackend: 401 invalid_token
        ABackend->>Auth: 새 Access Token 요청
        Auth-->>ABackend: 새 Access Token
        ABackend->>WApi: 동일 ENTERED 요청 1회 재시도
    end
    WApi->>Redis: ADMITTED → ENTERED, 슬롯 만료 연장
    WApi-->>ABackend: ENTERED, sessionExpiresAt
    ABackend-->>AFront: 서비스 이용 허용

    AFront->>ABackend: 서비스 완료
    ABackend->>WApi: Bearer Token으로 완료 통보
    WApi->>Redis: completedAt 기록, 슬롯 제거, publish
    WApi-->>ABackend: 멱등 완료 결과
    Redis-->>Internal: slot-released
    Internal->>Redis: 빈 슬롯 보충
```

## 8. 원자 처리

Lua script는 필요한 Key type, 입력값과 상태 JSON을 확인하고 시간 판단에는 Redis `TIME`을 사용한다. Redis Lua는 실행 중 오류가 발생해도 이전 쓰기를 롤백하지 않으므로 다음 원칙을 적용한다.

1. 공유 ZSET과 필수 Key의 type이 올바르지 않으면 쓰기 전에 전체 실행을 실패시킨다.
2. API와 유지보수는 로컬 복구 gate가 열린 경우에만 Redis 업무 처리를 수행한다. 닫혀 있으면 요청에 고정된 `503 SERVICE_UNAVAILABLE`과 `Retry-After`를 반환한다.
3. 유지보수는 후보 조회와 변경을 설정된 batch 범위로 제한한다.
4. 개별 요청 상태가 없으면 아래 고아 정리 규칙을 적용한다.
5. 유지보수와 Polling에서 손상된 요청 JSON을 발견하면 원문을 짧은 TTL의 격리 Key에 보관하고 아래 상태별 안전 종료 규칙을 적용한다.
6. Redis 연결·명령 장애는 요청에 `503`을 반환하고 복구 gate를 닫는다. 필수 내부 처리 실패도 `503`으로 응답한다. 운영자는 `noeviction`과 메모리 여유를 확보하고 Redis 오류의 원인을 확인한다.

### 8.1 대기 등록

등록 Lua script는 다음 작업을 한 번에 처리한다.

1. 요청 상태 Key가 있으면 인증된 `serviceId`와 payload fingerprint를 비교한다.
2. 모두 같으면 token과 Redis 상태를 변경하지 않고 현재 상태를 반환한다.
3. 다르면 값을 변경하지 않고 `409 Conflict`를 반환한다.
4. 상태 Key가 없으면 요청 상태를 `requestTtl`로 저장한다.
5. 대기 ZSET과 heartbeat ZSET에 `ZADD NX`로 등록한다.
6. `waitingSessionVersion=1`인 일회용 `waitingToken`을 `waitingTokenTtl`로 저장한다.
7. 신규 등록에만 `waitingUrl`을 반환한다.

쓰기 전에 Key type과 입력값을 검증한다. Redis Lua는 오류 발생 전의 쓰기를 자동 롤백하지 않으므로 검증 이후 쓰기를 시작한다.

payload fingerprint는 `serviceId`와 `redirectTargetId`처럼 등록 결과에 영향을 주는 필드를 고정된 순서와 인코딩으로 정규화한 뒤 SHA-256으로 계산한다. JSON 문자열 자체를 hash하지 않는다.

### 8.2 Waiting token 소비와 Polling

token 소비는 요청의 `serviceId`와 token hash로 Key를 찾은 뒤, 저장된 `serviceId`, 최신 token hash와 `waitingSessionVersion` 일치 확인, token 삭제, 요청 상태의 token hash 제거와 Waiting 세션 생성을 하나의 원자 처리로 묶는다. 기존 `currentWaitingSessionHash`가 있으면 이전 세션 Key를 삭제하고 새 session hash를 요청 상태에 기록한다. `WAITING`은 최대 대기시간이 유효하고 heartbeat가 유효하거나 복구 barrier가 활성일 때, `ADMITTED`는 활성 슬롯이 만료 전일 때만 세션을 만든다. 시간상 이미 만료된 요청은 같은 script에서 `EXPIRED`로 정리하고 token을 폐기한다. `ENTERED`, `EXPIRED`, `CANCELLED`, 이미 소비됐거나 만료된 token도 거부한다. Browser가 보낸 `serviceId`만으로 요청을 조회하거나 권한을 부여하지 않는다.

상태 Polling은 Waiting 세션에서 `serviceId`와 `reservationRequestId`를 얻는다. 하나의 원자 Lua script에서 다음을 수행한다.

1. 세션과 요청의 연결, session hash와 `waitingSessionVersion`이 요청 상태의 현재 값과 일치하는지 확인한다. 이전 세션이면 heartbeat를 갱신하지 않고 `401`을 반환한다.
2. Redis `TIME`으로 현재 시각을 구하고 요청 상태와 heartbeat를 읽는다.
3. `WAITING`인데 대기 또는 heartbeat ZSET member가 없으면 어떤 member도 다시 만들지 않고 남은 member를 제거한 뒤 `EXPIRED`, `INTERNAL_INCONSISTENCY`로 종료한다. 같은 ID를 자동 재등록하지 않는다.
4. `WAITING`인데 최대 대기시간이 지났거나, 복구 barrier가 끝난 상태에서 heartbeat가 만료됐으면 원자적으로 `EXPIRED` 처리하고 종료 상태를 반환한다.
5. 세션의 `nextPollAllowedAt`보다 빠른 Polling이면 heartbeat를 갱신하지 않고 9장의 계산식으로 구한 `Retry-After`와 함께 `429`를 반환한다.
6. 허용된 `WAITING` Polling이면 heartbeat score를 갱신한다.
7. 서버가 기본 조회 주기에 jitter를 적용한 최종 `nextPollAfterMs`를 계산하고 같은 시각을 세션의 `nextPollAllowedAt`에 저장한다.
8. 요청 상태와 `ZRANK`를 함께 읽어 응답한다.

### 8.3 만료 정리와 빈 슬롯 보충

Scheduler와 Pub/Sub Subscriber는 서비스별 유지보수 lease를 획득한 뒤 같은 Lua script를 호출한다. 한 번의 실행은 후보를 최대 `maxCandidatesScannedPerRun`개만 조회하고, 활성 슬롯 만료·고아 정리 최대 `maxActiveExpirationsPerRun`개, 대기 만료·고아 정리 최대 `maxWaitingExpirationsPerRun`개와 입장 최대 `maxAdmissionsPerRun`개만 처리한다. 각 batch는 요청 단위로 계산하며 하나의 요청에서 여러 ZSET member를 제거해도 1건이다. 활성 슬롯 정리와 대기 정리에 별도 예산을 보장해 한 종류의 적체가 다른 종류를 계속 굶기지 않게 한다.

1. Redis `TIME`으로 현재 시각을 구한다.
2. 만료된 활성 슬롯을 최대 `maxActiveExpirationsPerRun`개 처리한다. 기존 상태가 `ADMITTED` 또는 미완료 `ENTERED`이면 상태를 `EXPIRED`로 바꾸고 `expiredAt`과 각각 `ADMISSION_TIMEOUT`, `SESSION_TIMEOUT`을 기록한 뒤 슬롯을 제거한다. 실제 제거됐으면 슬롯 반환 이력을 기록하고 `slot-released`를 발행한다.
3. 만료된 활성 슬롯의 상태 Key가 없거나 상태가 더 이상 미완료 `ADMITTED`·`ENTERED`가 아니면 고아 member로 제거한다. 실제 제거됐으면 슬롯 반환 이력을 기록하고 `slot-released`를 발행한다. 상태 JSON이 손상됐으면 원문을 격리 Key에 보관하고 최소한의 `EXPIRED`, `STATE_CORRUPTED` 상태로 교체한 뒤 슬롯을 제거한다. 만료시각 전의 손상된 활성 슬롯은 실제 이용 중일 수 있으므로 score 만료 전에는 제거하지 않는다.
4. `maxWaitDuration`을 넘은 `WAITING` 요청과, 복구 barrier가 지난 뒤 heartbeat timeout을 넘은 `WAITING` 요청을 최대 `maxWaitingExpirationsPerRun`개 처리한다. heartbeat 후보의 대기 ZSET membership도 먼저 확인해 하나라도 없으면 남은 member를 제거하고 `EXPIRED`, `INTERNAL_INCONSISTENCY`로 종료한다. 두 member가 정상이면 각각 `MAX_WAIT_DURATION`, `HEARTBEAT_TIMEOUT`을 기록한다.
5. 상태 Key가 없거나 더 이상 `WAITING`이 아닌 고아 대기·heartbeat member를 같은 대기 정리 batch 범위에서 제거한다. `WAITING` 상태 JSON이 손상됐으면 원문을 격리 Key에 보관하고 최소한의 `EXPIRED`, `STATE_CORRUPTED` 상태로 교체한 뒤 대기·heartbeat member를 제거한다.
6. `activeCount`와 `maxConcurrentUsers`로 가용 슬롯을 계산한다.
7. 대기열 앞쪽 후보를 전체 실행 누적 `maxCandidatesScannedPerRun` 범위에서만 조회한다.
8. 각 입장 후보에 대해 상태가 `WAITING`인지, heartbeat가 존재하고 유효한지, 최대 대기시간을 넘지 않았는지 다시 확인한다.
9. 복구 barrier가 활성이고 대기열 선두의 heartbeat만 만료됐다면 해당 요청을 만료하거나 입장시키지 않고 실행을 끝낸다. 뒤 요청을 먼저 입장시키지 않아 FIFO를 유지한다.
10. barrier와 관계없는 무효 후보는 남은 대기 정리 batch가 있으면 `EXPIRED` 또는 고아 정리한다. 대기 정리 batch가 소진됐으면 더 뒤의 후보를 입장시키지 않고 실행을 끝내며 정리 backlog 경보를 평가한다.
11. 검증을 통과한 후보를 최대 `min(availableSlots, maxAdmissionsPerRun)`명까지 `ADMITTED`로 변경한다.
12. 대기·heartbeat ZSET에서 제거하고 활성 슬롯에 `now + admissionTimeout`으로 추가한다.

여러 Spring 인스턴스가 동시에 신호를 받아도 서비스별 lease를 획득한 한 인스턴스만 Lua script를 호출한다. lease 만료 등으로 드물게 실행이 겹쳐도 Redis가 Lua script를 순차 실행하므로 활성 슬롯 제한을 중복 적용하지 않는다. 만료나 입장 후보가 batch보다 많으면 다음 Scheduler 실행에서 이어서 처리한다.

### 8.4 실제 입장

서비스 A Backend의 입장 요청은 원자적으로 다음을 수행한다.

1. 인증된 `serviceId`와 요청 소유권을 확인한다.
2. 상태가 `ADMITTED`이고 활성 슬롯이 만료되지 않았는지 확인한다.
3. `ENTERED`로 변경하고 슬롯 score를 `now + maxSessionDuration`으로 갱신한다.
4. 같은 시각을 `sessionExpiresAt`으로 반환한다.

이미 `ENTERED`이고 활성 슬롯이 남아 있으면 멱등 성공으로 처리하되 만료시간을 연장하지 않는다. 완료됐거나 활성 슬롯이 없는 `ENTERED`는 재입장을 허용하지 않는다.

### 8.5 완료와 취소

정상 완료 Lua script는 `completedAt` 기록, 활성 슬롯 제거, 슬롯 반환 이력 기록과 `PUBLISH`를 한 번에 수행한다. 슬롯을 실제로 제거한 경우에만 반환 이력을 기록하고 `slot-released`를 발행한다.

- 최초 완료: 슬롯을 제거하고 성공을 반환한다.
- 중복 완료: 저장된 완료 결과를 반환한다.
- `enteredAt`이 있고 세션이 이미 만료된 완료: `completedAt`을 기록하고 슬롯이 반환됐음을 나타내는 멱등 성공을 반환한다.
- 한 번도 `ENTERED`가 아니었던 요청: `409 Conflict`를 반환한다.

취소는 `WAITING`과 `ADMITTED`에서만 허용한다. `ADMITTED` 취소는 활성 슬롯을 제거하고, 실제 제거됐으면 반환 이력을 기록한 뒤 `slot-released`를 발행한다. `ENTERED` 이후에는 완료 API를 사용한다.

## 9. Polling 정책

상태 조회 응답은 서버가 기본 주기에 ±10% jitter를 적용해 계산한 최종 `nextPollAfterMs`를 포함한다. Waiting Front는 이 값을 다시 변경하지 않고 그대로 사용한다. 서버는 같은 허용시각을 Waiting 세션의 `nextPollAllowedAt`에 기록해 응답값과 rate limit 기준이 어긋나지 않게 한다.

| 예상 대기시간 | 기본 조회 주기 |
|---|---:|
| 1분 미만 | 1초 |
| 1분 이상 60분 미만 | 10초 |
| 60분 이상 또는 계산 불가 | 15초 |

`429` 또는 `503` 응답에는 `Retry-After`를 우선 적용한다. 허용된 Polling만 heartbeat를 갱신하며 너무 빠른 요청은 heartbeat를 갱신하지 않고 `429`를 반환한다. Browser가 백그라운드에서 복귀해 허용시각 이후 호출하는 것은 정상 Polling으로 처리한다. Waiting Front는 `visibilitychange`로 화면이 다시 활성화되면 진행 중인 중복 호출이 없을 때 즉시 한 번 조회한다.

복구 barrier가 비활성일 때 `429`의 재시도 시간은 초 단위로 올림해 다음과 같이 계산한다.

```text
remainingHeartbeat = lastPolledAt + heartbeatTimeout - now
pollDelay = ceilSeconds(nextPollAllowedAt - now)

Retry-After = max(
  1 second,
  min(
    pollDelay,
    maxRetryAfter,
    remainingHeartbeat - retryAfterSafetyMargin
  )
)
```

복구 barrier가 비활성이고 `remainingHeartbeat <= retryAfterSafetyMargin`이면 `429`를 반환하지 않고 같은 Polling Lua에서 요청을 `EXPIRED`로 변경해 `200 OK` 종료 상태를 반환한다. 복구 barrier가 활성인 동안에도 `nextPollAllowedAt` 이전 요청은 heartbeat를 갱신하지 않고 `429`를 반환하되, `Retry-After = max(1 second, min(pollDelay, maxRetryAfter))`를 사용한다. `503`은 `maxRetryAfter` 이하로 반환하며, 장애가 heartbeat timeout까지 지속되면 장애 복구 grace 정책을 적용한다.

`WAITING`일 때만 Polling과 heartbeat를 계속한다. `ADMITTED`이면 서비스 A로 이동하고, `EXPIRED` 또는 `CANCELLED`이면 Polling을 종료한다.

기본 정책은 Browser가 모바일 앱 전환이나 백그라운드 timer 제한을 겪어도 20분 동안 대기를 유지하는 것이다. 이 시간 동안 이탈 요청이 대기 순번에 남을 수 있지만 활성 슬롯은 점유하지 않는다. Front가 다시 보이면 즉시 Polling해 heartbeat와 화면 상태를 회복한다.

### 9.1 예상 대기시간

예상 대기시간은 현재 순번이 가용 슬롯 범위에 들어가거나 최근 슬롯 반환 속도만큼 슬롯이 추가로 반환되어 `ADMITTED`가 될 때까지의 추정값이다. Polling Lua는 순번, 활성 슬롯 수와 최근 반환 표본을 같은 Redis 시각 기준으로 읽어 다음 값을 계산한다.

```text
availableSlots = max(0, maxConcurrentUsers - activeSlotCount)
requiredReleases = max(0, position - availableSlots)
rawBatchDelaySeconds =
  ceil(position / maxAdmissionsPerRun) * schedulerIntervalSeconds
observationSeconds = max(
  etaMinObservationSeconds,
  min(etaWindowSeconds, now - oldestSampleAt)
)
releaseRatePerSecond = releaseSampleCount / observationSeconds
fallbackReleaseRatePerSecond = etaInitialReleaseRatePerSecond
effectiveReleaseRatePerSecond =
  releaseSampleCount >= etaMinSamples
    ? releaseRatePerSecond
    : fallbackReleaseRatePerSecond
rawReleaseDelaySeconds = requiredReleases / effectiveReleaseRatePerSecond
estimatedWaitSeconds =
  ceil(max(rawBatchDelaySeconds, rawReleaseDelaySeconds))
```

`position`은 1부터 시작한다. 현재 가용 슬롯 범위라도 유지보수 batch를 거쳐야 하므로 `WAITING` 응답에서 `0`초를 반환하지 않는다. `requiredReleases=0`이면 `rawReleaseDelaySeconds=0`으로 두고 반환 표본 없이 batch 지연을 사용한다. 추가 슬롯 반환이 필요한데 최근 `etaWindow` 안의 표본이 `etaMinSamples`보다 적으면 `etaInitialReleaseRatePerSecond`가 양수일 때 그 값을 fallback 반환률로 사용한다. fallback이 0이면 `estimatedWaitSeconds=null`과 15초 주기를 반환한다. 충분한 표본이 있으면 기존처럼 실측 반환률로 전환한다. batch 지연과 반환 지연 중 큰 값을 선택한 뒤 최종 초 단위 값만 올림해 1초·10초·15초 Polling 구간을 선택한다.

표본 범위는 millisecond 기준 `[nowMs - etaWindowMs, nowMs]`이며, 관측시간은 millisecond 차이를 실수 초로 변환한다. 최종 ETA에서만 `ceil`한다. 최소 표본과 양수 관측시간이 있으면 반환률은 양수여야 하며, 0 또는 비정상 값은 손상 방어 경로로 `null` 처리한다. 예상시간, `nextPollAfterMs`와 세션의 `nextPollAllowedAt`은 하나의 Polling Lua에서 결정해 서로 다른 시점의 대기열 상태가 섞이지 않게 한다.

이 값은 최근 처리 추세 또는 초기 반환률에 기반한 추정치이며 입장 시각을 보장하지 않는다. 서비스 이용 패턴이 급변하면 관측 구간 동안 오차가 남을 수 있다. Redis 재시작 또는 이력 Key 만료 후에는 최소 표본이 다시 쌓일 때까지 초기 반환률을 사용하며, 초기 반환률이 0이면 `null`로 안전하게 복귀한다.

### 9.2 대기 진행률

Waiting Front는 현재 페이지에서 처음 받은 유효한 `position`을 최초 순번으로 보관한다. 진행률은 `(최초 순번 - 현재 순번) / 최초 순번 * 100`으로 계산하고 0~100 범위로 제한한다. 전체 대기 인원과 현재 순번이 함께 감소하는 구조에서 진행률이 계속 0%로 보이는 문제를 피하기 위한 표시 전용 값이며, 새로고침하면 새 최초 순번을 기준으로 다시 시작한다.

## 10. 외부 HTTP API

OAuth2가 활성화된 Waiting Room은 JWT Access Token을 검증하는 Resource Server로 동작한다. Access Token의 발급·갱신과 서비스 A의 Client 인증은 외부 Authorization Server와 서비스 A의 책임이다. OAuth2를 비활성화하면 Backend API 인증은 API Gateway 또는 private network에 위임된다.

서비스 A Backend가 호출하는 등록·Waiting token 재발급·입장·완료·취소 API는 JWT의 `RS256` 서명, `iss`, `aud`, `exp`, `nbf`, endpoint별 scope와 `client_id`가 소유한 `serviceId`를 검증한다. `client_id`는 대소문자를 구분하는 문자열 claim으로 고정하며 없거나 다른 형식이면 token을 거부한다. `sub` 또는 `azp`를 대체 claim으로 사용하지 않는다. Browser의 Waiting token 교환과 세션 기반 Polling에는 Backend용 Access Token을 요구하지 않는다.

위 JWT 검증은 `waiting-room.security.oauth2.enabled=true`일 때 적용한다. `false`이면 Backend API는 token 없이 호출할 수 있고, 외부 인증·인가와 서비스별 접근 통제는 운영자가 구성한 API Gateway 또는 private network가 담당한다. Waiting token 교환과 Browser 세션 검증은 OAuth2 설정과 관계없이 항상 적용한다.

| API 기능 | 필수 scope |
|---|---|
| 대기 등록 | `waiting:create` |
| Waiting token 재발급 | `waiting:token:issue` |
| 입장 확정 | `waiting:enter` |
| 서비스 이용 완료 | `waiting:complete` |
| 대기 취소 | `waiting:cancel` |

### 10.1 대기 등록

```http
POST /api/v1/waiting-requests
Authorization: Bearer {accessToken}
Idempotency-Key: reservation-123
Content-Type: application/json

{
  "serviceId": "reservation-service",
  "redirectTargetId": "service-entry"
}
```

```json
{
  "reservationRequestId": "reservation-123",
  "status": "WAITING",
  "waitingUrl": "/waiting?serviceId=reservation-service#token=opaque-one-time-token"
}
```

- 신규 등록은 `201 Created`를 반환한다.
- 동일 payload 재시도는 `200 OK`와 현재 상태만 반환하며 token을 발급하거나 교체하지 않는다.
- 같은 Idempotency-Key의 payload가 다르면 `409 Conflict`를 반환한다.
- `waitingUrl`은 Waiting origin 기준 상대 URL이다. 서비스 A는 설정으로 알고 있는 Waiting origin과 결합해 Browser를 이동시킨다.

### 10.2 Waiting token 교환

```http
POST /api/v1/waiting-session
Content-Type: application/json
X-Waiting-Request: 1
Sec-Fetch-Site: same-origin

{
  "serviceId": "reservation-service",
  "token": "opaque-one-time-token"
}
```

Waiting Front는 URL fragment에서 token을 읽은 즉시 `history.replaceState()`로 제거하고 이 API의 body로 한 번 전달한다. 성공 응답은 `204 No Content`이며 `__Host-waiting_session` 쿠키에 `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`를 설정한다. `Domain`은 설정하지 않는다. 교환과 Polling은 모두 위 두 Header를 요구하며 `Origin`이 있으면 scheme, host, port가 요청 origin과 일치해야 한다.

Waiting Front와 Waiting API는 동일 origin으로 제공한다. Browser API 호출은 same-origin cookie를 사용하며 임의 origin CORS와 Front/API 분리 배포를 지원하지 않는다.

쿠키를 잃어버린 사용자가 같은 요청으로 복귀해야 하면 서비스 A Backend가 새 token을 발급받는다.

```http
POST /api/v1/waiting-requests/reservation-123/access-tokens?serviceId=reservation-service
Authorization: Bearer {accessToken}
```

`WAITING`은 최대 대기시간이 유효하고 heartbeat가 유효하거나 복구 barrier가 활성일 때, `ADMITTED`는 활성 슬롯이 만료 전일 때만 새 token을 발급한다. 시간상 이미 만료된 요청은 token 발급과 같은 원자 처리에서 `EXPIRED`로 정리하고 발급을 거부한다. 새 token 발급 Lua는 기존 token과 현재 Waiting 세션 Key를 삭제하고, `waitingSessionVersion`을 증가시키며, 새 token에 같은 version을 연결한다. 따라서 재발급 직후부터 이전 세션은 Polling에 사용할 수 없다.

```json
{
  "reservationRequestId": "reservation-123",
  "status": "WAITING",
  "waitingUrl": "/waiting?serviceId=reservation-service#token=new-opaque-one-time-token"
}
```

등록 응답을 받지 못한 서비스 A는 같은 Idempotency-Key로 등록 결과를 확인한 뒤, 응답에 `waitingUrl`이 없으면 이 API를 명시적으로 호출한다.

재발급 경쟁은 최대 8회 시도하며 계속 충돌하면 `503 SERVICE_UNAVAILABLE`과 `Retry-After`를 반환한다.

### 10.3 상태 조회

```http
GET /api/v1/waiting-session
Cookie: __Host-waiting_session=...
X-Waiting-Request: 1
Sec-Fetch-Site: same-origin
```

상태 조회는 HTTP 형식상 GET이지만 heartbeat와 `nextPollAllowedAt`을 변경한다. Waiting Front의 fetch만 허용하기 위해 `X-Waiting-Request: 1`, `Sec-Fetch-Site: same-origin`과, 제공된 경우 정확한 `Origin`을 검증한다. cross-origin CORS는 허용하지 않는다.

```json
{
  "reservationRequestId": "reservation-123",
  "status": "WAITING",
  "position": 42,
  "estimatedWaitSeconds": 320,
  "nextPollAfterMs": 10000
}
```

`estimatedWaitSeconds`는 `ADMITTED`까지의 추정 초 단위 시간이다. 현재 가용 슬롯 범위이면 유지보수 batch 지연을 반환하고, 추가 슬롯 반환이 필요한데 최근 표본이 부족하면 `null`이다. Waiting Front는 값을 자체 재계산하지 않고 서버 응답을 표시한다.

입장 허용 시 등록된 redirect target으로 URL을 생성한다.

```json
{
  "reservationRequestId": "reservation-123",
  "status": "ADMITTED",
  "redirectUrl": "https://service-a.example.com/service-entry?reservationRequestId=reservation-123"
}
```

서비스 A는 callback에서 query의 `reservationRequestId`만 신뢰하지 않고 신청 당시 서버에 저장한 사용자·요청 소유 관계를 확인한다. Waiting Room과 서비스 A가 서로 다른 site이면 callback top-level GET에도 서비스 A 로그인 세션을 전달할 수 있도록 해당 인증 쿠키의 `SameSite` 정책을 정한다. 이를 허용할 수 없는 서비스는 서버가 발급·소비하는 짧은 수명의 일회용 handoff token을 별도 연동 계약으로 사용한다.

종료 상태도 오류가 아닌 현재 상태이므로 `200 OK`로 반환한다.

`expirationReason`은 정상 만료의 `HEARTBEAT_TIMEOUT`, `MAX_WAIT_DURATION`, `ADMISSION_TIMEOUT`, `SESSION_TIMEOUT`과 정합성 보호를 위한 `INTERNAL_INCONSISTENCY`, `STATE_CORRUPTED`를 구분한다. 마지막 두 값은 자동 재등록하지 않고 서비스 A가 새 요청 생성 여부를 결정한다.

```json
{
  "reservationRequestId": "reservation-123",
  "status": "EXPIRED",
  "expirationReason": "HEARTBEAT_TIMEOUT"
}
```

```json
{
  "reservationRequestId": "reservation-123",
  "status": "CANCELLED"
}
```

### 10.4 서비스 진입

```http
POST /api/v1/waiting-requests/reservation-123/enter?serviceId=reservation-service
Authorization: Bearer {accessToken}
```

```json
{
  "reservationRequestId": "reservation-123",
  "status": "ENTERED",
  "sessionExpiresAt": "2026-09-21T10:32:00Z"
}
```

- 최초 또는 활성 상태의 멱등 재시도는 `200 OK`다.
- `WAITING`, `EXPIRED`, `CANCELLED`, 완료된 `ENTERED`는 `409 Conflict`다.
- Browser 이동 중 입장 유효시간이 끝났다면 서비스 A가 만료 안내를 표시한다. 같은 ID를 자동 재등록하지 않는다.

### 10.5 서비스 완료

```http
POST /api/v1/waiting-requests/reservation-123/complete?serviceId=reservation-service
Authorization: Bearer {accessToken}
```

최초 완료, 중복 완료와 이미 만료되어 슬롯이 반환된 완료는 `200 OK`다. 한 번도 `ENTERED`가 아니었던 요청은 `409 Conflict`다.

### 10.6 취소

```http
POST /api/v1/waiting-requests/reservation-123/cancel?serviceId=reservation-service
Authorization: Bearer {accessToken}
```

`WAITING` 또는 `ADMITTED`에서만 허용한다. 동일 취소의 재시도는 멱등 성공으로 처리한다.

### 10.7 공통 오류

| HTTP 상태 | 의미 |
|---|---|
| `400 Bad Request` | 필수 Header·query·JSON 또는 입력값 오류, `INVALID_REQUEST` |
| `401 Unauthorized` | Access Token 없음은 `WWW-Authenticate: Bearer`, 만료·잘못된 token은 `WWW-Authenticate: Bearer error="invalid_token"`, Waiting 세션 실패는 `WAITING_SESSION_INVALID` |
| `403 Forbidden` | scope 부족은 `WWW-Authenticate: Bearer error="insufficient_scope", scope="{requiredScope}"` |
| `404 Not Found` | 존재하지 않거나 호출 주체가 소유하지 않은 요청 |
| `409 Conflict` | payload 불일치 또는 허용되지 않은 상태 전이 |
| `429 Too Many Requests` | 호출 제한 초과, `Retry-After` 포함 |
| `503 Service Unavailable` | Redis 또는 필수 내부 처리 실패, `Retry-After` 포함 |

Controller가 반환하는 오류 body는 `code`, `message`, `traceId`, `retryable`을 공통 필드로 사용한다. 상태 전이 충돌은 `code`로 `INVALID_STATE`, `PAYLOAD_MISMATCH`, `ADMISSION_EXPIRED`를 구분한다. Security Filter에서 거부한 Bearer 인증·scope 오류는 상태 코드와 `WWW-Authenticate` Header로 판단하며 공통 JSON body를 요구하지 않는다. Browser 세션 실패는 `401 WAITING_SESSION_INVALID`이며 Bearer challenge를 포함하지 않는다.

## 11. 설정과 환경 변수

```yaml
waiting-room:
  security:
    oauth2:
      enabled: ${WAITING_ROOM_OAUTH2_ENABLED:false}
    clients:
      reservation-service-client:
        services:
          - reservation-service
  demo:
    seed-enabled: ${WAITING_ROOM_DEMO_SEED_ENABLED:false}
    jwt-enabled: ${WAITING_ROOM_DEMO_JWT_ENABLED:false}
    jwt-ttl: ${WAITING_ROOM_DEMO_JWT_TTL:PT12H}
    jwt-issuer: ${WAITING_ROOM_DEMO_JWT_ISSUER:jn-waiting-room-demo}
    jwt-audience: ${WAITING_ROOM_DEMO_JWT_AUDIENCE:waiting-room-api}
    jwt-client-id: ${WAITING_ROOM_DEMO_JWT_CLIENT_ID:demo-reservation-client}
    service-id: ${WAITING_ROOM_DEMO_SERVICE_ID:reservation-service}
    active-users: ${WAITING_ROOM_DEMO_ACTIVE_USERS:0}
    waiting-users: ${WAITING_ROOM_DEMO_WAITING_USERS:0}
    active-expiry-min: ${WAITING_ROOM_DEMO_ACTIVE_EXPIRY_MIN:PT30S}
    active-expiry-max: ${WAITING_ROOM_DEMO_ACTIVE_EXPIRY_MAX:PT2M30S}
  scheduler-interval: ${WAITING_ROOM_SCHEDULER_INTERVAL:PT1S}
  max-retry-after: ${WAITING_ROOM_MAX_RETRY_AFTER:PT30S}
  retry-after-safety-margin: ${WAITING_ROOM_RETRY_AFTER_SAFETY_MARGIN:PT1S}
  heartbeat-expiration-recovery-grace: ${WAITING_ROOM_HEARTBEAT_EXPIRATION_RECOVERY_GRACE:PT20M}
  quarantine-ttl: ${WAITING_ROOM_QUARANTINE_TTL:PT1H}
  maintenance-health-timeout: ${WAITING_ROOM_MAINTENANCE_HEALTH_TIMEOUT:PT30S}
  maintenance-lease-timeout: ${WAITING_ROOM_MAINTENANCE_LEASE_TIMEOUT:PT5S}
  redis-safety-check-interval: ${WAITING_ROOM_REDIS_SAFETY_CHECK_INTERVAL:PT5S}
  redis-memory-warning-ratio: ${WAITING_ROOM_REDIS_MEMORY_WARNING_RATIO:0.80}
  redis-memory-block-ratio: ${WAITING_ROOM_REDIS_MEMORY_BLOCK_RATIO:0.90}
  redis-maxmemory-policy-attested: ${WAITING_ROOM_REDIS_MAXMEMORY_POLICY_ATTESTED:false}
  redis-maxmemory-bytes: ${WAITING_ROOM_REDIS_MAXMEMORY_BYTES:0}
  max-admissions-per-run: ${WAITING_ROOM_MAX_ADMISSIONS_PER_RUN:100}
  max-active-expirations-per-run: ${WAITING_ROOM_MAX_ACTIVE_EXPIRATIONS_PER_RUN:100}
  max-waiting-expirations-per-run: ${WAITING_ROOM_MAX_WAITING_EXPIRATIONS_PER_RUN:100}
  max-candidates-scanned-per-run: ${WAITING_ROOM_MAX_CANDIDATES_SCANNED_PER_RUN:300}
  services:
    reservation-service:
      max-concurrent-users: ${WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS}
      heartbeat-timeout: ${WAITING_ROOM_RESERVATION_HEARTBEAT_TIMEOUT:PT20M}
      admission-timeout: ${WAITING_ROOM_RESERVATION_ADMISSION_TIMEOUT:PT2M}
      max-session-duration: ${WAITING_ROOM_RESERVATION_MAX_SESSION_DURATION:PT30M}
      eta-window: ${WAITING_ROOM_RESERVATION_ETA_WINDOW:PT5M}
      eta-min-samples: ${WAITING_ROOM_RESERVATION_ETA_MIN_SAMPLES:10}
      eta-min-observation: ${WAITING_ROOM_RESERVATION_ETA_MIN_OBSERVATION:PT1M}
      eta-initial-release-rate-per-second: ${WAITING_ROOM_RESERVATION_ETA_INITIAL_RELEASE_RATE_PER_SECOND:0}
      request-ttl: ${WAITING_ROOM_RESERVATION_REQUEST_TTL:PT24H}
      waiting-token-ttl: ${WAITING_ROOM_RESERVATION_TOKEN_TTL:PT5M}
      max-wait-duration: ${WAITING_ROOM_RESERVATION_MAX_WAIT_DURATION:PT23H}
      cleanup-margin: ${WAITING_ROOM_RESERVATION_CLEANUP_MARGIN:PT10M}
      redirects:
        service-entry: ${WAITING_ROOM_RESERVATION_ENTRY_URL}
```

OAuth2를 사용하는 배포는 사용자 설정 파일에 다음 값을 추가한다.

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com
          audiences:
            - waiting-room-api
```

`enabled=true`인 외부 인증 배포는 issuer와 audience가 필수이며 누락되면 기동하지 않는다. `enabled=false`이면 Backend JWT 검증을 적용하지 않고 `UPSTREAM_MANAGED` 모드로 기동한다. 이 모드에서 Backend API는 Access Token 없이 호출할 수 있으므로 운영자는 API Gateway 또는 private network로 직접 접근을 제한해야 한다. 비활성 모드는 기동 로그에 경고를 기록하며 `/actuator/info`의 `backendAuthentication`은 `OAUTH2_RESOURCE_SERVER` 또는 `UPSTREAM_MANAGED`를 반환한다.

기본 설정은 health probe를 활성화하고 readiness group의 `include: "*"`로 Redis와 `waitingRoomReadiness`를 포함한 모든 등록된 health contributor를 확인한다. `waitingRoomReadiness`는 `recoveryReady`와 `redisSafetyReady`가 모두 참일 때 `UP`, 그렇지 않으면 `OUT_OF_SERVICE`다. readiness 실패는 `/actuator/health/readiness`에서 `503`이며 liveness는 `/actuator/health/liveness`에서 별도로 확인한다. 두 boolean은 indicator의 상세 값이며 공개 health 응답에 상세 표시를 설정하지 않으면 `status`만 반환한다. 내부 오류나 Redis 주소는 indicator 상세에 포함하지 않는다.

OAuth2가 활성화된 경우 `waiting-room.security.clients`는 JWT의 `client_id`와 호출 가능한 `serviceId`를 연결하며 요청 body나 query의 `serviceId`만으로 소유권을 결정하지 않는다. `UPSTREAM_MANAGED` 모드에서는 등록된 `serviceId`인지에 대한 업무 검증만 수행한다.

### 11.1 사용자 설정 파일

이미지 내부 `application.yml`은 제품 기본값을 제공하고 사용자는 다음 위치에 자신의 설정을 read-only로 mount할 수 있다.

```text
/app/config/application.yml
```

```bash
docker run \
  -p 8080:8080 \
  -v "$(pwd)/application.yml:/app/config/application.yml:ro" \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SERVER_FORWARD_HEADERS_STRATEGY=framework \
  -e SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/app/config/ \
  jn-waiting-room:local
```

외부 YAML은 이미지 기본값을 덮어쓰고 환경변수와 실행 인자는 외부 YAML보다 높은 우선순위를 가진다. 기본 설정을 유지하기 위해 `SPRING_CONFIG_LOCATION` 대신 `SPRING_CONFIG_ADDITIONAL_LOCATION`을 사용한다. 설정 파일을 필수로 요구하는 배포는 `optional:`을 제거해 파일 누락 시 기동을 실패시킨다.

위 forwarded-header 설정은 외부 HTTPS를 종료하는 신뢰 reverse proxy 뒤에서 same-origin 검증이 공개 scheme·host·port를 사용하도록 한다. 외부 YAML의 `server.forward-headers-strategy: framework`로도 지정할 수 있다. 신뢰 프록시는 클라이언트가 보낸 `X-Forwarded-*`를 제거하고 실제 외부 연결 기준으로 덮어써야 하며, 애플리케이션 직접 접근은 차단한다. 이미지 기본값은 forwarded header를 신뢰하도록 변경하지 않는다.

외부 파일에는 `spring.data.redis.host`·`port`, 서비스의 `max-concurrent-users`와 `redirects.service-entry`를 지정한다. 컨테이너의 `localhost`는 컨테이너 자신이므로 Redis가 연결된 네트워크의 hostname을 사용한다. `WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS` 같은 짧은 환경변수 별칭은 기본 YAML의 placeholder를 통해 연결된다. 외부 YAML에서 값을 직접 덮어썼다면 같은 `${환경변수:기본값}` placeholder를 유지하거나 해당 Spring property에 대응하는 환경변수를 사용한다.

서비스 목록, `client_id`-`serviceId` 매핑, 수용량과 redirect target은 YAML에 두고 Redis 비밀번호 같은 비밀값은 환경변수 또는 배포 플랫폼의 Secret으로 주입한다.

### 11.2 단일 이미지와 실행 Jar

저장소 루트에서 `docker build -t jn-waiting-room:local .`로 빌드한다. Node 24.21.0/pnpm 12.4.1의 production Front와 Java 21 Backend를 빌드하고, runtime에는 JRE와 실행 Jar만 포함한다. 프로세스는 UID/GID `10001:10001`로 실행하며 Redis, TLS proxy와 Authorization Server는 외부에서 제공한다.

WSL에서 실행 Jar만 생성할 때는 다음 명령을 사용한다.

```bash
source ~/.sdkman/bin/sdkman-init.sh
cd back-end
bash gradlew :app:bootJar
```

`bootJar`는 nvm의 프로젝트 Node와 `pnpm build`를 실행해 `front-end/dist`를 `BOOT-INF/classes/wwwroot`에 포함한다. 결과는 `back-end/app/build/libs/jn-waiting-room.jar`이며 `java -jar`로 실행한다. `bootRun`, `test`, `frontendDev`는 production Front를 빌드하지 않는다. `-PfrontendPrebuilt=true`는 이미 생성한 `dist`를 사용하며 Docker의 Front stage가 이 경로를 사용한다.

`/waiting`은 production SPA 진입점이며 `/assets/*`는 정적 자산을 제공한다. `/api/unknown`, 존재하지 않는 자산과 `/demo/reservation`·`/demo/admitted`는 production 이미지에서 `404`를 반환한다. production Front에는 demo 페이지와 token helper가 포함되지 않는다.

### 11.3 개발·데모 실행

`waiting-room.demo`는 개발·데모 데이터와 인증 시뮬레이터 전용이다. 데모 데이터는 애플리케이션 시작 시 생성하지 않는다. 데모 화면에서 예매하기를 누르면 `POST /api/v1/demo/reset`이 초기 활성 테스트 사용자와 대기 테스트 사용자를 생성하고, 완료 후 새 대기 신청을 보낸다. reset endpoint는 `demo` Spring profile, `seed-enabled=true`, `waiting-room.security.oauth2.enabled=true`가 모두 지정된 경우에만 생성되며 Bearer JWT의 `waiting:create` scope를 요구한다. `demo` Spring profile과 `jwt-enabled=true`가 함께 지정된 경우에만 애플리케이션 시작 시 임시 RSA key pair를 생성하고, 같은 공개키로 검증 가능한 12시간 만료의 서명된 데모 JWT를 개발 전용 endpoint에서 발급한다. `jwt-enabled=true`만으로는 다른 profile에서 활성화되지 않으며 `prod`와 `demo` profile을 함께 지정하면 기동을 실패시킨다. 재시작하면 key pair가 교체되어 기존 데모 JWT가 무효화된다.

기본·demo profile은 Redis 정책과 메모리를 조회하지 않는다. Redis가 없어도 서버가 기동하여 복구를 재시도하며, 데모 reset 요청에서 데이터를 생성할 때는 연결 가능한 Redis가 필요하다. reset은 기존 서비스 maintenance lease를 고유 토큰과 `heartbeatExpirationRecoveryGrace` 기간으로 획득한다. Scheduler 또는 다른 reset이 lease를 보유하면 데이터를 변경하기 전에 실패한다. 초기화 중에는 lease를 삭제하지 않고 성공·실패 후 자신의 토큰과 일치하는 lease만 해제한다.

데모는 단일 인스턴스와 한 화면의 예매하기 흐름을 지원하며, reset 응답 후 해당 화면의 새 신청을 보낸다. 일반 등록 API와 독립적으로 실행되는 reset의 모든 교차 순서를 보장하지 않는다. 유지보수와 reset 간 경쟁은 maintenance lease로 차단하지만 일반 등록 API에 데모 전용 gate를 적용하지 않는다.

데모 인증 시뮬레이터는 단일 인스턴스 개발 실행만 지원한다. 발급 endpoint는 localhost 또는 외부에서 접근할 수 없는 개발망에만 노출하고 production ingress에는 연결하지 않는다. 데모 `client_id`는 데모 `serviceId`만 소유하며 운영 Redis와 운영 서비스 설정을 함께 사용하지 않는다.

데모 JWT는 `iss`, `aud`, `client_id`와 `waiting:create`, `waiting:token:issue`, `waiting:enter`, `waiting:complete`, `waiting:cancel` scope를 포함한다. 데모 Front가 Backend용 token을 직접 사용하는 것은 개발 편의를 위한 예외이며 token은 메모리에만 둔다. 개인키와 고정 JWT를 환경변수 또는 Frontend bundle에 넣지 않는다. 일반 Front는 `pnpm run build`, 데모 Front는 `pnpm run build:demo`로 분리하며 운영 CI는 일반 build와 `prod` Spring profile만 사용한다.

데모 JWT 인증과 클릭 시 reset을 실행하는 Backend는 `SPRING_PROFILES_ACTIVE=demo`, `WAITING_ROOM_DEMO_JWT_ENABLED=true`, `WAITING_ROOM_DEMO_SEED_ENABLED=true`, `WAITING_ROOM_OAUTH2_ENABLED=true`, `WAITING_ROOM_DEMO_ACTIVE_USERS`, `WAITING_ROOM_DEMO_WAITING_USERS`, `WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS`, `WAITING_ROOM_RESERVATION_ENTRY_URL`을 설정한다. 예를 들어 별도 개발 Redis가 localhost:6379에 실행 중이면 다음과 같이 시작한다.

```bash
cd back-end
SPRING_PROFILES_ACTIVE=demo \
WAITING_ROOM_DEMO_JWT_ENABLED=true \
WAITING_ROOM_DEMO_SEED_ENABLED=true \
WAITING_ROOM_DEMO_ACTIVE_USERS=100 \
WAITING_ROOM_DEMO_WAITING_USERS=200 \
WAITING_ROOM_OAUTH2_ENABLED=true \
WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=100 \
WAITING_ROOM_RESERVATION_ENTRY_URL=http://localhost:5173/demo/admitted \
SPRING_DOCKER_COMPOSE_ENABLED=false \
bash gradlew :app:bootRun
```

Front는 별도 터미널에서 `cd front-end && pnpm dev`로 시작하고 `http://localhost:5173/demo/reservation`에 접근한다. Vite는 `/api`를 Backend로 proxy한다. Browser가 보내는 `Origin`과 Backend에서 보는 Host가 일치해야 하므로 외부 proxy 사용 시 신뢰한 forwarded header 구성을 맞춘다. `Secure` cookie와 Fetch Metadata를 지원하는 localhost 또는 HTTPS 환경에서 실행한다. `pnpm build:demo`는 demo route를 포함하는 정적 빌드이며 Dockerfile은 항상 일반 `pnpm build`를 사용한다.

`POST /api/v1/demo/token`은 인증 없이 `accessToken`, `expiresAt`을 반환하고 `Cache-Control: no-store`를 설정한다. 데모 Front는 token promise를 메모리에 공유하며 Backend 응답이 `401`일 때만 같은 요청을 새 token으로 한 번 재시도한다. 늦은 동시 `401`은 이미 갱신된 cache를 지우지 않는다.

IntelliJ 데모 실행은 서비스 수용량 5,000명, reset으로 생성하는 최초 활성 테스트 사용자 5,000명과 최초 대기 테스트 사용자 10,000명으로 구성한다. 예매하기를 누를 때마다 대상 서비스의 런타임 큐와 고정 데모 요청을 초기화하고 새 데이터를 구성한다. 최초 활성 테스트 슬롯에만 reset 시점부터 30~150초 사이의 무작위 만료시간을 지정한다. 한 주기 최대 입장 수는 60명, 입장 확인 제한은 60초, 초기 반환률은 초당 55.6명으로 두어 새 신청자가 약 3분 동안 완만한 대기 인원 감소를 관찰하도록 한다. 실제 브라우저 요청과 이후 입장자는 서비스의 `admission-timeout`과 `max-session-duration`을 그대로 사용한다.

`application-demo.yml`은 위 인원·만료·수용량·입장·ETA 설정과 `http://localhost:5173/demo/admitted` 입장 URL을 demo profile의 기본값으로 제공한다. 기존 `WAITING_ROOM_*` 환경변수로 모든 값을 덮어쓸 수 있다. `scripts/dev.ps1`은 지정한 `FrontendHost`·`FrontendPort`로 입장 URL을 Backend에 전달한다.

상태조회 시 Redis는 현재 활성 슬롯 수와 대기 순번을 원자적으로 비교한다. 가용 슬롯 범위에서는 반환 표본 없이 유지보수 batch 지연을 계산하고, 그 예상 대기시간 구간에 따라 Polling 주기를 선택한다. 추가 슬롯 반환이 필요한 요청은 최근 반환률까지 반영한다.

`etaWindow`, `etaMinSamples`, `etaMinObservation`, `etaInitialReleaseRatePerSecond`는 서비스별 이용 패턴에 따라 조정한다. `etaWindow`와 `etaMinObservation`은 양수이고 `etaMinObservation <= etaWindow`, `etaMinSamples >= 1`이어야 한다. 초기 반환률은 0 이상의 유한한 값이어야 하며 운영 기본값 0은 fallback 비활성화를 뜻한다. 기본 실측 조건은 최근 5분 동안 최소 10개 반환 표본과 최소 1분 관측시간이다. IntelliJ 데모는 조기 실측 전환으로 ETA가 크게 흔들리지 않도록 최소 표본을 5,000개로 덮어쓴다.

애플리케이션은 시작 시 서비스별로 다음 조건을 검증하고 위반하면 기동을 실패한다.

```text
requestTtl >
  maxWaitDuration
  + admissionTimeout
  + maxSessionDuration
  + cleanupMargin
```

다음 조건도 함께 검증한다.

- `maxRetryAfter <` 모든 서비스의 `heartbeatTimeout`
- `0 < retryAfterSafetyMargin <` 모든 서비스의 `heartbeatTimeout`
- `heartbeatExpirationRecoveryGrace >=` 모든 서비스의 `heartbeatTimeout`
- `maintenanceHealthTimeout >= max(schedulerInterval * 3, maintenanceLeaseTimeout * 2)`
- `maintenanceLeaseTimeout > schedulerInterval`
- `schedulerInterval <` 모든 서비스의 `admissionTimeout`
- `maxCandidatesScannedPerRun >= maxAdmissionsPerRun`
- 모든 batch와 시간 설정은 0보다 큼
- 서비스별 `maxConcurrentUsers`는 1 이상
- `serviceId`는 영문 소문자, 숫자와 `-`만 허용하고 Redis hash tag 문자인 `{`, `}`를 허용하지 않음
- redirect target ID는 서비스 안에서 유일하고 URI는 HTTP/HTTPS absolute URI이며 userinfo와 fragment가 없음. `prod` profile에서는 HTTPS만 허용함

`cleanup-margin`은 전체 대기·입장·이용 종료 후 요청 정리를 위한 여유시간이며 기본 10분이다. 상태 전이는 생성 시의 요청 TTL을 유지하고 위 식은 등호를 허용하지 않는다.

모든 Duration 설정은 양수이며 millisecond로 변환 가능한 `long` 범위여야 한다. 복구 barrier의 TTL인 `heartbeatExpirationRecoveryGrace + cleanupMargin` 합도 이 범위를 넘으면 기동 시 설정 오류로 거부한다.

운영 profile은 시작 시, Redis 복구 bootstrap 직후 업무 gate를 열기 전, `redis-safety-check-interval`(기본 5초)마다 Redis `maxmemory-policy=noeviction`을 확인한다. standalone 직접 property와 cluster의 `{node}.property`를 해석하여 모든 노드의 정책을 확인한다. `CONFIG GET`이 `NOPERM`으로 거부된 경우에만 운영자가 사전 검증한 `redis-maxmemory-policy-attested=true`를 사용할 수 있다. 정책 불일치, 연결·인증 오류나 확인 실패는 Redis 안전성 readiness를 닫고 신규 등록을 거부하며 다음 주기에 다시 확인한다.

메모리는 `INFO memory`의 노드별 `used_memory / maxmemory`를 계산하고 가장 높은 사용률로 판정한다. 노드의 `maxmemory`가 없거나 0이면 `redis-maxmemory-bytes`의 양수 값을 해당 노드의 한도로 사용하며 클러스터 총합 한도가 아니다. 사용할 한도가 없거나 노드 정보 누락·불일치, INFO 조회·파싱 실패가 있으면 안전성 readiness를 닫는다. 기본 사용률 0.80 이상은 경고 로그를, 0.90 이상은 신규 등록의 고정된 `503 SERVICE_UNAVAILABLE`을 발생시킨다. 사용률이 차단 임계값 아래로 내려가면 다음 확인에서 등록을 재개한다. 메모리 차단 자체는 readiness를 내리지 않으며 복구 상태가 정상일 때 기존 요청의 Polling, token 교환·재발급, 입장·완료·취소는 계속 사용할 수 있다.

운영자는 요청·세션·token·격리 Key와 상태 전이에 필요한 메모리를 산정하고 Redis 한도 안에 여유 공간을 확보한다. `noeviction`은 Key 축출을 막지만 OOM이나 Lua rollback을 보장하지 않는다.

시간 설정의 소유자는 Waiting Room이다. 서비스 A는 `enter` 응답의 `sessionExpiresAt`을 저장하고 같은 시각에 이용을 종료한다. 동시 수용량은 `serviceId`별로 설정한다. 용량 감소 시 가용 슬롯은 0으로 제한하고 기존 활성 사용자는 강제 종료하지 않는다.

## 12. 실패와 재시도

| 상황 | 처리 |
|---|---|
| Backend API의 Access Token 만료 | Waiting API는 `401 invalid_token`을 반환한다. 서비스 A는 외부 Authorization Server에서 새 Access Token을 확보하고 한 번 재시도한다. 등록은 같은 Idempotency-Key와 payload를 유지하고, 입장·완료·취소는 같은 `reservationRequestId`를 사용해 상태 전이의 멱등성을 유지한다. Access Token 만료는 대기 요청이나 Waiting 세션을 제거하지 않는다. |
| 입장 재시도 중 `admissionTimeout` 만료 | 새 Access Token으로 재시도하더라도 `ADMITTED` 요청의 `admissionExpiresAt`이 지났으면 `EXPIRED`, `ADMISSION_EXPIRED`로 입장을 거부한다. 인증 실패를 이유로 입장 유효시간을 연장하지 않는다. |
| 등록 응답을 받지 못함 | 같은 Idempotency-Key와 payload로 상태를 확인하고 `waitingUrl`이 없으면 access-token API를 명시적으로 호출한다. |
| Waiting token 만료·소비됨 | 최대 대기시간이 유효하고 heartbeat가 유효하거나 복구 barrier가 활성인 `WAITING`, 또는 활성 슬롯 만료 전 `ADMITTED`이면 서비스 A Backend를 통해 새 token을 발급받는다. |
| 상태 Polling 실패 | `maxRetryAfter` 이하의 `Retry-After` 또는 마지막 `nextPollAfterMs`를 적용해 재시도한다. 전체 Waiting API 또는 Redis 장애를 감지하면 heartbeat 만료 정리를 중단한다. 애플리케이션 시작과 Redis 연결 복구 시 공유 유지보수 heartbeat가 없거나 만료됐으면 모든 유지보수 실행보다 먼저 복구 barrier를 기록하고, `heartbeatExpirationRecoveryGrace` 동안 정상 Browser의 heartbeat 갱신을 기다린 뒤 heartbeat 만료를 재개한다. 최대 대기시간 만료는 계속 적용한다. |
| `slot-released` 유실 | 다음 Scheduler 실행이 Redis 상태를 기준으로 빈 슬롯을 보충한다. |
| `/enter` 응답 timeout | 같은 요청으로 재시도한다. `200`이면 이용을 허용하고 `ADMISSION_EXPIRED`이면 만료 안내를 표시한다. |
| 완료 통보 실패 | 서비스 A가 영속 저장소에 보관하고 지수 backoff와 jitter로 재시도한다. 간격은 30초로 제한하며 요청 TTL 종료 후에는 경보와 수동 확인 대상으로 전환한다. |
| 서비스 A가 완료를 통보하지 않음 | `maxSessionDuration` 만료 시 슬롯을 반환한다. |
| 요청 상태 Key가 없음 | 대기·heartbeat·활성 슬롯의 고아 ZSET member를 제한된 batch로 제거한다. 활성 슬롯을 실제로 제거하면 `slot-released`를 발행한다. |
| 요청 상태 JSON 손상 | 원문을 짧은 TTL의 격리 Key에 보관하고 `EXPIRED`, `STATE_CORRUPTED`로 교체한다. 대기 member는 즉시 제거하고 활성 슬롯은 기존 score 만료 후 제거한다. 다른 정상 후보의 처리는 계속한다. |
| `WAITING` 상태와 ZSET member 불일치 | 남은 member를 제거하고 `EXPIRED`, `INTERNAL_INCONSISTENCY`로 종료한다. 같은 ID를 자동 재등록하지 않는다. |
| Redis 또는 Lua 실패 | 성공으로 가장하지 않고 `503`과 짧은 `Retry-After`를 반환한다. |
| Redis 메모리 사용률 차단 | 운영 사용률이 차단 임계값 이상이면 신규 등록만 `503`으로 거부한다. 복구가 정상인 기존 요청 처리는 유지하고 다음 안전성 확인에서 임계값 아래로 내려가면 등록을 재개한다. |
| Redis OOM | 요청에 `503`을 반환하고 복구 gate를 닫는다. `noeviction`은 Key 축출만 막으며 Lua rollback을 보장하지 않으므로 운영자는 메모리 여유와 Redis 상태를 확인한다. |
| Spring 인스턴스 중단 | lease TTL이 끝난 뒤 다른 인스턴스가 lease를 획득해 같은 원자 script를 실행한다. |

서비스 A는 재기동 후에도 미통보 완료를 재시도할 수 있도록 자신의 영속 저장소에 완료 통보 상태를 보관한다. 애플리케이션 메모리 대기열로 우회하지 않는다.

## 13. 운영 관측

최소한 다음 값을 `serviceId`별로 기록한다.

| 항목 | 목적 |
|---|---|
| 대기열과 heartbeat ZSET 크기 | 적체와 이탈 정리 확인 |
| 가장 오래된 대기·heartbeat 시간 | Scheduler 지연 확인 |
| 활성 슬롯 수와 최대 수용량 | 초과 수용 여부 확인 |
| 상태별 요청 수 | 상태 전이 추이 확인 |
| Redis 메모리 사용량과 OOM 오류 | `noeviction` 환경의 등록 실패 사전 감지 |
| `STATE_CORRUPTED` 발생 수와 격리 Key 수 | 데이터 손상 즉시 탐지와 수동 원인 분석 |
| 최근 슬롯 반환 표본 수·관측시간·반환률 | 예상 대기시간 계산 근거와 표본 부족 확인 |
| 예상 대기시간의 실제 입장시간 대비 오차 | 관측 구간과 최소 표본 수 조정 |
| Lua 실행시간, 조회 후보 수와 batch 처리량 | Redis 장시간 점유 방지 |
| Scheduler·Subscriber 성공과 실패 | 즉시 처리와 보정 동작 확인 |
| 대기 정리 batch 소진 횟수와 최장 cleanup backlog 시간 | 무효 후보 적체로 인한 입장 지연 확인 |
| API 오류율과 응답시간 | 사용자·연동 서비스 영향 확인 |

활성 슬롯 수가 설정 용량을 넘거나 Lua 실행시간이 운영 기준을 초과하거나 Scheduler 성공 기록이 연속해서 사라지면 경보한다. `STATE_CORRUPTED`는 최초 1건부터 즉시 경보한다. 대기 정리 batch가 연속 소진되거나 cleanup backlog의 최장 대기시간이 운영 기준을 넘으면 입장 지연 경보를 발생시키고 batch 또는 등록 rate limit을 조정한다.

## 14. 지원 범위와 동작 보장

### 14.1 지원 기능

- OAuth2 JWT Resource Server 또는 `UPSTREAM_MANAGED` Backend 인증 모드
- OAuth2 활성화 시 JWT `client_id`와 `serviceId` 소유권 및 API별 scope 검증
- Access Token 만료 시 `401`과 새 token을 사용한 멱등 재시도
- `reservationRequestId` 기반 멱등 등록과 payload 충돌 검사
- 일회용 `waitingToken`과 HttpOnly Waiting 세션 쿠키
- 대기·heartbeat·활성 슬롯 Sorted Set과 요청 상태 TTL
- 적응형 Polling, heartbeat와 예상 대기시간
- 5개 상태와 서비스별 동시 수용량
- 입장·완료·취소 HTTP API와 오류 계약
- Redis Pub/Sub 즉시 신호와 Scheduler 보정
- 서비스별 유지보수 lease와 제한된 후보·변경 batch
- Redis 연결 복구 barrier와 readiness gate
- 고아 membership 정리와 손상 상태 격리
- 운영 Redis `noeviction` 확인, 메모리 임계 경고와 신규 등록 차단
- 단일 Docker 이미지와 외부 YAML 설정 mount
- `demo` profile 전용 JWT 인증 시뮬레이터

### 14.2 지원하지 않는 기능

- Authorization Server와 Access Token 발급·갱신
- Opaque Token introspection
- Front와 Waiting API의 cross-origin 분리 배포
- 외부 메시지 브로커
- 장기 이력 데이터베이스
- 동적 서비스 관리 화면
- 자동 용량 증감
- Redis 장애로 유실된 데이터 복원

### 14.3 동작 보장

1. 동일 ID와 payload 재등록은 대기 member와 token을 변경하지 않고 현재 상태만 반환한다.
2. 동일 ID의 다른 payload는 `409`로 거부된다.
3. Waiting token은 한 번만 소비되고 이후 상태 조회는 세션 쿠키로 수행된다.
4. Polling이 중단된 `WAITING` 요청은 heartbeat timeout 후 만료된다.
5. 최대 대기시간을 넘은 요청과 고아 member가 정리된다.
6. Lua 한 번의 입장·만료 처리는 조회 후보와 활성 슬롯 정리·대기 정리·입장 batch 설정을 넘지 않는다.
7. 활성 슬롯 수가 서비스별 최대 수용량을 넘지 않는다.
8. Waiting Front가 `ADMITTED` 응답 후 등록된 서비스 A 주소로 이동한다.
9. 입장, 완료와 취소의 재시도가 상태와 슬롯을 중복 변경하지 않는다.
10. Pub/Sub 메시지가 유실돼도 Scheduler가 설정된 `schedulerInterval` 주기로 빈 슬롯을 보충한다.
11. `ADMITTED`와 미완료 `ENTERED` 슬롯이 만료되면 요청 상태도 원자적으로 `EXPIRED`가 된다.
12. 복구 barrier가 비활성일 때 heartbeat가 만료된 요청은 만료 batch가 밀려 있어도 입장되지 않는다.
13. 환경변수의 TTL, batch, 서비스 ID, 용량과 redirect 불변조건을 위반하면 애플리케이션이 기동되지 않는다.
14. Redis 연결 복구 후 공유 barrier가 끝나기 전에는 heartbeat timeout으로 요청을 만료시키지 않는다.
15. 여러 인스턴스가 같은 Scheduler tick 또는 Pub/Sub 신호를 받아도 서비스별 lease를 획득한 한 인스턴스만 유지보수 Lua를 실행한다.
16. 한 Browser에서 새 Waiting 요청을 시작하면 기존 화면을 종료하고 활성 세션을 새 요청 하나로 교체한다.
17. token 재발급 후 이전 Waiting 세션은 Polling과 heartbeat 갱신에 사용할 수 없다.
18. Redis 복구 시 barrier bootstrap이 끝나기 전에는 readiness가 열리지 않는다.
19. `WAITING` 상태와 ZSET member가 불일치하면 재시도 루프 대신 명시적인 종료 상태가 된다.
20. 운영 readiness는 Redis `noeviction` 정책과 사용 가능한 메모리 한도를 확인한다.
21. 메모리 차단 임계값 이상이면 신규 등록만 거부하고, 정상 복구 상태의 기존 요청 처리는 유지한다. 사용률이 내려가면 안전성 확인 후 등록을 재개한다.
22. `WAITING` 응답은 같은 Redis 시각의 순번·활성 슬롯·최근 반환 표본으로 ETA를 계산하며, 추가 슬롯 반환이 필요한데 최소 표본이 부족하면 `null`을 반환한다.
23. 예상 대기시간은 유지보수 batch 지연과 슬롯 반환 지연을 함께 반영하고, 계산된 구간에 따라 `nextPollAfterMs`를 결정한다.
24. OAuth2가 활성화되면 유효한 JWT Access Token과 필수 scope가 없는 Backend는 등록·token 재발급·입장·완료·취소 API를 호출할 수 없다.
25. OAuth2가 활성화되면 인증된 `client_id`는 설정으로 소유한 `serviceId` 요청만 생성하거나 변경할 수 있다.
26. Access Token이 대기 중 만료되어도 요청과 Waiting 세션은 유지되며, 새 token을 사용한 동일 입장·완료 요청은 멱등하게 처리된다.
27. 데모 JWT 발급 기능은 `demo` profile과 `jwt-enabled=true`가 모두 설정된 단일 인스턴스 개발 환경에서만 동작하며, 운영 profile과 일반 Front build에는 발급 기능과 데모 route가 포함되지 않는다.
28. `UPSTREAM_MANAGED` 모드에서도 Waiting token, Browser 세션, 상태 전이와 Redis 정합성 검증은 동일하게 적용된다.

## 15. 배포 확인 목록

- 외부 YAML이 `/app/config/application.yml`에 read-only로 mount됐는지 확인한다.
- `backendAuthentication`이 의도한 `OAUTH2_RESOURCE_SERVER` 또는 `UPSTREAM_MANAGED`인지 확인한다.
- OAuth2를 활성화하면 issuer, audience, JWKS와 `client_id`-`serviceId` 매핑을 확인한다.
- OAuth2를 비활성화하면 API Gateway 또는 private network가 Waiting Room 직접 접근을 차단하는지 확인한다.
- Redis 연결, `noeviction`, 메모리 여유와 readiness를 확인한다.
- 운영 build에 데모 route와 JWT 발급 endpoint가 없는지 확인한다.
- Access Token, Waiting token과 세션 쿠키가 로그에 기록되지 않는지 확인한다.
