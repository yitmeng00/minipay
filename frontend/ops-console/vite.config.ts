import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/**
 * Each bank is a separate backend (bank-app with profile bank-a/b/c). The console talks to them
 * through same-origin proxy prefixes, so the bank switcher just changes the prefix and no CORS
 * configuration is needed:  /bank-a/api/... -> http://localhost:8081/api/...
 */
const banks = { 'bank-a': 8081, 'bank-b': 8082, 'bank-c': 8083 }

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: Object.fromEntries(
      Object.entries(banks).map(([prefix, port]) => [
        `/${prefix}`,
        {
          target: `http://localhost:${port}`,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(new RegExp(`^/${prefix}`), ''),
        },
      ]),
    ),
  },
})
