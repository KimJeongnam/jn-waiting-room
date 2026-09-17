import { Route, Routes } from 'react-router'

import { HomePage } from './pages/home/HomePage'
import { ReservationPage } from './pages/reservation/ReservationPage'
import { WaitingRoomPage } from './pages/waiting-room/WaitingRoomPage'

export interface AppProps {
  demoPagesEnabled?: boolean
}

const defaultDemoPagesEnabled =
  import.meta.env.DEV || import.meta.env.VITE_ENABLE_DEMO_PAGES === 'true'

/** 빌드 환경에 따라 실제 대기 페이지와 선택적인 데모 페이지를 연결한다. */
function App({ demoPagesEnabled = defaultDemoPagesEnabled }: AppProps) {
  return (
    <Routes>
      {demoPagesEnabled && (
        <Route path="/demo/reservation" element={<ReservationPage />} />
      )}
      <Route path="/waiting" element={<WaitingRoomPage />} />
      {demoPagesEnabled && <Route path="/demo/admitted" element={<HomePage />} />}
      <Route path="*" element={<p>페이지를 찾을 수 없습니다.</p>} />
    </Routes>
  )
}

export default App
