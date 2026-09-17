import { Button, Center } from '@mantine/core'
import { useNavigate } from 'react-router'

import './ReservationPage.css'

/** 개발 및 데모 환경에서 대기열 진입을 시작한다. */
export function ReservationPage() {
  const navigate = useNavigate()

  return (
    <main className="reservation-page">
      <Center mih="100vh">
        <Button onClick={() => navigate('/waiting')} size="lg">
          예매 신청
        </Button>
      </Center>
    </main>
  )
}
