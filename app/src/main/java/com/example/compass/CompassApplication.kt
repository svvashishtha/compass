package com.example.compass

import android.app.Application
import timber.log.Timber


class CompassApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}