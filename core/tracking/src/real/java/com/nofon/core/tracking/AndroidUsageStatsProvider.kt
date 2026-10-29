package com.nofon.core.tracking

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import com.nofon.core.tracking.domain.PermissionState
import com.nofon.core.tracking.domain.RawEventType
import com.nofon.core.tracking.domain.RawUsageEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Implémentation réelle de [TrackingProvider], adaptée du spike Android
 * (`nofon-spike/app/src/main/java/com/nofon/spike/data/AndroidUsageSource.kt`).
 *
 * Les codes d'événements sont mappés par entier plutôt que par constante nommée :
 * certaines constantes de `UsageEvents.Event` ne sont pas publiques à tous les
 * niveaux d'API.
 */
class AndroidUsageStatsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : TrackingProvider {

    private val usageStatsManager: UsageStatsManager
        get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    override fun permissionState(): PermissionState = PermissionState(
        usageAccessGranted = hasUsageAccess(),
        checkedAtMillis = System.currentTimeMillis()
    )

    override fun hasUsageAccess(): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    override fun usageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun queryEvents(beginMillis: Long, endMillis: Long): TrackingProvider.QueryOutcome {
        if (!hasUsageAccess()) return TrackingProvider.QueryOutcome.NoPermission

        return try {
            val raw = usageStatsManager.queryEvents(beginMillis, endMillis)
                ?: return TrackingProvider.QueryOutcome.NullReturned

            val out = ArrayList<RawUsageEvent>(1024)
            val event = UsageEvents.Event()
            while (raw.hasNextEvent()) {
                raw.getNextEvent(event)
                out += RawUsageEvent(
                    timestampMillis = event.timeStamp,
                    type = mapEventType(event.eventType),
                    packageName = event.packageName,
                    rawType = event.eventType,
                    className = event.className
                )
            }
            TrackingProvider.QueryOutcome.Success(out)
        } catch (e: Exception) {
            TrackingProvider.QueryOutcome.Failure(e.message ?: e::class.java.simpleName)
        }
    }

    private fun mapEventType(rawType: Int): RawEventType = when (rawType) {
        1 -> RawEventType.ACTIVITY_RESUMED        // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
        2 -> RawEventType.ACTIVITY_PAUSED         // ACTIVITY_PAUSED  / MOVE_TO_BACKGROUND
        15 -> RawEventType.SCREEN_INTERACTIVE
        16 -> RawEventType.SCREEN_NON_INTERACTIVE
        17 -> RawEventType.KEYGUARD_SHOWN
        18 -> RawEventType.KEYGUARD_HIDDEN
        23 -> RawEventType.ACTIVITY_STOPPED
        26 -> RawEventType.DEVICE_SHUTDOWN
        27 -> RawEventType.DEVICE_STARTUP
        else -> RawEventType.OTHER
    }

    override fun launchableApps(): List<TrackingProvider.InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                TrackingProvider.InstalledApp(pkg, resolveInfo.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override fun labelFor(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }
}
