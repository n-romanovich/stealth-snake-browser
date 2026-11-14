package com.nromanovich.stealthsnake

import android.app.Application

/**
 * Application entry point registering app-wide PIN foreground/background lock enforcement
 * across all browser and utility activities.
 */
class SnakeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PinLockCoordinator.ensureRegistered(this)
    }
}
