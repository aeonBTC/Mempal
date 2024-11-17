package com.example.mempal

import androidx.multidex.MultiDexApplication
import com.example.mempal.api.NetworkClient
import com.example.mempal.tor.TorManager

class MempalApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        NetworkClient.initialize(this)
        TorManager.getInstance().initialize(this)
    }
}
