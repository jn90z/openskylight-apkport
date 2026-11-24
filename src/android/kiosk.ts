import { DateTime } from 'luxon'
import { isInSleepWindow } from '@shared/sleepWindow'
import type { SettingsService } from '../main/services/settingsService'
import { host, request } from './host'

export function createAndroidKiosk(settings: SettingsService, broadcast: (channel: string, payload: unknown) => void) {
  let lastActivity = Date.now()
  let sleeping = false
  let screensaver = false
  let wakeUntil = 0
  let timer: ReturnType<typeof setTimeout> | undefined

  function setScreensaver(active: boolean): void {
    if (active === screensaver) return
    screensaver = active
    broadcast('push:kioskIdle', { state: active ? 'screensaver' : 'active' })
  }

  function apply(): void {
    if (timer !== undefined) clearTimeout(timer)
    const config = settings.getAll()
    const now = DateTime.local()
    const inWindow = config.sleep.enabled && isInSleepWindow(now.hour * 60 + now.minute, config.sleep.start, config.sleep.end)
    const sleep = inWindow && Date.now() >= wakeUntil
    if (sleeping !== sleep) {
      sleeping = sleep
      host('displaySleep', sleep)
      broadcast('push:sleepState', { sleeping })
    }
    const idleFor = Date.now() - lastActivity
    const idleTimeout = config.screensaver.idleMinutes * 60_000
    if (config.screensaver.folder && idleFor >= idleTimeout) setScreensaver(true)
    const untilMinute = 60_000 - (now.second * 1000 + now.millisecond)
    const untilIdle = config.screensaver.folder && idleFor < idleTimeout ? idleTimeout - idleFor : Infinity
    timer = setTimeout(apply, Math.min(untilMinute, untilIdle, wakeUntil > Date.now() ? wakeUntil - Date.now() : Infinity))
  }

  function reportActivity(): void {
    lastActivity = Date.now()
    if (sleeping) wakeUntil = lastActivity + 5 * 60_000
    if (screensaver) setScreensaver(false)
    if (sleeping) apply()
  }

  return {
    apply, reportActivity,
    getLaunchOnStartup: (stored: boolean) => stored,
    setLaunchOnStartup: (enabled: boolean) => { host('launchAtBoot', enabled) },
    previewScreensaver: () => { broadcast('push:kioskIdle', { state: 'screensaver' }) },
    listPhotos: () => {
      const folder = settings.getAll().screensaver.folder
      return folder ? host<string[]>('photos', folder) : []
    },
    async pickFolder() {
      const folder = await request<string | null>('pickFolder', null)
      const config = settings.getAll().screensaver
      if (folder === null) return { folder: config.folder }
      settings.set({ screensaver: { ...config, folder } })
      apply()
      return { folder }
    }
  }
}
