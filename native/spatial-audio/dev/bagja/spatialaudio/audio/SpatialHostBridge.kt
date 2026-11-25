package dev.bagja.spatialaudio.audio

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hubungkan kontrol overlay dan Universal Capture ke pemutar milik aplikasi host.
 * Set callback ini dari Application/PlaybackService milik aplikasi host.
 */
object SpatialHostBridge {
    @Volatile var onTogglePlayback: (() -> Unit)? = null
    @Volatile var onPausePlayback: (() -> Unit)? = null
    @Volatile var onSharedText: ((String) -> Unit)? = null

    /** Baris lirik aktif; host boleh memperbaruinya saat posisi lagu bergerak. */
    val currentLyrics = MutableStateFlow<String?>(null)
    val currentTitle = MutableStateFlow<String?>(null)
}
