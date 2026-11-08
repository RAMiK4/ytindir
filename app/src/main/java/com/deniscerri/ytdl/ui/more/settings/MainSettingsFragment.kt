package com.deniscerri.ytdl.ui.more.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.LayoutDirection
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.text.layoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deniscerri.ytdl.BuildConfig
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.BackupSettingsItem
import com.deniscerri.ytdl.database.models.CommandTemplate
import com.deniscerri.ytdl.database.models.CookieItem
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.HistoryItem
import com.deniscerri.ytdl.database.models.RestoreAppDataItem
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.models.SearchHistoryItem
import com.deniscerri.ytdl.database.models.TemplateShortcut
import com.deniscerri.ytdl.database.models.observeSources.ObserveSourcesItem
import com.deniscerri.ytdl.database.viewmodel.SettingsViewModel
import com.deniscerri.ytdl.util.LocaleUtil
import com.deniscerri.ytdl.util.BackupSettingsUtil
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.ThemeUtil
import com.deniscerri.ytdl.util.UiUtil
import com.deniscerri.ytdl.util.UpdateUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader


/** YTindir: the settings sections; backup and restore and about are sections of their own now */
class MainSettingsFragment : PreferenceFragmentCompat() {
    private lateinit var editor: SharedPreferences.Editor


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                v.paddingBottom + systemBars.bottom
            )

            insets
        }
    }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        val navController = findNavController()
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        editor = preferences.edit()

        val appearance = findPreference<Preference>("appearance")
        val separator = if (LocaleUtil.currentLocale().layoutDirection == LayoutDirection.RTL) "،" else ","
        appearance?.summary = "${if (Build.VERSION.SDK_INT < 33) getString(R.string.language) + "$separator " else ""}${getString(R.string.Theme)}$separator ${getString(R.string.accents)}$separator ${getString(R.string.preferred_search_engine)}"
        appearance?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_appearanceSettingsFragment)
            true
        }

        val folders = findPreference<Preference>("folders")
        folders?.summary = "${getString(R.string.music_directory)}$separator ${getString(R.string.video_directory)}$separator ${getString(R.string.cache_directory)}"
        folders?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_folderSettingsFragment)
            true
        }

        val downloading = findPreference<Preference>("downloading")
        downloading?.summary = "${getString(R.string.quick_download)}$separator ${getString(R.string.concurrent_downloads)}$separator ${getString(R.string.limit_rate)}"
        downloading?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_downloadSettingsFragment)
            true
        }

        val processing = findPreference<Preference>("processing")
        processing?.summary = "${getString(R.string.audio_format)}$separator ${getString(R.string.video_format)}$separator ${getString(R.string.add_chapters)}"
        processing?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_processingSettingsFragment)
            true
        }

        val updating = findPreference<Preference>("updating")
        updating?.summary = "${getString(R.string.update_ytdl)}$separator ${getString(R.string.update_app)}"
        updating?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_updateSettingsFragment)
            true
        }

        val advanced = findPreference<Preference>("advanced")
        advanced?.summary = "PO Token$separator ${getString(R.string.other_youtube_extractor_args)}"
        advanced?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_advancedSettingsFragment)
            true
        }

        findPreference<Preference>("backup_restore")?.apply {
            summary = "${getString(R.string.backup)}$separator ${getString(R.string.restore)}$separator ${getString(R.string.automatic_backup)}"
            setOnPreferenceClickListener {
                navController.navigate(R.id.action_mainSettingsFragment_to_backupSettingsFragment)
                true
            }
        }

        findPreference<Preference>("about")?.apply {
            summary = "${getString(R.string.version)} ${BuildConfig.VERSION_NAME}$separator ${getString(R.string.license)}"
            setOnPreferenceClickListener {
                navController.navigate(R.id.action_mainSettingsFragment_to_aboutSettingsFragment)
                true
            }
        }
    }
}
