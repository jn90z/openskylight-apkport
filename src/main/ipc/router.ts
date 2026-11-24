import { ipcMain, BrowserWindow } from 'electron'
import { dispatch, type ChannelTable, type Services } from './channels'

export { buildChannelTable, dispatch, type ChannelTable, type Services } from './channels'

export function broadcast(channel: string, payload: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) win.webContents.send(channel, payload)
}

export function registerIpcHandlers(services: Services, table: ChannelTable): void {
  for (const channel of table.keys()) {
    ipcMain.handle(channel, (_event, payload) => dispatch(services, table, channel, payload, { gate: 'pin', broadcast }))
  }
}
