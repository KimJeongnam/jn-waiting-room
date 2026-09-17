# 개발·데모 페이지 라우팅 설계

## 목적

`jn-waiting-room` 프런트엔드에 예매 신청, 대기, 입장 완료의 세 페이지를 React Router 선언형 방식으로 연결한다. 예매 신청과 입장 완료 페이지는 개발 및 데모에서만 노출하고, 일반 운영 빌드에서는 해당 라우트를 등록하지 않는다.

## 페이지와 경로

| 경로 | 페이지 | 일반 운영 빌드 |
|---|---|---|
| `/demo/reservation` | 예매 신청 페이지 | 미등록 |
| `/waiting` | 대기 페이지 | 등록 |
| `/demo/admitted` | 입장 완료 페이지 | 미등록 |

등록되지 않은 경로에는 간단한 Not Found 화면을 표시한다.

## 라우팅 방식

React Router의 Declarative Mode를 사용한다. 애플리케이션 진입점에 `BrowserRouter`를 배치하고, `App`에서 `Routes`와 `Route`로 페이지를 연결한다.

별도의 Data Router, loader, action, SSR, 파일 기반 라우팅은 도입하지 않는다. 백엔드 API 라우팅은 Spring Controller의 책임으로 남기며 React Router는 브라우저 화면 전환만 담당한다.

## 개발·데모 페이지 노출 정책

데모 페이지 활성 여부는 다음 조건으로 계산한다.

```ts
const demoPagesEnabled =
  import.meta.env.DEV ||
  import.meta.env.VITE_ENABLE_DEMO_PAGES === "true";
```

- `pnpm dev`: `import.meta.env.DEV`가 `true`이므로 데모 페이지를 표시한다.
- `pnpm build`: 명시적 플래그가 없으므로 데모 라우트를 등록하지 않는다.
- `pnpm build:demo`: `vite build --mode demo`와 `.env.demo`의 `VITE_ENABLE_DEMO_PAGES=true`를 사용해 데모 라우트를 등록한다.

`VITE_ENABLE_DEMO_PAGES`는 화면 노출을 제어하는 공개 빌드 플래그일 뿐 인증이나 보안 경계로 사용하지 않는다. 일반 운영 빌드에서는 데모 URL을 직접 입력해도 Not Found 화면만 표시한다.

## 화면 동작

### 예매 신청

- `예매 신청` 버튼 하나를 중심으로 표시한다.
- 버튼을 누르면 `/waiting`으로 이동한다.
- 이번 범위에서는 API 호출이나 대기표 발급을 수행하지 않는다.

### 대기

- 기존 `WaitingRoomPage`를 재사용한다.
- 현재 정적 대기 인원, 진행률, 예상 시간 표현을 유지한다.

### 입장 완료

- 기존 `HomePage`를 재사용한다.
- 실제 입장 API나 대기 완료 자동 이동은 이번 범위에서 구현하지 않는다.
- 개발자와 데모 진행자는 `/demo/admitted`에 직접 접근해 화면을 확인한다.

## 오류 처리

- 등록되지 않은 모든 경로는 Not Found 화면으로 처리한다.
- 일반 운영 빌드의 데모 경로도 동일하게 Not Found로 처리한다.
- 라우터 오류 경계와 서버 오류 화면은 이번 범위에 포함하지 않는다.

## 검증

자동 테스트로 다음을 검증한다.

1. 데모 활성 상태에서 `/demo/reservation`에 예매 신청 버튼이 표시된다.
2. 예매 신청 버튼을 누르면 `/waiting`으로 이동한다.
3. 데모 활성 상태에서 `/demo/admitted`에 입장 완료 화면이 표시된다.
4. 데모 비활성 상태에서 두 데모 경로는 Not Found를 표시한다.
5. 데모 활성 여부와 관계없이 `/waiting`은 대기 화면을 표시한다.
6. 기존 프런트엔드 테스트, lint, production build가 통과한다.

## 제외 범위

- Spring 대기열 API 구현
- 예매 신청 시 API 호출
- heartbeat 및 polling
- 입장 완료 자동 전환
- 운영 번들에서 데모 컴포넌트 코드를 물리적으로 제거하는 별도 엔트리 구성
- 인증과 권한 제어
