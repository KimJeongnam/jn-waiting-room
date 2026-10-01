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
import { useLocation, useNavigate, useSearchParams } from 'react-router'

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
  const { hash, pathname, search } = useLocation()
  const navigate = useNavigate()
  const serviceId = searchParams.get('serviceId')
  const routeKey = pathname + search
  const [waitingStatus, setWaitingStatus] =
    useState<WaitingStatusResponse | null>(null)
  const initialPosition = useRef<number | null>(null)
  const readyRouteRef = useRef<string | null>(null)
  const [readyRoute, setReadyRoute] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [fatalError, setFatalError] = useState<string | null>(null)
  const parameterError =
    serviceId && (new URLSearchParams(hash.slice(1)).get('token') || readyRoute === routeKey)
      ? null
      : '대기 세션 주소가 올바르지 않습니다. 새 대기 주소로 다시 접속해 주세요.'

  useEffect(() => {
    const targetServiceId = serviceId

    let cancelled = false
    let timer: number | undefined
    let inFlight = false
    let sessionReady = false
    const controller = new AbortController()

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
        const response = await fetch('/api/v1/waiting-session', {
          credentials: 'same-origin',
          headers: { 'X-Waiting-Request': '1' },
          signal: controller.signal,
        })
        if (!response.ok) {
          if (response.status === 401) {
            if (!cancelled) {
              sessionReady = false
              readyRouteRef.current = null
              setReadyRoute(null)
              setFatalError('대기 세션이 유효하지 않습니다. 새 대기 주소로 다시 접속해 주세요.')
            }
            return
          }
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

        if (result.status === 'ADMITTED' || result.status === 'ENTERED') {
          if (result.redirectUrl) {
            globalThis.location.replace(result.redirectUrl)
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

    /** 일회용 URL token을 cookie 세션으로 교환한 뒤 fragment를 지운다. */
    async function exchangeSession() {
      const token = new URLSearchParams(hash.slice(1)).get('token')
      if (!targetServiceId || !token) {
        return
      }
      try {
        const response = await fetch('/api/v1/waiting-session', {
          method: 'POST',
          credentials: 'same-origin',
          headers: {
            'Content-Type': 'application/json',
            'X-Waiting-Request': '1',
          },
          body: JSON.stringify({ serviceId: targetServiceId, token }),
          signal: controller.signal,
        })
        if (cancelled) {
          return
        }
        if (!response.ok) {
          setFatalError('대기 세션을 시작하지 못했습니다. 새 대기 주소로 다시 접속해 주세요.')
          return
        }
        readyRouteRef.current = routeKey
        setReadyRoute(routeKey)
        globalThis.history.replaceState(globalThis.history.state, '', routeKey)
        navigate({ pathname, search, hash: '' }, { replace: true })
      } catch (requestError) {
        if (!cancelled && !(requestError instanceof Error && requestError.name === 'AbortError')) {
          setFatalError('대기 세션을 시작하지 못했습니다. 새 대기 주소로 다시 접속해 주세요.')
        }
      }
    }

    /** 백그라운드에서 복귀하면 예약된 timer 대신 즉시 최신 상태를 조회한다. */
    function pollWhenVisible() {
      if (sessionReady && document.visibilityState === 'visible' && !inFlight) {
        if (timer !== undefined) {
          globalThis.clearTimeout(timer)
          timer = undefined
        }
        void poll()
      }
    }

    document.addEventListener('visibilitychange', pollWhenVisible)
    // StrictMode의 첫 effect가 정리된 뒤 token 교환을 한 번만 시작합니다.
    globalThis.queueMicrotask(() => {
      if (!cancelled) {
        setWaitingStatus(null)
        initialPosition.current = null
        setError(null)
        setFatalError(null)
        if (new URLSearchParams(hash.slice(1)).get('token')) {
          readyRouteRef.current = null
          setReadyRoute(null)
          void exchangeSession()
        } else if (readyRouteRef.current === routeKey) {
          sessionReady = true
          void poll()
        }
      }
    })
    return () => {
      cancelled = true
      controller.abort()
      document.removeEventListener('visibilitychange', pollWhenVisible)
      if (timer !== undefined) {
        globalThis.clearTimeout(timer)
      }
    }
  }, [hash, navigate, pathname, routeKey, search, serviceId])

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
  const accessError = fatalError ?? parameterError
  const terminal = Boolean(accessError) ||
    waitingStatus?.status === 'EXPIRED' || waitingStatus?.status === 'CANCELLED'
  const title = accessError
    ? '대기 세션을 시작할 수 없습니다.'
    : terminal
    ? waitingStatus?.status === 'EXPIRED'
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

            {!accessError && <Stack className="queue-stats" gap="xl" role="status">
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
            </Stack>}

            {!accessError && <Text c="dimmed" className="queue-warning" size="sm" ta="center">
              {terminal
                ? waitingStatus?.expirationReason
                : '대기 상태를 유지하려면 이 페이지를 열어두세요.'}
            </Text>}
            {(accessError ?? error) && (
              <Text role="alert">{accessError ?? error}</Text>
            )}
          </Stack>
        </Paper>
      </Container>
    </main>
  )
}
