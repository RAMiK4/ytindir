package com.deniscerri.ytdl.util

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import androidx.core.content.res.ResourcesCompat
import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.text.HtmlCompat
import androidx.core.text.parseAsHtml
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.google.android.material.color.DynamicColors


object ThemeUtil {

    private val activities = mutableListOf<Activity>()

    fun init(app: Application) {
        app.registerActivityLifecycleCallbacks(object: Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(p0: Activity, p1: Bundle?) {
                activities.add(p0)
            }

            override fun onActivityStarted(p0: Activity) {

            }

            override fun onActivityResumed(p0: Activity) {

            }

            override fun onActivityPaused(p0: Activity) {

            }

            override fun onActivityStopped(p0: Activity) {

            }

            override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {

            }

            override fun onActivityDestroyed(p0: Activity) {
                activities.remove(p0)
            }
        })

    }

    sealed class AppIcon(
        @StringRes val nameResource: Int,
        @DrawableRes val iconResource: Int,
        val activityAlias: String
    ) {
        object Default : AppIcon(R.string.auto, R.mipmap.ic_launcher, "Default")
        object Light : AppIcon(R.string.light, R.mipmap.ic_launcher_light, "LightIcon")
        object Dark : AppIcon(R.string.dark, R.mipmap.ic_launcher_dark, "DarkIcon")
        object Blue : AppIcon(R.string.blue, R.mipmap.ic_launcher_blue, "BlueIcon")
        object Green : AppIcon(R.string.green, R.mipmap.ic_launcher_green, "GreenIcon")
    }

    val availableIcons = listOf(
        AppIcon.Default,
        AppIcon.Light,
        AppIcon.Dark,
        AppIcon.Blue,
        AppIcon.Green,
    )

    fun recreateMain() {
        activities.firstOrNull { it.javaClass == MainActivity::class.java }?.recreate()
    }

    fun recreateAllActivities() {
        activities.forEach {
            it.recreate()
        }
    }

    fun updateThemes() {
        activities.forEach {
            updateTheme(it)
            it.recreate()
        }
    }

    fun updateTheme(activity: Activity) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)

        //update accent
        // YTindir: only our own tone and Material You. Old accent values fall back to our tone.
        if (sharedPreferences.getString("theme_accent", "ytindr") == "Default" && DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivityIfAvailable(activity)
            activity.setTheme(R.style.BaseTheme)
            // YTindir: keep the cards and dialogs only a shade off the background
            activity.theme.applyStyle(R.style.YTindir_DynamicSurfaces, true)
        } else {
            activity.setTheme(R.style.Theme_YTindir)
        }

        //high contrast theme
        if (sharedPreferences.getBoolean("high_contrast",false)) {
            activity.theme.applyStyle(R.style.Pure, true)
        }

        val theme = sharedPreferences.getString("ytdlnis_theme", "System")!!
        when (theme) {
            "Light" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
            "Dark" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            // or "System"
            else -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }


        val iconMode = sharedPreferences.getString("ytdlnis_icon", "Default")!!
        updateAppIcon(activity,theme, iconMode)
    }

    fun getThemeColor(context: Context, colorCode: Int): Int {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val value = TypedValue()
        context.theme.resolveAttribute(colorCode, value, true)
        return value.data

    }

    /**
     * Get the styled app name
     */
    fun getStyledAppName(context: Context): Spanned {
        // YTindir logo: "YT" in the accent colour, set in the YTindir logo font
        val colorPrimary = getThemeColor(context, androidx.appcompat.R.attr.colorPrimary)
        val text = SpannableStringBuilder("YTindir")
        text.setSpan(ForegroundColorSpan(colorPrimary), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        getDisplayTypeface(context)?.let {
            text.setSpan(DisplayTypefaceSpan(it), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    /** YTindir logo font: Archivo Expanded Black, with the "i" dot and "d" lined up to the capitals. Only holds the logo letters. */
    fun getDisplayTypeface(context: Context): Typeface? =
        runCatching { ResourcesCompat.getFont(context, R.font.ytindr_logo) }.getOrNull()

    private class DisplayTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.typeface = typeface }
        override fun updateMeasureState(tp: TextPaint) { tp.typeface = typeface }
    }


    // YTindir: the green icon is the only launcher icon. Components are only touched
    // when their state is wrong, so the launcher shortcut doesn't flicker on every start.
    @Suppress("UNUSED_PARAMETER")
    fun updateAppIcon(activity: Activity, theme: String, appIconMode: String) {
        val pm = activity.packageManager
        for (appIcon in availableIcons) {
            val component = ComponentName(activity.packageName, "com.deniscerri.ytdl." + appIcon.activityAlias)
            val wanted = if (appIcon == AppIcon.Green) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(component) != wanted) {
                pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }
}