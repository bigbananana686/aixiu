import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { previewFeedback } from './preview-feedback.js'

const feedback = previewFeedback(fileURLToPath(new URL('./.preview-feedback', import.meta.url)))
const root = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, root, 'AIXIU_')
  const proxy = { '/api/v1': { target: env.AIXIU_API_TARGET || 'http://127.0.0.1:18080', changeOrigin: true } }
  const demoFeedback = {
    name: 'aixiu-local-feedback',
    configureServer(server) { server.middlewares.use(feedback) },
    configurePreviewServer(server) { server.middlewares.use(feedback) },
  }
  return {
    plugins: [vue(), mode === 'demo' && demoFeedback],
    server: {
      proxy,
      port: 5173,
      strictPort: true,
      fs: { deny: ['.env', '.env.*', '*.{crt,pem}', '**/.git/**', '**/.preview-feedback/**'] },
      watch: { ignored: ['**/.preview-feedback/**'] },
    },
    preview: { port: 5173, strictPort: true, proxy },
  }
})
