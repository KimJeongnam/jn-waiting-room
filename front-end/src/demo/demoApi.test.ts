import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

describe('demo API authentication', () => {
  beforeEach(() => vi.resetModules())
  afterEach(() => vi.unstubAllGlobals())

  it('401일 때만 새 토큰으로 같은 요청을 한 번 재시도하고 두 번째 401을 반환한다', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ accessToken: 'first-token', expiresAt: '2099-01-01T00:00:00Z' }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(Response.json({ accessToken: 'second-token', expiresAt: '2099-01-01T00:00:00Z' }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
    vi.stubGlobal('fetch', fetchMock)
    const { demoFetch } = await import('./demoApi')
    const request = { method: 'POST', headers: { 'Idempotency-Key': 'same-id' }, body: '{"serviceId":"reservation-service"}' }

    const response = await demoFetch('/api/v1/waiting-requests', request)

    expect(response.status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(4)
    for (const index of [1, 3]) {
      const [url, options] = fetchMock.mock.calls[index]
      expect(url).toBe('/api/v1/waiting-requests')
      expect(options.method).toBe('POST')
      expect(options.body).toBe(request.body)
      expect(new Headers(options.headers).get('Idempotency-Key')).toBe('same-id')
      expect(new Headers(options.headers).get('Authorization')).toBe(index === 1 ? 'Bearer first-token' : 'Bearer second-token')
    }
  })

  it('같은 모듈의 다음 요청은 메모리 토큰을 재사용한다', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ accessToken: 'memory-token', expiresAt: '2099-01-01T00:00:00Z' }))
      .mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    const { demoFetch } = await import('./demoApi')
    await demoFetch('/api/v1/waiting-requests/id/enter', { method: 'POST' })
    await demoFetch('/api/v1/waiting-requests/id/complete', { method: 'POST' })
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(new Headers(fetchMock.mock.calls[2][1].headers).get('Authorization')).toBe('Bearer memory-token')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(document.cookie).not.toContain('memory-token')
  })

  it('늦게 도착한 동시 401은 다른 요청이 갱신한 토큰을 폐기하지 않는다', async () => {
    let rejectSecondRequest!: (response: Response) => void
    let issuedTokens = 0
    const fetchMock = vi.fn(async (url: string, options: RequestInit) => {
      if (url === '/api/v1/demo/token') {
        return Response.json({ accessToken: `token-${++issuedTokens}` })
      }
      const authorization = new Headers(options.headers).get('Authorization')
      if (authorization === 'Bearer token-1') {
        if (url.endsWith('/first')) return new Response(null, { status: 401 })
        return new Promise<Response>((resolve) => { rejectSecondRequest = resolve })
      }
      return Response.json({ authorization })
    })
    vi.stubGlobal('fetch', fetchMock)
    const { demoFetch } = await import('./demoApi')

    const first = demoFetch('/api/first', { method: 'POST' })
    const second = demoFetch('/api/second', { method: 'POST' })
    expect(await (await first).json()).toEqual({ authorization: 'Bearer token-2' })
    rejectSecondRequest(new Response(null, { status: 401 }))

    expect(await (await second).json()).toEqual({ authorization: 'Bearer token-2' })
    expect(await (await demoFetch('/api/third', { method: 'POST' })).json())
      .toEqual({ authorization: 'Bearer token-2' })
    expect(issuedTokens).toBe(2)
  })

  it('토큰 발급 실패 시 Backend 요청을 보내지 않는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 503 }))
    vi.stubGlobal('fetch', fetchMock)
    const { demoFetch } = await import('./demoApi')
    await expect(demoFetch('/api/v1/waiting-requests', { method: 'POST' })).rejects.toThrow('데모 인증')
    expect(fetchMock).toHaveBeenCalledOnce()
  })
})
