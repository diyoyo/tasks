package org.tasks

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.JobIntentService
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.work.Configuration
import com.mikepenz.iconics.Iconics
import com.todoroo.astrid.service.Upgrader
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tasks.billing.Inventory
import org.tasks.caldav.CaldavSynchronizer
import org.tasks.icons.OutlinedGoogleMaterial
import org.tasks.icons.OutlinedGoogleMaterial2
import org.tasks.injection.InjectingJobIntentService
import org.tasks.jobs.WorkManager
import org.tasks.location.GeofenceApi
import org.tasks.opentasks.OpenTaskContentObserver
import org.tasks.preferences.Preferences
import org.tasks.receivers.RefreshReceiver
import org.tasks.scheduling.NotificationSchedulerIntentService
import org.tasks.themes.ThemeBase
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import org.tasks.widget.AppWidgetManager
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class Tasks : Application(), Configuration.Provider {

    @Inject @ApplicationContext lateinit var context: Context
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var buildSetup: BuildSetup
    @Inject lateinit var inventory: Inventory
    @Inject lateinit var localBroadcastManager: LocalBroadcastManager
    @Inject lateinit var upgrader: Lazy<Upgrader>
    @Inject lateinit var workManager: Lazy<WorkManager>
    @Inject lateinit var geofenceApi: Lazy<GeofenceApi>
    @Inject lateinit var appWidgetManager: Lazy<AppWidgetManager>
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var contentObserver: Lazy<OpenTaskContentObserver>

    override fun onCreate() {
        super.onCreate()
        buildSetup.setup()
        upgrade()
        preferences.isSyncOngoing = false
        ThemeBase.getThemeBase(preferences, inventory, null).setDefaultNightMode()
        localBroadcastManager.registerRefreshReceiver(RefreshBroadcastReceiver())
        backgroundWork()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    localBroadcastManager.broadcastRefresh()
                    if (currentTimeMillis() - preferences.lastSync > TimeUnit.MINUTES.toMillis(5)) {
                        owner.lifecycle.coroutineScope.launch {
                            workManager.get().sync(true)
                        }
                    }
                }

                override fun onPause(owner: LifecycleOwner) {
                    owner.lifecycle.coroutineScope.launch {
                        workManager.get().startEnqueuedSync()
                    }
                }
            }
        )
    }

    private fun upgrade() {
        val lastVersion = preferences.lastSetVersion
        val currentVersion = BuildConfig.VERSION_CODE
        Timber.i("Astrid Startup. %s => %s", lastVersion, currentVersion)

        // invoke upgrade service
        if (lastVersion != currentVersion) {
            upgrader.get().upgrade(lastVersion, currentVersion)
            preferences.setDefaults()
        }
    }

    private fun backgroundWork() = CoroutineScope(Dispatchers.Default).launch {
        Iconics.registerFont(OutlinedGoogleMaterial)
        Iconics.registerFont(OutlinedGoogleMaterial2)
        inventory.updateTasksAccount()
        NotificationSchedulerIntentService.enqueueWork(context)
        workManager.get().apply {
            updateBackgroundSync()
            scheduleBackup()
            scheduleConfigRefresh()
            updatePurchases()
            scheduleRefresh()
        }
        OpenTaskContentObserver.registerObserver(context, contentObserver.get())
        geofenceApi.get().registerAll()
        appWidgetManager.get().reconfigureWidgets()
        CaldavSynchronizer.registerFactories()
    }

    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        localBroadcastManager.reconfigureWidgets()
    }

    private class RefreshBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            JobIntentService.enqueueWork(
                    context,
                    RefreshReceiver::class.java,
                    InjectingJobIntentService.JOB_ID_REFRESH_RECEIVER,
                    intent)
        }
    }

    companion object {
        @Suppress("KotlinConstantConditions")
        const val IS_GOOGLE_PLAY = BuildConfig.FLAVOR == "googleplay"
        @Suppress("KotlinConstantConditions")
        const val IS_GENERIC = BuildConfig.FLAVOR == "generic"
    }
}