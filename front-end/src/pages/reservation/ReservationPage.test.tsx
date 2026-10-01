/**
 * @author jeongnam
 * @since 2026-09-22
 * @file ReservationPage.test.tsx
 * @description 데모 예매 신청의 API 등록과 대기 페이지 이동을 검증한다.
 */
import { MantineProvider } from '@mantine/core'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ReservationPage } from './ReservationPage'

describe('ReservationPage', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('등록 401 재시도에서도 처음 만든 Idempotency-Key와 본문을 유지한다', async () => {
    let registrations = 0
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/api/v1/demo/token') return Promise.resolve(Response.json({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' }))
      if (url === '/api/v1/demo/reset') return Promise.resolve(new Response(null, { status: 204 }))
      registrations++
      return Promise.resolve(registrations === 1 ? new Response(null, { status: 401 }) : Response.json({
        reservationRequestId: 'reservation-retry', status: 'WAITING', waitingUrl: '/waiting?serviceId=reservation-service#token=one-time-secret',
      }))
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<MantineProvider><MemoryRouter initialEntries={['/demo/reservation']}><Routes>
      <Route path="/demo/reservation" element={<ReservationPage />} />
      <Route path="/waiting" element={<p>대기 페이지</p>} />
    </Routes></MemoryRouter></MantineProvider>)
    fireEvent.click(screen.getByRole('button', { name: '예매 신청' }))
    expect(await screen.findByText('대기 페이지')).toBeInTheDocument()
    const requests = fetchMock.mock.calls.filter(([url]) => url === '/api/v1/waiting-requests')
    expect(requests).toHaveLength(2)
    const first = requests[0][1]
    const second = requests[1][1]
    expect(new Headers(first.headers).get('Idempotency-Key')).toBe(new Headers(second.headers).get('Idempotency-Key'))
    expect(first.body).toBe(second.body)
    expect(second.method).toBe('POST')
    expect(new Headers(second.headers).get('Authorization')).toBe('Bearer demo-token')
  })

  it('데모 데이터를 초기화한 뒤 예매 신청을 등록한다', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(
      url === '/api/v1/demo/token'
        ? Response.json({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' })
        : url === '/api/v1/demo/reset'
          ? new Response(null, { status: 204 })
          : Response.json({
              reservationRequestId: 'reservation-1',
              status: 'WAITING',
              waitingUrl: '/waiting?serviceId=reservation-service#token=one-time-secret',
            }),
    ))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/demo/reservation']}>
          <Routes>
            <Route path="/demo/reservation" element={<ReservationPage />} />
            <Route path="/waiting" element={<p>대기 페이지</p>} />
          </Routes>
        </MemoryRouter>
      </MantineProvider>,
    )

    fireEvent.click(screen.getByRole('button', { name: '예매 신청' }))
    expect(await screen.findByText('대기 페이지')).toBeInTheDocument()

    const resetIndex = fetchMock.mock.calls.findIndex(([url]) => url === '/api/v1/demo/reset')
    const registrationIndex = fetchMock.mock.calls.findIndex(([url]) => url === '/api/v1/waiting-requests')
    expect(resetIndex).toBeGreaterThan(-1)
    expect(registrationIndex).toBeGreaterThan(resetIndex)
    expect(fetchMock.mock.calls[resetIndex][1]).toMatchObject({ method: 'POST' })
    expect(new Headers(fetchMock.mock.calls[resetIndex][1].headers).get('Authorization')).toBe('Bearer demo-token')
  })

  it('데모 초기화에 실패하면 예매 신청을 등록하지 않는다', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(
      url === '/api/v1/demo/token'
        ? Response.json({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' })
        : new Response(null, { status: url === '/api/v1/demo/reset' ? 500 : 200 }),
    ))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/demo/reservation']}>
          <Routes>
            <Route path="/demo/reservation" element={<ReservationPage />} />
            <Route path="/waiting" element={<p>대기 페이지</p>} />
          </Routes>
        </MemoryRouter>
      </MantineProvider>,
    )

    fireEvent.click(screen.getByRole('button', { name: '예매 신청' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('데모 테스트 데이터를 초기화하지 못했습니다.')
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/v1/waiting-requests')).toBe(false)
  })

  it('데모 초기화 요청이 실패하면 예매 신청을 등록하지 않는다', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/api/v1/demo/token') {
        return Promise.resolve(Response.json({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' }))
      }
      if (url === '/api/v1/demo/reset') return Promise.reject(new Error('network error'))
      return Promise.resolve(Response.json({}))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/demo/reservation']}>
          <Routes>
            <Route path="/demo/reservation" element={<ReservationPage />} />
            <Route path="/waiting" element={<p>대기 페이지</p>} />
          </Routes>
        </MemoryRouter>
      </MantineProvider>,
    )

    fireEvent.click(screen.getByRole('button', { name: '예매 신청' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('데모 테스트 데이터를 초기화하지 못했습니다.')
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/v1/waiting-requests')).toBe(false)
  })

  it('예매 신청을 등록한 뒤 서버가 반환한 대기 페이지로 이동한다', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(url === '/api/v1/demo/token'
      ? Response.json({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' })
      : url === '/api/v1/demo/reset'
        ? new Response(null, { status: 204 })
        : {
      ok: true,
      json: () =>
        Promise.resolve({
          reservationRequestId: 'reservation-1',
          status: 'WAITING',
          waitingUrl:
            '/waiting?serviceId=reservation-service#token=one-time-secret',
        }),
    }))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <MantineProvider>
        <MemoryRouter initialEntries={['/demo/reservation']}>
          <Routes>
            <Route path="/demo/reservation" element={<ReservationPage />} />
            <Route path="/waiting" element={<p>대기 페이지</p>} />
          </Routes>
        </MemoryRouter>
      </MantineProvider>,
    )

    fireEvent.click(screen.getByRole('button', { name: '예매 신청' }))

    await waitFor(() => {
      expect(screen.getByText('대기 페이지')).toBeInTheDocument()
    })

    const requests = fetchMock.mock.calls.filter(([url]) => url === '/api/v1/waiting-requests')
    expect(requests).toHaveLength(1)
    const [url, options] = requests[0]
    expect(url).toBe('/api/v1/waiting-requests')
    expect(options.method).toBe('POST')
    expect(new Headers(options.headers).get('Content-Type')).toBe('application/json')
    expect(new Headers(options.headers).get('Idempotency-Key')).not.toBe('')
    expect(new Headers(options.headers).get('Authorization')).toBe('Bearer demo-token')
    expect(options.body).toBe(
      JSON.stringify({
        serviceId: 'reservation-service',
        redirectTargetId: 'service-entry',
      }),
    )
  })
})
