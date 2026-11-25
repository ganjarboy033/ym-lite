/**
 * Universal scraper engine (browser-friendly)
 * Inspired by @coflyn/scrapr API shape + Mori's runtime loader.
 *
 * Engine bisa di-update remotely lewat Settings (unduh bundle JS baru).
 */

import { detectPlatform, extractYoutubeId, platformLabel, type PlatformId } from './platforms'
import type { Track } from '../lib/types'

export interface MediaItem {
  type: 'video' | 'audio' | 'image' | 'file'
  quality?: string
  url: string
  ext?: string
  size?: string
}

export interface ScrapeResult {
  status: boolean
  platform: PlatformId
  platformLabel: string
  title: string
  author?: string
  thumbnail?: string
  duration?: number
  items: MediaItem[]
  error?: string
  rawUrl: string
}

export const SCRAPER_VERSION = '0.2.0-lite'

/** Entry utama: paste URL apa saja */
export async function scrapeUrl(url: string): Promise<ScrapeResult> {
  const cleaned = url.trim()
  if (!cleaned) {
    return emptyResult(cleaned, 'unknown', 'URL kosong')
  }

  const platform = detectPlatform(cleaned)
  const label = platformLabel(platform)

  try {
    switch (platform) {
      case 'youtube':
        return await scrapeYoutube(cleaned)
      case 'tiktok':
        return await scrapeTiktok(cleaned)
      case 'instagram':
        return await scrapeGeneric(cleaned, platform, label)
      case 'twitter':
        return await scrapeGeneric(cleaned, platform, label)
      case 'soundcloud':
        return await scrapeGeneric(cleaned, platform, label)
      default:
        return await scrapeGeneric(cleaned, platform, label)
    }
  } catch (err: any) {
    return emptyResult(cleaned, platform, err?.message || 'Scrape gagal')
  }
}

function emptyResult(url: string, platform: PlatformId, error: string): ScrapeResult {
  return {
    status: false,
    platform,
    platformLabel: platformLabel(platform),
    title: '',
    items: [],
    error,
    rawUrl: url,
  }
}

async function scrapeYoutube(url: string): Promise<ScrapeResult> {
  const videoId = extractYoutubeId(url)
  if (!videoId) {
    return emptyResult(url, 'youtube', 'Video ID tidak ditemukan')
  }

  // Metadata via oEmbed (stabil, official)
  let title = 'YouTube Video'
  let author = ''
  let thumbnail = `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`

  try {
    const oembed = await fetch(
      `https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=${videoId}&format=json`
    )
    if (oembed.ok) {
      const data = await oembed.json()
      title = data.title || title
      author = data.author_name || ''
      thumbnail = data.thumbnail_url || thumbnail
    }
  } catch {}

  // Stream URL: di production butuh converter / engine scraper penuh
  // Di sini kita sediakan metadata + link ke YouTube, plus placeholder item
  // yang bisa diisi engine custom (update dari Settings).
  const items: MediaItem[] = [
    {
      type: 'video',
      quality: 'YouTube (buka di player)',
      url: `https://www.youtube.com/watch?v=${videoId}`,
      ext: 'mp4',
    },
  ]

  // Coba resolver eksternal ringan (bisa gagal / berubah – update engine untuk ganti)
  try {
    const extra = await tryYoutubeConverters(url, videoId)
    if (extra.length) items.unshift(...extra)
  } catch {}

  return {
    status: true,
    platform: 'youtube',
    platformLabel: 'YouTube',
    title,
    author,
    thumbnail,
    items,
    rawUrl: url,
  }
}

async function tryYoutubeConverters(url: string, videoId: string): Promise<MediaItem[]> {
  // Placeholder – engine custom / remote bundle bisa isi fungsi ini
  // Scrapr asli pakai convert1s, ytmp3.gg, dll yang sering berubah.
  return []
}

async function scrapeTiktok(url: string): Promise<ScrapeResult> {
  // Metadata terbatas tanpa backend; engine custom bisa ganti ini
  return {
    status: true,
    platform: 'tiktok',
    platformLabel: 'TikTok',
    title: 'TikTok Video',
    thumbnail: '',
    items: [
      {
        type: 'video',
        quality: 'original',
        url, // user masih bisa buka; engine full akan resolve direct mp4
        ext: 'mp4',
      },
    ],
    rawUrl: url,
    error:
      'Direct stream butuh engine scraper penuh. Update engine di Settings atau pakai bundle dari scrapr/Mori.',
  }
}

async function scrapeGeneric(
  url: string,
  platform: PlatformId,
  label: string
): Promise<ScrapeResult> {
  return {
    status: true,
    platform,
    platformLabel: label,
    title: `${label} media`,
    items: [{ type: 'file', url, quality: 'source link' }],
    rawUrl: url,
    error:
      'Platform ini butuh engine scraper. Unduh/update engine di Settings (mirip Mori).',
  }
}

/** Convert ScrapeResult → Track untuk library/player */
export function scrapeToTrack(r: ScrapeResult): Track | null {
  if (!r.status || !r.title) return null
  const videoId =
    r.platform === 'youtube' ? extractYoutubeId(r.rawUrl) || undefined : undefined
  const best = r.items.find((i) => i.type === 'audio') || r.items[0]
  return {
    id: videoId || `url-${Date.now()}`,
    title: r.title,
    artist: r.author || r.platformLabel,
    duration: r.duration || 0,
    thumbnail: r.thumbnail || '',
    source: r.platform === 'youtube' ? 'youtube' : 'url',
    videoId,
    url: best?.url,
  }
}
