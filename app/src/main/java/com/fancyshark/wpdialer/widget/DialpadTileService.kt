package com.fancyshark.wpdialer.widget

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fancyshark.wpdialer.MainActivity

/**
 * Quick Settings "dialpad" tile: opens the app straight on the dialpad.
 * A plain action tile — no state to toggle, so it always shows inactive.
 */
class DialpadTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // From the lock screen, unlock first — the dialpad isn't a
        // show-when-locked activity.
        if (isLocked) unlockAndRun { openDialpad() } else openDialpad()
    }

    // The Intent overload is only reached on API 33 (minSdk), where it's
    // the sole option — the PendingIntent one is 34+. Lint can't see the guard.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openDialpad() {
        val intent = Shortcuts.launchIntent(this, MainActivity.ACTION_SHORTCUT_DIALPAD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // 34+ throws on the Intent overload for apps targeting 34+.
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}
