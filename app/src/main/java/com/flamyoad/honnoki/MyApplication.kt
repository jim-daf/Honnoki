package com.flamyoad.honnoki

import android.app.Application
import android.os.Build
import android.os.Process
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import kotlin.system.exitProcess
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

        installWebViewMultiProcessGuard()
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

    /**
     * Belt-and-suspenders for https://crbug.com/558377. The data-directory suffix
     * above prevents the collision when two processes have *different* names, but
     * the same `RuntimeException` can also be triggered by:
     *
     *   - a stale data-dir lock left over from a previously-killed instance of
     *     this same process (the lock owner PID is gone but the file lock remains);
     *   - the system swapping the WebView provider implementation while we're
     *     running, causing two AwBrowserProcess attempts in quick succession.
     *
     * In both cases the suffix can't help. Letting the exception propagate kills
     * the app with a user-visible "App keeps stopping" dialog and a Crashlytics
     * report (issue #45). Instead we install a fallback handler that recognises
     * this exact exception, logs it, and silently terminates the current process.
     * Android's ActivityManager will restart any foreground component cleanly,
     * by which time the lock will have been released by the OS.
     *
     * The handler is intentionally narrow — it only swallows the WebView
     * multi-process exception. Every other crash is forwarded to the previous
     * default handler (Crashlytics, etc.) untouched.
     */
    private fun installWebViewMultiProcessGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isWebViewMultiProcessCrash(throwable)) {
                Timber.w(
                    throwable,
                    "Swallowing WebView multi-process data-dir crash; killing pid %d to release the lock",
                    Process.myPid()
                )
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } else {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun isWebViewMultiProcessCrash(throwable: Throwable?): Boolean {
        var t: Throwable? = throwable
        while (t != null) {
            val msg = t.message
            if (t is RuntimeException &&
                msg != null &&
                msg.contains("Using WebView from more than one process")
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }
}