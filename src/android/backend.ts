import './web-platform'
import { DateTime } from 'luxon'
import { openDatabase } from '../main/db/client'
import { buildChannelTable, dispatch, type Services } from '../main/ipc/channels'
import { createCompanionTokens } from '../main/companion/companionTokens'
import { createSettingsService } from '../main/services/settingsService'
import { createPeopleService } from '../main/services/peopleService'
import { createCalendarService } from '../main/services/calendarService'
import { createEventService } from '../main/services/eventService'
import { createChoresService } from '../main/services/choresService'
import { createRewardsService } from '../main/services/rewardsService'
import { createListsService } from '../main/services/listsService'
import { createMealsService } from '../main/services/mealsService'
import { createAuthService } from '../main/services/authService'
import { createWeatherService } from '../main/services/weatherService'
import { createRssService } from '../main/services/rssService'
import { createBirdNetService } from '../main/services/birdnetService'
import { AppError } from '../main/services/errors'
import { createIcsSync } from '../main/sync/icsSync'
import { createGoogleSync } from '../main/sync/googleSync'
import { createOutboxWorker } from '../main/sync/outboxWorker'
import { createSyncManager } from '../main/sync/scheduler'
import { COMPANION_CHANNELS } from '@shared/ipc/companionChannels'
import type { IpcChannel } from '@shared/ipc/contract'
import { completeRequest, host } from './host'
import { fireTimer } from './web-platform'
import { createAndroidGoogleAuth } from './google-auth'
import { createAndroidCamera } from './camera'
import { createAndroidKiosk } from './kiosk'

const { db } = openDatabase('openskylight.db')
const settings = createSettingsService(db)
const auth = createAuthService(settings)
const tokens = createCompanionTokens(settings)
const deviceTz = () => DateTime.local().zoneName ?? 'UTC'
const googleAuth = createAndroidGoogleAuth(db)
const googleSync = createGoogleSync({ db, auth: googleAuth as unknown as Parameters<typeof createGoogleSync>[0]['auth'], deviceTz })
const broadcast = (channel: string, payload: unknown): void => { host('push', [channel, payload]) }
const outbox = createOutboxWorker({ db, sync: googleSync, onConflict: (title) => broadcast('push:syncConflict', { title }) })
const ics = createIcsSync({ db, deviceTz })
const syncManager = createSyncManager({ db, auth: googleAuth, google: googleSync, outbox, ics, broadcast })
const kiosk = createAndroidKiosk(settings, broadcast)
const chores = createChoresService(db, deviceTz)
const camera = createAndroidCamera(settings)
const version = host<string>('version')
const companion = {
  applySettings() {
    const { enabled, port } = settings.getAll().companion
    host('companionConfigure', { enabled, port })
  },
  getStatus(): ReturnType<Services['companion']['getStatus']> {
    const status = host<Omit<ReturnType<Services['companion']['getStatus']>, 'pairedCount'>>('companionStatus', settings.getAll().companion.port)
    return { ...status, pairedCount: tokens.count() }
  },
  issueToken() {
    const [url] = companion.getStatus().urls
    if (!url) throw new AppError('UNAVAILABLE', 'Enable the companion server and connect the tablet to Wi-Fi first')
    return { url: `${url}#t=${tokens.issue()}` }
  },
  unpairAll() { tokens.revokeAll() }
}
const services: Services = {
  settings, auth, googleAuth, googleSync, syncManager, kiosk, chores, camera, companion,
  icsSync: ics,
  people: createPeopleService(db),
  calendars: createCalendarService(db),
  events: createEventService(db),
  rewards: createRewardsService(db, chores),
  lists: createListsService(db),
  meals: createMealsService(db),
  weather: createWeatherService(settings),
  rss: createRssService(),
  birdnet: createBirdNetService(),
  updater: { quitAndInstall() { throw new AppError('UNAVAILABLE', 'Update OpenSkyLight by installing its signed APK') } }
}
const table = buildChannelTable(services, { version, name: 'android', plaintextSecrets: () => false })

async function invoke(id: number, channel: IpcChannel, payload: unknown, bearer: string | null): Promise<void> {
  let status = 200
  let result: unknown
  if (bearer !== null && !tokens.verify(bearer)) {
    status = 401
    result = { ok: false, error: { code: 'UNAUTHORIZED', message: 'Not paired — scan the QR code on the kiosk' } }
  } else if (bearer !== null && !COMPANION_CHANNELS.has(channel)) {
    status = 404
    result = { ok: false, error: { code: 'NOT_FOUND', message: 'Unknown channel' } }
  } else {
    result = await dispatch(services, table, channel, payload, { gate: bearer === null ? 'pin' : 'none', broadcast })
    if (channel === 'settings:set' && (result as { ok: boolean }).ok) kiosk.apply()
  }
  host('reply', [id, status, result])
}

Object.assign(globalThis, {
  oslInvoke: (...args: Parameters<typeof invoke>) => { void invoke(...args).catch(() => {
    host('reply', [args[0], 500, { ok: false, error: { code: 'INTERNAL', message: 'Something went wrong' } }])
  }) },
  oslComplete: completeRequest,
  oslTimer: fireTimer
})
companion.applySettings()
host('launchAtBoot', settings.getAll().launchOnStartup)
kiosk.apply()
syncManager.start()
