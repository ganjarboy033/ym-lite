export interface Track {
  id: string
  title: string
  artist: string
  duration: number
  thumbnail: string
  source: 'youtube' | 'local' | 'url'
  url?: string
  videoId?: string
}

export interface DownloadJob {
  id: string
  track: Track
  format: 'mp3' | 'mp4'
  status: 'queued' | 'downloading' | 'converting' | 'done' | 'error'
  progress: number
  error?: string
  filePath?: string
  createdAt: number
}

export interface AppSettings {
  engineVersion: string
  lastEngineCheck: number
  quality: 'high' | 'medium' | 'low'
  downloadFolder: string
  autoUpdateEngine: boolean
  customEngineUrl: string
  useCustomEngine: boolean
  autoCheckAppUpdate: boolean
}

export type TabId = 'download' | 'search' | 'spatial' | 'library' | 'history' | 'settings'
