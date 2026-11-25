/**
 * Engine Manager – mirip konsep remote update YMusic
 *
 * - Ada engine bawaan (built-in)
 * - Bisa unduh engine baru dari URL (GitHub raw, CDN, server sendiri)
 * - Simpan di localStorage (web) / Filesystem (nanti Capacitor)
 * - Bisa revert ke bawaan kapan saja
 *
 * Catatan penting:
 * Engine YMusic asli (engine.js Closure) TIDAK akan jalan di sini
 * karena terikat native protocol mereka. Sistem ini untuk
 * engine Lite kita sendiri atau engine custom yang kamu buat/port.
 */

import { loadSettings, saveSettings } from './settings'

const ENGINE_STORAGE_KEY = 'ymusic-lite-custom-engine'
const ENGINE_META_KEY = 'ymusic-lite-custom-engine-meta'

export interface EngineMeta {
  version: string
  sourceUrl: string
  downloadedAt: number
  size: number
}

export interface EngineStatus {
  active: 'builtin' | 'custom'
  builtinVersion: string
  custom: EngineMeta | null
  lastError?: string
}

const BUILTIN_VERSION = '0.1.0-lite'

/** Ambil meta engine custom yang tersimpan */
export function getCustomEngineMeta(): EngineMeta | null {
  try {
    const raw = localStorage.getItem(ENGINE_META_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

/** Ambil source code engine custom */
export function getCustomEngineSource(): string | null {
  return localStorage.getItem(ENGINE_STORAGE_KEY)
}

export function getEngineStatus(): EngineStatus {
  const settings = loadSettings()
  const meta = getCustomEngineMeta()
  return {
    active: settings.useCustomEngine && meta ? 'custom' : 'builtin',
    builtinVersion: BUILTIN_VERSION,
    custom: meta,
  }
}

/**
 * Unduh engine dari URL dan simpan.
 * URL boleh:
 * - https://raw.githubusercontent.com/.../engine.js
 * - URL server sendiri
 * - file lokal (via Capacitor nanti)
 */
export async function downloadEngine(url: string): Promise<EngineMeta> {
  if (!url || !url.startsWith('http')) {
    throw new Error('URL tidak valid (harus http/https)')
  }

  const res = await fetch(url, { cache: 'no-store' })
  if (!res.ok) {
    throw new Error(`Gagal unduh: HTTP ${res.status}`)
  }

  const text = await res.text()
  if (text.length < 50) {
    throw new Error('File terlalu kecil, sepertinya bukan engine')
  }

  // Coba deteksi versi dari komentar atau export
  let version = 'custom'
  const verMatch = text.match(/ENGINE_VERSION\s*=\s*['"`]([^'"`]+)['"`]/) ||
                   text.match(/version\s*[:=]\s*['"`]([^'"`]+)['"`]/i)
  if (verMatch) version = verMatch[1]

  const meta: EngineMeta = {
    version,
    sourceUrl: url,
    downloadedAt: Date.now(),
    size: text.length,
  }

  localStorage.setItem(ENGINE_STORAGE_KEY, text)
  localStorage.setItem(ENGINE_META_KEY, JSON.stringify(meta))
  saveSettings({
    useCustomEngine: true,
    customEngineUrl: url,
    engineVersion: version,
    lastEngineCheck: Date.now(),
  })

  return meta
}

/** Kembalikan ke engine bawaan */
export function revertToBuiltin() {
  localStorage.removeItem(ENGINE_STORAGE_KEY)
  localStorage.removeItem(ENGINE_META_KEY)
  saveSettings({
    useCustomEngine: false,
    engineVersion: BUILTIN_VERSION,
  })
}

/** Hapus engine custom tanpa ubah setting aktif */
export function clearCustomEngine() {
  localStorage.removeItem(ENGINE_STORAGE_KEY)
  localStorage.removeItem(ENGINE_META_KEY)
}

/**
 * Coba load engine custom (eval / dynamic import style).
 * Untuk keamanan, di production sebaiknya pakai Web Worker atau CSP ketat.
 * Saat ini hanya menyimpan & menandai aktif.
 */
export function activateCustomEngine(): boolean {
  const src = getCustomEngineSource()
  if (!src) return false
  // Di masa depan: eval di Worker atau import() blob URL
  // Untuk sekarang cukup flag di settings
  saveSettings({ useCustomEngine: true })
  return true
}

/**
 * Contoh URL yang bisa dipakai (placeholder):
 * - Engine Lite yang kamu host sendiri
 * - Atau hasil build engine-js YMusic yang sudah kamu port
 */
export const EXAMPLE_ENGINE_URLS = [
  // Contoh format GitHub raw (ganti dengan repo/engine kamu sendiri)
  // 'https://raw.githubusercontent.com/username/repo/main/dist/engine.js',
]
