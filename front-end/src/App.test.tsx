/**
 * @author jeongnam
 * @since 2026-09-22
 * @file App.test.tsx
 * @description 환경별 라우팅과 데모 예매 신청 흐름을 검증한다.
 */
import { MantineProvider } from '@mantine/core'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import App from './App'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

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
  it('데모 환경에서 예매 신청 후 대기 페이지로 이동한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(Response.json({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' }))
        .mockResolvedValueOnce({ ok: true, status: 204 })
        .mockResolvedValueOnce({
          ok: true,
          json: () =>
            Promise.resolve({
              reservationRequestId: 'reservation-1',
              status: 'WAITING',
              waitingUrl:
                '/waiting?serviceId=reservation-service#token=one-time-secret',
            }),
        })
        .mockResolvedValueOnce({ ok: true, status: 204 })
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
        }),
    )
    renderApp('/demo/reservation', true)

    fireEvent.click(await screen.findByRole('button', { name: '예매 신청' }))

    expect(await screen.findByText('1 명')).toBeInTheDocument()
  })

  it('데모 환경에서 입장 API 성공 후 완료 페이지를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => Promise.resolve(url === '/api/v1/demo/token'
      ? Response.json({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' })
      : new Response(null, { status: 200 }))))
    renderApp('/demo/admitted?reservationRequestId=reservation-1', true)

    expect(
      await screen.findByRole('heading', {
        name: '예매 페이지에 입장했습니다.',
      }),
    ).toBeInTheDocument()
  })

  it.each(['/demo/reservation', '/demo/admitted'])(
    '일반 운영 환경에서 %s 경로를 노출하지 않는다',
    (path) => {
      const fetchMock = vi.fn()
      vi.stubGlobal('fetch', fetchMock)
      renderApp(path, false)

      expect(screen.getByText('페이지를 찾을 수 없습니다.')).toBeInTheDocument()
      expect(fetchMock).not.toHaveBeenCalled()
    },
  )

  it('일반 운영 환경에서도 대기 페이지를 표시한다', () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise(() => {})))
    renderApp('/waiting?serviceId=reservation-service#token=one-time-secret', false)

    expect(
      screen.getByRole('heading', {
        name: '사용자가 많아 접속 대기중입니다.',
      }),
    ).toBeInTheDocument()
  })
})
