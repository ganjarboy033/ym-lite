import type { AppSettings } from './types'

const KEY = 'ymusic-lite-settings'

const defaults: AppSettings = {
  engineVersion: '0.2.0-lite',
  lastEngineCheck: 0,
  quality: 'high',
  downloadFolder: 'YMusicLite',
  autoUpdateEngine: false,
  customEngineUrl: '',
  useCustomEngine: false,
  autoCheckAppUpdate: true,
}

export function loadSettings(): AppSettings {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return { ...defaults }
    return { ...defaults, ...JSON.parse(raw) }
  } catch {
    return { ...defaults }
  }
}

export function saveSettings(partial: Partial<AppSettings>) {
  const current = loadSettings()
  const next = { ...current, ...partial }
  localStorage.setItem(KEY, JSON.stringify(next))
  return next
}

export function resetSettings() {
  localStorage.removeItem(KEY)
  return { ...defaults }
}
