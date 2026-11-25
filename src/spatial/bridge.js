/**
 * Bridge ke native SpatialAudio plugin (Android).
 * Di web preview: no-op / local only.
 */

function getCapacitor() {
  return typeof window !== 'undefined' ? window.Capacitor : null
}

function isNative() {
  const Cap = getCapacitor()
  return Boolean(Cap?.isNativePlatform?.() && Cap.getPlatform?.() === 'android')
}

function getPlugin() {
  const Cap = getCapacitor()
  if (!Cap?.Plugins?.SpatialAudio) return null
  return Cap.Plugins.SpatialAudio
}

export const SpatialBridge = {
  isNative,
  async getState() {
    const p = getPlugin()
    if (!p) return null
    return p.getState()
  },
  async setSettings(settings) {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.setSettings({ settings })
  },
  async applyPreset(id) {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.applyPreset({ id })
  },
  async setFloatingBubble(enabled) {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.setFloatingBubble({ enabled })
  },
  async setFloatingLyrics(enabled) {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.setFloatingLyrics({ enabled })
  },
  async chooseImpulseResponse() {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.chooseImpulseResponse()
  },
  async setNowPlaying(title, lyrics = null) {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.setNowPlaying({ title, lyrics })
  },
  async requestPlaybackToggle() {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.requestPlaybackToggle()
  },
  async requestPlaybackPause() {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.requestPlaybackPause()
  },
  async setGlobalEq(enabled) {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.setGlobalEq({ enabled })
  },
  async startCapture() {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.requestUniversalCapture()
  },
  async stopCapture() {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.stopUniversalCapture()
  },
  async savePreset(name, settings) {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.savePreset({ name, settings })
  },
  async deletePreset(id) {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.deletePreset({ id })
  },
  async exportPreset(name, settings) {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.exportPreset({ name, settings })
  },
  async importPreset(json) {
    const p = getPlugin()
    if (!p) return { webPreview: true }
    return p.importPreset({ json })
  },
  async addFrameListener(callback) {
    const p = getPlugin()
    if (!p?.addListener) return null
    return p.addListener('frame', callback)
  },
}
