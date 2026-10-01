/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomPage.test.tsx
 * @description 대기 상태 표시, 서버 지정 Polling 주기와 입장 이동을 검증한다.
 */
import { MantineProvider } from '@mantine/core'
import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { formatEstimatedWaitTime } from './formatEstimatedWaitTime'
import { WaitingRoomPage } from './WaitingRoomPage'

/** Browser 세션 교환은 성공시키고 상태조회 응답만 테스트에 맡긴다. */
function stubSessionFetch(pollFetch: () => Promise<unknown>) {
  vi.stubGlobal('fetch', vi.fn((_url: string, options?: RequestInit) =>
    options?.method === 'POST'
      ? Promise.resolve({ ok: true, status: 204 })
      : pollFetch(),
  ))
}

/** 같은 페이지에서 새 대기 URL로 이동하는 테스트 경로를 제공한다. */
function WaitingWithNavigation() {
  const navigate = useNavigate()
  return <>
    <button onClick={() => navigate('/waiting?serviceId=reservation-service#token=next')}>
      새 요청
    </button>
    <WaitingRoomPage />
  </>
}

/** Router가 보유한 URL fragment의 현재 값을 화면에 노출한다. */
function WaitingWithRouteHash() {
  const { hash } = useLocation()
  return <>
    <output data-testid="route-hash">{hash}</output>
    <WaitingRoomPage />
  </>
}

describe('formatEstimatedWaitTime', () => {
  it.each<[number, string]>([
    [59, '59초'],
    [60, '01분 00초'],
    [3599, '59분 59초'],
    [3600, '01시간 00분 00초'],
  ])('%i초를 %s 형식으로 표시한다', (seconds, expected) => {
    expect(formatEstimatedWaitTime(seconds)).toBe(expected)
  })
})

describe('WaitingRoomPage', () => {
  afterEach(() => {
    cleanup()
    window.history.replaceState({}, '', '/')
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('fragment token을 한 번 교환하고 제거한 뒤 query 없이 cookie로 조회한다', async () => {
    window.history.replaceState(
      {},
      '',
      '/waiting?serviceId=reservation-service#token=one-time-secret',
    )
    const fetchMock = vi.fn().mockImplementation((_url: string, options?: RequestInit) => {
      if (options?.method === 'POST') {
        return Promise.resolve({ ok: true, status: 204 })
      }
      return new Promise(() => {})
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <StrictMode>
        <MantineProvider>
          <MemoryRouter
            initialEntries={[
              '/waiting?serviceId=reservation-service#token=one-time-secret',
            ]}
          >
            <WaitingWithRouteHash />
          </MemoryRouter>
        </MantineProvider>
      </StrictMode>,
    )

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(fetchMock.mock.calls[0]).toEqual([
      '/api/v1/waiting-session',
      {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-Waiting-Request': '1',
        },
        body: JSON.stringify({
          serviceId: 'reservation-service',
          token: 'one-time-secret',
        }),
        signal: expect.any(AbortSignal),
      },
    ])
    expect(window.location.hash).toBe('')
    expect(screen.getByTestId('route-hash')).toBeEmptyDOMElement()
    expect(fetchMock.mock.calls[1]).toEqual([
      '/api/v1/waiting-session',
      {
        credentials: 'same-origin',
        headers: { 'X-Waiting-Request': '1' },
        signal: expect.any(AbortSignal),
      },
    ])
  })

  it('token 없이 처음 접근하면 요청하지 않고 오류를 표시한다', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/waiting?serviceId=reservation-service']}>
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('대기 세션')
    expect(screen.queryByLabelText('대기열 처리 중')).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('token 교환 실패 시 상태조회를 시도하지 않는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401 })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={['/waiting?serviceId=reservation-service#token=invalid']}
        >
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('대기 세션')
    expect(screen.queryByLabelText('대기열 처리 중')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('cookie 상태조회가 401이면 세션 오류를 표시하고 재시도하지 않는다', async () => {
    vi.useFakeTimers()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 204 })
      .mockResolvedValueOnce({ ok: false, status: 401 })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/waiting?serviceId=reservation-service#token=valid']}>
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    await act(async () => Promise.resolve())
    expect(screen.getByRole('alert')).toHaveTextContent('대기 세션')
    expect(screen.queryByLabelText('대기열 처리 중')).not.toBeInTheDocument()
    document.dispatchEvent(new Event('visibilitychange'))
    await act(async () => Promise.resolve())
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it.each([429, 503])('%i 상태조회는 Retry-After 뒤 다시 조회한다', async (status) => {
    vi.useFakeTimers()
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 204 })
      .mockResolvedValueOnce({
        ok: false, status, headers: new Headers({ 'Retry-After': '2' }),
      })
      .mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({
          reservationRequestId: 'reservation-1', status: 'WAITING', position: 1,
          waitingCount: 1, estimatedWaitSeconds: null, nextPollAfterMs: 15_000,
        }),
      })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/waiting?serviceId=reservation-service#token=valid']}>
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    await act(async () => Promise.resolve())
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await act(async () => { await vi.advanceTimersByTimeAsync(2_000) })
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(screen.getByText('1 명')).toBeInTheDocument()
  })

  it('cookie 상태조회에서 ADMITTED면 redirectUrl로 이동한다', async () => {
    const replace = vi.fn()
    vi.stubGlobal('location', { replace })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 204 })
      .mockResolvedValueOnce({
        ok: true,
        json: () => Promise.resolve({
          reservationRequestId: 'reservation-1', status: 'ADMITTED',
          position: null, waitingCount: 0, estimatedWaitSeconds: null,
          nextPollAfterMs: null, redirectUrl: 'https://service.example/entry',
        }),
      })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/waiting?serviceId=reservation-service#token=valid']}>
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    await waitFor(() => expect(replace).toHaveBeenCalledWith('https://service.example/entry'))
  })

  it('새 대기 URL로 이동하면 이전 진행률과 timer를 정리한다', async () => {
    vi.useFakeTimers()
    let pollCount = 0
    const fetchMock = vi.fn().mockImplementation((_url: string, options?: RequestInit) => {
      if (options?.method === 'POST') {
        return Promise.resolve({ ok: true, status: 204 })
      }
      pollCount += 1
      const position = pollCount === 1 ? 100 : pollCount === 2 ? 75 : 10
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve({
          reservationRequestId: pollCount < 3 ? 'old' : 'new',
          status: 'WAITING', position, waitingCount: position,
          estimatedWaitSeconds: 60, nextPollAfterMs: pollCount < 3 ? 1_000 : 15_000,
        }),
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/waiting?serviceId=reservation-service#token=first']}>
          <WaitingWithNavigation />
        </MemoryRouter>
      </MantineProvider>,
    )

    await act(async () => Promise.resolve())
    await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '25')

    fireEvent.click(screen.getByRole('button', { name: '새 요청' }))
    await act(async () => Promise.resolve())
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')
    expect(screen.getByText('10 명')).toBeInTheDocument()

    await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })
    expect(fetchMock).toHaveBeenCalledTimes(5)
  })

  it('A 교환 중 B로 이동하면 A 요청을 abort하고 늦은 A 응답으로 Polling하지 않는다', async () => {
    let resolveA!: (response: { ok: boolean; status: number }) => void
    const aResponse = new Promise<{ ok: boolean; status: number }>((resolve) => {
      resolveA = resolve
    })
    const fetchMock = vi.fn().mockImplementation((_url: string, options?: RequestInit) => {
      if (options?.method === 'POST') {
        const { token } = JSON.parse(String(options.body)) as { token: string }
        return token === 'first'
          ? aResponse
          : Promise.resolve({ ok: true, status: 204 })
      }
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve({
          reservationRequestId: 'new', status: 'WAITING', position: 2,
          waitingCount: 2, estimatedWaitSeconds: 60, nextPollAfterMs: 15_000,
        }),
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/waiting?serviceId=reservation-service#token=first']}>
          <WaitingWithNavigation />
        </MemoryRouter>
      </MantineProvider>,
    )

    await act(async () => Promise.resolve())
    const aSignal = (fetchMock.mock.calls[0][1] as RequestInit).signal
    fireEvent.click(screen.getByRole('button', { name: '새 요청' }))
    expect(aSignal).toBeInstanceOf(AbortSignal)
    expect(aSignal?.aborted).toBe(true)
    expect(await screen.findByText('2 명')).toBeInTheDocument()

    await act(async () => resolveA({ ok: true, status: 204 }))
    expect(fetchMock.mock.calls.filter(([, options]) => options?.method !== 'POST')).toHaveLength(1)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('StrictMode에서도 최초 상태조회 요청을 한 번만 전송한다', async () => {
    const fetchMock = vi.fn().mockImplementation(() => new Promise(() => {}))
    stubSessionFetch(fetchMock)

    render(
      <StrictMode>
        <MantineProvider>
          <MemoryRouter
            initialEntries={[
              '/waiting?serviceId=reservation-service#token=valid',
            ]}
          >
            <WaitingRoomPage />
          </MemoryRouter>
        </MantineProvider>
      </StrictMode>,
    )

    await act(async () => Promise.resolve())
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('첫 상태응답 전에는 대기 정보를 조회 중으로 표시한다', () => {
    stubSessionFetch(vi.fn().mockImplementation(() => new Promise(() => {})))

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service#token=valid',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    expect(screen.getAllByText('조회 중')).toHaveLength(2)
    expect(screen.queryByText('0 명')).not.toBeInTheDocument()
  })

  it('예상시간 산정 근거가 없으면 입장 순서를 기다리는 중이라고 안내한다', async () => {
    stubSessionFetch(
      vi.fn().mockResolvedValue({
        ok: true,
        json: () =>
          Promise.resolve({
            reservationRequestId: 'reservation-1',
            status: 'WAITING',
            position: 1,
            waitingCount: 1,
            estimatedWaitSeconds: null,
            nextPollAfterMs: 15_000,
          }),
      }),
    )

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service#token=valid',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    expect(
      await screen.findByText('입장 순서를 기다리는 중'),
    ).toBeInTheDocument()
    expect(screen.queryByText('계산 중')).not.toBeInTheDocument()
  })

  it('서버가 반환한 대기 인원과 예상 대기 시간을 안내한다', async () => {
    stubSessionFetch(
      vi.fn().mockResolvedValue({
        ok: true,
        json: () =>
          Promise.resolve({
            reservationRequestId: 'reservation-1',
            status: 'WAITING',
            position: 7,
            waitingCount: 42,
            estimatedWaitSeconds: 62,
            nextPollAfterMs: 10_000,
          }),
      }),
    )

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service#token=valid',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    expect(
      screen.getByRole('heading', {
        name: '사용자가 많아 접속 대기중입니다.',
      }),
    ).toBeInTheDocument()
    expect(await screen.findByText('42 명')).toBeInTheDocument()

    const progressBar = screen.getByRole('progressbar', {
      name: '대기열 진행률',
    })
    expect(progressBar).toHaveAttribute('aria-valuenow', '0')

    expect(screen.getByText('01분 02초')).toBeInTheDocument()
    expect(
      screen.getByText('대기 상태를 유지하려면 이 페이지를 열어두세요.'),
    ).toBeInTheDocument()
  })

  it('최초 순번 대비 현재 순번이 줄어든 비율을 진행률로 표시한다', async () => {
    vi.useFakeTimers()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: () =>
          Promise.resolve({
            reservationRequestId: 'reservation-1',
            status: 'WAITING',
            position: 100,
            waitingCount: 100,
            estimatedWaitSeconds: 120,
            nextPollAfterMs: 10_000,
          }),
      })
      .mockResolvedValue({
        ok: true,
        json: () =>
          Promise.resolve({
            reservationRequestId: 'reservation-1',
            status: 'WAITING',
            position: 75,
            waitingCount: 75,
            estimatedWaitSeconds: 90,
            nextPollAfterMs: 10_000,
          }),
      })
    stubSessionFetch(fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service#token=valid',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    await act(async () => Promise.resolve())
    const progressBar = screen.getByRole('progressbar', {
      name: '대기열 진행률',
    })
    expect(progressBar).toHaveAttribute('aria-valuenow', '0')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000)
    })

    expect(progressBar).toHaveAttribute('aria-valuenow', '25')
  })

  it('서버가 반환한 nextPollAfterMs 뒤에 상태를 다시 조회한다', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          reservationRequestId: 'reservation-1',
          status: 'WAITING',
          position: 1,
          waitingCount: 1,
          estimatedWaitSeconds: null,
          nextPollAfterMs: 1_000,
        }),
    })
    stubSessionFetch(fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service#token=valid',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    await act(async () => Promise.resolve())
    expect(fetchMock).toHaveBeenCalledOnce()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000)
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it.each([429, 503])(
    '%i 응답은 Retry-After가 지난 뒤 오류 표시 없이 다시 조회한다',
    async (status) => {
      vi.useFakeTimers()
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce({
          ok: false,
          status,
          headers: new Headers({ 'Retry-After': '2' }),
        })
        .mockResolvedValue({
          ok: true,
          json: () =>
            Promise.resolve({
              reservationRequestId: 'reservation-1',
              status: 'WAITING',
              position: 1,
              waitingCount: 1,
              estimatedWaitSeconds: null,
              nextPollAfterMs: 15_000,
            }),
        })
      stubSessionFetch(fetchMock)

      render(
        <MantineProvider>
          <MemoryRouter
            initialEntries={[
              '/waiting?serviceId=reservation-service#token=valid',
            ]}
          >
            <WaitingRoomPage />
          </MemoryRouter>
        </MantineProvider>,
      )

      await act(async () => Promise.resolve())
      expect(fetchMock).toHaveBeenCalledOnce()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1_999)
      })
      expect(fetchMock).toHaveBeenCalledOnce()

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1)
      })
      expect(fetchMock).toHaveBeenCalledTimes(2)
    },
  )

  it('화면이 다시 보이면 예약된 주기를 기다리지 않고 즉시 조회한다', async () => {
    vi.useFakeTimers()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          reservationRequestId: 'reservation-1',
          status: 'WAITING',
          position: 1,
          waitingCount: 1,
          estimatedWaitSeconds: null,
          nextPollAfterMs: 15_000,
        }),
    })
    stubSessionFetch(fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service#token=valid',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    await act(async () => Promise.resolve())
    expect(fetchMock).toHaveBeenCalledOnce()

    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
      await Promise.resolve()
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it.each(['ADMITTED', 'ENTERED'] as const)(
    '%s 상태면 서버가 등록한 URL로 이동한다',
    async (status) => {
      const replace = vi.fn()
      vi.stubGlobal('location', { replace })
      stubSessionFetch(
        vi.fn().mockResolvedValue({
          ok: true,
          json: () =>
            Promise.resolve({
              reservationRequestId: 'reservation-1',
              status,
              position: null,
              waitingCount: 0,
              estimatedWaitSeconds: null,
              nextPollAfterMs: null,
              redirectUrl:
                'https://service.example/entry?reservationRequestId=reservation-1',
            }),
          }),
      )

      render(
        <MantineProvider>
          <MemoryRouter
            initialEntries={[
              '/waiting?serviceId=reservation-service#token=valid',
            ]}
          >
            <WaitingRoomPage />
          </MemoryRouter>
        </MantineProvider>,
      )

      await waitFor(() => {
        expect(replace).toHaveBeenCalledWith(
          'https://service.example/entry?reservationRequestId=reservation-1',
        )
      })
    },
  )

  it('ENTERED 상태에 redirectUrl이 없으면 이동할 수 없음을 안내한다', async () => {
    const replace = vi.fn()
    vi.stubGlobal('location', { replace })
    stubSessionFetch(
      vi.fn().mockResolvedValue({
        ok: true,
        json: () =>
          Promise.resolve({
            reservationRequestId: 'reservation-1',
            status: 'ENTERED',
            position: null,
            waitingCount: 0,
            estimatedWaitSeconds: null,
            nextPollAfterMs: null,
          }),
      }),
    )

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service#token=valid',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '입장할 서비스 주소를 받지 못했습니다.',
    )
    expect(replace).not.toHaveBeenCalled()
  })

  it('만료된 요청은 Polling을 종료하고 만료 상태를 안내한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          reservationRequestId: 'reservation-1',
          status: 'EXPIRED',
          position: null,
          waitingCount: 0,
          estimatedWaitSeconds: null,
          nextPollAfterMs: null,
          expirationReason: 'HEARTBEAT_TIMEOUT',
        }),
    })
    stubSessionFetch(fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service#token=valid',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    expect(
      await screen.findByRole('heading', { name: '대기 요청이 만료되었습니다.' }),
    ).toBeInTheDocument()
    expect(screen.getByText('HEARTBEAT_TIMEOUT')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledOnce()
  })
})
