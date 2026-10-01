import { Button, Container, Paper, Stack, Text, Title } from '@mantine/core'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { demoFetch } from '../../demo/demoApi'

import './HomePage.css'

/** 데모 Bearer로 실제 입장을 확인하고 완료 요청을 전송한다. JWT는 화면에 노출하지 않는다. */
export function HomePage() {
  const [params] = useSearchParams()
  const reservationRequestId = params.get('reservationRequestId')
  return <Admission key={reservationRequestId} reservationRequestId={reservationRequestId} />
}

/** 요청 식별자가 바뀌면 화면 상태를 초기화하고 해당 요청의 입장과 완료만 처리한다. */
function Admission({ reservationRequestId }: { reservationRequestId: string | null }) {
  const [status, setStatus] = useState<'entering' | 'failed' | 'entered' | 'completing' | 'completed'>(reservationRequestId ? 'entering' : 'failed')
  const [error, setError] = useState<string | null>(reservationRequestId ? null : '예매 신청 식별자가 없습니다. 예매 신청부터 다시 진행해 주세요.')

  useEffect(() => {
    let cancelled = false
    if (!reservationRequestId) return
    /** 입장 API 성공 후에만 입장 완료 화면을 표시한다. 401 단일 재시도는 helper가 담당한다. */
    async function enter() {
      try {
        const response = await demoFetch(
          `/api/v1/waiting-requests/${encodeURIComponent(reservationRequestId!)}/enter?serviceId=reservation-service`,
          { method: 'POST' },
        )
        if (response.status === 409) {
          const problem = await response.json() as { code?: string }
          if (problem.code === 'ADMISSION_EXPIRED') {
            throw new Error('입장 가능 시간이 만료되었습니다. 예매 신청부터 다시 진행해 주세요.')
          }
        }
        if (!response.ok) throw new Error('입장을 확인하지 못했습니다. 다시 시도해 주세요.')
        if (!cancelled) setStatus('entered')
      } catch (requestError) {
        if (!cancelled) {
          setStatus('failed')
          setError(requestError instanceof Error ? requestError.message : '네트워크 연결을 확인해 주세요.')
        }
      }
    }
    void enter()
    return () => { cancelled = true }
  }, [reservationRequestId])

  /** 완료 성공 후 버튼을 비활성화해 같은 화면에서 중복 완료 요청을 방지한다. */
  async function complete() {
    if (status !== 'entered' || !reservationRequestId) return
    setStatus('completing')
    setError(null)
    try {
      const response = await demoFetch(
        `/api/v1/waiting-requests/${encodeURIComponent(reservationRequestId)}/complete?serviceId=reservation-service`,
        { method: 'POST' },
      )
      if (!response.ok) throw new Error('예매 완료를 처리하지 못했습니다. 다시 시도해 주세요.')
      setStatus('completed')
    } catch (requestError) {
      setStatus('entered')
      setError(requestError instanceof Error ? requestError.message : '네트워크 연결을 확인해 주세요.')
    }
  }

  return (
    <main className="home-page">
      <Container size="xs" w="100%">
        <Paper
          aria-labelledby="home-title"
          className="home-card"
          component="section"
          p={{ base: 'xl', sm: 40 }}
          radius="lg"
          shadow="md"
        >
          <Stack align="center" gap="md">
            <Text c="blue" fw={700}>
              {status === 'failed' ? '입장 실패' : status === 'entering' ? '입장 확인' : status === 'completed' ? '예매 완료' : '입장 완료'}
            </Text>

            <Title id="home-title" order={1} ta="center">
              {status === 'failed' ? '입장할 수 없습니다.' : status === 'entering' ? '입장을 확인하고 있습니다.' : status === 'completed' ? '예매가 완료되었습니다.' : '예매 페이지에 입장했습니다.'}
            </Title>

            <Text c="dimmed" ta="center">
              {status === 'failed' ? '아래 안내를 확인해 주세요.' : status === 'entering' ? '잠시 기다려 주세요.' : status === 'completed' ? '이용이 완료되었습니다.' : '대기열을 통과했습니다.'}
            </Text>
            {error && <Text role="alert" c="red">{error}</Text>}
            {(status === 'entered' || status === 'completing' || status === 'completed') && (
              <Button onClick={complete} loading={status === 'completing'} disabled={status === 'completed'}>
                예매 완료
              </Button>
            )}
          </Stack>
        </Paper>
      </Container>
    </main>
  )
}
