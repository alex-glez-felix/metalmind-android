package io.github.alexglezfelix.metalmind

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** El tile de Ajustes rápidos: un toque y se abre la captura. */
class CaptureTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        // Con el teléfono bloqueado, primero se desbloquea: la nota va al vault.
        if (isLocked) unlockAndRun(::open) else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(CaptureLaunch.pendingIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(CaptureLaunch.intent(this))
        }
    }
}
