/**
 * YMusic Lite Engine
 * - Search (YouTube Data API / demo)
 * - Universal scrape (URL → media) via scraper.ts
 * - Bisa diganti / di-update lewat engine-manager (mirip Mori)
 */

import type { Track } from '../lib/types'
export { scrapeUrl, scrapeToTrack, SCRAPER_VERSION } from './scraper'
export type { ScrapeResult, MediaItem } from './scraper'
export { detectPlatform, platformLabel, extractYoutubeId } from './platforms'
export type { PlatformId } from './platforms'

const YT_API_BASE = 'https://www.googleapis.com/youtube/v3'

export interface EngineSearchResult {
  tracks: Track[]
  nextPageToken?: string
}

export interface EngineConfig {
  apiKey?: string
  clientName?: string
}

let config: EngineConfig = {}

export function configureEngine(cfg: EngineConfig) {
  config = { ...config, ...cfg }
}

export async function searchTracks(
  query: string,
  maxResults = 20
): Promise<EngineSearchResult> {
  const q = query.trim()
  if (!q) return { tracks: [] }

  if (!config.apiKey) {
    return { tracks: demoTracks(q) }
  }

  try {
    const params = new URLSearchParams({
      part: 'snippet',
      type: 'video',
      maxResults: String(maxResults),
      q,
      key: config.apiKey,
      videoCategoryId: '10',
    })
    const res = await fetch(`${YT_API_BASE}/search?${params}`)
    if (!res.ok) return { tracks: demoTracks(q) }
    const data = await res.json()
    const tracks: Track[] = (data.items || []).map((item: any) => ({
      id: item.id.videoId,
      videoId: item.id.videoId,
      title: item.snippet.title,
      artist: item.snippet.channelTitle,
      duration: 0,
      thumbnail:
        item.snippet.thumbnails?.medium?.url ||
        item.snippet.thumbnails?.default?.url ||
        '',
      source: 'youtube' as const,
    }))
    return { tracks, nextPageToken: data.nextPageToken }
  } catch {
    return { tracks: demoTracks(q) }
  }
}

export async function getTrackDetails(videoId: string): Promise<Partial<Track> | null> {
  if (!config.apiKey) return null
  try {
    const params = new URLSearchParams({
      part: 'contentDetails,snippet',
      id: videoId,
      key: config.apiKey,
    })
    const res = await fetch(`${YT_API_BASE}/videos?${params}`)
    if (!res.ok) return null
    const data = await res.json()
    const item = data.items?.[0]
    if (!item) return null
    return {
      duration: parseDuration(item.contentDetails?.duration),
      title: item.snippet?.title,
      artist: item.snippet?.channelTitle,
      thumbnail: item.snippet?.thumbnails?.medium?.url,
    }
  } catch {
    return null
  }
}

/** Resolve stream untuk download – diisi engine custom / remote */
export async function resolveStreamUrl(
  _track: Track,
  _format: 'mp3' | 'mp4' = 'mp3'
): Promise<{ url: string; format: string } | null> {
  return null
}

export const ENGINE_VERSION = '0.2.0-lite'

function parseDuration(iso?: string): number {
  if (!iso) return 0
  const m = iso.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/)
  if (!m) return 0
  return (
    parseInt(m[1] || '0') * 3600 +
    parseInt(m[2] || '0') * 60 +
    parseInt(m[3] || '0')
  )
}

function demoTracks(query: string): Track[] {
  return [
    {
      id: 'demo-1',
      videoId: 'dQw4w9WgXcQ',
      title: `${query} – Demo Track 1`,
      artist: 'YMusic Lite Demo',
      duration: 212,
      thumbnail: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg',
      source: 'youtube',
    },
    {
      id: 'demo-2',
      videoId: '9bZkp7q19f0',
      title: `${query} – Demo Track 2`,
      artist: 'YMusic Lite Demo',
      duration: 253,
      thumbnail: 'https://i.ytimg.com/vi/9bZkp7q19f0/mqdefault.jpg',
      source: 'youtube',
    },
  ]
}
