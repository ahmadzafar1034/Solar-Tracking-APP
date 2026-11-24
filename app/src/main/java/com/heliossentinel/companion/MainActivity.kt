package com.heliossentinel.companion

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.heliossentinel.companion.notifications.AlertNotifier
import com.heliossentinel.companion.service.MonitorService
import com.heliossentinel.companion.ui.navigation.HeliosNavGraph
import com.heliossentinel.companion.ui.theme.HeliosSentinelTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result read on demand */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as HeliosApp).trackerRepository

        askForNotificationsOnce()

        // Start/stop the background monitoring service to match the setting.
        // Done only while the activity is visible, which Android requires for foreground-service starts.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsFlow.map { it.backgroundMonitoring }.distinctUntilChanged().collect { enabled ->
                    if (enabled) MonitorService.start(this@MainActivity) else MonitorService.stop(this@MainActivity)
                }
            }
        }

        setContent {
            HeliosSentinelTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HeliosNavGraph(repository = repository)
                }
            }
        }
    }

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || AlertNotifier.canPost(this)) return
        val prefs = getSharedPreferences("helios_flags", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
