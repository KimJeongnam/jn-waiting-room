/**
 * @author jeongnam
 * @since 2026-09-22
 * @file ReservationPage.tsx
 * @description 개발 및 데모용 예매 신청을 등록하고 대기 페이지로 이동한다.
 */
import { Button, Center } from '@mantine/core'
import { useState } from 'react'
import { useNavigate } from 'react-router'

import './ReservationPage.css'

interface CreateWaitingResponse {
  /** 대기 신청 식별자다. */
  reservationRequestId: string
  /** 등록 직후의 요청 상태다. */
  status: string
  /** Browser가 이동할 Waiting Front URL이다. */
  waitingUrl?: string
}

/** 개발 및 데모 환경에서 대기열 진입을 시작한다. */
export function ReservationPage() {
  const navigate = useNavigate()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /** 한 번의 예매 신청을 등록하고 서버가 지정한 대기 URL로 이동한다. */
  async function submitReservation() {
    setSubmitting(true)
    setError(null)

    try {
      const response = await fetch('/api/v1/waiting-requests', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': globalThis.crypto.randomUUID(),
        },
        body: JSON.stringify({
          serviceId: 'reservation-service',
          redirectTargetId: 'service-entry',
        }),
      })
      if (!response.ok) {
        throw new Error('예매 신청을 등록하지 못했습니다.')
      }

      const result = (await response.json()) as CreateWaitingResponse
      if (!result.waitingUrl) {
        throw new Error('대기 페이지 주소를 받지 못했습니다.')
      }
      navigate(result.waitingUrl)
    } catch (requestError) {
      setError(
        requestError instanceof Error
          ? requestError.message
          : '예매 신청을 등록하지 못했습니다.',
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="reservation-page">
      <Center mih="100vh">
        <div>
          <Button loading={submitting} onClick={submitReservation} size="lg">
            예매 신청
          </Button>
          {error && <p role="alert">{error}</p>}
        </div>
      </Center>
    </main>
  )
}
