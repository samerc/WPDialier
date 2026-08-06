package com.fancyshark.wpdialer

import android.app.Application
import com.fancyshark.wpdialer.data.CrashLog

class WpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
