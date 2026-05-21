package com.hank.flow.open

import android.app.Application
import com.hank.flow.open.log.OpenFlowLog

class OpenFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        OpenFlowLog.init(this)
        OpenFlowLog.d(OpenFlowLog.Tag.APP, "app_create")
    }

    companion object {
        lateinit var instance: OpenFlowApp
            private set
    }
}
