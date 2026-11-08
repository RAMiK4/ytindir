package com.deniscerri.ytdl.ui.downloadcard

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.deniscerri.ytdl.util.YoutubeThumbnail
import com.deniscerri.ytdl.util.Extensions.isYoutubeURL
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
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import kotlinx.coroutines.withContext

/**
 * YTindir: the download card's format list, laid out like YouTube's download menu: one row per quality
 * (4K and 2K in the better of AV1 and VP9, 1080p / 720p in H.264), then audio only (Opus, M4A,
 * MP3) and the thumbnail. With the link's formats read, the rows are its real formats with their sizes;
 * before that (quick download) they are placeholders yt-dlp resolves when the download starts.
 * A video or audio choice goes to its tab of the card through [FormatViewModel.quickPick] and is
 * marked in the list; Download starts the download with it (or, with "quick_pick_opens_card" on,
 * the choice closes the list and goes back to the card). The thumbnail is queued as a download of its own.
 */
class QuickFormatBottomSheetDialog : BottomSheetDialogFragment() {

    companion object {
        private const val THUMBNAIL_ROW = "quick_thumbnail_row"
    }

    /**
     * The item of the tab that opened the list, read again whenever the tab's item changes (its data and
     * formats arrive while the list is open), so the list always shows the current title, qualities and sizes
     */
    var itemProvider: (() -> DownloadItem?)? = null

    /** opens the usual format list (every format, and reading them for a quick download) */
    var onAllFormats: (() -> Unit)? = null

    // the row picked in this list ("v:1080", "a:mp3"), marked instead of the item's format: the card may
    // not have taken it yet, and a choice for the other tab is not on this tab's item
    private var markedRow: String? = null

    // the real size of YouTube's thumbnail, read once
    private var thumbnailBytes = 0L
    private var thumbnailSizeRequested = false

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
        // recreated without the card (e.g. after the process was killed): nothing to pick for
        if (itemProvider?.invoke() == null) {
            dismissAllowingStateLoss()
            return
        }

        val formatViewModel = ViewModelProvider(requireActivity())[FormatViewModel::class.java]
        val resultViewModel = ViewModelProvider(requireActivity())[ResultViewModel::class.java]
        val progress = view.findViewById<LinearProgressIndicator>(R.id.quick_sheet_progress)
        val loading = MutableStateFlow(false)

        // YTindir: the card downloads what is chosen (the item's own format when nothing was picked here)
        view.findViewById<View>(R.id.quick_sheet_download).setOnClickListener {
            formatViewModel.quickDownload.value = true
            dismiss()
        }
        view.findViewById<View>(R.id.quick_sheet_all_formats).apply {
            isVisible = onAllFormats != null
            setOnClickListener {
                val openAll = onAllFormats
                dismiss()
                openAll?.invoke()
            }
        }

        // the list again whenever the card's item changed (and once now)
        viewLifecycleOwner.lifecycleScope.launch {
            formatViewModel.quickItemVersion.collect {
                itemProvider?.invoke()?.let { render(view, it, loading.value) }
            }
        }
        // a progress bar while the link's data or formats are read
        viewLifecycleOwner.lifecycleScope.launch {
            combine(resultViewModel.updatingData, resultViewModel.updatingFormats) { data, formats -> data || formats }
                .collect { isLoading ->
                    loading.value = isLoading
                    progress.isVisible = isLoading
                    itemProvider?.invoke()?.let { renderHeader(view, it, isLoading) }
                }
        }
    }

    /** The title of what is being downloaded, with its channel and length under it */
    private fun renderHeader(view: View, item: DownloadItem, loading: Boolean) {
        view.findViewById<TextView>(R.id.quick_sheet_title).text = item.title.ifBlank {
            if (loading) getString(R.string.loading) + "…" else item.url
        }
        val duration = item.duration.takeUnless { it.isBlank() || it == "-1" || it == "0:00" }
        val subtitle = listOfNotNull(item.author.takeIf { it.isNotBlank() }, duration).joinToString(" · ")
        view.findViewById<TextView>(R.id.quick_sheet_subtitle).apply {
            text = subtitle
            isVisible = subtitle.isNotBlank()
        }
    }

    private fun render(view: View, item: DownloadItem, loading: Boolean) {
        renderHeader(view, item, loading)
        val list = view.findViewById<LinearLayout>(R.id.quick_format_list)
        list.removeAllViews()
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val formatViewModel = ViewModelProvider(requireActivity())[FormatViewModel::class.java]
        val downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        val formatUtil = FormatUtil(requireContext())

        val opensCard = preferences.getBoolean("quick_pick_opens_card", false)
        fun pick(pick: QuickPick, row: String) {
            // the tab first, so the download button takes the item of the picked type
            formatViewModel.quickTabSwitch.value = pick.type
            formatViewModel.quickPick.value = pick
            if (opensCard) {
                dismiss()
            } else {
                markedRow = row
                render(view, item, loading)
            }
        }
        fun marked(row: String, selectedOnItem: Boolean) = markedRow?.let { it == row } ?: selectedOnItem

        val realFormats = if (QuickFormats.hasNoRealFormats(item)) listOf() else item.allFormats.toList()

        // videos: the quality with its frame rate and badge, the codec and what the quality is good for
        val videoContainer = if (item.type == DownloadType.video) item.container
            else preferences.getString("video_format", "")!!.takeUnless { it == "Default" }.orEmpty()
        val isVideoSelected: (Format) -> Boolean = { f ->
            item.type == DownloadType.video && item.format.format_id == f.format_id && item.format.vcodec == f.vcodec
        }
        // the link's real video formats; placeholders (resolved by yt-dlp) when there are none, e.g. before
        // the formats were read or when only the audio streams are known
        val videoChoices = QuickFormats.realVideoChoices(realFormats)
        addHeader(list, R.string.quick_download_quality)
        if (videoChoices.isEmpty()) {
            QuickFormats.videoFormats(videoContainer).forEach { format ->
                val height = QuickFormats.heightOf(format) ?: 0
                val subtitle = "${QuickFormats.codecLabel(format)} · ${getString(QuickFormats.hint(height))}"
                addRow(list, qualityTitle(height, null), QuickFormats.badge(height), subtitle, null,
                    marked("v:$height", isVideoSelected(format))) {
                    pick(QuickPick(DownloadType.video, format), "v:$height")
                }
            }
        } else {
            val audioIDs = downloadViewModel.getPreferredAudioFormats(realFormats)
            val audioForVideo = realFormats.filter { audioIDs.contains(it.format_id) }
            val audioSize = audioForVideo.sumOf { it.filesize }
            videoChoices.forEach { format ->
                val height = QuickFormats.qualityHeight(format) ?: 0
                val subtitle = "${QuickFormats.codecLabel(format)} · ${getString(QuickFormats.hint(height))}"
                val size = if (format.filesize > 0) FileUtil.convertFileSize(format.filesize + audioSize) else null
                addRow(list, qualityTitle(height, QuickFormats.fps(format)), QuickFormats.badge(height), subtitle, size,
                    marked("v:$height", isVideoSelected(format))) {
                    pick(QuickPick(DownloadType.video, format, audioForVideo), "v:$height")
                }
            }
        }

        // audio only: the link's AAC as it is (M4A), or its best audio as a 192 kbps MP3
        addHeader(list, R.string.quick_audio_only)
        val isAudioSelected: (Format, String) -> Boolean = { f, container ->
            item.type == DownloadType.audio && item.format.format_id == f.format_id && item.container == container
        }
        val realAudio = QuickFormats.realAudioChoices(realFormats) { formatUtil.sortAudioFormats(it, url = item.url) }
        val aac = realAudio.firstOrNull { it.first == "m4a" }?.second
        val m4a = QuickFormats.audioFormats().first { it.format_id == QuickFormats.AUDIO_M4A }
        val m4aFormat = aac ?: m4a
        // the stream's real bitrate, else YouTube's usual one (approximate)
        val m4aKbps = aac?.let { QuickFormats.kbps(it) }
        addRow(list, m4a.format_note, if (m4aKbps != null) "$m4aKbps kbps" else "≈${QuickFormats.USUAL_AAC_KBPS} kbps",
            "AAC · ${getString(R.string.quick_source_audio)}",
            aac?.filesize?.takeIf { it > 0 }?.let { FileUtil.convertFileSize(it) },
            marked("a:m4a", isAudioSelected(m4aFormat, m4a.container))) {
            pick(QuickPick(DownloadType.audio, m4aFormat, audioContainer = m4a.container), "a:m4a")
        }
        val mp3 = QuickFormats.audioFormats().first { it.format_id == QuickFormats.AUDIO_MP3 }
        val mp3Size = QuickFormats.mp3SizeEstimate(aac, item.duration)
        addRow(list, mp3.format_note, "${QuickFormats.MP3_KBPS} kbps",
            "${QuickFormats.MP3_SOURCE_LABEL} · ${getString(R.string.quick_hint_recommended)}",
            // a constant bitrate MP3 of the link's length
            mp3Size?.let { FileUtil.convertFileSize(it) },
            marked("a:mp3", isAudioSelected(mp3, mp3.container))) {
            // with its estimated size, which the card shows as approximate
            val mp3Format = mp3.copy(filesize = mp3Size ?: 0L, isFilesizeApprox = mp3Size != null)
            pick(QuickPick(DownloadType.audio, mp3Format, audioContainer = mp3.container), "a:mp3")
        }

        // thumbnail: YouTube's original JPG
        addDivider(list)
        val videoPath = if (item.type == DownloadType.video) item.downloadPath
            else preferences.getString("video_path", FileUtil.getDefaultVideoPath())!!
        val thumbnailRow = addRow(list, getString(R.string.quick_thumbnail), "JPG", getString(R.string.quick_original_size),
            "≈" + FileUtil.convertFileSize(YoutubeThumbnail.USUAL_SIZE_BYTES), false) {
            saveThumbnail(item, videoPath)
            dismiss()
        }
        thumbnailRow.tag = THUMBNAIL_ROW
        if (item.url.isYoutubeURL()) {
            // the real size of YouTube's JPG, without the approximate sign (read once, the list is drawn again
            // as the link's data arrives)
            if (thumbnailBytes > 0) {
                thumbnailRow.findViewById<TextView>(R.id.quick_size).text = FileUtil.convertFileSize(thumbnailBytes)
            } else if (!thumbnailSizeRequested) {
                thumbnailSizeRequested = true
                viewLifecycleOwner.lifecycleScope.launch {
                    val bytes = withContext(Dispatchers.IO) { YoutubeThumbnail.fetchSize(item.url) }
                    if (bytes > 0) {
                        thumbnailBytes = bytes
                        view.findViewById<LinearLayout>(R.id.quick_format_list)
                            .findViewWithTag<View>(THUMBNAIL_ROW)?.findViewById<TextView>(R.id.quick_size)?.text = FileUtil.convertFileSize(bytes)
                    }
                }
            }
        }
    }

    /** "1080p" with its frame rate after it, smaller and lighter: "1080p 60fps" */
    private fun qualityTitle(height: Int, fps: Int?): CharSequence {
        if (fps == null || fps <= 0) return "${height}p"
        val fpsText = " ${fps}fps"
        return SpannableString("${height}p$fpsText").apply {
            val start = length - fpsText.length
            setSpan(RelativeSizeSpan(0.72f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant)),
                start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    private fun addRow(list: LinearLayout, title: CharSequence, badge: String?, subtitle: String, size: String?, selected: Boolean, onClick: () -> Unit): View {
        val row = layoutInflater.inflate(R.layout.quick_format_row, list, false)
        row.findViewById<TextView>(R.id.quick_title).text = title
        row.findViewById<TextView>(R.id.quick_badge).apply {
            text = badge
            isVisible = badge != null
        }
        row.findViewById<TextView>(R.id.quick_subtitle).text = subtitle
        row.findViewById<TextView>(R.id.quick_size).apply {
            text = size
            isVisible = size != null && !size.endsWith("?")
        }
        row.findViewById<ImageView>(R.id.quick_check).visibility = if (selected) View.VISIBLE else View.GONE
        row.isSelected = selected
        row.setOnClickListener { onClick() }
        list.addView(row)
        return row
    }

    /**
     * Only the thumbnail, in its original size, to the Thumbnails folder in the video folder:
     * YouTube's own JPG, other sites through yt-dlp.
     */
    private fun saveThumbnail(item: DownloadItem, videoPath: String) {
        val app = requireActivity().application
        Toast.makeText(app, app.getString(R.string.quick_thumbnail_downloading), Toast.LENGTH_SHORT).show()
        if (item.url.isYoutubeURL()) {
            // its own scope: the list (and maybe the card) closes right away
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                runCatching { YoutubeThumbnail.save(app, item.url, item.title, videoPath) }
                    .onSuccess { Toast.makeText(app, app.getString(R.string.quick_thumbnail_saved), Toast.LENGTH_SHORT).show() }
                    .onFailure { e -> Toast.makeText(app, e.message ?: app.getString(R.string.errored), Toast.LENGTH_LONG).show() }
            }
            return
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(app)
        val downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        val thumbnailItem = QuickFormats.thumbnailItem(item, videoPath, preferences.getString("thumbnail_format", "jpg").orEmpty())
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
