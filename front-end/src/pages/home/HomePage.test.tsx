import { MantineProvider } from '@mantine/core'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { HomePage } from './HomePage'

/** 데모 경로에만 실제 입장 화면을 구성한다. */
function renderHome(path = '/demo/admitted?reservationRequestId=reservation-1') {
  return render(<MantineProvider><MemoryRouter initialEntries={[path]}><HomePage /></MemoryRouter></MantineProvider>)
}

describe('HomePage demo entry', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('Bearer 입장 성공 후 완료 요청을 전송하고 완료 버튼을 비활성화한다', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(
      url === '/api/v1/demo/token'
        ? Response.json({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' })
        : Response.json({ status: url.includes('/enter?') ? 'ENTERED' : 'COMPLETED' }),
    ))
    vi.stubGlobal('fetch', fetchMock)
    renderHome()
    expect(await screen.findByRole('heading', { name: '예매 페이지에 입장했습니다.' })).toBeInTheDocument()
    const enter = fetchMock.mock.calls.find(([url]) => url.includes('/enter?'))!
    expect(enter[0]).toBe('/api/v1/waiting-requests/reservation-1/enter?serviceId=reservation-service')
    expect(enter[1].method).toBe('POST')
    expect(new Headers(enter[1].headers).get('Authorization')).toBe('Bearer demo-token')
    fireEvent.click(screen.getByRole('button', { name: '예매 완료' }))
    expect(await screen.findByRole('heading', { name: '예매가 완료되었습니다.' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '예매 완료' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '예매 완료' }))
    const completeCalls = fetchMock.mock.calls.filter(([url]) => url.includes('/complete?'))
    expect(completeCalls).toHaveLength(1)
    expect(completeCalls[0][0]).toBe('/api/v1/waiting-requests/reservation-1/complete?serviceId=reservation-service')
  })

  it('식별자가 없으면 요청하지 않고 오류를 표시한다', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    renderHome('/demo/admitted')
    expect(screen.getByRole('alert')).toHaveTextContent('식별자가 없습니다')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('입장 만료 409를 사용자에게 알리고 완료 버튼을 숨긴다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => Promise.resolve(url === '/api/v1/demo/token'
      ? Response.json({ accessToken: 'demo-token', expiresAt: '2099-01-01T00:00:00Z' })
      : Response.json({ code: 'ADMISSION_EXPIRED' }, { status: 409 }))))
    renderHome()
    expect(await screen.findByRole('alert')).toHaveTextContent('입장 가능 시간이 만료')
    expect(screen.queryByRole('button', { name: '예매 완료' })).not.toBeInTheDocument()
  })

  it('네트워크 실패를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    renderHome()
    expect(await screen.findByRole('alert')).toHaveTextContent('네트워크 연결')
  })

  it('입장 중 토큰 재발급 실패를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
    renderHome()
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('데모 인증 토큰'))
  })
})
