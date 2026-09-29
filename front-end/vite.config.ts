import react from '@vitejs/plugin-react'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // 환경변수가 .env 파일보다 우선하도록 병합합니다.
  const env = {
    FRONTEND_HOST: undefined,
    FRONTEND_PORT: undefined,
    BACKEND_HOST: undefined,
    SERVER_ADDRESS: undefined,
    BACKEND_PORT: undefined,
    SERVER_PORT: undefined,
    ...loadEnv(mode, process.cwd(), ''), ...process.env }
  const frontendHost = env.FRONTEND_HOST ?? '127.0.0.1'
  const frontendPort = Number(env.FRONTEND_PORT ?? 5173)
  const backendHost = env.BACKEND_HOST ?? env.SERVER_ADDRESS ?? '127.0.0.1'
  const backendPort = Number(env.BACKEND_PORT ?? env.SERVER_PORT ?? 8080)

  return {
    plugins: [react()],
    server: {
      host: frontendHost,
      port: frontendPort,
      strictPort: true,
      proxy: {
        '/api': {
          target: `http://${backendHost}:${backendPort}`,
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
    },
  }
})
