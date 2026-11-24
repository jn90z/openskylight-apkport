import { cpSync, mkdirSync, renameSync, rmSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { build } from 'esbuild'

function run(command, args) {
  const result = spawnSync(command, args, { stdio: 'inherit', shell: process.platform === 'win32' })
  if (result.status !== 0) throw result.error ?? new Error(`${command} failed (${result.status})`)
}

run('npx', ['--no-install', 'vite', 'build', '--config', 'android.vite.config.ts'])
renameSync('out/android/android.html', 'out/android/index.html')
run('npx', ['--no-install', 'vite', 'build', '--config', 'companion.vite.config.ts'])
run('npx', ['--no-install', 'cap', 'sync', 'android'])
rmSync('out/companion-package/companion', { recursive: true, force: true })
cpSync('out/companion', 'out/companion-package/companion', { recursive: true })
mkdirSync('out/android-backend', { recursive: true })
await build({
  entryPoints: ['src/android/backend.ts'],
  outfile: 'out/android-backend/backend.js',
  bundle: true,
  format: 'iife',
  platform: 'neutral',
  mainFields: ['browser', 'module', 'main'],
  // QuickJS 2024 misparses a class field named get. Lower class fields in this bundle.
  target: 'es2020',
  minify: true,
  alias: {
    '@shared': './src/shared',
    'better-sqlite3': './src/android/sqlite.ts',
    'node:crypto': './src/android/crypto.ts',
    '@googleapis/calendar': './src/android/google-calendar.ts',
    'fast-xml-parser': './src/android/xml.ts'
  }
})
