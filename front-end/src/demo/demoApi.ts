/**
 * 데모 JWT는 모듈 메모리에만 유지한다. 저장소, cookie, URL과 로그에 기록하지 않는다.
 * 동시 요청도 같은 발급 결과를 사용하며 실패한 발급 결과는 보관하지 않는다.
 */
let token: Promise<string> | undefined

/** 데모 전용 endpoint에서 토큰을 발급받고 메모리에서만 재사용한다. */
function accessToken(): Promise<string> {
  token ??= fetch('/api/v1/demo/token', { method: 'POST', cache: 'no-store' })
    .then(async (response) => {
      if (!response.ok) throw new Error('데모 인증 토큰을 발급받지 못했습니다.')
      const result = await response.json() as { accessToken?: unknown }
      if (typeof result.accessToken !== 'string' || !result.accessToken) {
        throw new Error('데모 인증 토큰을 받지 못했습니다.')
      }
      return result.accessToken
    })
    .catch(() => {
      token = undefined
      throw new Error('데모 인증 토큰을 발급받지 못했습니다. 네트워크 연결을 확인해 주세요.')
    })
  return token
}

/**
 * 데모 Backend 요청에 Bearer를 추가하고 401에만 토큰을 갱신해 한 번 재시도한다.
 * URL, method, 문자열 body와 Idempotency-Key는 재시도 동안 유지한다.
 */
export async function demoFetch(url: string, options: RequestInit): Promise<Response> {
  for (let attempt = 0; attempt < 2; attempt++) {
    const headers = new Headers(options.headers)
    const requestToken = accessToken()
    headers.set('Authorization', `Bearer ${await requestToken}`)
    const response = await fetch(url, { ...options, headers }).catch(() => {
      throw new Error('네트워크 연결을 확인한 뒤 다시 시도해 주세요.')
    })
    if (response.status !== 401) return response
    // 늦게 도착한 401이 다른 요청에서 갱신한 토큰을 지우지 않도록 한다.
    if (token === requestToken) token = undefined
    if (attempt === 1) return response
  }
  throw new Error('데모 인증에 실패했습니다.')
}
