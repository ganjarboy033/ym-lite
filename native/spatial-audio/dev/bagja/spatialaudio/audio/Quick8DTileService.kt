package dev.bagja.spatialaudio.audio

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.bagja.spatialaudio.data.MixerRepository
import dev.bagja.spatialaudio.data.UniversalAudioMode

/**
 * Quick Settings Tile di Panel Notifikasi Android untuk menyalakan/mematikan
 * efek 8D & Global System EQ Laguku kapan saja dari aplikasi mana pun.
 * Karya DEV BAGJA / KruzenLab (bagja.dev).
 */
class Quick8DTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        MixerRepository.init(this)
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        MixerRepository.init(this)
        val cur = MixerRepository.settings.value
        val nextEnabled = !(cur.enabled && (MixerRepository.globalEqActive.value || MixerRepository.universalCaptureActive.value))

        if (nextEnabled) {
            MixerRepository.update { it.copy(enabled = true) }
            if (!MixerRepository.universalCaptureActive.value) {
                SystemGlobalEqManager.setEnabled(true, MixerRepository.settings.value)
            }
        } else {
            if (MixerRepository.universalCaptureActive.value) {
                Universal8DService.stop(this)
            }
            SystemGlobalEqManager.setEnabled(false)
            MixerRepository.update { it.copy(enabled = false) }
        }
        syncTile()
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val isActive = MixerRepository.settings.value.enabled &&
            (MixerRepository.universalCaptureActive.value || MixerRepository.globalEqActive.value)
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Laguku 8D"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (MixerRepository.universalMode.value) {
                UniversalAudioMode.CAPTURE_8D -> "Capture 8D Aktif"
                UniversalAudioMode.GLOBAL_EQ -> "Global EQ Aktif"
                UniversalAudioMode.OFF -> if (MixerRepository.settings.value.enabled) "Internal 8D Siap" else "Mati"
            }
        }
        tile.updateTile()
    }
}
