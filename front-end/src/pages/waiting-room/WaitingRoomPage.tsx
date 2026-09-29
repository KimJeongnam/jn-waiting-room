/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomPage.tsx
 * @description 서버 상태를 Polling하고 입장 허용 시 등록된 서비스 URL로 이동한다.
 */
import {
  Container,
  Loader,
  Paper,
  Progress,
  Stack,
  Text,
  Title,
} from '@mantine/core'
import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'

import { formatEstimatedWaitTime } from './formatEstimatedWaitTime'
import './WaitingRoomPage.css'

const DEFAULT_POLL_DELAY_MS = 15_000

type WaitingStatus = 'WAITING' | 'ADMITTED' | 'ENTERED' | 'EXPIRED' | 'CANCELLED'

interface WaitingStatusResponse {
  /** 대기 신청 식별자다. */
  reservationRequestId: string
  /** 현재 서버 권위 상태다. */
  status: WaitingStatus
  /** 1부터 시작하는 현재 대기 순번이다. */
  position: number | null
  /** 대상 서비스의 전체 대기 인원이다. */
  waitingCount: number
  /** 예상 대기시간이며 계산할 수 없으면 null이다. */
  estimatedWaitSeconds: number | null
  /** 다음 상태조회까지 기다릴 milliseconds다. */
  nextPollAfterMs: number | null
  /** 입장이 허용됐을 때 이동할 등록 URL이다. */
  redirectUrl?: string
  /** 요청이 만료된 원인이다. */
  expirationReason?: string
}

/** Retry-After 초 값을 다음 상태조회까지 기다릴 milliseconds로 변환한다. */
function retryAfterMs(response: Response) {
  const seconds = Number(response.headers.get('Retry-After'))
  return Number.isFinite(seconds) && seconds > 0
    ? Math.ceil(seconds * 1_000)
    : DEFAULT_POLL_DELAY_MS
}

/** 접속 순서를 기다리는 사용자에게 현재 대기 상태를 안내한다. */
export function WaitingRoomPage() {
  const [searchParams] = useSearchParams()
  const serviceId = searchParams.get('serviceId')
  const reservationRequestId = searchParams.get('reservationRequestId')
  const [waitingStatus, setWaitingStatus] =
    useState<WaitingStatusResponse | null>(null)
  const initialPosition = useRef<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const parameterError =
    serviceId && reservationRequestId
      ? null
      : '대기 요청 정보가 올바르지 않습니다.'

  useEffect(() => {
    if (!serviceId || !reservationRequestId) {
      return
    }
    const targetServiceId = serviceId
    const targetReservationRequestId = reservationRequestId

    let cancelled = false
    let timer: number | undefined
    let inFlight = false

    /** 기존 예약을 교체하고 지정된 시간 뒤 한 번만 상태를 조회한다. */
    function schedulePoll(delayMs: number) {
      if (timer !== undefined) {
        globalThis.clearTimeout(timer)
      }
      timer = globalThis.setTimeout(() => {
        timer = undefined
        void poll()
      }, delayMs)
    }

    /** 서버 상태를 조회하고 WAITING 상태에서만 다음 Polling을 예약한다. */
    async function poll() {
      if (cancelled || inFlight) {
        return
      }
      inFlight = true
      try {
        const query = new URLSearchParams({
          serviceId: targetServiceId,
          reservationRequestId: targetReservationRequestId,
        })
        const response = await fetch(`/api/v1/waiting-session?${query}`)
        if (!response.ok) {
          if (response.status === 429 || response.status === 503) {
            if (!cancelled) {
              setError(null)
              schedulePoll(retryAfterMs(response))
            }
            return
          }
          throw new Error('대기 상태를 조회하지 못했습니다.')
        }

        const result = (await response.json()) as WaitingStatusResponse
        if (cancelled) {
          return
        }
        if (
          result.status === 'WAITING' &&
          result.position != null &&
          initialPosition.current == null
        ) {
          initialPosition.current = result.position
        }
        setWaitingStatus(result)
        setError(null)

        if (result.status === 'ADMITTED') {
          if (result.redirectUrl) {
            globalThis.location.assign(result.redirectUrl)
          } else {
            setError('입장할 서비스 주소를 받지 못했습니다.')
          }
          return
        }
        if (result.status === 'WAITING') {
          schedulePoll(result.nextPollAfterMs ?? DEFAULT_POLL_DELAY_MS)
        }
      } catch (requestError) {
        if (cancelled) {
          return
        }
        setError(
          requestError instanceof Error
            ? requestError.message
            : '대기 상태를 조회하지 못했습니다.',
        )
        schedulePoll(DEFAULT_POLL_DELAY_MS)
      } finally {
        inFlight = false
      }
    }

    /** 백그라운드에서 복귀하면 예약된 timer 대신 즉시 최신 상태를 조회한다. */
    function pollWhenVisible() {
      if (document.visibilityState === 'visible' && !inFlight) {
        if (timer !== undefined) {
          globalThis.clearTimeout(timer)
          timer = undefined
        }
        void poll()
      }
    }

    document.addEventListener('visibilitychange', pollWhenVisible)
    // 개발 모드 StrictMode의 첫 effect가 정리된 뒤 실제 최초 조회를 한 번만 실행합니다.
    globalThis.queueMicrotask(() => void poll())
    return () => {
      cancelled = true
      document.removeEventListener('visibilitychange', pollWhenVisible)
      if (timer !== undefined) {
        globalThis.clearTimeout(timer)
      }
    }
  }, [reservationRequestId, serviceId])

  /** 현재 페이지에서 처음 확인한 순번 대비 감소한 비율을 표시한다. */
  const progress =
    initialPosition.current && waitingStatus?.position != null
      ? Math.max(
          0,
          Math.min(
            100,
            Math.round(
              ((initialPosition.current - waitingStatus.position) /
                initialPosition.current) *
                100,
            ),
          ),
        )
      : 0
  const terminal =
    waitingStatus?.status === 'EXPIRED' || waitingStatus?.status === 'CANCELLED'
  const title = terminal
    ? waitingStatus.status === 'EXPIRED'
      ? '대기 요청이 만료되었습니다.'
      : '대기 요청이 취소되었습니다.'
    : '사용자가 많아 접속 대기중입니다.'

  return (
    <main className="queue-page">
      <Container size="xs" w="100%">
        <Paper
          aria-labelledby="queue-title"
          className="queue-card"
          component="section"
          p={{ base: 'xl', sm: 40 }}
          radius="lg"
          shadow="md"
        >
          <Stack align="center" gap="xl">
            {!terminal && (
              <Loader aria-label="대기열 처리 중" size="lg" type="dots" />
            )}

            <Title id="queue-title" order={1} ta="center">
              {title}
            </Title>

            <Stack className="queue-stats" gap="xl" role="status">
              <Stack align="center" className="queue-stat" gap={0}>
                <Text c="dimmed" fw={500}>
                  대기 인원
                </Text>
                <Text className="queue-count queue-value" fw={700}>
                  {waitingStatus
                    ? `${waitingStatus.waitingCount.toLocaleString('ko-KR')} 명`
                    : '조회 중'}
                </Text>
              </Stack>

              <Progress
                aria-label="대기열 진행률"
                className="queue-progress"
                color="blue"
                radius="xl"
                size="md"
                value={progress}
              />

              <Stack align="center" className="queue-stat" gap={0}>
                <Text c="dimmed" fw={500}>
                  예상 대기 시간
                </Text>
                <Text className="queue-value" fw={700} size="xl">
                  {!waitingStatus
                    ? '조회 중'
                    : waitingStatus.estimatedWaitSeconds == null
                      ? '입장 순서를 기다리는 중'
                    : formatEstimatedWaitTime(
                        waitingStatus.estimatedWaitSeconds,
                      )}
                </Text>
              </Stack>
            </Stack>

            <Text c="dimmed" className="queue-warning" size="sm" ta="center">
              {terminal
                ? waitingStatus.expirationReason
                : '대기 상태를 유지하려면 이 페이지를 열어두세요.'}
            </Text>
            {(error ?? parameterError) && (
              <Text role="alert">{error ?? parameterError}</Text>
            )}
          </Stack>
        </Paper>
      </Container>
    </main>
  )
}
