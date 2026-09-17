import { StrictMode } from 'react'
import { MantineProvider } from '@mantine/core'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import '@mantine/core/styles.css'
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider defaultColorScheme="light">
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </MantineProvider>
  </StrictMode>,
)
