package com.hank.flow.open

import android.app.Application
import com.hank.flow.open.history.HistoryStore
import com.hank.flow.open.log.OpenFlowLog
import java.io.File

class OpenFlowApp : Application() {

    val historyStore: HistoryStore by lazy {
        HistoryStore(File(filesDir, "history"))
    }

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
