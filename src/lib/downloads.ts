import type { DownloadJob, Track } from './types'
import { resolveStreamUrl } from '../engine'
import { loadSettings } from './settings'

type DownloadListener = (jobs: DownloadJob[]) => void

const STORAGE_KEY = 'ymusic-lite-downloads'

class DownloadManager {
  private jobs: DownloadJob[] = []
  private listeners = new Set<DownloadListener>()
  private filesystemReady = false

  constructor() {
    this.load()
    this.initFilesystem()
  }

  private async initFilesystem() {
    try {
      const { Capacitor } = await import('@capacitor/core')
      if (Capacitor.isNativePlatform()) {
        await import('@capacitor/filesystem')
        this.filesystemReady = true
      }
    } catch {
      this.filesystemReady = false
    }
  }

  subscribe(fn: DownloadListener) {
    this.listeners.add(fn)
    fn(this.jobs)
    return () => this.listeners.delete(fn)
  }

  private emit() {
    this.save()
    this.listeners.forEach((fn) => fn([...this.jobs]))
  }

  private load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) this.jobs = JSON.parse(raw)
    } catch {
      this.jobs = []
    }
  }

  private save() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.jobs))
    } catch {}
  }

  getJobs() {
    return [...this.jobs]
  }

  async enqueue(track: Track, format: 'mp3' | 'mp4') {
    const id = `dl-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
    const job: DownloadJob = {
      id,
      track,
      format,
      status: 'queued',
      progress: 0,
      createdAt: Date.now(),
    }
    this.jobs.unshift(job)
    this.emit()
    this.process(job)
    return job
  }

  private async process(job: DownloadJob) {
    const update = (partial: Partial<DownloadJob>) => {
      Object.assign(job, partial)
      this.emit()
    }

    try {
      update({ status: 'downloading', progress: 5 })

      const stream = await resolveStreamUrl(job.track, job.format)

      if (!stream) {
        for (let p = 15; p <= 70; p += 15) {
          await sleep(250)
          if (job.status === 'error') return
          update({ progress: p })
        }
        update({
          status: 'error',
          progress: 0,
          error:
            'Stream URL belum tersedia. Engine scraper (mirip YMusic) belum diaktifkan. ' +
            'Lihat tab Settings → Engine untuk update / info.',
        })
        return
      }

      update({ status: 'downloading', progress: 40 })

      const res = await fetch(stream.url)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const blob = await res.blob()
      update({ progress: 80, status: 'converting' })

      const fileName = sanitizeFileName(
        `${job.track.artist} - ${job.track.title}.${job.format}`
      )

      if (this.filesystemReady) {
        const path = await this.saveWithCapacitor(blob, fileName)
        update({ status: 'done', progress: 100, filePath: path })
      } else {
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = fileName
        a.click()
        URL.revokeObjectURL(url)
        update({ status: 'done', progress: 100, filePath: `(browser) ${fileName}` })
      }
    } catch (err: any) {
      update({
        status: 'error',
        error: err?.message || 'Download gagal',
      })
    }
  }

  private async saveWithCapacitor(blob: Blob, fileName: string): Promise<string> {
    const { Filesystem, Directory } = await import('@capacitor/filesystem')
    const settings = loadSettings()
    const folder = settings.downloadFolder || 'YMusicLite'

    const base64 = await blobToBase64(blob)

    try {
      await Filesystem.mkdir({
        path: folder,
        directory: Directory.Documents,
        recursive: true,
      })
    } catch {}

    const path = `${folder}/${fileName}`
    await Filesystem.writeFile({
      path,
      data: base64,
      directory: Directory.Documents,
    })

    return path
  }

  remove(id: string) {
    this.jobs = this.jobs.filter((j) => j.id !== id)
    this.emit()
  }

  clearFinished() {
    this.jobs = this.jobs.filter(
      (j) => j.status === 'downloading' || j.status === 'queued'
    )
    this.emit()
  }
}

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms))
}

function sanitizeFileName(name: string) {
  return name.replace(/[<>:"/\\|?*]/g, '_').slice(0, 120)
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onloadend = () => {
      const result = reader.result as string
      resolve(result.split(',')[1] || result)
    }
    reader.onerror = reject
    reader.readAsDataURL(blob)
  })
}

export const downloads = new DownloadManager()
