import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'

interface NativeBridge {
  invoke(options: { channel: string; payload: unknown }): Promise<unknown>
  ready(): Promise<void>
  addListener(event: string, handler: (data: unknown) => void): Promise<PluginListenerHandle>
  cameraPosition(options: {
    sessionId: string; left: number; top: number; width: number; height: number; visible: boolean
  }): Promise<void>
}

const native = registerPlugin<NativeBridge>('OpenSkyLight')

export function installAndroidBridge(): void {
  if (window.osl) return
  let readyScheduled = false
  window.osl = {
    async invoke(channel, payload) {
      try {
        const result = await native.invoke({ channel, payload })
        if (channel === 'birdnet:getDetections') {
          const response = result as { ok: boolean; data?: { detections: { image: string }[] } }
          for (const bird of response.data?.detections ?? []) {
            if (bird.image.startsWith('osl-bird://image?')) bird.image = bird.image.replace('osl-bird://image?', '/_osl/bird-image?')
          }
        }
        return result
      } catch {
        return { ok: false, error: { code: 'BRIDGE_ERROR', message: 'Could not contact the Android backend' } }
      }
    },
    on(channel, callback) {
      let disposed = false
      const listener = native.addListener(channel, (data) => {
        if (!disposed) callback(data)
      })
      if (!readyScheduled) {
        readyScheduled = true
        // Subscribe before asking the backend to publish its current kiosk state.
        window.setTimeout(() => { void native.ready().catch(() => undefined) }, 0)
      }
      return () => {
        disposed = true
        void listener.then((subscription) => subscription.remove()).catch(() => undefined)
      }
    },
    androidCameraPosition: (options) => native.cameraPosition(options)
  }
}
