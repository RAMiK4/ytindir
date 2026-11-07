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
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.FormatViewModel
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.FormatUtil
import com.deniscerri.ytdl.util.QuickFormats
import com.deniscerri.ytdl.util.QuickPick
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YTindir: the download card's format list, laid out like YouTube's download menu: one row per quality
 * (4K and 2K in the better of AV1 and VP9, 1080p / 720p / 480p in H.264), then audio only (Opus, M4A,
 * MP3) and the thumbnail. With the link's formats read, the rows are its real formats with their sizes;
 * before that (quick download) they are placeholders yt-dlp resolves when the download starts.
 * A video or audio choice goes to its tab of the card through [FormatViewModel.quickPick];
 * the thumbnail is queued as a download of its own.
 */
class QuickFormatBottomSheetDialog : BottomSheetDialogFragment() {

    /** the item of the tab that opened the list */
    var item: DownloadItem? = null

    /** opens the usual format list (every format, and reading them for a quick download) */
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
        val downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        val formatUtil = FormatUtil(requireContext())
        val playsEverywhere = getString(R.string.quick_plays_everywhere)
        val bestQuality = getString(R.string.quick_best_quality)

        fun pick(pick: QuickPick) {
            formatViewModel.quickPick.value = pick
            formatViewModel.quickTabSwitch.tryEmit(pick.type)
            dismiss()
        }

        val realFormats = if (QuickFormats.hasNoRealFormats(item)) listOf() else item.allFormats.toList()

        // videos
        val videoContainer = if (item.type == DownloadType.video) item.container
            else preferences.getString("video_format", "")!!.takeUnless { it == "Default" }.orEmpty()
        val isVideoSelected: (Format) -> Boolean = { f ->
            item.type == DownloadType.video && item.format.format_id == f.format_id && item.format.vcodec == f.vcodec
        }
        if (realFormats.isEmpty()) {
            addHeader(list, R.string.quick_download_quality)
            QuickFormats.videoFormats(videoContainer).forEach { format ->
                val subtitle = if (format.vcodec == QuickFormats.CODEC_AV1_VP9) "AV1 / VP9 · $bestQuality"
                    else "H.264 · $playsEverywhere"
                addRow(list, format.format_note, QuickFormats.badge(QuickFormats.heightOf(format)), subtitle, null, isVideoSelected(format)) {
                    pick(QuickPick(DownloadType.video, format))
                }
            }
        } else {
            val videoChoices = QuickFormats.realVideoChoices(realFormats)
            if (videoChoices.isNotEmpty()) {
                addHeader(list, R.string.quick_download_quality)
                val audioIDs = downloadViewModel.getPreferredAudioFormats(realFormats)
                val audioForVideo = realFormats.filter { audioIDs.contains(it.format_id) }
                val audioSize = audioForVideo.sumOf { it.filesize }
                videoChoices.forEach { format ->
                    val height = QuickFormats.heightOf(format)
                    val codec = QuickFormats.codecLabel(format)
                    val subtitle = listOfNotNull(
                        codec,
                        QuickFormats.fpsLabel(format),
                        if (codec == "H.264") playsEverywhere else bestQuality
                    ).joinToString(" · ")
                    val size = if (format.filesize > 0) format.filesize + audioSize else 0L
                    addRow(list, "${height}p", QuickFormats.badge(height), subtitle, size, isVideoSelected(format)) {
                        pick(QuickPick(DownloadType.video, format, audioForVideo))
                    }
                }
            }
        }

        // audio only
        addHeader(list, R.string.quick_audio_only)
        val isAudioSelected: (Format, String) -> Boolean = { f, container ->
            item.type == DownloadType.audio && item.format.format_id == f.format_id && item.container == container
        }
        val realAudio = QuickFormats.realAudioChoices(realFormats) { formatUtil.sortAudioFormats(it, url = item.url) }
        val quickAudio = QuickFormats.audioFormats()
        quickAudio.forEach { quick ->
            val container = quick.container
            // the link's own Opus / AAC stream where it has one, its best audio as MP3
            val real = realAudio.firstOrNull { it.first == container }?.second
            val format = real ?: quick
            val details = when (quick.format_id) {
                QuickFormats.AUDIO_OPUS -> listOfNotNull("Opus", real?.let { QuickFormats.kbpsLabel(it) }, getString(R.string.quick_ogg_file))
                QuickFormats.AUDIO_M4A -> listOfNotNull("AAC", real?.let { QuickFormats.kbpsLabel(it) }, playsEverywhere)
                else -> listOf("MP3", playsEverywhere)
            }
            addRow(list, quick.format_note, null, details.joinToString(" · "), real?.filesize, isAudioSelected(format, container)) {
                pick(QuickPick(DownloadType.audio, format, audioContainer = container))
            }
        }

        // thumbnail
        addDivider(list)
        val videoPath = if (item.type == DownloadType.video) item.downloadPath
            else preferences.getString("video_path", FileUtil.getDefaultVideoPath())!!
        val folder = FileUtil.formatPath(videoPath)
            .replace(Regex("^/storage/emulated/\\d+/"), "")
            .trimEnd('/') + "/" + QuickFormats.THUMBNAIL_FOLDER
        addRow(list, getString(R.string.quick_thumbnail), null, getString(R.string.quick_thumbnail_summary, folder), null, false) {
            queueThumbnail(item, videoPath)
            dismiss()
        }

        onAllFormats?.let { openAll ->
            addRow(list, getString(R.string.quick_all_formats), null, getString(R.string.quick_all_formats_summary), null, false) {
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

    private fun addDivider(list: LinearLayout) {
        val divider = View(requireContext())
        divider.setBackgroundColor(MaterialColors.getColor(list, com.google.android.material.R.attr.colorOutlineVariant))
        val density = resources.displayMetrics.density
        list.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, density.toInt())).apply {
            setMargins((24 * density).toInt(), (6 * density).toInt(), (24 * density).toInt(), (4 * density).toInt())
        })
    }

    private fun addRow(list: LinearLayout, title: String, badge: String?, subtitle: String, size: Long?, selected: Boolean, onClick: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.quick_format_row, list, false)
        row.findViewById<TextView>(R.id.quick_title).text = title
        row.findViewById<TextView>(R.id.quick_badge).apply {
            text = badge
            isVisible = badge != null
        }
        row.findViewById<TextView>(R.id.quick_subtitle).text = subtitle
        row.findViewById<TextView>(R.id.quick_size).apply {
            val readable = if (size != null && size > 0) FileUtil.convertFileSize(size) else null
            text = readable
            isVisible = readable != null && readable != "?"
        }
        row.findViewById<ImageView>(R.id.quick_check).visibility = if (selected) View.VISIBLE else View.GONE
        row.isSelected = selected
        row.setOnClickListener { onClick() }
        list.addView(row)
    }

    /** Only the thumbnail, in its original size, to the Thumbnails folder in the video folder */
    private fun queueThumbnail(item: DownloadItem, videoPath: String) {
        val app = requireActivity().application
        val preferences = PreferenceManager.getDefaultSharedPreferences(app)
        val downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        val thumbnailItem = QuickFormats.thumbnailItem(item, videoPath, preferences.getString("thumbnail_format", "jpg").orEmpty())
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
