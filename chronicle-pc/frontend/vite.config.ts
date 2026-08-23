import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Same-origin API during `npm run dev`
      '/entries': 'http://127.0.0.1:8765',
      '/kb': 'http://127.0.0.1:8765',
      '/notes': 'http://127.0.0.1:8765',
      '/brain': 'http://127.0.0.1:8765',
      '/curation': 'http://127.0.0.1:8765',
      '/recall': 'http://127.0.0.1:8765',
      '/search': 'http://127.0.0.1:8765',
      '/ask': 'http://127.0.0.1:8765',
      '/resume': 'http://127.0.0.1:8765',
      '/process': 'http://127.0.0.1:8765',
      '/health': 'http://127.0.0.1:8765',
      '/connect': 'http://127.0.0.1:8765',
      '/models': 'http://127.0.0.1:8765',
      '/enrich': 'http://127.0.0.1:8765',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
})
