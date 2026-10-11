package de.homevoice.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.homevoice.assistant.service.AssistantService
import de.homevoice.assistant.settings.Settings
import de.homevoice.assistant.ui.HomeScreen
import de.homevoice.assistant.ui.SettingsScreen
import de.homevoice.assistant.ui.theme.HomeVoiceTheme
import kotlinx.coroutines.delay
import java.time.LocalTime

class MainActivity : ComponentActivity() {

    private val container get() = (application as App).container

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AssistantService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        setContent {
            HomeVoiceTheme {
                val settings by container.settings.settings.collectAsStateWithLifecycle()
                val status by container.controller.status.collectAsStateWithLifecycle()
                val timers by container.timerManager.timers.collectAsStateWithLifecycle()
                val ringing by container.timerManager.ringing.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var rescan by remember { mutableStateOf(0) }

                val photoVersion by container.photos.version.collectAsStateWithLifecycle()
                val photos by produceState(emptyList<Uri>(), settings.photoTreeUri, rescan, photoVersion) {
                    value = container.photos.scan(settings.photoTreeUri)
                }
                LaunchedEffect(settings.photoTreeUri) {
                    while (true) {
                        delay(PHOTO_RESCAN_MS)
                        rescan++
                    }
                }

                var night by remember { mutableStateOf(false) }
                var webUrl by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(settings.nightModeEnabled, settings.nightStartHour, settings.nightEndHour) {
                    while (true) {
                        webUrl = container.webServer.url()
                        night = isNight(settings)
                        applyBrightness(night)
                        delay(30_000)
                    }
                }

                BackHandler { if (showSettings) showSettings = false } // launcher: back does nothing on home

                if (showSettings) {
                    SettingsScreen(
                        initial = settings,
                        status = status,
                        photoCount = photos.size,
                        setupHint = setupHint(webUrl),
                        onSave = { saved -> container.settings.update { saved } },
                        onTestHomeAssistant = { container.controller.refreshHomeAssistantTools() },
                        onClose = { showSettings = false; hideSystemBars() },
                        onOpenSystemSettings = { startActivity(Intent(AndroidSettings.ACTION_SETTINGS)) },
                        onChooseLauncher = { startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS)) },
                    )
                } else {
                    HomeScreen(
                        settings = settings,
                        status = status,
                        photos = photos,
                        timers = timers,
                        ringing = ringing,
                        night = night,
                        setupHint = setupHint(webUrl).takeIf { settings.geminiApiKey.isBlank() },
                        onTap = { container.controller.toggle() },
                        onLongPress = { showSettings = true },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AssistantService.start(this)
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupHint(url: String?): String =
        "Einrichtung im Browser: ${url ?: "(kein WLAN)"} · PIN ${container.settings.webPin}"

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyBrightness(night: Boolean) {
        val target = if (night) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window.attributes.screenBrightness != target) {
            window.attributes = window.attributes.apply { screenBrightness = target }
        }
    }

    private fun isNight(s: Settings): Boolean {
        if (!s.nightModeEnabled || s.nightStartHour == s.nightEndHour) return false
        val hour = LocalTime.now().hour
        return if (s.nightStartHour < s.nightEndHour) hour in s.nightStartHour until s.nightEndHour
        else hour >= s.nightStartHour || hour < s.nightEndHour
    }

    private companion object {
        const val PHOTO_RESCAN_MS = 60 * 60_000L
    }
}
