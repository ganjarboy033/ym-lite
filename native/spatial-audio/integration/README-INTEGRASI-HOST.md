# Integrasi ke aplikasi pemutar host

Modul Capacitor ini menyediakan UI + native DSP, tetapi **tidak menggantikan player host**. Agar efek terdengar, host wajib memasang `MixerProcessor` pada pipeline PCM Media3/ExoPlayer yang benar-benar memutar audio.

## 1. Pasang processor pada DefaultAudioSink

API AudioSink dapat berubah sesuai versi Media3. Buat `DefaultRenderersFactory` khusus dan pasang `MixerProcessor` pada `DefaultAudioSink.DefaultAudioProcessorChain` (atau mekanisme audio processor chain yang tersedia pada versi Media3 proyek host). Jangan menambahkan processor hanya sebagai dependency: instance-nya harus menjadi bagian dari sink yang dipakai ExoPlayer.

Contoh konsep (sesuaikan constructor dengan versi Media3 host):

```kotlin
@OptIn(androidx.media3.common.util.UnstableApi::class)
class SpatialRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    private val mixer = MixerProcessor(context.applicationContext)
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(mixer))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
```

Buat ExoPlayer dengan `setRenderersFactory(SpatialRenderersFactory(context))`. Pastikan tidak mengganti konfigurasi sink yang sudah dibutuhkan host (audio attributes, tunneling, float output, offload). Bila host memakai custom sink, gabungkan `MixerProcessor` ke chain tersebut.

`MixerProcessor` sekarang membaca `MixerRepository.settings` langsung saat memproses blok PCM, jadi perubahan kontrol web akan diterapkan tanpa membuat ulang player.

## 2. Hubungkan kontrol floating dengan player

Salin `HostPlaybackBridge.kt.example`, ubah package, lalu panggil `bindSpatialHost(exoPlayer)` setelah player siap dan `unbindSpatialHost()` saat playback service dihancurkan. Panggil `updateSpatialLyrics(title, activeLine)` dari parser lirik host saat baris aktif berubah.

## 3. Custom impulse response

Tombol pemilih WAV memanggil Android Storage Access Framework (`ACTION_OPEN_DOCUMENT`), menyimpan izin baca URI persisten, dan menulis URI ke `MixerSettings.customIrUri`. `MixerEngine` memuat WAV melalui `PartitionedConvolver.loadWavIr` di worker executor. Tidak perlu izin penyimpanan luas.

## 4. Izin

Gabungkan `AndroidManifest-snippet.xml` ke Manifest host. Untuk Android 13+, minta POST_NOTIFICATIONS saat dibutuhkan. Untuk Universal Capture, Android akan menampilkan consent MediaProjection; hanya audio aplikasi yang mengizinkan playback capture yang dapat diambil. Floating Bubble membutuhkan izin tampil di atas aplikasi lain.
