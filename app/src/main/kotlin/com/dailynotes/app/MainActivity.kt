package com.dailynotes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dailynotes.app.ui.HomeScreen
import com.dailynotes.app.ui.MeScreen
import com.dailynotes.app.ui.NotesScreen
import com.dailynotes.app.ui.SettingsScreen
import com.dailynotes.app.ui.theme.DailyNotesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DailyNotesTheme {
                AppScaffold()
            }
        }
    }
}

private enum class Tab(val label: String) {
    HOME("主页"), NOTES("笔记"), SETTINGS("设置"), ME("我")
}

@Composable
private fun AppScaffold() {
    var current by rememberSaveable { mutableStateOf(Tab.HOME) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { current = tab },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    Tab.HOME -> Icons.Filled.Home
                                    Tab.NOTES -> Icons.Filled.Edit
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                    Tab.ME -> Icons.Filled.Person
                                },
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (current) {
                Tab.HOME -> HomeScreen()
                Tab.NOTES -> NotesScreen()
                Tab.SETTINGS -> SettingsScreen()
                Tab.ME -> MeScreen()
            }
        }
    }
}
