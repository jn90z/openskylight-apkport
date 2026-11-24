import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'node:path'

/** Bundles the existing OpenSkyLight renderer for the Android WebView. */
export default defineConfig({
  root: resolve(__dirname, 'src/renderer'),
  base: './',
  build: {
    target: 'chrome74',
    outDir: resolve(__dirname, 'out/android'),
    emptyOutDir: true,
    rollupOptions: { input: resolve(__dirname, 'src/renderer/android.html') }
  },
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@shared': resolve(__dirname, 'src/shared'),
      '@renderer': resolve(__dirname, 'src/renderer/src')
    }
  }
})
