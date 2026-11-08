package com.deniscerri.ytdl.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.FormatRecyclerView
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.ui.downloadcard.FormatSelectionBottomSheetDialog.FormatCategory
import com.deniscerri.ytdl.ui.downloadcard.FormatSelectionBottomSheetDialog.FormatSorting
import com.deniscerri.ytdl.ui.downloadcard.FormatTuple
import com.deniscerri.ytdl.ui.downloadcard.MultipleItemFormatTuple
import com.deniscerri.ytdl.util.Extensions.isYoutubeURL
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.FormatUtil
import com.deniscerri.ytdl.util.QuickFormats
import com.deniscerri.ytdl.util.QuickPick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class FormatViewModel(private val application: Application) : AndroidViewModel(application) {
    private val downloadRepository: DownloadRepository
    val selectedItems = MutableStateFlow(listOf<DownloadItem>())
    val selectedItemsSharedFlow = MutableSharedFlow<List<DownloadItem>>(replay = 1)
    var formats : Flow<List<FormatRecyclerView>>
    var showFilterBtn = MutableSharedFlow<Boolean>(1)
    var showRefreshBtn = MutableSharedFlow<Boolean>(1)
    private var canUpdate = true
    var canMultiSelectAudio = MutableStateFlow(false)
    var isMissingFormats = MutableStateFlow(false)

    private var formatUtil = FormatUtil(application)
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)

    var sortBy = FormatSorting.valueOf(sharedPreferences.getString("format_order", "filesize")!!)
    // YTindir: the filter is gone from the format list, so always every format
    var filterBy = MutableStateFlow(FormatCategory.ALL)

    // YTindir: a choice from the quick download card's list, picked up by the audio or video tab
    val quickPick = MutableStateFlow<QuickPick?>(null)
    // ...and the tab the card switches to for it (kept until the card has switched)
    val quickTabSwitch = MutableStateFlow<DownloadType?>(null)
    // ...the tab whose quick list opens by itself when the card opens (a new YouTube download)
    val quickAutoOpen = MutableStateFlow<DownloadType?>(null)
    // ...and a counter the tabs raise when their item changed (data or formats read), so an open
    // quick list shows the new title, qualities and sizes
    val quickItemVersion = MutableStateFlow(0)

    // YTindir: the open groups of dubbed formats in the format list (closed when the list opens)
    val openFormatGroups = MutableStateFlow(setOf<String>())

    fun toggleFormatGroup(group: String) {
        openFormatGroups.value = openFormatGroups.value.let { if (group in it) it - group else it + group }
    }

    private val _noFreeSpace = MutableSharedFlow<String?>()
    val noFreeSpace = _noFreeSpace.asSharedFlow()

    init {
        downloadRepository = DownloadRepository(DBManager.getInstance(application).downloadDao)
        formats = combine(listOf(selectedItemsSharedFlow, filterBy, openFormatGroups)) { f ->
            val items = selectedItems.value

            if (items.isEmpty()) {
                mutableListOf()
            }else {
                val formats = if (items.size == 1) {
                    items.first().allFormats
                }else {
                    val flatFormatCollection = items.map { it.allFormats }.flatten()

                    flatFormatCollection.groupingBy { it.format_id }.eachCount()
                        .filter { it.value == items.size }
                        .mapValues { flatFormatCollection.first { f -> f.format_id == it.key } }
                        .map { it.value }.toMutableList()
                }

                isMissingFormats.apply {
                    val vl = formats.isEmpty()
                    value = vl
                    emit(vl)
                }

                var chosenFormats: List<Format>

                if (items.size > 1) {
                    if (!isMissingFormats.value) {
                        chosenFormats = formats.mapTo(mutableListOf()) {it.copy()}
                        chosenFormats = when(items.first().type){
                            DownloadType.audio -> chosenFormats.filter { it.format_note.contains("audio", ignoreCase = true) }
                            else -> chosenFormats
                        }
                        chosenFormats = QuickFormats.withoutLowQualities(chosenFormats)
                        chosenFormats.forEach {
                            it.filesize = items.map { itm -> itm.allFormats }.flatten().filter { f -> f.format_id == it.format_id }.sumOf { itt -> itt.filesize }
                        }
                    }else{
                        chosenFormats = formats
                    }
                }else{
                    // YTindir: no low quality audio or videos below 480p (when the link has better ones)
                    chosenFormats = QuickFormats.withoutLowQualities(formats)
                    if(!isMissingFormats.value){
                        if(items.first().type == DownloadType.audio){
                            chosenFormats = chosenFormats.filter { it.format_note.contains("audio", ignoreCase = true) }
                        }
                    }
                }

                showFilterBtn.apply {
                    val vl = chosenFormats.isNotEmpty() || items.all { it.url.isYoutubeURL() }
                    emit(vl)
                }
                showRefreshBtn.apply {
                    val vl = (isMissingFormats.value || items.isEmpty() || items.first().url.isEmpty()) && canUpdate
                    emit(vl)
                }

                //sort
                var finalFormats: List<Format> = when(sortBy){
                    FormatSorting.container -> chosenFormats.groupBy { it.container }.flatMap { it.value }
                    FormatSorting.id -> chosenFormats.sortedBy { it.format_id }
                    FormatSorting.codec -> {
                        val codecOrder = application.resources.getStringArray(R.array.video_codec_values).toMutableList()
                        codecOrder.removeAt(0)
                        chosenFormats.groupBy { format -> codecOrder.indexOfFirst { format.vcodec.matches("^(${it})(.+)?$".toRegex()) } }

                            .flatMap {
                                it.value.sortedByDescending { l -> l.filesize }
                            }
                    }
                    FormatSorting.filesize -> chosenFormats
                }

                //filter category
                when(filterBy.value){
                    FormatCategory.ALL -> {}
                    FormatCategory.SUGGESTED -> {
                        finalFormats = if (items.first().type == DownloadType.audio){
                            formatUtil.sortAudioFormats(finalFormats, url = items.first().url)
                        }else{
                            val audioFormats = finalFormats.filter {  it.vcodec.isBlank() || it.vcodec == "none" }
                            val videoFormats = finalFormats.filter {  it.vcodec.isNotBlank() && it.vcodec != "none" }

                            formatUtil.sortVideoFormats(videoFormats, url = items.first().url) + formatUtil.sortAudioFormats(audioFormats, url = items.first().url)
                        }
                    }
                    FormatCategory.SMALLEST -> {
                        val tmpFormats = finalFormats
                            .asSequence()
                            .map { it.copy() }
                            .filter { it.filesize > 0 }
                            .onEach {
                                var tmp = it.format_note.lowercase()
                                //remove brackets
                                tmp = tmp.replace(" (.*)".toRegex(), "")

                                //formats that end like 1080P
                                if (tmp.endsWith("060")){
                                    tmp = tmp.removeSuffix("60")
                                }
                                tmp = tmp.removeSuffix("p")
                                //formats that are written like 1920X1080
                                val split = tmp.split("x")
                                if (split.size > 1){
                                    tmp = split[1]
                                }
                                it.format_note = tmp
                            }
                            .groupBy { it.format_note  }
                            .map { it.value.minBy { it2 -> it2.filesize } }.toList()
                        finalFormats = finalFormats.filter { tmpFormats.map { it2 -> it2.format_id }.contains(it.format_id) }
                    }
                    FormatCategory.GENERIC -> {
                        finalFormats = listOf()
                    }
                }

                canMultiSelectAudio.apply {
                    val vl = items.first().type == DownloadType.video && finalFormats.find { it.vcodec.isBlank() || it.vcodec == "none" } != null
                    value = vl
                    emit(vl)
                }

                if (finalFormats.isEmpty()) {
                    finalFormats = if (items.first().type == DownloadType.audio){
                        formatUtil.getGenericAudioFormats(application.resources)
                    }else{
                        formatUtil.getGenericVideoFormats(application.resources)
                    }
                }


                val results = mutableListOf<FormatRecyclerView>()
                // YTindir: formats with dubbed audio (YouTube's other languages) in a group of their own,
                // closed until its label is tapped, so the original formats come first
                val originals = QuickFormats.originalLanguages(finalFormats)
                fun addSection(title: String, sectionFormats: List<Format>, withLabel: Boolean, dubbedFilter: (Format) -> Boolean = { true }) {
                    if (originals == null) {
                        if (withLabel) results.add(FormatRecyclerView(label = title))
                        results.addAll(sectionFormats.map { FormatRecyclerView(null, it) })
                        return
                    }
                    val (allDubbed, original) = sectionFormats.partition { QuickFormats.isDubbed(it, originals) }
                    val dubbed = allDubbed.filter(dubbedFilter)
                    if (original.isNotEmpty()) {
                        results.add(FormatRecyclerView(label = application.getString(R.string.format_group_original, title)))
                        results.addAll(original.map { FormatRecyclerView(null, it) })
                    }
                    if (dubbed.isNotEmpty()) {
                        val group = title
                        val open = group in openFormatGroups.value
                        val label = application.getString(R.string.format_group_dubbed, title, dubbed.size)
                        results.add(FormatRecyclerView(label = (if (open) "▾  " else "▸  ") + label, group = group))
                        if (open) results.addAll(dubbed.map { FormatRecyclerView(null, it) })
                    }
                }
                val isVideoFormat: (Format) -> Boolean = { it.vcodec.isNotBlank() && it.vcodec != "none" }
                if (canMultiSelectAudio.value) {
                    // dubbed videos: only 1080p for now (dozens of languages times every quality otherwise)
                    addSection(application.getString(R.string.video), finalFormats.filter(isVideoFormat), true) {
                        QuickFormats.qualityHeight(it) == 1080
                    }
                    addSection(application.getString(R.string.audio), finalFormats.filterNot(isVideoFormat), true)
                }else{
                    val title = application.getString(if (items.first().type == DownloadType.audio) R.string.audio else R.string.video)
                    addSection(title, finalFormats, false) { !isVideoFormat(it) || QuickFormats.qualityHeight(it) == 1080 }
                }

                results
            }
        }
    }

    fun setItems(list: List<DownloadItem>, updateFormats: Boolean? = null) = viewModelScope.launch {
        canUpdate = updateFormats ?: canUpdate
        selectedItems.apply {
            value = list
            emit(list)
        }
        selectedItemsSharedFlow.emit(list)
    }

    fun setItem(item: DownloadItem, updateFormats: Boolean? = null) = viewModelScope.launch {
        canUpdate = updateFormats ?: canUpdate
        selectedItems.apply {
            value = listOf(item)
            emit(listOf(item))
        }
        selectedItemsSharedFlow.emit(listOf(item))
    }


    fun getFormatsForItemsBasedOnFormat(item: Format?, audioFormats: List<Format>? = null) : MutableList<MultipleItemFormatTuple> {
        val formatsToReturn = mutableListOf<MultipleItemFormatTuple>()

        val genericAudioFormats = formatUtil.getGenericAudioFormats(application.resources)
        val genericVideoFormats = formatUtil.getGenericVideoFormats(application.resources)

        val f = if (genericAudioFormats.contains(item) || genericVideoFormats.contains(item)) item else null

        selectedItems.value.forEach {
            formatsToReturn.add(
                MultipleItemFormatTuple(
                    it.url,
                    FormatTuple(
                        f ?: it.allFormats.firstOrNull { af -> af.format_id == item?.format_id },
                        audioFormats?.map { sa ->
                            it.allFormats.first { a -> a.format_id == sa.format_id }
                        }?.ifEmpty { null }
                    )
                )
            )
        }

        return formatsToReturn
    }

    fun checkFreeSpace(size: Long, path: String) = viewModelScope.launch {
        _noFreeSpace.emit(null)
        if (size > 10L && sharedPreferences.getBoolean("check_available_storage", true)) {
            File(FileUtil.formatPath(path)).apply {
                if (size > this.freeSpace && this.freeSpace >= 10L) {
                    val warningTxt = application.getString(R.string.no_free_space_warning) +
                            "\n" + "${application.getString(R.string.file_size)}:\t${FileUtil.convertFileSize(size)}" +
                            "\n" + "${application.getString(R.string.freespace)}:\t${FileUtil.convertFileSize(this.freeSpace)}"

                    _noFreeSpace.emit(warningTxt)
                }
            }
        }
    }
}