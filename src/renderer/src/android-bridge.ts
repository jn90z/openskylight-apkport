/**
 * Android compatibility adapter for the existing OpenSkyLight IPC contract.
 *
 * This adapter intentionally fails closed. Returning fabricated success or
 * empty datasets would make calendar changes appear to persist when they do not.
 * Native handlers must implement the actual channels before an APK is usable.
 */
const allowedPrefixes = [
  'app:', 'settings:', 'people:', 'calendars:', 'events:', 'google:',
  'ics:', 'sync:', 'weather:', 'auth:', 'chores:', 'stars:', 'rewards:',
  'lists:', 'listItems:', 'meals:', 'screensaver:', 'kiosk:', 'rss:',
  'camera:', 'companion:', 'birdnet:'
]

export function installAndroidBridge(): void {
  if (typeof window === 'undefined' || window.osl) return
  window.osl = {
    async invoke(channel: string, _payload: unknown): Promise<unknown> {
      if (!allowedPrefixes.some((prefix) => channel.startsWith(prefix))) {
        return { ok: false, error: { code: 'CHANNEL_BLOCKED', message: 'Blocked channel: ' + channel } }
      }
      return {
        ok: false,
        error: {
          code: 'ANDROID_NOT_IMPLEMENTED',
          message: 'OpenSkyLight Android backend is not implemented for ' + channel
        }
      }
    },
    on(_channel: string, _callback: (data: unknown) => void): () => void {
      return () => {}
    }
  }
}
