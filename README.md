# YMusic Lite — All Downloader

App **ringan** Capacitor + Vite yang menggabungkan ide:

- **YMusic** → music client + engine yang bisa di-update
- **scrapr** → universal media extractor (TikTok, YT, IG, X, Spotify, …)
- **Mori** → all-downloader UI + update dari GitHub Releases

## Fitur

| Fitur | Status |
|-------|--------|
| Paste URL → deteksi platform → scrape | ✅ |
| YouTube / TikTok / IG / X / Spotify / … | ✅ deteksi (resolve stream penuh via engine update) |
| YouTube IFrame player in-app | ✅ |
| Library + Riwayat | ✅ |
| Download MP3/MP4 + Capacitor Filesystem | ✅ arsitektur |
| **Update app dari GitHub Releases** | ✅ (seperti Mori) |
| **Update engine/scraper dari URL** | ✅ unduh + revert ke bawaan |
| Cek update scrapr (npm/GitHub) | ✅ |

## Tabs

1. **Download** — halaman utama: tempel link apa saja
2. **Search** — cari YouTube
3. **Library** — koleksi tersimpan
4. **Riwayat** — hasil scrape sebelumnya
5. **Settings** — update app, update engine, folder download

## Update seperti Mori

**App update**
- Settings → **Cek Update**
- Membaca `https://api.github.com/repos/<repo>/releases/latest`
- Jika ada APK → tombol unduh
- Checkbox “Cek update otomatis saat buka app”

**Engine / Scraper update**
- Isi URL bundle (GitHub raw / CDN)
- **Unduh Engine** → disimpan lokal
- **Kembalikan Bawaan** kapan saja
- **Cek update scrapr** → buka release scrapr di GitHub

> Ganti `GITHUB_REPO` di `src/lib/updater.ts` ke repo kamu sendiri agar update APK berfungsi.

## Engine bawaan vs penuh

Engine bawaan **sengaja minimal** (metadata + deteksi platform).  
Resolve stream langsung (TikTok no-watermark, YT mp3, dll) butuh bundle yang sering di-update oleh developer — persis alasan Mori/scrapr punya sistem update.

Alur yang disarankan:
1. Developer update scrapr / port ke browser bundle
2. Host di GitHub Releases atau raw
3. User: Settings → paste URL → Unduh Engine
4. Tanpa rebuild APK

## Menjalankan

```bash
cd ymusic-lite
npm install
npm run dev
```

### Android

```bash
npm run build
npx cap add android
npx cap sync
npx cap open android
```

## Sumber

- scrapr: https://github.com/coflyn/scrapr (MIT)
- Mori: https://github.com/coflyn/Mori (GPL-3.0)
- YMusic V4: engine-js remote-update concept (GPL-3.0)

Kode UI & arsitektur Lite dibuat dari nol.  
Jika mem-port logika dari Mori/YMusic, patuhi lisensi masing-masing.


## Update scraper dari app (scrapr resmi)

Sumber: [github.com/coflyn/scrapr](https://github.com/coflyn/scrapr) · npm `@coflyn/scrapr`

Di **Settings → Engine / Scraper**:

1. **Cek Update scrapr** — baca versi terbaru dari npm registry (fallback: GitHub `package.json`)
2. **Update Sekarang** — unduh versi + bundle, simpan di app
3. **Kembalikan Bawaan** — hapus scraper custom
4. **Buka GitHub scrapr** — halaman repo

Tidak perlu rebuild APK. Dev publish versi baru di npm → user tekan Update di app.

File terkait: `src/lib/scraper-updater.ts`


## Spatial Audio (Kruzen 8D)

Modul **Kruzen Spatial Audio** terintegrasi sebagai tab **8D**:

- Pergerakan 8D, EQ 10-band, reverb, filter, preset ruang
- UI web (HTML/CSS/JS) di `src/spatial/`
- Native Android DSP di `native/spatial-audio/` (Kotlin)

### Android native

Setelah `npx cap add android`:

```bash
npm run copy:native   # salin source Kotlin ke android/
# Gabungkan AndroidManifest-snippet.xml & host bridge (lihat native/spatial-audio/integration/)
npx cap sync android
```

Di browser: UI penuh berfungsi (preview); DSP native hanya di Android.
