import type { Track } from './types'

type PlayerListener = (state: PlayerState) => void

export interface PlayerState {
  track: Track | null
  playing: boolean
  currentTime: number
  duration: number
  buffered: number
  mode: 'audio' | 'youtube-iframe' | 'none'
}

/**
 * Player yang mendukung:
 * 1. <audio> untuk file lokal / URL langsung
 * 2. YouTube IFrame Player API untuk videoId (in-app)
 */
class Player {
  private audio = new Audio()
  private ytPlayer: any = null
  private ytReady = false
  private state: PlayerState = {
    track: null,
    playing: false,
    currentTime: 0,
    duration: 0,
    buffered: 0,
    mode: 'none',
  }
  private listeners = new Set<PlayerListener>()
  private ytContainerId = 'yt-player-container'
  private timeInterval: number | null = null

  constructor() {
    this.audio.preload = 'metadata'
    this.audio.addEventListener('timeupdate', () => this.emit())
    this.audio.addEventListener('durationchange', () => {
      this.state.duration = this.audio.duration || 0
      this.emit()
    })
    this.audio.addEventListener('play', () => {
      this.state.playing = true
      this.emit()
    })
    this.audio.addEventListener('pause', () => {
      this.state.playing = false
      this.emit()
    })
    this.audio.addEventListener('ended', () => {
      this.state.playing = false
      this.state.currentTime = 0
      this.emit()
    })
    this.audio.addEventListener('progress', () => {
      if (this.audio.buffered.length) {
        this.state.buffered = this.audio.buffered.end(this.audio.buffered.length - 1)
        this.emit()
      }
    })

    this.loadYouTubeAPI()
  }

  private loadYouTubeAPI() {
    if ((window as any).YT) {
      this.ytReady = true
      return
    }
    const tag = document.createElement('script')
    tag.src = 'https://www.youtube.com/iframe_api'
    document.head.appendChild(tag)
    ;(window as any).onYouTubeIframeAPIReady = () => {
      this.ytReady = true
    }
  }

  subscribe(fn: PlayerListener) {
    this.listeners.add(fn)
    fn(this.state)
    return () => this.listeners.delete(fn)
  }

  private emit() {
    if (this.state.mode === 'audio') {
      this.state.currentTime = this.audio.currentTime
    }
    this.listeners.forEach((fn) => fn({ ...this.state }))
  }

  private ensureYtContainer() {
    let el = document.getElementById(this.ytContainerId)
    if (!el) {
      el = document.createElement('div')
      el.id = this.ytContainerId
      el.style.cssText =
        'position:fixed;bottom:140px;right:12px;width:280px;height:158px;z-index:30;border-radius:12px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,0.5);background:#000;'
      document.body.appendChild(el)
    }
    return el
  }

  private destroyYt() {
    if (this.timeInterval) {
      clearInterval(this.timeInterval)
      this.timeInterval = null
    }
    if (this.ytPlayer) {
      try {
        this.ytPlayer.destroy()
      } catch {}
      this.ytPlayer = null
    }
    const el = document.getElementById(this.ytContainerId)
    if (el) el.remove()
  }

  async play(track: Track) {
    this.audio.pause()
    this.destroyYt()

    this.state.track = track
    this.state.playing = false
    this.state.currentTime = 0
    this.state.duration = track.duration || 0

    if (track.source === 'youtube' && track.videoId) {
      this.state.mode = 'youtube-iframe'
      this.emit()
      await this.playYouTube(track.videoId)
      return
    }

    if (track.url) {
      this.state.mode = 'audio'
      this.audio.src = track.url
      try {
        await this.audio.play()
      } catch (e) {
        console.warn('play failed', e)
      }
      this.emit()
      return
    }

    this.state.mode = 'none'
    this.emit()
  }

  private async playYouTube(videoId: string) {
    if (!this.ytReady) {
      await new Promise<void>((resolve) => {
        const check = setInterval(() => {
          if (this.ytReady || (window as any).YT) {
            clearInterval(check)
            this.ytReady = true
            resolve()
          }
        }, 100)
        setTimeout(() => {
          clearInterval(check)
          resolve()
        }, 5000)
      })
    }

    if (!(window as any).YT?.Player) {
      window.open(`https://www.youtube.com/watch?v=${videoId}`, '_blank')
      return
    }

    const container = this.ensureYtContainer()
    container.innerHTML = ''

    this.ytPlayer = new (window as any).YT.Player(this.ytContainerId, {
      height: '158',
      width: '280',
      videoId,
      playerVars: {
        autoplay: 1,
        controls: 1,
        modestbranding: 1,
        rel: 0,
        playsinline: 1,
      },
      events: {
        onReady: (e: any) => {
          this.state.playing = true
          this.state.duration = e.target.getDuration?.() || this.state.duration
          this.emit()
          this.timeInterval = window.setInterval(() => {
            if (this.ytPlayer?.getCurrentTime) {
              this.state.currentTime = this.ytPlayer.getCurrentTime()
              this.state.duration = this.ytPlayer.getDuration?.() || this.state.duration
              this.state.playing = this.ytPlayer.getPlayerState?.() === 1
              this.emit()
            }
          }, 500)
        },
        onStateChange: (e: any) => {
          this.state.playing = e.data === 1
          if (e.data === 0) {
            this.state.playing = false
            this.state.currentTime = 0
          }
          this.emit()
        },
        onError: () => {
          window.open(`https://www.youtube.com/watch?v=${videoId}`, '_blank')
        },
      },
    })
  }

  toggle() {
    if (!this.state.track) return

    if (this.state.mode === 'youtube-iframe' && this.ytPlayer) {
      const state = this.ytPlayer.getPlayerState?.()
      if (state === 1) this.ytPlayer.pauseVideo()
      else this.ytPlayer.playVideo()
      return
    }

    if (this.state.playing) this.audio.pause()
    else this.audio.play().catch(() => {})
  }

  seek(time: number) {
    if (this.state.mode === 'youtube-iframe' && this.ytPlayer?.seekTo) {
      this.ytPlayer.seekTo(time, true)
      return
    }
    this.audio.currentTime = time
  }

  stop() {
    this.audio.pause()
    this.destroyYt()
    this.state.track = null
    this.state.playing = false
    this.state.mode = 'none'
    this.emit()
  }

  getState() {
    return { ...this.state }
  }
}

export const player = new Player()
