import { MantineProvider } from '@mantine/core'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'

import App from './App'

afterEach(cleanup)

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
  it('데모 환경에서 예매 신청 후 대기 페이지로 이동한다', () => {
    renderApp('/demo/reservation', true)

    fireEvent.click(screen.getByRole('button', { name: '예매 신청' }))

    expect(
      screen.getByRole('heading', {
        name: '사용자가 많아 접속 대기중입니다.',
      }),
    ).toBeInTheDocument()
  })

  it('데모 환경에서 입장 완료 페이지를 표시한다', () => {
    renderApp('/demo/admitted', true)

    expect(
      screen.getByRole('heading', {
        name: '예매 페이지에 입장했습니다.',
      }),
    ).toBeInTheDocument()
  })

  it.each(['/demo/reservation', '/demo/admitted'])(
    '일반 운영 환경에서 %s 경로를 노출하지 않는다',
    (path) => {
      renderApp(path, false)

      expect(screen.getByText('페이지를 찾을 수 없습니다.')).toBeInTheDocument()
    },
  )

  it('일반 운영 환경에서도 대기 페이지를 표시한다', () => {
    renderApp('/waiting', false)

    expect(
      screen.getByRole('heading', {
        name: '사용자가 많아 접속 대기중입니다.',
      }),
    ).toBeInTheDocument()
  })
})
