/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomPage.test.tsx
 * @description 대기 상태 표시, 서버 지정 Polling 주기와 입장 이동을 검증한다.
 */
import { MantineProvider } from '@mantine/core'
import { StrictMode } from 'react'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { formatEstimatedWaitTime } from './formatEstimatedWaitTime'
import { WaitingRoomPage } from './WaitingRoomPage'

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
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('StrictMode에서도 최초 상태조회 요청을 한 번만 전송한다', async () => {
    const fetchMock = vi.fn().mockImplementation(() => new Promise(() => {}))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <StrictMode>
        <MantineProvider>
          <MemoryRouter
            initialEntries={[
              '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
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
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise(() => {})))

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
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
    vi.stubGlobal(
      'fetch',
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
            '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
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
    vi.stubGlobal(
      'fetch',
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
            '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
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
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
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
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
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
      vi.stubGlobal('fetch', fetchMock)

      render(
        <MantineProvider>
          <MemoryRouter
            initialEntries={[
              '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
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
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
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

  it('입장이 허용되면 서버가 등록한 URL로 이동한다', async () => {
    const assign = vi.fn()
    vi.stubGlobal('location', { assign })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () =>
          Promise.resolve({
            reservationRequestId: 'reservation-1',
            status: 'ADMITTED',
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
            '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>
      </MantineProvider>,
    )

    await waitFor(() => {
      expect(assign).toHaveBeenCalledWith(
        'https://service.example/entry?reservationRequestId=reservation-1',
      )
    })
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
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter
          initialEntries={[
            '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
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
