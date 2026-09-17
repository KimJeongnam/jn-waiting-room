import { MantineProvider } from '@mantine/core'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

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
  it('대기 인원과 예상 대기 시간을 안내한다', () => {
    render(
      <MantineProvider>
        <WaitingRoomPage />
      </MantineProvider>,
    )

    expect(
      screen.getByRole('heading', {
        name: '사용자가 많아 접속 대기중입니다.',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText('12,345 명')).toBeInTheDocument()

    const progressBar = screen.getByRole('progressbar', {
      name: '대기열 진행률',
    })
    expect(progressBar).toHaveAttribute('aria-valuenow', '65')

    expect(screen.getByText('01시간 02분 03초')).toBeInTheDocument()
    expect(
      screen.getByText(
        '※ 대기중 새로고침 하거나 다시 접속하시면 대기 순서가 초기화 되므로 유의 하세요.',
      ),
    ).toBeInTheDocument()
  })
})
