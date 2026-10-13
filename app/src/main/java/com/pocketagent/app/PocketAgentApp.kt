package com.pocketagent.app

import android.app.Application
import com.pocketagent.app.data.SettingsRepository

class PocketAgentApp : Application() {
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
    }
}
