package com.hank.flow.open

import android.app.Application

class OpenFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: OpenFlowApp
            private set
    }
}
