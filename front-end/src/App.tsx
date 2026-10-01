import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router'

import { WaitingRoomPage } from './pages/waiting-room/WaitingRoomPage'

export interface AppProps {
  demoPagesEnabled?: boolean
}

const defaultDemoPagesEnabled =
  import.meta.env.DEV || import.meta.env.MODE === 'demo'

// 운영 빌드에서는 import 자체를 제거하여 데모 페이지와 토큰 코드를 배포하지 않는다.
const ReservationPage = defaultDemoPagesEnabled
  ? lazy(() => import('./pages/reservation/ReservationPage').then((module) => ({ default: module.ReservationPage })))
  : undefined
const HomePage = defaultDemoPagesEnabled
  ? lazy(() => import('./pages/home/HomePage').then((module) => ({ default: module.HomePage })))
  : undefined

/** 빌드 환경에 따라 실제 대기 페이지와 선택적인 데모 페이지를 연결한다. */
function App({ demoPagesEnabled = defaultDemoPagesEnabled }: AppProps) {
  return (
    <Suspense fallback={<p>페이지를 불러오고 있습니다.</p>}>
      <Routes>
        {demoPagesEnabled && ReservationPage && (
          <Route path="/demo/reservation" element={<ReservationPage />} />
        )}
        <Route path="/waiting" element={<WaitingRoomPage />} />
        {demoPagesEnabled && HomePage && <Route path="/demo/admitted" element={<HomePage />} />}
        <Route path="*" element={<p>페이지를 찾을 수 없습니다.</p>} />
      </Routes>
    </Suspense>
  )
}

export default App
