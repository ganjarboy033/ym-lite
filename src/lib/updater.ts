/**
 * App + Engine updater (mirip Mori)
 * - Cek GitHub Releases untuk APK baru
 * - Cek / unduh engine scraper baru
 */

export const APP_VERSION = '0.2.0'
/** Ganti ke repo kamu sendiri nanti */
export const GITHUB_REPO = 'ymusic-lite/ymusic-lite'
export const UPDATE_CHECK_URL = `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`
export const REPO_URL = `https://github.com/${GITHUB_REPO}`

/** Repo engine/scraper (bisa scrapr atau fork kamu) */
export const SCRAPER_REPO = 'coflyn/scrapr'
export const SCRAPER_RELEASES = `https://api.github.com/repos/${SCRAPER_REPO}/releases/latest`

export interface ReleaseInfo {
  tag: string
  name: string
  body: string
  publishedAt: string
  htmlUrl: string
  apkUrl?: string
  apkName?: string
  isNewer: boolean
}

export function isNewerVersion(latest: string, current: string): boolean {
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

export async function checkAppUpdate(): Promise<ReleaseInfo | null> {
  try {
    const res = await fetch(UPDATE_CHECK_URL, {
      headers: {
        Accept: 'application/vnd.github+json',
        'User-Agent': 'YMusic-Lite',
      },
    })
    if (!res.ok) return null
    const data = await res.json()
    const tag = (data.tag_name || '').replace(/^v/i, '')
    const assets: any[] = data.assets || []
    const apk = assets.find(
      (a) =>
        a.name?.toLowerCase().endsWith('.apk') ||
        a.content_type === 'application/vnd.android.package-archive'
    )
    return {
      tag,
      name: data.name || tag,
      body: data.body || '',
      publishedAt: data.published_at || '',
      htmlUrl: data.html_url || REPO_URL,
      apkUrl: apk?.browser_download_url,
      apkName: apk?.name,
      isNewer: isNewerVersion(tag, APP_VERSION),
    }
  } catch {
    return null
  }
}

export async function checkScraperUpdate(): Promise<{
  tag: string
  isNewer: boolean
  htmlUrl: string
  publishedAt: string
} | null> {
  try {
    const res = await fetch(SCRAPER_RELEASES, {
      headers: {
        Accept: 'application/vnd.github+json',
        'User-Agent': 'YMusic-Lite',
      },
    })
    if (!res.ok) return null
    const data = await res.json()
    const tag = data.tag_name || ''
    return {
      tag,
      isNewer: true, // user decides; scrapr versioning berbeda
      htmlUrl: data.html_url || `https://github.com/${SCRAPER_REPO}`,
      publishedAt: data.published_at || '',
    }
  } catch {
    return null
  }
}

/** Buka URL unduhan APK / release page */
export function openRelease(url: string) {
  if (window.Capacitor?.getPlatform?.() === 'android') {
    // Native bisa pakai browser intent
    window.open(url, '_system')
  } else {
    window.open(url, '_blank')
  }
}
