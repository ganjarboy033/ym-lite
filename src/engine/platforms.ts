/** Platform detection from URL */

export type PlatformId =
  | 'youtube'
  | 'tiktok'
  | 'instagram'
  | 'twitter'
  | 'facebook'
  | 'spotify'
  | 'soundcloud'
  | 'pinterest'
  | 'reddit'
  | 'bilibili'
  | 'threads'
  | 'unknown'

const rules: { id: PlatformId; patterns: RegExp[] }[] = [
  {
    id: 'youtube',
    patterns: [
      /(?:youtube\.com|youtu\.be|youtube-nocookie\.com)/i,
      /youtube\.com\/shorts\//i,
    ],
  },
  {
    id: 'tiktok',
    patterns: [/tiktok\.com/i, /vt\.tiktok\.com/i, /vm\.tiktok\.com/i, /tt\.site/i],
  },
  {
    id: 'instagram',
    patterns: [/instagram\.com/i, /instagr\.am/i],
  },
  {
    id: 'twitter',
    patterns: [/twitter\.com/i, /x\.com\//i, /t\.co\//i],
  },
  {
    id: 'facebook',
    patterns: [/facebook\.com/i, /fb\.watch/i, /fb\.com/i],
  },
  {
    id: 'spotify',
    patterns: [/open\.spotify\.com/i, /spotify\.link/i],
  },
  {
    id: 'soundcloud',
    patterns: [/soundcloud\.com/i],
  },
  {
    id: 'pinterest',
    patterns: [/pinterest\./i, /pin\.it/i],
  },
  {
    id: 'reddit',
    patterns: [/reddit\.com/i, /redd\.it/i],
  },
  {
    id: 'bilibili',
    patterns: [/bilibili\.com/i, /b23\.tv/i],
  },
  {
    id: 'threads',
    patterns: [/threads\.net/i],
  },
]

export function detectPlatform(url: string): PlatformId {
  for (const r of rules) {
    if (r.patterns.some((p) => p.test(url))) return r.id
  }
  return 'unknown'
}

export function platformLabel(id: PlatformId): string {
  const map: Record<PlatformId, string> = {
    youtube: 'YouTube',
    tiktok: 'TikTok',
    instagram: 'Instagram',
    twitter: 'X / Twitter',
    facebook: 'Facebook',
    spotify: 'Spotify',
    soundcloud: 'SoundCloud',
    pinterest: 'Pinterest',
    reddit: 'Reddit',
    bilibili: 'Bilibili',
    threads: 'Threads',
    unknown: 'Unknown',
  }
  return map[id] || id
}

export function extractYoutubeId(url: string): string | null {
  const m = url.match(
    /(?:youtube\.com\/(?:[^/]+\/.+\/|(?:v|e(?:mbed)?|shorts)\/|.*[?&]v=)|youtu\.be\/)([^"&?/\s]{11})/i
  )
  return m ? m[1] : null
}
