package com.deniscerri.ytdl

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.core.models.ExecuteException
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.repository.ObserveSourcesRepository
import com.deniscerri.ytdl.services.BgUtilsPoTokenGeneratorService
import com.deniscerri.ytdl.util.ApkInstallUtil
import com.deniscerri.ytdl.util.BgUtilsPoTokenGeneratorUtil
import com.deniscerri.ytdl.util.Extensions.hasReachedEnd
import com.deniscerri.ytdl.util.NotificationUtil
import com.deniscerri.ytdl.util.ObserveAlarmScheduler
import com.deniscerri.ytdl.util.ThemeUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class App : Application() {

    /**
     * YTindir: the share menu's "Quick download" entry downloaded the last used choice in the background,
     * with nothing telling what it would be: turned off for those who had it on.
     */
    private fun disableQuickDownloadShare() {
        runCatching {
            val alias = android.content.ComponentName(this, "com.deniscerri.ytdl.quickDownloadShareAlias")
            if (packageManager.getComponentEnabledSetting(alias) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                packageManager.setComponentEnabledSetting(alias,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            }
            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("show_quick_download_share", false).apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val sharedPreferences =  PreferenceManager.getDefaultSharedPreferences(this@App)
        setDefaultValues()
        disableQuickDownloadShare()
        // YTindir: the format list's filter and its NewPipe format source are gone: back to every format, from yt-dlp
        PreferenceManager.getDefaultSharedPreferences(this).let { sp ->
            if (!sp.getBoolean("format_filter_reset", false)) {
                sp.edit().putString("format_filter", "ALL").putString("formats_source", "yt-dlp")
                    .putBoolean("format_filter_reset", true).apply()
            }
        }
        // YTindir: SponsorBlock was a hidden setting stored as on; it comes back off, for the user to turn on
        PreferenceManager.getDefaultSharedPreferences(this).let { sp ->
            if (!sp.getBoolean("sponsorblock_reset", false)) {
                sp.edit().putBoolean("use_sponsorblock", false).putBoolean("sponsorblock_reset", true).apply()
            }
        }
        // YTindir: the preferred format sizes and the format list's order are hidden settings now: back to their
        // defaults once, so a value set before doesn't keep changing which format is picked or how the list is sorted
        PreferenceManager.getDefaultSharedPreferences(this).let { sp ->
            if (!sp.getBoolean("format_size_order_reset", false)) {
                sp.edit().putString("preferred_format_size", "").putString("preferred_format_size_audio", "")
                    .putString("format_order", "filesize").putBoolean("format_size_order_reset", true).apply()
            }
        }
        ThemeUtil.init(this)

        val processName = getProcessNameImpl()
        if (processName.endsWith(":incognito_process") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            webViewDataSuffix = "incognito_store"
            WebView.setDataDirectorySuffix("incognito_store")
            return
        }
        // YTindir: the cookies browser runs in a process of its own, so its WebView can't put the
        // app's screens on the system's light/dark mode (it reset the resources' configuration)
        if (processName.endsWith(":cookies_process")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                webViewDataSuffix = "cookies_store"
                WebView.setDataDirectorySuffix("cookies_store")
            }
            return
        }

        applicationScope = CoroutineScope(SupervisorJob())
        applicationScope.launch((Dispatchers.IO)) {
            try {
                createNotificationChannels()
                initLibraries()

                val appVer = sharedPreferences.getString("version", "")!!
                if(appVer.isEmpty() || appVer != BuildConfig.VERSION_NAME){
                    sharedPreferences.edit(commit = true){
                        putString("version", BuildConfig.VERSION_NAME)
                    }
                }

                val db = DBManager.getInstance(this@App)
                val scheduler = ObserveAlarmScheduler(this@App)
                db.observeSourcesDao.getAllSources()
                    .filter { it.status == ObserveSourcesRepository.SourceStatus.ACTIVE && !it.hasReachedEnd() }
                    .forEach { scheduler.schedule(it) }         // idempotent: FLAG_UPDATE_CURRENT updates in place

            }catch (e: Exception){
                Looper.prepare().runCatching {
                    Toast.makeText(this@App, e.message, Toast.LENGTH_SHORT).show()
                }
                e.printStackTrace()
            }
        }
    }
    @Throws(ExecuteException::class)
    private fun initLibraries() {
        RuntimeManager.getInstance().init(this)
    }

    private fun setDefaultValues(){
        val SPL = 1
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        if (sp.getInt("spl", 0) != SPL) {
            PreferenceManager.setDefaultValues(this, R.xml.root_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.downloading_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.general_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.processing_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.folders_preference, true)
            PreferenceManager.setDefaultValues(this, R.xml.updating_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.ytdlp_update_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.app_update_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.advanced_preferences, true)
            // YTindir: recommended defaults for new installs (existing settings are never touched)
            sp.edit()
                .putString("ytdlnis_theme", "Dark")
                .putString("audio_format", "mp3")
                .putString("audio_codec", "opus")
                .putString("audio_bitrate", "192k")
                .putString("video_format", "mp4")
                .putString("video_codec", "avc|h264")
                .putString("video_quality", "1080p_ytdlnisgeneric")
                .putString("file_name_template", "%(title)s")
                .putString("file_name_template_audio", "%(title)s")
                .putString("navigation_bar", "0,3,1,2")
                .putString("ytdlp_source", "nightly")
                .putBoolean("use_cookies", true)
                .putBoolean("embed_lyrics", true)
                .putBoolean("embed_metadata", true)
                .putBoolean("embed_thumbnail", true)
                .putBoolean("crop_thumbnail", false)
                .putBoolean("force_keyframes", true)
                .putBoolean("remember_download_type", true)
                .putBoolean("metered_networks", true)
                .putBoolean("check_clipboard_home", true)
                .putBoolean("automatic_backup", true)
                .putBoolean("auto_update_ytdlp", true)
                .putBoolean("update_app", true)
                .putBoolean("ytindir_media_defaults_v1", true)
                .apply()
            sp.edit().putInt("spl", SPL).apply()
        }

    }

    private fun createNotificationChannels() {
        val notificationUtil = NotificationUtil(this)
        notificationUtil.createNotificationChannel()
    }

    private fun getProcessNameImpl() : String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            packageName
        }
    }

    companion object {
        private const val TAG = "App"
        private lateinit var applicationScope: CoroutineScope
        lateinit var instance: App
        /** The WebView data directory suffix of this process, null for the default one. */
        var webViewDataSuffix: String? = null
    }
}