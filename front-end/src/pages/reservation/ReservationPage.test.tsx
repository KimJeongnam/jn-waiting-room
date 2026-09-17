import { MantineProvider } from '@mantine/core'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'

import { ReservationPage } from './ReservationPage'

describe('ReservationPage', () => {
  it('예매 신청 버튼을 누르면 대기 페이지로 이동한다', () => {
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

    expect(screen.getByText('대기 페이지')).toBeInTheDocument()
  })
})
