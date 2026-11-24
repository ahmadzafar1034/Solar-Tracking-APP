package com.heliossentinel.companion

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.heliossentinel.companion.data.local.HistoryStore
import com.heliossentinel.companion.data.prefs.SettingsRepository
import com.heliossentinel.companion.data.repository.TrackerRepository
import com.heliossentinel.companion.notifications.AlertNotifier

/**
 * Application class acting as a minimal, dependency-free service locator.
 * Also tells the repository when the app is on screen, so polling pauses
 * in the background unless background monitoring is switched on.
 */
class HeliosApp : Application() {

    lateinit var trackerRepository: TrackerRepository
        private set

    override fun onCreate() {
        super.onCreate()
        trackerRepository = TrackerRepository(
            context = this,
            historyStore = HistoryStore(this),
            settingsRepository = SettingsRepository(this),
            notifier = AlertNotifier(this)
        )

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = trackerRepository.setUiVisible(true)
            override fun onStop(owner: LifecycleOwner) = trackerRepository.setUiVisible(false)
        })
    }
}
