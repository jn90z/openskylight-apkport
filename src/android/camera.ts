import { uuidv7 } from '@shared/uuid'
import type { SettingsService } from '../main/services/settingsService'
import { isValidRtspUrl } from '../main/services/cameraArgs'
import { invalid, notFound } from '../main/services/errors'
import { host } from './host'

type Camera = { id: string; name: string; encryptedUrl: string }

export function createAndroidCamera(settings: SettingsService) {
  const key = 'android.cameras.v1'
  const read = (): Camera[] => JSON.parse(settings.getRaw(key) ?? '[]')
  const save = (cameras: Camera[]): void => { settings.setRaw(key, JSON.stringify(cameras)) }

  return {
    list() {
      return read().map(({ id, name, encryptedUrl }) => {
        try {
          host('decrypt', encryptedUrl)
          return { id, name }
        } catch {
          return { id, name, needsUrl: true }
        }
      })
    },
    add(name: string, url: string) {
      if (!isValidRtspUrl(url)) throw invalid('Enter a valid RTSP camera URL')
      if (new URL(url).protocol !== 'rtsp:') throw invalid('Android camera playback requires rtsp://')
      const id = uuidv7()
      save([...read(), { id, name, encryptedUrl: host('encrypt', url) }])
      return { id, name }
    },
    remove(cameraId: string) {
      save(read().filter(({ id }) => id !== cameraId))
      host('cameraRemove', cameraId)
    },
    async start(cameraId: string) {
      const camera = read().find(({ id }) => id === cameraId)
      if (!camera) throw notFound('Camera')
      const sessionId = uuidv7()
      host('cameraStart', { cameraId, sessionId, url: host('decrypt', camera.encryptedUrl) })
      return { wsUrl: '', sessionId }
    },
    stop(sessionId: string) {
      host('cameraStop', sessionId)
    }
  }
}
