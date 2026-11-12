package com.example.mempal

import android.app.Application
import com.example.mempal.api.NetworkClient
import com.example.mempal.tor.TorManager

class MempalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Tor must be initialized before NetworkClient so prefs/status are available.
        TorManager.getInstance().initialize(this)
        NetworkClient.initialize(this)
    }
}
