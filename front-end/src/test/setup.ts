import '@testing-library/jest-dom/vitest'
import { vi } from 'vitest'

// MantineProvider가 색상 모드를 확인할 때 사용하는 브라우저 API입니다.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
})
