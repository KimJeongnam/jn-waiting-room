/**
 * @author jeongnam
 * @since 2026-09-22
 * @file ReservationPage.test.tsx
 * @description 데모 예매 신청의 API 등록과 대기 페이지 이동을 검증한다.
 */
import { MantineProvider } from '@mantine/core'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ReservationPage } from './ReservationPage'

describe('ReservationPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('예매 신청을 등록한 뒤 서버가 반환한 대기 페이지로 이동한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          reservationRequestId: 'reservation-1',
          status: 'WAITING',
          waitingUrl:
            '/waiting?serviceId=reservation-service&reservationRequestId=reservation-1',
        }),
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

    await waitFor(() => {
      expect(screen.getByText('대기 페이지')).toBeInTheDocument()
    })

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/waiting-requests')
    expect(options.method).toBe('POST')
    expect(options.headers['Content-Type']).toBe('application/json')
    expect(options.headers['Idempotency-Key']).not.toBe('')
    expect(options.body).toBe(
      JSON.stringify({
        serviceId: 'reservation-service',
        redirectTargetId: 'service-entry',
      }),
    )
  })
})
