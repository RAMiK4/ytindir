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

        fun pick(pick: QuickPick) {
            formatViewModel.quickPick.value = pick
            formatViewModel.quickTabSwitch.tryEmit(pick.type)
            dismiss()
        }

        val realFormats = if (QuickFormats.hasNoRealFormats(item)) listOf() else item.allFormats.toList()

        // videos: the quality with its frame rate and badge, the codec and what the quality is good for
        val videoContainer = if (item.type == DownloadType.video) item.container
            else preferences.getString("video_format", "")!!.takeUnless { it == "Default" }.orEmpty()
        val isVideoSelected: (Format) -> Boolean = { f ->
            item.type == DownloadType.video && item.format.format_id == f.format_id && item.format.vcodec == f.vcodec
        }
        if (realFormats.isEmpty()) {
            addHeader(list, R.string.quick_download_quality)
            QuickFormats.videoFormats(videoContainer).forEach { format ->
                val height = QuickFormats.heightOf(format) ?: 0
                val subtitle = "${QuickFormats.codecLabel(format)} · ${getString(QuickFormats.hint(height))}"
                addRow(list, qualityTitle(height, null), QuickFormats.badge(height), subtitle, null, isVideoSelected(format)) {
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
                    val height = QuickFormats.heightOf(format) ?: 0
                    val subtitle = "${QuickFormats.codecLabel(format)} · ${getString(QuickFormats.hint(height))}"
                    val size = if (format.filesize > 0) FileUtil.convertFileSize(format.filesize + audioSize) else null
                    addRow(list, qualityTitle(height, QuickFormats.fps(format)), QuickFormats.badge(height), subtitle, size,
                        isVideoSelected(format)) {
                        pick(QuickPick(DownloadType.video, format, audioForVideo))
                    }
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
        addRow(list, m4a.format_note, "≈${aac?.let { QuickFormats.kbps(it) } ?: QuickFormats.USUAL_AAC_KBPS} kbps",
            "AAC · ${getString(R.string.quick_source_audio)}",
            aac?.filesize?.takeIf { it > 0 }?.let { FileUtil.convertFileSize(it) },
            isAudioSelected(m4aFormat, m4a.container)) {
            pick(QuickPick(DownloadType.audio, m4aFormat, audioContainer = m4a.container))
        }
        val mp3 = QuickFormats.audioFormats().first { it.format_id == QuickFormats.AUDIO_MP3 }
        val mp3Size = QuickFormats.mp3SizeEstimate(aac, item.duration)
        addRow(list, mp3.format_note, "${QuickFormats.MP3_KBPS} kbps",
            "Opus → MP3 · ${getString(R.string.quick_hint_recommended)}",
            mp3Size?.let { "≈" + FileUtil.convertFileSize(it) },
            isAudioSelected(mp3, mp3.container)) {
            pick(QuickPick(DownloadType.audio, mp3, audioContainer = mp3.container))
        }

        // thumbnail: YouTube's original JPG
        addDivider(list)
        val videoPath = if (item.type == DownloadType.video) item.downloadPath
            else preferences.getString("video_path", FileUtil.getDefaultVideoPath())!!
        addRow(list, getString(R.string.quick_thumbnail), "JPG", getString(R.string.quick_original_size),
            "≈" + FileUtil.convertFileSize(YoutubeThumbnail.USUAL_SIZE_BYTES), false) {
            saveThumbnail(item, videoPath)
            dismiss()
        }

        onAllFormats?.let { openAll ->
            addRow(list, getString(R.string.quick_all_formats), null, getString(R.string.quick_all_formats_summary), null, false) {
                dismiss()
                openAll()
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

    private fun addRow(list: LinearLayout, title: CharSequence, badge: String?, subtitle: String, size: String?, selected: Boolean, onClick: () -> Unit) {
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
