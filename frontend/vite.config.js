import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The backend has no CORS configuration, so the browser talks to it through this same-origin proxy
// (both `npm run dev` and `npm run preview`). Targets come from .env.local / environment variables.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const api = env.VITE_PROXY_TARGET || 'http://localhost:8080'
  const booking = env.VITE_BOOKING_PROXY_TARGET || 'http://localhost:8081'
  const proxy = {
    '/api': { target: api, changeOrigin: true },
    '/actuator': { target: api, changeOrigin: true },
    '/booking-api': {
      target: booking,
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/booking-api/, ''),
    },
  }
  return {
    plugins: [react(), tailwindcss()],
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, strictPort: true, proxy },
    test: { environment: 'jsdom', include: ['src/**/*.test.{js,jsx}'] },
  }
})
