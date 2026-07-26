package uk.co.cinema.splmeter

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import uk.co.cinema.splmeter.data.Prefs
import uk.co.cinema.splmeter.ui.CalibrationScreen
import uk.co.cinema.splmeter.ui.HistoryScreen
import uk.co.cinema.splmeter.ui.MeterCalibrationScreen
import uk.co.cinema.splmeter.ui.RecordScreen
import uk.co.cinema.splmeter.ui.ReportScreen
import uk.co.cinema.splmeter.ui.SettingsScreen
import uk.co.cinema.splmeter.ui.SplTheme

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissions.launch(needed.toTypedArray())

        setContent {
            SplTheme {
                val settings by Prefs.state.collectAsState()
                KeepScreenOn(settings.keepScreenOn)
                Root()
            }
        }
    }

    @Composable
    private fun KeepScreenOn(enabled: Boolean) {
        androidx.compose.runtime.LaunchedEffect(enabled) {
            if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("record", "Record", Icons.Default.GraphicEq),
    Tab("history", "History", Icons.Default.History),
    Tab("calibration", "Cal", Icons.Default.Tune),
    Tab("settings", "Settings", Icons.Default.Settings)
)

@Composable
fun Root() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "record", modifier = Modifier.padding(padding)) {
            composable("record") { RecordScreen(onOpenReport = { nav.navigate("report/$it") }) }
            composable("history") { HistoryScreen(onOpen = { nav.navigate("report/$it") }) }
            composable("report/{id}") { backStack ->
                ReportScreen(
                    sessionId = backStack.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() }
                )
            }
            composable("calibration") {
                CalibrationScreen(onCalibrateLevel = { nav.navigate("calibrate-level") })
            }
            composable("calibrate-level") {
                MeterCalibrationScreen(onBack = { nav.popBackStack() })
            }
            composable("settings") { SettingsScreen() }
        }
    }
}
