# Demo Pages Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** React Router 선언형 라우팅으로 예매 신청, 대기, 입장 완료 페이지를 연결하고 일반 production build에서 개발·데모 페이지 경로를 비활성화한다.

**Architecture:** `BrowserRouter`는 애플리케이션 진입점에서 브라우저 History를 제공하고, `App`은 `Routes`와 `Route`만 책임진다. 예매 신청과 입장 완료 라우트는 Vite의 개발 플래그 또는 명시적인 데모 빌드 플래그가 참일 때만 등록하며, 실제 대기 페이지는 모든 빌드에서 등록한다.

**Tech Stack:** React 19, TypeScript 6, Vite 8, React Router Declarative Mode, Mantine 9, Vitest 5, Testing Library

## Global Constraints

- 라우팅은 React Router Declarative Mode만 사용한다.
- `/demo/reservation`과 `/demo/admitted`는 일반 `pnpm build` 결과에서 라우트로 등록하지 않는다.
- `/waiting`은 개발·데모·일반 production build 모두에서 등록한다.
- 개발 서버에서는 데모 페이지를 자동 활성화하고, 데모 production build는 `VITE_ENABLE_DEMO_PAGES=true`로 명시적으로 활성화한다.
- API 호출, heartbeat, polling, 입장 완료 자동 전환, 인증은 구현하지 않는다.
- 기존 `HomePage`와 `WaitingRoomPage`의 화면 내용은 변경하지 않는다.
- 현재 checkout의 기존 미커밋 변경과 LF 개행을 보존한다.

---

## File Structure

- `front-end/src/pages/reservation/ReservationPage.tsx`: 예매 신청 버튼과 `/waiting` 이동만 담당한다.
- `front-end/src/pages/reservation/ReservationPage.css`: 예매 신청 버튼을 화면 중앙에 배치한다.
- `front-end/src/App.tsx`: 세 페이지의 선언형 라우트, 데모 라우트 조건, Not Found 처리를 담당한다.
- `front-end/src/App.test.tsx`: 라우트 노출 정책과 예매 신청 버튼 이동을 검증한다.
- `front-end/src/main.tsx`: 최상위 `BrowserRouter`를 제공한다.
- `front-end/package.json`: `react-router` 의존성과 `build:demo` 명령을 기록한다.
- `front-end/pnpm-lock.yaml`: 설치된 React Router 버전과 의존성 해시를 고정한다.
- `front-end/.env.demo`: 데모 production build에서만 데모 라우트를 활성화한다.

### Task 1: 예매 신청 페이지

**Files:**
- Create: `front-end/src/pages/reservation/ReservationPage.tsx`
- Create: `front-end/src/pages/reservation/ReservationPage.css`
- Test: `front-end/src/pages/reservation/ReservationPage.test.tsx`

**Interfaces:**
- Consumes: React Router의 `useNavigate(): NavigateFunction`
- Produces: `export function ReservationPage(): JSX.Element`
- Navigation: `예매 신청` 버튼 클릭 시 `/waiting`

- [ ] **Step 1: React Router 의존성을 설치한다**

Run:

```bash
cd /home/jeongnam/workspace/jn-waiting-room/front-end
source "$HOME/.nvm/nvm.sh"
nvm use
corepack pnpm add react-router
```

Expected: `package.json`의 dependencies와 `pnpm-lock.yaml`에 React Router가 추가된다.

- [ ] **Step 2: 실패하는 예매 신청 페이지 테스트를 작성한다**

Create `front-end/src/pages/reservation/ReservationPage.test.tsx`:

```tsx
import { MantineProvider } from '@mantine/core'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'

import { ReservationPage } from './ReservationPage'

describe('ReservationPage', () => {
  it('예매 신청 버튼을 누르면 대기 페이지로 이동한다', () => {
    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/demo/reservation']}>
          <Routes>
            <Route path="/demo/reservation" element={<ReservationPage />} />
            <Route path="/waiting" element={<p>대기 페이지</p>} />
          </Routes>
        </MemoryRouter>
      </MantineProvider>,
    )

    fireEvent.click(screen.getByRole('button', { name: '예매 신청' }))

    expect(screen.getByText('대기 페이지')).toBeInTheDocument()
  })
})
```

- [ ] **Step 3: 테스트가 구현 부재로 실패하는지 확인한다**

Run:

```bash
cd /home/jeongnam/workspace/jn-waiting-room/front-end
source "$HOME/.nvm/nvm.sh"
nvm use
corepack pnpm test -- src/pages/reservation/ReservationPage.test.tsx
```

Expected: `ReservationPage` 모듈을 찾을 수 없어 FAIL.

- [ ] **Step 4: 최소 예매 신청 페이지를 구현한다**

Create `front-end/src/pages/reservation/ReservationPage.tsx`:

```tsx
import { Button, Center } from '@mantine/core'
import { useNavigate } from 'react-router'

import './ReservationPage.css'

/** 개발 및 데모 환경에서 대기열 진입을 시작한다. */
export function ReservationPage() {
  const navigate = useNavigate()

  return (
    <main className="reservation-page">
      <Center h="100%">
        <Button onClick={() => navigate('/waiting')} size="lg">
          예매 신청
        </Button>
      </Center>
    </main>
  )
}
```

Create `front-end/src/pages/reservation/ReservationPage.css`:

```css
.reservation-page {
  min-height: 100vh;
}
```

- [ ] **Step 5: 예매 신청 페이지 테스트가 통과하는지 확인한다**

Run:

```bash
cd /home/jeongnam/workspace/jn-waiting-room/front-end
source "$HOME/.nvm/nvm.sh"
nvm use
corepack pnpm test -- src/pages/reservation/ReservationPage.test.tsx
```

Expected: 1 test PASS.

- [ ] **Step 6: Task 1 변경을 커밋한다**

```bash
git add front-end/package.json front-end/pnpm-lock.yaml \
  front-end/src/pages/reservation/ReservationPage.tsx \
  front-end/src/pages/reservation/ReservationPage.css \
  front-end/src/pages/reservation/ReservationPage.test.tsx
git commit -m "feat: add demo reservation page"
```

### Task 2: 선언형 라우팅과 데모 노출 정책

**Files:**
- Modify: `front-end/src/main.tsx`
- Modify: `front-end/src/App.tsx`
- Modify: `front-end/src/App.test.tsx`
- Modify: `front-end/package.json`
- Create: `front-end/.env.demo`

**Interfaces:**
- Consumes: Task 1의 `ReservationPage`, 기존 `WaitingRoomPage`, 기존 `HomePage`
- Produces: `export interface AppProps { demoPagesEnabled?: boolean }`
- Produces: `export default function App({ demoPagesEnabled }: AppProps): JSX.Element`
- Routes: `/demo/reservation`, `/waiting`, `/demo/admitted`, `*`

- [ ] **Step 1: 라우팅 정책을 표현하는 실패 테스트로 기존 App 테스트를 교체한다**

Replace `front-end/src/App.test.tsx`:

```tsx
import { MantineProvider } from '@mantine/core'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'

import App from './App'

function renderApp(path: string, demoPagesEnabled: boolean) {
  return render(
    <MantineProvider>
      <MemoryRouter initialEntries={[path]}>
        <App demoPagesEnabled={demoPagesEnabled} />
      </MemoryRouter>
    </MantineProvider>,
  )
}

describe('App routes', () => {
  it('데모 환경에서 예매 신청 후 대기 페이지로 이동한다', () => {
    renderApp('/demo/reservation', true)

    fireEvent.click(screen.getByRole('button', { name: '예매 신청' }))

    expect(
      screen.getByRole('heading', {
        name: '사용자가 많아 접속 대기중입니다.',
      }),
    ).toBeInTheDocument()
  })

  it('데모 환경에서 입장 완료 페이지를 표시한다', () => {
    renderApp('/demo/admitted', true)

    expect(
      screen.getByRole('heading', {
        name: '예매 페이지에 입장했습니다.',
      }),
    ).toBeInTheDocument()
  })

  it.each(['/demo/reservation', '/demo/admitted'])(
    '일반 운영 환경에서 %s 경로를 노출하지 않는다',
    (path) => {
      renderApp(path, false)

      expect(screen.getByText('페이지를 찾을 수 없습니다.')).toBeInTheDocument()
    },
  )

  it('일반 운영 환경에서도 대기 페이지를 표시한다', () => {
    renderApp('/waiting', false)

    expect(
      screen.getByRole('heading', {
        name: '사용자가 많아 접속 대기중입니다.',
      }),
    ).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: 테스트가 기존 단일 Home 화면 때문에 실패하는지 확인한다**

Run:

```bash
cd /home/jeongnam/workspace/jn-waiting-room/front-end
source "$HOME/.nvm/nvm.sh"
nvm use
corepack pnpm test -- src/App.test.tsx
```

Expected: `AppProps`와 라우트가 없어 FAIL.

- [ ] **Step 3: App에 선언형 라우트와 데모 조건을 구현한다**

Replace `front-end/src/App.tsx`:

```tsx
import { Route, Routes } from 'react-router'

import { HomePage } from './pages/home/HomePage'
import { ReservationPage } from './pages/reservation/ReservationPage'
import { WaitingRoomPage } from './pages/waiting-room/WaitingRoomPage'

export interface AppProps {
  demoPagesEnabled?: boolean
}

const defaultDemoPagesEnabled =
  import.meta.env.DEV ||
  import.meta.env.VITE_ENABLE_DEMO_PAGES === 'true'

/** 빌드 환경에 따라 실제 대기 페이지와 선택적인 데모 페이지를 연결한다. */
function App({
  demoPagesEnabled = defaultDemoPagesEnabled,
}: AppProps) {
  return (
    <Routes>
      {demoPagesEnabled && (
        <Route path="/demo/reservation" element={<ReservationPage />} />
      )}
      <Route path="/waiting" element={<WaitingRoomPage />} />
      {demoPagesEnabled && (
        <Route path="/demo/admitted" element={<HomePage />} />
      )}
      <Route path="*" element={<p>페이지를 찾을 수 없습니다.</p>} />
    </Routes>
  )
}

export default App
```

- [ ] **Step 4: 최상위 BrowserRouter를 추가한다**

Update `front-end/src/main.tsx` so the rendered tree is:

```tsx
import { StrictMode } from 'react'
import { MantineProvider } from '@mantine/core'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import '@mantine/core/styles.css'
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider defaultColorScheme="light">
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </MantineProvider>
  </StrictMode>,
)
```

- [ ] **Step 5: 데모 build mode를 추가한다**

Add to `front-end/package.json` scripts:

```json
"build:demo": "tsc -b && vite build --mode demo"
```

Create `front-end/.env.demo`:

```dotenv
VITE_ENABLE_DEMO_PAGES=true
```

- [ ] **Step 6: 라우팅 테스트가 통과하는지 확인한다**

Run:

```bash
cd /home/jeongnam/workspace/jn-waiting-room/front-end
source "$HOME/.nvm/nvm.sh"
nvm use
corepack pnpm test -- src/App.test.tsx src/pages/reservation/ReservationPage.test.tsx
```

Expected: 6 tests PASS.

- [ ] **Step 7: 일반 build와 데모 build를 모두 검증한다**

Run:

```bash
cd /home/jeongnam/workspace/jn-waiting-room/front-end
source "$HOME/.nvm/nvm.sh"
nvm use
corepack pnpm build
corepack pnpm build:demo
```

Expected: 두 명령 모두 TypeScript 및 Vite build 성공. 일반 build에서는 `defaultDemoPagesEnabled`가 거짓이고, demo mode에서는 `VITE_ENABLE_DEMO_PAGES`가 문자열 `true`로 치환된다.

- [ ] **Step 8: 전체 프런트엔드 회귀 검증을 실행한다**

Run:

```bash
cd /home/jeongnam/workspace/jn-waiting-room/front-end
source "$HOME/.nvm/nvm.sh"
nvm use
corepack pnpm test
corepack pnpm lint
corepack pnpm build
git -C /home/jeongnam/workspace/jn-waiting-room diff --check
```

Expected: 전체 테스트 PASS, Oxlint 경고·오류 0개, production build 성공, `git diff --check` 출력 없음.

- [ ] **Step 9: Task 2 변경을 커밋한다**

```bash
git add front-end/src/main.tsx front-end/src/App.tsx \
  front-end/src/App.test.tsx front-end/package.json front-end/.env.demo
git commit -m "feat: add environment-aware demo routes"
```

## Manual Verification

- [ ] `corepack pnpm dev`에서 `/demo/reservation`, `/waiting`, `/demo/admitted`를 각각 열어 화면을 확인한다.
- [ ] `/demo/reservation`의 버튼을 눌러 주소와 화면이 `/waiting`으로 바뀌는지 확인한다.
- [ ] 일반 production build preview에서 `/demo/reservation`과 `/demo/admitted`가 Not Found를 표시하는지 확인한다.
- [ ] demo build preview에서 두 데모 경로가 다시 표시되는지 확인한다.

Production preview와 demo preview를 동시에 비교해야 한다면 서로 다른 출력 디렉터리 또는 순차 빌드를 사용하며, 생성된 `dist`는 커밋하지 않는다.
