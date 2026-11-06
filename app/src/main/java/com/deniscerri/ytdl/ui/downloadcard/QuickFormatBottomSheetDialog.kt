package com.deniscerri.ytdl.ui.downloadcard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.FormatViewModel
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.QuickFormats
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YTindir: the format list of the quick download card (formats not read yet): 4K and 2K in the
 * better of AV1 and VP9, 1080p / 720p / 480p in H.264, then audio only (Opus, M4A, MP3) and the
 * thumbnail. A video or audio choice goes to its tab of the card through [FormatViewModel.quickPick];
 * the thumbnail is queued as a download of its own.
 */
class QuickFormatBottomSheetDialog : BottomSheetDialogFragment() {

    /** the item of the tab that opened the list */
    var item: DownloadItem? = null

    /** opens the usual format list, which can read the link's formats */
    var onAllFormats: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.quick_format_sheet, container, false)

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val item = item
        // recreated without the card (e.g. after the process was killed): nothing to pick for
        if (item == null) {
            dismissAllowingStateLoss()
            return
        }

        val list = view.findViewById<LinearLayout>(R.id.quick_format_list)
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val formatViewModel = ViewModelProvider(requireActivity())[FormatViewModel::class.java]

        fun pick(format: Format, type: DownloadType) {
            formatViewModel.quickPick.value = format
            formatViewModel.quickTabSwitch.tryEmit(type)
            dismiss()
        }

        // videos
        addHeader(list, R.string.quick_download_quality)
        val videoContainer = if (item.type == DownloadType.video) item.container
            else preferences.getString("video_format", "")!!.takeUnless { it == "Default" }.orEmpty()
        QuickFormats.videoFormats(videoContainer).forEach { format ->
            val subtitle = if (format.vcodec == QuickFormats.CODEC_AV1_VP9) {
                "AV1 / VP9 · ${getString(R.string.quick_best_quality)}"
            } else {
                "H.264 · ${getString(R.string.quick_plays_everywhere)}"
            }
            val selected = item.type == DownloadType.video && QuickFormats.isQuickVideo(item.format) &&
                item.format.format_id == format.format_id
            addRow(list, format.format_note, QuickFormats.badge(QuickFormats.heightOf(format)), subtitle, selected) {
                pick(format, DownloadType.video)
            }
        }

        // audio only
        addHeader(list, R.string.quick_audio_only)
        QuickFormats.audioFormats().forEach { format ->
            val subtitle = when (format.format_id) {
                QuickFormats.AUDIO_OPUS -> "Opus · ${getString(R.string.quick_ogg_file)}"
                QuickFormats.AUDIO_M4A -> "AAC · ${getString(R.string.quick_plays_everywhere)}"
                else -> "MP3 · ${getString(R.string.quick_plays_everywhere)}"
            }
            val selected = item.type == DownloadType.audio && item.format.format_id == format.format_id
            addRow(list, format.format_note, null, subtitle, selected) {
                pick(format, DownloadType.audio)
            }
        }

        // thumbnail
        addHeader(list, R.string.quick_thumbnail)
        val videoPath = if (item.type == DownloadType.video) item.downloadPath
            else preferences.getString("video_path", FileUtil.getDefaultVideoPath())!!
        val folder = FileUtil.formatPath(videoPath)
            .replace(Regex("^/storage/emulated/\\d+/"), "")
            .trimEnd('/') + "/" + QuickFormats.THUMBNAIL_FOLDER
        addRow(list, getString(R.string.quick_thumbnail), null, getString(R.string.quick_thumbnail_summary, folder), false) {
            queueThumbnail(item, videoPath)
            dismiss()
        }

        onAllFormats?.let { openAll ->
            addRow(list, getString(R.string.quick_all_formats), null, getString(R.string.quick_all_formats_summary), false) {
                dismiss()
                openAll()
            }
        }
    }

    private fun addHeader(list: LinearLayout, @StringRes title: Int) {
        val header = layoutInflater.inflate(R.layout.quick_format_header, list, false) as TextView
        header.setText(title)
        list.addView(header)
    }

    private fun addRow(list: LinearLayout, title: String, badge: String?, subtitle: String, selected: Boolean, onClick: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.quick_format_row, list, false)
        row.findViewById<TextView>(R.id.quick_title).text = title
        row.findViewById<TextView>(R.id.quick_badge).apply {
            text = badge
            isVisible = badge != null
        }
        row.findViewById<TextView>(R.id.quick_subtitle).text = subtitle
        row.findViewById<ImageView>(R.id.quick_check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        row.isSelected = selected
        row.setOnClickListener { onClick() }
        list.addView(row)
    }

    /** Only the thumbnail, in its original size, to the Thumbnails folder in the video folder */
    private fun queueThumbnail(item: DownloadItem, videoPath: String) {
        val app = requireActivity().application
        val preferences = PreferenceManager.getDefaultSharedPreferences(app)
        val downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        val thumbnailItem = Gson().fromJson(Gson().toJson(item), DownloadItem::class.java).apply {
            id = 0
            type = DownloadType.command
            format = Format(
                format_id = QuickFormats.THUMBNAIL,
                format_note = QuickFormats.thumbnailCommand(preferences.getString("thumbnail_format", "jpg").orEmpty())
            )
            container = ""
            downloadSections = ""
            extraCommands = ""
            customFileNameTemplate = ""
            SaveThumb = false
            allFormats = mutableListOf()
            downloadPath = videoPath
            status = DownloadRepository.Status.Queued.toString()
            downloadStartTime = 0
            logID = null
        }
        Toast.makeText(app, app.getString(R.string.quick_thumbnail_downloading), Toast.LENGTH_SHORT).show()
        // its own scope: the list (and maybe the card) closes right away
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                downloadViewModel.queueDownloads(listOf(thumbnailItem), ignoreDuplicates = true)
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, e.message ?: app.getString(R.string.errored), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
