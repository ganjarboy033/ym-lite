import './styles/main.css'
import type { TabId, Track } from './lib/types'
import { renderSpatialPanel, spatialSetNowPlaying } from './spatial/panel'
import {
  searchTracks,
  ENGINE_VERSION,
  scrapeUrl,
  scrapeToTrack,
  SCRAPER_VERSION,
  type ScrapeResult,
} from './engine'
import { player } from './lib/player'
import { downloads } from './lib/downloads'
import { loadSettings, saveSettings } from './lib/settings'
import {
  getEngineStatus,
  downloadEngine,
  revertToBuiltin,
  getCustomEngineMeta,
} from './lib/engine-manager'
import {
  APP_VERSION,
  checkAppUpdate,
  openRelease,
  REPO_URL,
  type ReleaseInfo,
} from './lib/updater'
import {
  checkScraprUpdate,
  updateScraprFromOfficial,
  revertScraprToBuiltin,
  getScraprStatus,
  getCurrentScraprVersion,
  SCRAPR_LINKS,
  type ScraprUpdateInfo,
} from './lib/scraper-updater'

const app = document.querySelector<HTMLDivElement>('#app')!

let currentTab: TabId = 'download'
let searchResults: Track[] = []
let isSearching = false
let library: Track[] = loadLibrary()
let history: ScrapeResult[] = loadHistory()
let engineBusy = false
let engineMsg = ''
let scrapeBusy = false
let lastScrape: ScrapeResult | null = null
let updateInfo: ReleaseInfo | null = null
let updateChecking = false
let scraprInfo: ScraprUpdateInfo | null = null
let scraprBusy = false
let scraprMsg = 

function loadLibrary(): Track[] {
  try {
    return JSON.parse(localStorage.getItem('ymusic-lite-library') || '[]')
  } catch {
    return []
  }
}
function saveLibrary() {
  localStorage.setItem('ymusic-lite-library', JSON.stringify(library))
}
function loadHistory(): ScrapeResult[] {
  try {
    return JSON.parse(localStorage.getItem('ymusic-lite-scrape-history') || '[]')
  } catch {
    return []
  }
}
function saveHistory() {
  localStorage.setItem(
    'ymusic-lite-scrape-history',
    JSON.stringify(history.slice(0, 50))
  )
}

function toast(msg: string) {
  let el = document.querySelector('.toast') as HTMLDivElement
  if (!el) {
    el = document.createElement('div')
    el.className = 'toast'
    document.body.appendChild(el)
  }
  el.textContent = msg
  el.classList.add('show')
  clearTimeout((el as any)._t)
  ;(el as any)._t = setTimeout(() => el.classList.remove('show'), 2800)
}

function formatTime(sec: number) {
  if (!sec || !isFinite(sec)) return '0:00'
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}

function icon(name: string) {
  const icons: Record<string, string> = {
    spatial: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 3a9 9 0 100 18 9 9 0 000-18zm0 2a7 7 0 110 14 7 7 0 010-14zm0 2.5a1.5 1.5 0 100 3 1.5 1.5 0 000-3zm-4.5 5a1 1 0 100 2 1 1 0 000-2zm9 0a1 1 0 100 2 1 1 0 000-2zM12 15a1.5 1.5 0 100 3 1.5 1.5 0 000-3z"/></svg>`,
    download: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>`,
    search: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>`,
    library: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-1 9h-4v4h-2v-4H9V9h4V5h2v4h4v2z"/></svg>`,
    history: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M13 3a9 9 0 00-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0013 21a9 9 0 000-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>`,
    settings: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M19.14 12.94c.04-.31.06-.63.06-.94 0-.31-.02-.63-.06-.94l2.03-1.58a.49.49 0 00.12-.61l-1.92-3.32a.49.49 0 00-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 00-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.04.31-.06.63-.06.94s.02.63.06.94l-2.03 1.58a.49.49 0 00-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6A3.6 3.6 0 1112 8.4a3.6 3.6 0 010 7.2z"/></svg>`,
    play: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>`,
    pause: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>`,
    more: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>`,
    paste: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M19 2h-4.18C14.4.84 13.3 0 12 0c-1.3 0-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm7 18H5V4h2v3h10V4h2v16z"/></svg>`,
  }
  return icons[name] || ''
}

function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/* ========== DOWNLOAD TAB (main) ========== */
function renderDownloadTab() {
  return `
    <div class="dl-hero">
      <h2 style="font-size:1.35rem;font-weight:700;margin-bottom:6px">All Downloader</h2>
      <p style="color:var(--text-secondary);font-size:0.9rem;margin-bottom:18px">
        Paste link TikTok, YouTube, Instagram, X, Spotify, dll.
      </p>
      <div class="url-box">
        <input type="url" id="url-input" placeholder="Tempel URL di sini..." autocomplete="off" />
        <button class="icon-btn" id="btn-paste" title="Paste">${icon('paste')}</button>
      </div>
      <button class="primary-btn" id="btn-scrape" ${scrapeBusy ? 'disabled' : ''}>
        ${scrapeBusy ? 'Memproses...' : 'Download'}
      </button>
      <div class="platform-chips">
        <span>YouTube</span><span>TikTok</span><span>Instagram</span><span>X</span>
        <span>Spotify</span><span>SoundCloud</span><span>+more</span>
      </div>
    </div>

    ${lastScrape ? renderScrapeResult(lastScrape) : ''}
  `
}

function renderScrapeResult(r: ScrapeResult) {
  return `
    <div class="scrape-card">
      ${r.thumbnail ? `<img class="scrape-thumb" src="${escapeHtml(r.thumbnail)}" alt="" />` : ''}
      <div class="scrape-info">
        <div class="scrape-platform">${escapeHtml(r.platformLabel)}</div>
        <div class="scrape-title">${escapeHtml(r.title || '(tanpa judul)')}</div>
        ${r.author ? `<div class="scrape-author">${escapeHtml(r.author)}</div>` : ''}
        ${r.error ? `<div class="scrape-error">${escapeHtml(r.error)}</div>` : ''}
      </div>
      <div class="scrape-actions">
        ${r.items
          .map(
            (item, i) => `
          <button class="sheet-btn scrape-dl-btn" data-idx="${i}">
            ${icon('download')}
            ${item.type.toUpperCase()} ${item.quality ? '· ' + escapeHtml(item.quality) : ''}
            ${item.ext ? `(${item.ext})` : ''}
          </button>`
          )
          .join('')}
        ${
          r.status
            ? `<button class="sheet-btn" id="btn-add-lib">${icon('library')} Simpan ke Library</button>
               <button class="sheet-btn" id="btn-play-scrape">${icon('play')} Putar</button>`
            : ''
        }
      </div>
    </div>
  `
}

/* ========== SEARCH ========== */
function renderTrackList(tracks: Track[], emptyMsg = 'Belum ada') {
  if (!tracks.length) {
    return `<div class="empty-state"><h3>${emptyMsg}</h3></div>`
  }
  return `
    <div class="track-list">
      ${tracks
        .map(
          (t) => `
        <div class="track-item" data-id="${t.id}">
          <img class="thumb" src="${t.thumbnail || ''}" alt="" loading="lazy" onerror="this.style.opacity=0.3" />
          <div class="info">
            <div class="title">${escapeHtml(t.title)}</div>
            <div class="meta">${escapeHtml(t.artist)}${t.duration ? ' · ' + formatTime(t.duration) : ''}</div>
          </div>
          <div class="actions">
            <button class="icon-btn more-btn" data-id="${t.id}">${icon('more')}</button>
          </div>
        </div>`
        )
        .join('')}
    </div>`
}

/* ========== SETTINGS ========== */
function renderSettings() {
  const settings = loadSettings()
  const status = getEngineStatus()
  const meta = getCustomEngineMeta()

  return `
    <div class="section-title">Update Aplikasi</div>
    <div class="settings-card">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div>
          <div style="font-weight:600">Versi saat ini</div>
          <div style="font-size:0.85rem;color:var(--text-secondary)">v${APP_VERSION}</div>
        </div>
        <button class="primary-btn sm" id="btn-check-update" ${updateChecking ? 'disabled' : ''}>
          ${updateChecking ? 'Cek...' : 'Cek Update'}
        </button>
      </div>
      ${
        updateInfo
          ? `<div style="font-size:0.9rem;margin-bottom:10px">
              ${
                updateInfo.isNewer
                  ? `<span style="color:var(--accent)">Update tersedia: v${escapeHtml(updateInfo.tag)}</span>`
                  : `<span style="color:var(--text-secondary)">Sudah versi terbaru</span>`
              }
              ${
                updateInfo.isNewer && updateInfo.apkUrl
                  ? `<button class="primary-btn sm" id="btn-download-apk" style="margin-top:8px;width:100%">Unduh APK v${escapeHtml(updateInfo.tag)}</button>`
                  : updateInfo.isNewer
                    ? `<button class="sheet-btn" id="btn-open-release" style="margin-top:8px">Buka halaman Release</button>`
                    : ''
              }
            </div>`
          : ''
      }
      <label class="check-row">
        <input type="checkbox" id="auto-check-app" ${settings.autoCheckAppUpdate ? 'checked' : ''} />
        Cek update otomatis saat buka app
      </label>
    </div>

    <div class="section-title">Engine / Scraper (scrapr)</div>
    <div class="settings-card">
      <div style="display:flex;justify-content:space-between;margin-bottom:10px">
        <div>
          <div style="font-weight:600">Versi scraper</div>
          <div style="font-size:0.85rem;color:var(--text-secondary)">
            Sekarang: <strong>${getCurrentScraprVersion()}</strong>
            ${scraprInfo ? ` · Terbaru: <strong style="color:var(--accent)">v${escapeHtml(scraprInfo.latest)}</strong>` : ''}
          </div>
        </div>
        <div style="font-size:0.8rem;color:var(--text-muted);text-align:right">
          ${getScraprStatus().source}
          ${getScraprStatus().updatedAt ? '<br/>' + new Date(getScraprStatus().updatedAt!).toLocaleString('id') : ''}
        </div>
      </div>

      ${scraprInfo?.isNewer ? `<div style="background:rgba(29,185,84,0.12);border-radius:8px;padding:10px 12px;margin-bottom:12px;font-size:0.9rem">
        Update tersedia: <strong>v${escapeHtml(scraprInfo.latest)}</strong> dari ${scraprInfo.publishedVia}
        ${scraprInfo.description ? `<div style="font-size:0.8rem;color:var(--text-secondary);margin-top:4px">${escapeHtml(scraprInfo.description).slice(0, 120)}</div>` : ''}
      </div>` : scraprInfo ? `<div style="font-size:0.85rem;color:var(--text-secondary);margin-bottom:10px">Sudah versi terbaru (v${escapeHtml(scraprInfo.latest)})</div>` : ''}

      <div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px">
        <button class="primary-btn sm" id="btn-check-scrapr" style="flex:1" ${scraprBusy ? 'disabled' : ''}>
          ${scraprBusy ? 'Memproses...' : 'Cek Update scrapr'}
        </button>
        <button class="primary-btn sm" id="btn-update-scrapr" style="flex:1;background:${scraprInfo?.isNewer ? 'var(--accent)' : 'var(--bg-hover)'};color:${scraprInfo?.isNewer ? '#000' : 'var(--text)'}" ${scraprBusy ? 'disabled' : ''}>
          ${scraprBusy ? 'Mengunduh...' : 'Update Sekarang'}
        </button>
      </div>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <button class="sheet-btn" id="btn-revert-scrapr" style="flex:1;justify-content:center">Kembalikan Bawaan</button>
        <button class="sheet-btn" id="btn-open-scrapr-repo" style="flex:1;justify-content:center">Buka GitHub scrapr</button>
      </div>
      ${scraprMsg ? `<div class="engine-msg">${escapeHtml(scraprMsg)}</div>` : ''}
      <p style="font-size:0.75rem;color:var(--text-muted);margin-top:12px;line-height:1.4">
        Sumber: <a href="${SCRAPR_LINKS.npm}" style="color:var(--accent)">npm @coflyn/scrapr</a> ·
        <a href="${SCRAPR_LINKS.repo}" style="color:var(--accent)">GitHub</a><br/>
        Update mengunduh versi & bundle dari repo resmi. Bundle Node disimpan sebagai artefak;
        resolve stream di WebView memakai engine browser bawaan + metadata versi ini.
      </p>
    </div>

    <div class="section-title">Engine custom (URL manual)</div>
    <div class="settings-card">
      <label class="check-row">
        <input type="checkbox" id="auto-update-engine" ${settings.autoUpdateEngine ? 'checked' : ''} />
        Cek update engine otomatis
      </label>
      <div style="margin:12px 0 8px">
        <label style="font-size:0.85rem;color:var(--text-secondary)">URL Engine bundle custom</label>
        <input type="url" id="engine-url" value="${escapeHtml(settings.customEngineUrl || '')}"
          placeholder="https://raw.githubusercontent.com/.../engine.js"
          class="text-input" />
      </div>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <button class="primary-btn sm" id="btn-download-engine" style="flex:1" ${engineBusy ? 'disabled' : ''}>
          ${engineBusy ? 'Mengunduh...' : 'Unduh dari URL'}
        </button>
        <button class="sheet-btn" id="btn-revert-engine" style="flex:1;justify-content:center">Revert URL engine</button>
      </div>
      ${engineMsg ? `<div class="engine-msg">${escapeHtml(engineMsg)}</div>` : ''}
    </div>

    <div class="section-title">Download</div>
    <div class="settings-card">
      <label style="font-size:0.85rem;color:var(--text-secondary)">Folder (Android Documents)</label>
      <input type="text" id="download-folder" value="${escapeHtml(settings.downloadFolder)}" class="text-input" />
    </div>

    <div class="section-title">Tentang</div>
    <div style="color:var(--text-secondary);font-size:0.88rem;line-height:1.55">
      <p>All-downloader ringan berbasis Capacitor + engine scrapr-style.</p>
      <p style="margin-top:8px">Engine bawaan sengaja minimal. Untuk resolve stream penuh (TikTok no-WM, YT mp3, dll) unduh / host bundle dari <strong>scrapr</strong> atau port Mori scrapers, lalu isi URL di atas.</p>
      <p style="margin-top:8px;font-size:0.8rem;color:var(--text-muted)">App v${APP_VERSION} · Engine ${ENGINE_VERSION} · Scraper ${SCRAPER_VERSION}</p>
    </div>
  `
}

function renderTabContent(): string {
  if (currentTab === 'spatial') {
    return `<div id="spatial-host" class="spatial-host"><div class="spinner"></div><p style="text-align:center;color:var(--text-muted);font-size:0.85rem">Memuat Spatial Audio…</p></div>`
  }
  if (currentTab === 'download') return renderDownloadTab()
  if (currentTab === 'search') {
    if (isSearching) return `<div class="spinner"></div>`
    return renderTrackList(searchResults, 'Ketik di kotak search')
  }
  if (currentTab === 'library') {
    return `<div class="section-title">Library <span class="count">${library.length}</span></div>
      ${renderTrackList(library, 'Library kosong')}`
  }
  if (currentTab === 'history') {
    if (!history.length) return `<div class="empty-state"><h3>Belum ada riwayat</h3></div>`
    return `
      <div class="section-title">Riwayat <span class="count">${history.length}</span></div>
      ${history
        .map(
          (h, i) => `
        <div class="track-item hist-item" data-hist="${i}">
          <img class="thumb" src="${h.thumbnail || ''}" alt="" onerror="this.style.opacity=0.2" />
          <div class="info">
            <div class="title">${escapeHtml(h.title || h.rawUrl)}</div>
            <div class="meta">${escapeHtml(h.platformLabel)}</div>
          </div>
        </div>`
        )
        .join('')}`
  }
  if (currentTab === 'settings') return renderSettings()
  return ''
}

function render() {
  const state = player.getState()
  app.innerHTML = `
    <header class="topbar">
      <h1>${currentTab === 'download' ? 'Downloader' : currentTab === 'search' ? 'Search' : currentTab === 'library' ? 'Library' : currentTab === 'history' ? 'Riwayat' : 'Settings'}</h1>
      <div class="search-box ${currentTab === 'search' ? '' : 'hidden'}">
        <span class="icon">${icon('search')}</span>
        <input type="search" id="search-input" placeholder="Cari lagu..." autocomplete="off" />
      </div>
    </header>
    <main class="main">${renderTabContent()}</main>
    <div class="player-bar ${state.track ? 'visible' : ''}" id="player-bar">
      <div class="progress-wrap"><div class="progress-bar" id="progress-bar" style="width:${state.duration ? (state.currentTime / state.duration) * 100 : 0}%"></div></div>
      ${
        state.track
          ? `<img class="thumb" src="${state.track.thumbnail || ''}" alt="" />
             <div class="info"><div class="title">${escapeHtml(state.track.title)}</div>
             <div class="artist">${escapeHtml(state.track.artist)}</div></div>
             <div class="controls"><button class="icon-btn" id="btn-play-pause">${state.playing ? icon('pause') : icon('play')}</button></div>`
          : ''
      }
    </div>
    <nav class="bottom-nav">
      <button class="nav-item ${currentTab === 'download' ? 'active' : ''}" data-tab="download">${icon('download')}<span>Download</span></button>
      <button class="nav-item ${currentTab === 'search' ? 'active' : ''}" data-tab="search">${icon('search')}<span>Search</span></button>
      <button class="nav-item ${currentTab === 'spatial' ? 'active' : ''}" data-tab="spatial">${icon('spatial')}<span>8D</span></button>
      <button class="nav-item ${currentTab === 'library' ? 'active' : ''}" data-tab="library">${icon('library')}<span>Library</span></button>
      <button class="nav-item ${currentTab === 'history' ? 'active' : ''}" data-tab="history">${icon('history')}<span>Riwayat</span></button>
      <button class="nav-item ${currentTab === 'settings' ? 'active' : ''}" data-tab="settings">${icon('settings')}<span>Settings</span></button>
    </nav>
    <div class="sheet-overlay" id="sheet-overlay">
      <div class="sheet"><div class="sheet-handle"></div>
        <h3 id="sheet-title">Opsi</h3>
        <div class="sheet-actions" id="sheet-actions"></div>
      </div>
    </div>
  `
  bindEvents()
}

async function doScrape() {
  const input = document.getElementById('url-input') as HTMLInputElement
  const url = input?.value.trim()
  if (!url) {
    toast('Tempel URL dulu')
    return
  }
  scrapeBusy = true
  render()
  // restore url
  const again = document.getElementById('url-input') as HTMLInputElement
  if (again) again.value = url

  try {
    const result = await scrapeUrl(url)
    lastScrape = result
    if (result.status) {
      history.unshift(result)
      saveHistory()
    }
    toast(result.status ? 'Berhasil' : result.error || 'Gagal')
  } catch (e: any) {
    toast(e?.message || 'Error')
  } finally {
    scrapeBusy = false
    render()
    const restore = document.getElementById('url-input') as HTMLInputElement
    if (restore) restore.value = url
  }
}

function bindEvents() {
  // Mount Kruzen Spatial panel when tab aktif
  if (currentTab === 'spatial') {
    const host = document.getElementById('spatial-host')
    if (host) {
      renderSpatialPanel(host).catch((e) => {
        console.error(e)
        host.innerHTML = `<div class="empty-state"><h3>Gagal memuat Spatial</h3><p>${e?.message || e}</p></div>`
      })
    }
  }

  document.querySelectorAll('.nav-item').forEach((btn) => {
    btn.addEventListener('click', () => {
      currentTab = (btn as HTMLElement).dataset.tab as TabId
      render()
      // Auto cek scrapr saat buka Settings (jika opsi aktif)
      if (currentTab === 'settings' && loadSettings().autoUpdateEngine && !scraprInfo) {
        checkScraprUpdate()
          .then((info) => {
            scraprInfo = info
            if (info.isNewer) toast(`Scraper update v${info.latest} tersedia`)
            render()
          })
          .catch(() => {})
      }
    })
  })

  document.getElementById('btn-scrape')?.addEventListener('click', doScrape)
  document.getElementById('url-input')?.addEventListener('keydown', (e) => {
    if ((e as KeyboardEvent).key === 'Enter') doScrape()
  })
  document.getElementById('btn-paste')?.addEventListener('click', async () => {
    try {
      const text = await navigator.clipboard.readText()
      const input = document.getElementById('url-input') as HTMLInputElement
      if (input && text) {
        input.value = text.trim()
        toast('Ditempel')
      }
    } catch {
      toast('Tidak bisa akses clipboard')
    }
  })

  // Scrape result actions
  document.querySelectorAll('.scrape-dl-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      if (!lastScrape) return
      const idx = parseInt((btn as HTMLElement).dataset.idx || '0', 10)
      const item = lastScrape.items[idx]
      if (!item) return
      const track = scrapeToTrack(lastScrape)
      if (track) {
        track.url = item.url
        const format = item.type === 'audio' || item.ext === 'mp3' ? 'mp3' : 'mp4'
        downloads.enqueue(track, format)
        toast(`Download ${format.toUpperCase()} dimulai`)
      } else {
        // direct open
        window.open(item.url, '_blank')
      }
    })
  })
  document.getElementById('btn-add-lib')?.addEventListener('click', () => {
    if (!lastScrape) return
    const track = scrapeToTrack(lastScrape)
    if (track) {
      if (!library.some((t) => t.id === track.id)) {
        library.unshift(track)
        saveLibrary()
      }
      toast('Ditambah ke Library')
    }
  })
  document.getElementById('btn-play-scrape')?.addEventListener('click', () => {
    if (!lastScrape) return
    const track = scrapeToTrack(lastScrape)
    if (track) {
      player.play(track)
      toast(`Memutar: ${track.title}`)
    }
  })

  // History click
  document.querySelectorAll('.hist-item').forEach((el) => {
    el.addEventListener('click', () => {
      const i = parseInt((el as HTMLElement).dataset.hist || '0', 10)
      lastScrape = history[i]
      currentTab = 'download'
      render()
    })
  })

  // Search
  const input = document.getElementById('search-input') as HTMLInputElement | null
  if (input) {
    let timer: any
    input.addEventListener('input', () => {
      clearTimeout(timer)
      timer = setTimeout(async () => {
        const q = input.value.trim()
        if (q.length < 2) {
          searchResults = []
          render()
          return
        }
        isSearching = true
        render()
        const res = await searchTracks(q)
        searchResults = res.tracks
        isSearching = false
        render()
        const again = document.getElementById('search-input') as HTMLInputElement
        if (again) {
          again.value = q
          again.focus()
        }
      }, 450)
    })
  }

  document.querySelectorAll('.track-item:not(.hist-item)').forEach((el) => {
    el.addEventListener('click', (e) => {
      if ((e.target as HTMLElement).closest('.more-btn')) return
      const id = (el as HTMLElement).dataset.id!
      const track = searchResults.find((t) => t.id === id) || library.find((t) => t.id === id)
      if (track) {
        player.play(track)
        toast(`Memutar: ${track.title}`)
      }
    })
  })
  document.querySelectorAll('.more-btn').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation()
      const id = (btn as HTMLElement).dataset.id!
      const track = searchResults.find((t) => t.id === id) || library.find((t) => t.id === id)
      if (track) openSheet(track)
    })
  })

  document.getElementById('btn-play-pause')?.addEventListener('click', () => player.toggle())
  document.getElementById('sheet-overlay')?.addEventListener('click', (e) => {
    if (e.target === e.currentTarget) closeSheet()
  })

  // Settings
  document.getElementById('auto-check-app')?.addEventListener('change', (e) => {
    saveSettings({ autoCheckAppUpdate: (e.target as HTMLInputElement).checked })
  })
  document.getElementById('auto-update-engine')?.addEventListener('change', (e) => {
    saveSettings({ autoUpdateEngine: (e.target as HTMLInputElement).checked })
  })
  document.getElementById('download-folder')?.addEventListener('change', (e) => {
    saveSettings({
      downloadFolder: (e.target as HTMLInputElement).value.trim() || 'YMusicLite',
    })
  })
  document.getElementById('btn-check-update')?.addEventListener('click', async () => {
    updateChecking = true
    render()
    updateInfo = await checkAppUpdate()
    updateChecking = false
    if (!updateInfo) toast('Gagal cek update (repo belum ada / offline)')
    else if (updateInfo.isNewer) toast(`Update v${updateInfo.tag} tersedia`)
    else toast('Sudah versi terbaru')
    render()
  })
  document.getElementById('btn-download-apk')?.addEventListener('click', () => {
    if (updateInfo?.apkUrl) openRelease(updateInfo.apkUrl)
    else if (updateInfo?.htmlUrl) openRelease(updateInfo.htmlUrl)
  })
  document.getElementById('btn-open-release')?.addEventListener('click', () => {
    if (updateInfo?.htmlUrl) openRelease(updateInfo.htmlUrl)
    else openRelease(REPO_URL)
  })
  document.getElementById('btn-download-engine')?.addEventListener('click', async () => {
    const urlInput = document.getElementById('engine-url') as HTMLInputElement
    const url = urlInput?.value.trim()
    if (!url) {
      engineMsg = 'Error: isi URL dulu'
      render()
      return
    }
    engineBusy = true
    engineMsg = ''
    render()
    try {
      const meta = await downloadEngine(url)
      engineMsg = `Berhasil! Engine v${meta.version}`
      toast('Engine diunduh')
    } catch (err: any) {
      engineMsg = `Error: ${err?.message || 'gagal'}`
    } finally {
      engineBusy = false
      render()
    }
  })
  document.getElementById('btn-revert-engine')?.addEventListener('click', () => {
    revertToBuiltin()
    engineMsg = 'Kembali ke engine bawaan'
    toast('Engine bawaan')
    render()
  })
  document.getElementById('btn-check-scrapr')?.addEventListener('click', async () => {
    scraprBusy = true
    scraprMsg = ''
    render()
    try {
      scraprInfo = await checkScraprUpdate()
      scraprMsg = scraprInfo.isNewer
        ? `Update tersedia: v${scraprInfo.latest}`
        : `Sudah terbaru: v${scraprInfo.latest}`
      toast(scraprMsg)
    } catch (e: any) {
      scraprMsg = `Error: ${e?.message || 'gagal cek'}`
      toast(scraprMsg)
    } finally {
      scraprBusy = false
      render()
    }
  })

  document.getElementById('btn-update-scrapr')?.addEventListener('click', async () => {
    scraprBusy = true
    scraprMsg = 'Mengunduh dari npm / GitHub...'
    render()
    try {
      const meta = await updateScraprFromOfficial()
      scraprInfo = await checkScraprUpdate().catch(() => scraprInfo)
      scraprMsg = `Berhasil update ke v${meta.version} (${meta.source})`
      toast(`Scraper v${meta.version} terpasang`)
    } catch (e: any) {
      scraprMsg = `Error: ${e?.message || 'gagal update'}`
      toast(scraprMsg)
    } finally {
      scraprBusy = false
      render()
    }
  })

  document.getElementById('btn-revert-scrapr')?.addEventListener('click', () => {
    revertScraprToBuiltin()
    scraprInfo = null
    scraprMsg = 'Kembali ke engine bawaan'
    toast('Scraper dikembalikan ke bawaan')
    render()
  })

  document.getElementById('btn-open-scrapr-repo')?.addEventListener('click', () => {
    openRelease(SCRAPR_LINKS.repo)
  })
}

function openSheet(track: Track) {
  const overlay = document.getElementById('sheet-overlay')!
  const title = document.getElementById('sheet-title')!
  const actions = document.getElementById('sheet-actions')!
  title.textContent = track.title
  actions.innerHTML = `
    <button class="sheet-btn" data-action="play">${icon('play')} Putar</button>
    <button class="sheet-btn" data-action="library">${icon('library')} ${library.some((t) => t.id === track.id) ? 'Hapus Library' : 'Tambah Library'}</button>
    <button class="sheet-btn" data-action="mp3">${icon('download')} Download MP3</button>
    <button class="sheet-btn" data-action="mp4">${icon('download')} Download MP4</button>
  `
  actions.querySelectorAll('.sheet-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      const action = (btn as HTMLElement).dataset.action
      if (action === 'play') {
        player.play(track)
        toast(`Memutar: ${track.title}`)
      } else if (action === 'library') {
        const idx = library.findIndex((t) => t.id === track.id)
        if (idx >= 0) {
          library.splice(idx, 1)
          toast('Dihapus')
        } else {
          library.unshift(track)
          toast('Ditambah')
        }
        saveLibrary()
      } else if (action === 'mp3' || action === 'mp4') {
        downloads.enqueue(track, action)
        toast(`Download ${action.toUpperCase()}`)
      }
      closeSheet()
      render()
    })
  })
  overlay.classList.add('open')
}
function closeSheet() {
  document.getElementById('sheet-overlay')?.classList.remove('open')
}

let lastSpatialTitle = ''
player.subscribe(() => {
  const progress = document.getElementById('progress-bar')
  const state = player.getState()
  if (progress && state.duration) {
    progress.style.width = `${(state.currentTime / state.duration) * 100}%`
  }
  const btn = document.getElementById('btn-play-pause')
  if (btn) btn.innerHTML = state.playing ? icon('pause') : icon('play')
  if (state.track && state.track.title !== lastSpatialTitle) {
    lastSpatialTitle = state.track.title
    spatialSetNowPlaying(state.track.title, state.track.artist)
  }
})

// boot
render()

// auto check update
const bootSettings = loadSettings()
if (bootSettings.autoCheckAppUpdate) {
  checkAppUpdate().then((info) => {
    if (info?.isNewer) {
      updateInfo = info
      toast(`Update v${info.tag} tersedia — buka Settings`)
    }
  })
}

console.log(`[YMusic Lite] v${APP_VERSION} · engine ${ENGINE_VERSION} · scraper ${SCRAPER_VERSION}`)
