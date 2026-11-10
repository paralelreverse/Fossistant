package org.fossistant.assistant.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import org.fossistant.assistant.R
import org.fossistant.assistant.model.BlobMoodTheme

/**
 * Dynamically updates the Android Launcher Icon (<activity-alias>) and Recent-Apps Task Description
 * whenever the user changes the [BlobMoodTheme] color style or custom AI Name in Settings.
 */
object AppIconSwitcher {

    private val themeToAliasMap: Map<BlobMoodTheme, String> = mapOf(
        BlobMoodTheme.COSMIC_NEBULA to "org.fossistant.assistant.ui.AliasCosmicNebula",
        BlobMoodTheme.CRIMSON_EMBER to "org.fossistant.assistant.ui.AliasCrimsonEmber",
        BlobMoodTheme.EMERALD_AURORA to "org.fossistant.assistant.ui.AliasEmeraldAurora",
        BlobMoodTheme.SOLAR_GOLD to "org.fossistant.assistant.ui.AliasSolarGold",
        BlobMoodTheme.CYBER_ICE to "org.fossistant.assistant.ui.AliasCyberIce",
        BlobMoodTheme.MIDNIGHT_ORCHID to "org.fossistant.assistant.ui.AliasMidnightOrchid"
    )

    fun drawableResForTheme(theme: BlobMoodTheme): Int = when (theme) {
        BlobMoodTheme.COSMIC_NEBULA -> R.drawable.ic_launcher_cosmic_nebula
        BlobMoodTheme.CRIMSON_EMBER -> R.drawable.ic_launcher_crimson_ember
        BlobMoodTheme.EMERALD_AURORA -> R.drawable.ic_launcher_emerald_aurora
        BlobMoodTheme.SOLAR_GOLD -> R.drawable.ic_launcher_solar_gold
        BlobMoodTheme.CYBER_ICE -> R.drawable.ic_launcher_cyber_ice
        BlobMoodTheme.MIDNIGHT_ORCHID -> R.drawable.ic_launcher_midnight_orchid
    }

    /**
     * Enables the launcher `<activity-alias>` corresponding to [selectedTheme] and disables the others
     * with [PackageManager.DONT_KILL_APP] so the home-screen app icon updates to match the blob's color style.
     */
    fun applyLauncherIconForTheme(context: Context, selectedTheme: BlobMoodTheme) {
        runCatching {
            val pm = context.packageManager
            val pkg = context.packageName
            themeToAliasMap.forEach { (theme, aliasClassName) ->
                val component = ComponentName(pkg, aliasClassName)
                val targetState = if (theme == selectedTheme) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                if (pm.getComponentEnabledSetting(component) != targetState) {
                    pm.setComponentEnabledSetting(
                        component,
                        targetState,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }
    }

    /**
     * Updates the Activity title and Android Recents / Task switcher label & icon when the user
     * changes the AI's name or blob color theme.
     */
    fun applyDynamicAppNameAndTaskDescription(
        activity: Activity,
        aiName: String,
        theme: BlobMoodTheme
    ) {
        val cleanName = aiName.trim().ifEmpty { "Fossistant" }
        runCatching {
            activity.title = cleanName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val desc = ActivityManager.TaskDescription.Builder()
                    .setLabel(cleanName)
                    .setIcon(drawableResForTheme(theme))
                    .build()
                activity.setTaskDescription(desc)
            } else {
                @Suppress("DEPRECATION")
                val bmp = BitmapFactory.decodeResource(activity.resources, drawableResForTheme(theme))
                @Suppress("DEPRECATION")
                activity.setTaskDescription(ActivityManager.TaskDescription(cleanName, bmp))
            }
        }
    }
}
