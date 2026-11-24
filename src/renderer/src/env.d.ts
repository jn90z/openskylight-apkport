/// <reference types="vite/client" />

declare global {
  interface Window {
    osl: {
      invoke(channel: string, payload: unknown): Promise<unknown>
      androidCameraPosition?(options: {
        sessionId: string; left: number; top: number; width: number; height: number; visible: boolean
      }): Promise<void>
      on(channel: string, callback: (data: unknown) => void): () => void
    }
  }
}

export {}
