package com.photostamp.app

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap starts or stops logging. */
class LoggingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (Control.loggingOn || Prefs(this).loggingDesired) {
            Control.stopLogging(this, byUser = true)
            AppLog.log("Logging stopped from the tile.")
        } else {
            Control.startLogging(this, auto = false)
        }
        refresh()
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 800)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Control.loggingOn || Prefs(this).loggingDesired
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "PhotoStamp"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            tile.subtitle = if (on) (Live.triggerReason ?: "Logging") else "Off"
        tile.updateTile()
    }
}
