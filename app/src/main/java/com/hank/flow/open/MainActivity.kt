package com.hank.flow.open

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hank.flow.open.ui.debug.DebugScreen
import com.hank.flow.open.ui.home.HomeScreen
import com.hank.flow.open.ui.modeldownload.ModelDownloadScreen
import com.hank.flow.open.ui.settings.SettingsScreen
import com.hank.flow.open.ui.theme.OpenFlowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenFlowTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.Home) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Text(entry.icon) },
                        label = { Text(entry.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (tab) {
                Tab.Home -> HomeScreen()
                Tab.Models -> ModelDownloadScreen()
                Tab.Debug -> DebugScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}

private enum class Tab(val title: String, val icon: String) {
    Home("主页", "🏠"),
    Models("模型", "📦"),
    Debug("调试", "🛠"),
    Settings("设置", "⚙️"),
}
