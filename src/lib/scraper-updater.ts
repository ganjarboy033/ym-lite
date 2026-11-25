/**
 * Update scraper engine dari https://github.com/coflyn/scrapr
 * Sumber resmi: npm @coflyn/scrapr + raw GitHub
 *
 * Alur (mirip Mori):
 * 1. Cek versi terbaru di npm registry
 * 2. Bandingkan dengan versi yang tersimpan di app
 * 3. User tekan "Update" → unduh metadata + bundle info
 * 4. Simpan lokal → bisa revert ke bawaan
 */

const NPM_LATEST = 'https://registry.npmjs.org/@coflyn/scrapr/latest'
const NPM_PACKAGE = 'https://registry.npmjs.org/@coflyn/scrapr'
const GITHUB_PACKAGE_JSON =
  'https://raw.githubusercontent.com/coflyn/scrapr/main/package.json'
const GITHUB_BUNDLE =
  'https://raw.githubusercontent.com/coflyn/scrapr/main/bundle.cjs'
const GITHUB_INDEX =
  'https://raw.githubusercontent.com/coflyn/scrapr/main/index.js'
const GITHUB_REPO = 'https://github.com/coflyn/scrapr'
const GITHUB_RELEASES_PAGE = 'https://github.com/coflyn/scrapr/releases'

const STORAGE_META = 'ymusic-lite-scrapr-meta'
const STORAGE_BUNDLE = 'ymusic-lite-scrapr-bundle'
const BUILTIN_VERSION = '0.2.0-lite'

export interface ScraprMeta {
  version: string
  updatedAt: number
  source: 'npm' | 'github' | 'builtin' | 'custom'
  description?: string
  tarball?: string
  integrity?: string
  size?: number
  homepage?: string
}

export interface ScraprUpdateInfo {
  current: string
  latest: string
  isNewer: boolean
  description?: string
  tarball?: string
  homepage?: string
  publishedVia: 'npm' | 'github'
}

function isNewerVersion(latest: string, current: string): boolean {
  if (current === BUILTIN_VERSION || current.includes('lite')) return true
  const parse = (v: string) =>
    v
      .replace(/^v/i, '')
      .split(/[.-]/)
      .map((n) => parseInt(n, 10) || 0)
  const l = parse(latest)
  const c = parse(current)
  for (let i = 0; i < Math.max(l.length, c.length); i++) {
    const a = l[i] || 0
    const b = c[i] || 0
    if (a > b) return true
    if (a < b) return false
  }
  return false
}

export function getStoredScraprMeta(): ScraprMeta | null {
  try {
    const raw = localStorage.getItem(STORAGE_META)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function getCurrentScraprVersion(): string {
  return getStoredScraprMeta()?.version || BUILTIN_VERSION
}

export function getStoredBundle(): string | null {
  return localStorage.getItem(STORAGE_BUNDLE)
}

/** Cek versi terbaru dari npm (utama) + fallback GitHub package.json */
export async function checkScraprUpdate(): Promise<ScraprUpdateInfo> {
  const current = getCurrentScraprVersion()

  // 1) npm registry
  try {
    const res = await fetch(NPM_LATEST, {
      cache: 'no-store',
      headers: { Accept: 'application/json', 'User-Agent': 'YMusic-Lite' },
    })
    if (res.ok) {
      const data = await res.json()
      const latest = data.version as string
      return {
        current,
        latest,
        isNewer: isNewerVersion(latest, current),
        description: data.description,
        tarball: data.dist?.tarball,
        homepage: data.homepage || GITHUB_REPO,
        publishedVia: 'npm',
      }
    }
  } catch (e) {
    console.warn('[scrapr-updater] npm check failed', e)
  }

  // 2) fallback: raw package.json di GitHub main
  try {
    const res = await fetch(GITHUB_PACKAGE_JSON, { cache: 'no-store' })
    if (res.ok) {
      const data = await res.json()
      const latest = data.version as string
      return {
        current,
        latest,
        isNewer: isNewerVersion(latest, current),
        description: data.description,
        homepage: GITHUB_REPO,
        publishedVia: 'github',
      }
    }
  } catch (e) {
    console.warn('[scrapr-updater] github check failed', e)
  }

  throw new Error('Tidak bisa cek update scrapr (offline / rate limit)')
}

/**
 * Update scraper dari sumber resmi.
 * Mengunduh package.json (versi) + mencoba bundle.cjs / index.js sebagai referensi.
 * Bundle Node tidak dijalankan di browser; metadata + file disimpan untuk
 * native bridge / update tracking (pola Mori).
 */
export async function updateScraprFromOfficial(): Promise<ScraprMeta> {
  const info = await checkScraprUpdate()

  // Simpan meta dari npm bila ada tarball
  let meta: ScraprMeta = {
    version: info.latest,
    updatedAt: Date.now(),
    source: info.publishedVia === 'npm' ? 'npm' : 'github',
    description: info.description,
    tarball: info.tarball,
    homepage: info.homepage,
  }

  // Coba unduh bundle.cjs (Node) sebagai artefak tersimpan
  // User / native layer bisa memakainya nanti
  try {
    const bundleRes = await fetch(GITHUB_BUNDLE, { cache: 'no-store' })
    if (bundleRes.ok) {
      const text = await bundleRes.text()
      if (text.length > 100) {
        // localStorage limit ~5MB; bundle scrapr bisa besar — simpan sebagian / skip
        if (text.length < 4_000_000) {
          localStorage.setItem(STORAGE_BUNDLE, text)
          meta.size = text.length
        } else {
          // terlalu besar: simpan hanya signature singkat
          meta.size = text.length
          localStorage.setItem(
            STORAGE_BUNDLE,
            `/* scrapr bundle ${info.latest} too large for localStorage (${text.length} bytes). Use tarball: ${info.tarball || GITHUB_BUNDLE} */\n`
          )
        }
      }
    }
  } catch {
    // optional
  }

  // Juga catat index.js kecil sebagai marker
  try {
    const idxRes = await fetch(GITHUB_INDEX, { cache: 'no-store' })
    if (idxRes.ok) {
      const idx = await idxRes.text()
      // merge info
      meta = { ...meta, description: meta.description || 'scrapr index loaded' }
      // jangan overwrite bundle besar dengan index kecil
      if (!localStorage.getItem(STORAGE_BUNDLE)) {
        localStorage.setItem(STORAGE_BUNDLE, idx)
        meta.size = idx.length
      }
    }
  } catch {}

  localStorage.setItem(STORAGE_META, JSON.stringify(meta))

  // sinkron ke settings engine
  try {
    const { saveSettings } = await import('./settings')
    saveSettings({
      engineVersion: meta.version,
      lastEngineCheck: Date.now(),
      useCustomEngine: true,
      customEngineUrl: info.tarball || GITHUB_BUNDLE,
    })
  } catch {}

  return meta
}

/** Kembalikan ke engine bawaan app */
export function revertScraprToBuiltin() {
  localStorage.removeItem(STORAGE_META)
  localStorage.removeItem(STORAGE_BUNDLE)
  import('./settings').then(({ saveSettings }) => {
    saveSettings({
      useCustomEngine: false,
      engineVersion: BUILTIN_VERSION,
      customEngineUrl: '',
    })
  }).catch(() => {})
}

export function getScraprStatus(): {
  version: string
  source: string
  updatedAt: number | null
  hasBundle: boolean
  bundleSize: number
} {
  const meta = getStoredScraprMeta()
  const bundle = getStoredBundle()
  return {
    version: meta?.version || BUILTIN_VERSION,
    source: meta?.source || 'builtin',
    updatedAt: meta?.updatedAt || null,
    hasBundle: !!bundle && !bundle.startsWith('/* scrapr bundle'),
    bundleSize: bundle?.length || 0,
  }
}

export const SCRAPR_LINKS = {
  repo: GITHUB_REPO,
  releases: GITHUB_RELEASES_PAGE,
  npm: 'https://www.npmjs.com/package/@coflyn/scrapr',
  rawPackage: GITHUB_PACKAGE_JSON,
  rawBundle: GITHUB_BUNDLE,
}
