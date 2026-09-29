# 예상 대기시간 산정 설계

## 1. 목적

Waiting Room이 현재 대기 순번과 최근 활성 슬롯 반환 속도를 사용해 `ADMITTED`까지의 예상 대기시간을 계산한다. 서비스 A 내부의 업무 완료시간은 예측 범위에 포함하지 않는다.

## 2. 설계 결정

입장 승인률이나 설정된 평균 이용시간 대신 실제 슬롯 반환률을 사용한다. 최초 가용 용량을 채우는 입장 승인은 처리율 표본이 아니지만, 정상 완료·만료·취소로 슬롯이 반환된 사건은 다음 대기자가 입장할 수 있게 만든 직접적인 원인이기 때문이다.

반환 이벤트는 다음 경우에만 기록한다.

- 정상 완료로 `ENTERED` 슬롯이 제거됨
- `SESSION_TIMEOUT` 또는 `ADMISSION_TIMEOUT`으로 슬롯이 제거됨
- `ADMITTED` 요청 취소로 슬롯이 제거됨
- 상태가 없거나 손상된 고아 활성 슬롯이 제거됨

각 Lua script는 `ZREM active-slots` 결과가 `1`일 때만 반환 이벤트를 기록한다. 이미 반환된 슬롯에 대한 중복 API 호출은 표본을 추가하지 않는다.

## 3. Redis 구조

```text
Key    slot-release-events:{serviceId}
Type   Sorted Set
Score  slotReleasedAt의 Unix epoch milliseconds
Member reservationRequestId
TTL    etaWindow * 2
```

슬롯 반환과 이력 추가는 동일한 Lua 실행에서 다른 명령의 interleaving 없이 처리한다. `nowMs`는 Redis `TIME`을 millisecond로 변환한 값이고 최근 범위는 `[nowMs - etaWindowMs, nowMs]`로 양 끝을 포함한다. cutoff보다 작은 score만 제거하고 TTL을 갱신한다. Polling은 TTL을 갱신하지 않는다.

모든 Key는 같은 `{serviceId}` Redis Cluster hash slot을 사용하고 호출자가 `KEYS[]`로 전달한다. Lua 내부에서 Key 이름을 조합하지 않는다. 첫 쓰기 전에 Key type과 입력값을 검증한다. 반환 이력의 `ZADD`, 정리 또는 TTL 갱신 `redis.pcall`이 실패하면 같은 script가 이력 Key를 삭제해 이후 ETA를 `null`로 복귀시키고 metric 오류를 반환·경보한다. 이력 삭제에 성공한 경우만 일반 write gate 차단의 예외로 핵심 상태 전이를 유지하며, 삭제도 실패하면 일반 쓰기 오류로 처리한다.

## 4. 계산식

서비스별 기본 설정은 다음과 같다.

```yaml
eta-window: PT5M
eta-min-samples: 10
eta-min-observation: PT1M
eta-initial-release-rate-per-second: 0
```

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
rawReleaseDelaySeconds = requiredReleases / releaseRatePerSecond
estimatedWaitSeconds =
  ceil(max(rawBatchDelaySeconds, rawReleaseDelaySeconds))
```

`position`은 1부터 시작한다.

- `requiredReleases == 0`: 반환 표본 없이 `rawBatchDelaySeconds` 사용
- 추가 반환이 필요하고 최근 표본 수가 `etaMinSamples` 미만이며 초기 반환률이 양수: 초기 반환률로 반환 지연 계산
- 추가 반환이 필요하고 최근 표본 수가 `etaMinSamples` 미만이며 초기 반환률이 0: `estimatedWaitSeconds=null`
- 반환률이 0이거나 Redis 이력이 없음: `estimatedWaitSeconds=null`
- 그 외: batch 지연과 반환 지연 중 큰 값

`WAITING` 요청은 반드시 유지보수 처리를 거치므로 ETA `0`을 반환하지 않는다. 관측시간은 millisecond 차이를 실수 초로 바꾸고 batch 지연과 반환 지연 중 큰 값을 선택한 다음 최종 ETA에서만 올림한다. 최소 표본과 양수 관측시간이 있는데 반환률이 0인 경우는 정상 분기가 아니라 손상 방어 분기다.

## 5. Polling 연동

Polling Lua가 순번, 활성 슬롯 수, 반환 표본과 가장 오래된 표본을 같은 Redis 시각으로 읽는다. 같은 script에서 예상시간과 jitter가 적용된 `nextPollAfterMs`, 세션의 `nextPollAllowedAt`을 결정한다.

- 1분 미만: 기본 1초
- 1분 이상 60분 미만: 기본 10초
- 60분 이상 또는 계산 불가: 기본 15초
- 모든 기본 주기에는 기존 ±10% jitter 적용

## 6. 장애와 데이터 부족

Redis 재시작이나 이력 TTL 만료 후 표본이 부족하면 설정된 초기 반환률을 사용한다. 초기 반환률이 0이면 `null`을 반환하고 Waiting Front는 `입장 순서를 기다리는 중`을 표시한다. 이력 손실은 대기 순서와 상태 전이에 영향을 주지 않는다.

## 7. 데모 동작

초기 `demo-active-*` 슬롯 생성은 반환 이벤트를 만들지 않는다. IntelliJ 데모는 활성 슬롯 5,000명, 대기 사용자 10,000명, 30~150초 만료 분산, 초당 초기 반환률 55.6명을 사용한다. 최초 응답은 초기 반환률로 ETA를 표시하고, 데모 최소 표본 5,000개가 쌓이면 실측 반환률로 전환한다.

## 8. 검증 범위

- 슬롯이 실제 제거된 경우에만 반환 이벤트가 한 번 기록되는지 검증
- 완료·세션 만료·입장 만료·`ADMITTED` 취소의 기록 검증
- 중복 완료와 이미 제거된 슬롯이 표본을 늘리지 않는지 검증
- 가용 슬롯 범위에서도 batch 지연을 반환하고 `WAITING` ETA가 0이 아닌지 검증
- 최소 표본 미만의 `null` 검증
- 순번, 가용 슬롯, 관측시간과 표본 수에 따른 계산값 검증
- 가용 슬롯이 batch 크기보다 많을 때 뒤 순번이 0초로 계산되지 않는지 검증
- 최소 표본 수의 직전·경계·직후 검증
- 관측 구간 cutoff 포함과 1ms 이전 표본 제외 검증
- 완료·만료·취소 경합과 지연된 완료에서 표본이 한 번만 기록되는지 검증
- 반환 이력 TTL 갱신, Polling 시 TTL 미갱신과 자연 만료 검증
- 반환 이력 갱신 오류 시 이력 Key 삭제와 `null` fallback 검증
- 관련 Key의 Redis Cluster hash slot 일치 검증
- 예상시간 1분·60분 경계의 Polling 주기 검증
- API 응답과 Front 표시 검증

## 9. 제외 범위

- 서비스 A 업무 완료시간 예측
- 장기 분석용 이력 저장
- 통계적 신뢰구간 또는 머신러닝 예측
- Redis 장애로 손실된 ETA 이력 복구
