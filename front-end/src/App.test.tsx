import { MantineProvider } from '@mantine/core'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import App from './App'

describe('App', () => {
  it('대기열 통과 후 Home 입장 화면을 표시한다', () => {
    render(
      <MantineProvider>
        <App />
      </MantineProvider>,
    )

    expect(
      screen.getByRole('heading', {
        name: '예매 페이지에 입장했습니다.',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText('대기열을 통과했습니다.')).toBeInTheDocument()
  })
})
