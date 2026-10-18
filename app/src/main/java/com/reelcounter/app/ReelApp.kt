package com.reelcounter.app

import android.app.Application

class ReelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReelRepository.init(this)
        AppSettings.init(this)
    }
}
