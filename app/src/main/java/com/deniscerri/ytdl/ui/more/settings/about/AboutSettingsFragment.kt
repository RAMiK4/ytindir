package com.deniscerri.ytdl.ui.more.settings.about

import android.os.Bundle
import androidx.preference.Preference
import com.deniscerri.ytdl.BuildConfig
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.ui.more.settings.BaseSettingsFragment
import com.deniscerri.ytdl.util.UiUtil

/** YTindir: about, a settings section of its own: version, package, the project, what it's built on, license */
class AboutSettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.about

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.about_preferences, rootKey)

        findPreference<Preference>("app_version")?.apply {
            summary = BuildConfig.VERSION_NAME
            setOnPreferenceClickListener {
                UiUtil.copyToClipboard("YTindir ${BuildConfig.VERSION_NAME}", requireActivity())
                true
            }
        }

        findPreference<Preference>("package_name")?.apply {
            summary = BuildConfig.APPLICATION_ID
            setOnPreferenceClickListener {
                UiUtil.copyToClipboard(summary.toString(), requireActivity())
                true
            }
        }
    }
}
