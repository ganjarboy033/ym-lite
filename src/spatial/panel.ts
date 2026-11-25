import { PANEL_HTML } from './panel-html'
import './spatial.css'

let loaded = false

/**
 * Inject HTML dulu, baru import main.js agar addEventListener menemukan elemen.
 */
export async function renderSpatialPanel(host: HTMLElement) {
  host.innerHTML = PANEL_HTML
  const root = (host.querySelector('#spatial-root') as HTMLElement) || host

  // Dynamic import setelah HTML ada di document
  const mod = await import('./main.js')
  if (typeof mod.mountSpatial === 'function') {
    await mod.mountSpatial(root)
  }
  loaded = true
  return root
}

export function isSpatialMounted() {
  return loaded
}

export async function spatialSetNowPlaying(title: string, lyrics?: string) {
  try {
    const { SpatialBridge } = await import('./bridge.js')
    await SpatialBridge.setNowPlaying(title, lyrics ?? null)
  } catch {
    /* web preview */
  }
}
