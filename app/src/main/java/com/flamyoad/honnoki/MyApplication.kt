package com.flamyoad.honnoki

import android.app.Application
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import com.flamyoad.honnoki.data.preference.UiPreference
import com.flamyoad.honnoki.di.*
import com.github.venom.Venom
import com.github.venom.service.NotificationConfig
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import timber.log.Timber

class MyApplication : Application() {

    private val uiPrefs: UiPreference by inject()

    val applicationScope = MainScope()

    override fun onCreate() {
        super.onCreate()

        applyWebViewDataDirectorySuffix()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        initializeVenom()

        val appModules = listOf(
            apiModules,
            cacheModules,
            dbModules,
            networkModules,
            sourceModules,
            repositoryModules,
            scopeModules,
            preferenceModules,
            viewModelModules
        )

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@MyApplication)
            modules(appModules)
        }

        applicationScope.launch {
            val nightModeEnabled = uiPrefs.nightModeEnabled.firstOrNull() ?: return@launch
            if (nightModeEnabled) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    private fun initializeVenom() {
        val venom = Venom.createInstance(this)

        val notification = NotificationConfig.Builder(this)
            .buttonCancel("Cancel")
            .buttonKill("Kill")
            .build()
        venom.initialize(notification)
        Venom.setGlobalInstance(venom)
    }

    /**
     * Workaround for https://crbug.com/558377 — when the same Android app is launched
     * in more than one process (e.g. an isolated `:remote` service started by a third
     * party SDK), the second WebView initialization in the same data directory throws:
     *
     *   java.lang.RuntimeException: Using WebView from more than one process at once
     *   with the same data directory is not supported.
     *
     * Setting a per-process suffix before any WebView is touched gives each process
     * its own data directory and avoids the crash. Available since API 28 (P).
     *
     * Tracking issue: https://github.com/flamyoad/Honnoki/issues/45
     */
    private fun applyWebViewDataDirectorySuffix() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val processName = getProcessName() ?: return
        if (packageName != processName) {
            try {
                WebView.setDataDirectorySuffix(processName)
            } catch (t: Throwable) {
                Timber.w(t, "Failed to set WebView data directory suffix for %s", processName)
            }
        }
    }
}