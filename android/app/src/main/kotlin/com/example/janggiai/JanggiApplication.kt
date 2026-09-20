package com.example.janggiai

import android.app.Application
import com.example.janggiai.di.AppContainer

class JanggiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTerminate() {
        container.engines.close()
        super.onTerminate()
    }
}
