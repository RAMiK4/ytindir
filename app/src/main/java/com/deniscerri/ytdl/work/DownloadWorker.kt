package com.deniscerri.ytdl.work

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.afollestad.materialdialogs.utils.MDUtil.getStringArray
import com.deniscerri.ytdl.App
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.HistoryItem
import com.deniscerri.ytdl.database.models.LogItem
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.util.UpdateUtil
import com.deniscerri.ytdl.database.repository.LogRepository
import com.deniscerri.ytdl.database.repository.ResultRepository
import com.deniscerri.ytdl.util.AlarmScheduler
import com.deniscerri.ytdl.util.Extensions.fixYtdlpPath
import com.deniscerri.ytdl.util.Extensions.getMediaDuration
import com.deniscerri.ytdl.util.Extensions.toStringDuration
import com.deniscerri.ytdl.util.Extensions.tryConvertToTimestamp
import com.deniscerri.ytdl.util.FileNameCleaner
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.NotificationUtil
import com.deniscerri.ytdl.util.SocialMediaUtil
import com.deniscerri.ytdl.util.WorkerEventBus
import com.deniscerri.ytdl.util.extractors.ytdlp.YTDLPUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.collections.addAll
import kotlin.random.Random

class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : YTDLPCoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val workNotif = NotificationUtil(App.Companion.instance).createDefaultWorkerNotification()

        return ForegroundInfo(
            1000000000,
            workNotif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }



    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("RestrictedApi")
    override suspend fun runWork(): Result {
        val workManager = WorkManager.Companion.getInstance(context)
        if (workManager.isRunning("download") || isStopped) return Result.Failure()

        setForegroundSafely()

        val parentContext = currentCoroutineContext()
        val workerScope = CoroutineScope(
            parentContext + Dispatchers.IO + SupervisorJob(parentContext[Job.Key])
        )

        val notificationUtil = NotificationUtil(App.Companion.instance)
        val dbManager = DBManager.Companion.getInstance(context)
        val dao = dbManager.downloadDao
        val historyDao = dbManager.historyDao
        val commandTemplateDao = dbManager.commandTemplateDao
        val logRepo = LogRepository(dbManager.logDao)
        val resultRepo = ResultRepository(dbManager.resultDao, commandTemplateDao, context)
        val ytdlpUtil = YTDLPUtil(context, commandTemplateDao)
        val handler = Handler(Looper.getMainLooper())
        val alarmScheduler = AlarmScheduler(context)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val time = System.currentTimeMillis() + 6000
        val priorityItemIDs = (inputData.getLongArray("priority_item_ids") ?: longArrayOf()).toMutableList()
        val continueAfterPriorityIds = inputData.getBoolean("continue_after_priority_ids", true)
        val queuedItems = if (priorityItemIDs.isEmpty()) {
            dao.getQueuedScheduledDownloadsUntil(time)
        }else {
            dao.getQueuedScheduledDownloadsUntilWithPriority(time, priorityItemIDs)
        }

        // this is needed for observe sources call, so it wont create result items
        // [removed]
        //val createResultItem = inputData.getBoolean("createResultItem", true)

        val confTmp = Configuration(context.resources.configuration)
        val locale = if (Build.VERSION.SDK_INT < 33) {
            sharedPreferences.getString("app_language", "")!!.ifEmpty { Locale.getDefault().language }
        }else{
            Locale.getDefault().language
        }.run {
            split("-")
        }.run {
            if (this.size == 1) Locale(this[0]) else Locale(this[0], this[1])
        }
        confTmp.setLocale(locale)
        val metrics = DisplayMetrics()
        val resources = Resources(context.assets, metrics, confTmp)

        val openQueueIntent = Intent(context, MainActivity::class.java)
        openQueueIntent.setAction(Intent.ACTION_VIEW)
        openQueueIntent.putExtra("destination", "Queue")
        val openDownloadQueue = PendingIntent.getActivity(
            context,
            1000000000,
            openQueueIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val downloadDelay = sharedPreferences.getString("download_delay", "0-0")!!
        val minDelay = downloadDelay.split("-")[0].toFloat()
        val maxDelay = downloadDelay.split("-")[1].toFloat()
        val hasDownloadDelay = minDelay > 0 || maxDelay > 0

        queuedItems.collectLatest { items ->
            if (this@DownloadWorker.isStopped) return@collectLatest

            runningYTDLInstances.clear()
            val activeDownloads = dao.getActiveDownloadsList()
            activeDownloads.forEach {
                runningYTDLInstances.add(it.id)
            }

            val running = ArrayList(runningYTDLInstances)
            val useScheduler = sharedPreferences.getBoolean("use_scheduler", false)
            if (items.isEmpty() && running.isEmpty()) {
                WorkManager.Companion.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                return@collectLatest
            }

            if (useScheduler){
                if (items.none{it.downloadStartTime > 0L} && running.isEmpty() && !alarmScheduler.isDuringTheScheduledTime()) {
                    WorkManager.Companion.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                    return@collectLatest
                }
            }

            if (priorityItemIDs.isEmpty() && !continueAfterPriorityIds) {
                WorkManager.Companion.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                return@collectLatest
            }

            var concurrentDownloads = sharedPreferences.getInt("concurrent_downloads", 1) - running.size
            if (hasDownloadDelay) {
                concurrentDownloads = 1 - running.size
            }

            val eligibleDownloads = if (priorityItemIDs.isNotEmpty()) {
                val tmp = priorityItemIDs.take(concurrentDownloads)
                items.filter { it.id !in running && tmp.contains(it.id) }
            }else{
                items.take(concurrentDownloads).filter {  it.id !in running }
            }

            eligibleDownloads.forEach{downloadItem ->
                priorityItemIDs.remove(downloadItem.id)

                val notification = notificationUtil.createDownloadServiceNotification(openDownloadQueue, downloadItem.title.ifEmpty { downloadItem.url })
                notificationUtil.notify(downloadItem.id.toInt(), notification)

                workerScope.launch {
                    val processDownloadBlock : suspend () -> Unit = {

                        downloadItem.status = DownloadRepository.Status.Active.toString()
                        dao.update(downloadItem)

                        if (hasDownloadDelay) {
                            val delaySec = if (minDelay >= maxDelay) minDelay else Random.Default.nextFloat() * (maxDelay - minDelay) + minDelay
                            if (delaySec > 0) {

                                workerScope.launch {
                                    delay(1000L)
                                    WorkerEventBus.post(
                                        WorkerProgress(
                                            0,
                                            context.getString(R.string.waiting_download_delay, "%.2f".format(delaySec)),
                                            downloadItem.id,
                                            downloadItem.logID
                                        )
                                    )
                                }

                                delay((delaySec * 1000).toLong())
                            }
                        }

                        // YTindir: TikTok's original upload, through tikwm.com; TikTok's best format if it fails
                        val formatId = downloadItem.format.format_id
                        // (also for quick downloads without a format list, unless a quality under 1080p is set)
                        val genericHeight = Regex("^(\\d+)p_ytdlnisgeneric$").find(formatId)?.groupValues?.get(1)?.toIntOrNull()
                        val usesTikTokOriginal = SocialMediaUtil.isTikTokOriginal(formatId) || (
                            SocialMediaUtil.siteOf(downloadItem.url) == SocialMediaUtil.Site.TIKTOK &&
                                (formatId == "best" || (genericHeight ?: 0) >= 1080))
                        val sectionMs = sectionLengthMs(downloadItem.downloadSections)
                        val tiktokSource = if (downloadItem.type == DownloadType.video && usesTikTokOriginal) {
                            WorkerEventBus.post(
                                WorkerProgress(0, "Getting the original video", downloadItem.id, downloadItem.logID)
                            )
                            withContext(Dispatchers.IO) { SocialMediaUtil.getTikTokOriginal(downloadItem.url) }
                        } else null
                        val sourceUrl = tiktokSource?.url
                        // a quick download has no title yet: "username_postid" from tikwm, as in the card
                        if (tiktokSource != null && downloadItem.title.isBlank()) {
                            withContext(Dispatchers.IO) { SocialMediaUtil.getTikTokInfo(downloadItem.url) }?.let { info ->
                                downloadItem.title = info.fileTitle
                                if (downloadItem.author.isBlank()) downloadItem.author = info.handle
                                if (downloadItem.thumb.isBlank()) downloadItem.thumb = info.cover
                            }
                        }

                        val request = ytdlpUtil.buildYTDLRequest(downloadItem, sourceUrl)

                        // DISABLED BECAUSE YT_DLP CONSIDERS DOWNLOAD FAILURE IF -U PART FAILS, ytdlnis #1043
    //                    val updateYTDLP = sharedPreferences.getBoolean("update_ytdlp_while_downloading", false)
    //                    if (updateYTDLP) {
    //                        request.addOption("-U")
    //                    }

                        downloadItem.status = DownloadRepository.Status.Active.toString()
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(1500)
                            //update item if its incomplete
                            resultRepo.updateDownloadItem(downloadItem)?.apply {
                                val status = dao.checkStatus(this.id)
                                if (status == DownloadRepository.Status.Active) {
                                    dao.updateWithoutUpsert(this)
                                }
                            }
                        }

                        val cacheDir = FileUtil.getCacheDownloadsPath(context)
                        val tempFileDir = File(cacheDir, downloadItem.id.toString())
                        tempFileDir.delete()
                        tempFileDir.mkdirs()

                        val downloadLocation = downloadItem.downloadPath
                        val keepCache = sharedPreferences.getBoolean("keep_cache", false)
                        val logDownloads = sharedPreferences.getBoolean(
                            "log_downloads",
                            false
                        ) && !downloadItem.incognito

                        val targetDir = File(FileUtil.formatPath(downloadItem.downloadPath))
                        val cacheDownloads = sharedPreferences.getBoolean("cache_downloads", true)
                        val writtenPath = downloadItem.type == DownloadType.command && downloadItem.format.format_note.contains("-P ")
                        val writeDirectly = writtenPath || (!cacheDownloads && targetDir.canWrite())
                        val cleanFileNames = downloadItem.type != DownloadType.command &&
                                sharedPreferences.getBoolean("clean_filenames", true)


                        val commandString = ytdlpUtil.parseYTDLRequestString(request)
                        val initialLogDetails = "Downloading:\n" +
                                "Title: ${downloadItem.title}\n" +
                                "URL: ${downloadItem.url}\n" +
                                "Type: ${downloadItem.type}\n" +
                                (tiktokSource?.let { "${it.note}\n" } ?: "") +
                                "Command:\n$commandString \n\n"
                        val logString = StringBuilder(initialLogDetails)
                        val logItem = LogItem(
                            0,
                            downloadItem.title.ifBlank { downloadItem.playlistTitle.ifEmpty { downloadItem.url } },
                            logString.toString(),
                            downloadItem.format,
                            downloadItem.type,
                            System.currentTimeMillis(),
                        )


                        runBlocking {
                            if (logDownloads) logItem.id = logRepo.insert(logItem)
                            downloadItem.logID = logItem.id
                            dao.update(downloadItem)
                        }

                        runCatching {
                            RuntimeManager.getInstance().destroyProcessById(downloadItem.id.toString())
                            RuntimeManager.getInstance().execute(
                                request = request,
                                processId = downloadItem.id.toString(),
                                redirectErrorStream = true,
                                usingCacheDir = true
                            ) { progress, _, line ->
                                WorkerEventBus.post(
                                    WorkerProgress(
                                        progress.toInt(),
                                        line,
                                        downloadItem.id,
                                        downloadItem.logID
                                    )
                                )
                                val title: String = downloadItem.title.ifEmpty { downloadItem.url }
                                // YTindir: ffmpeg's "frame=0 ... time=N/A" lines say nothing; its other
                                // lines are shown as "00:01:23 · 5.2MiB · 1.5x"
                                val stats = ffmpegStats(line, sectionMs)
                                if (stats?.first?.isEmpty() != true) {
                                    notificationUtil.updateDownloadNotification(
                                        downloadItem.id.toInt(),
                                        stats?.first ?: line, stats?.second ?: progress.toInt(), 0, title,
                                        NotificationUtil.Companion.DOWNLOAD_SERVICE_CHANNEL_ID
                                    )
                                }
                                CoroutineScope(Dispatchers.IO).launch {
                                    if (logDownloads) {
                                        logRepo.update(line, logItem.id)
                                    }
                                    logString.append("$line\n")
                                }
                            }
                        }.onSuccess {
                            notificationUtil.clearNotificationThrottleState(downloadItem.id.toInt())
                            resultRepo.updateDownloadItem(downloadItem)?.apply {
                                dao.updateWithoutUpsert(this)
                            }
                            //val wasQuickDownloaded = resultDao.getCountInt() == 0
                            runBlocking {
                                var finalPaths = mutableListOf<String>()

                                //after move paths
                                WorkerEventBus.post(
                                    WorkerProgress(
                                        100,
                                        "Scanning Files",
                                        downloadItem.id,
                                        downloadItem.logID
                                    )
                                )
                                val outputSequence = it.out.split("\n")

                                if (writeDirectly) {
                                    val fromAfterMove = outputSequence.asSequence()
                                        .filter { o -> o.startsWith("'/storage") }
                                    val fromSplitChapters = outputSequence.asSequence()
                                        .filter { o -> o.startsWith("[SplitChapters]") && o.contains("Destination: ") }
                                        .map { o -> o.substringAfter("Destination: ") }

                                    val moveFilesRegex = Regex("""^\[MoveFiles]\s+Moving file ".*" to "(.*)"\s*$""")
                                    val fromMoveFiles = outputSequence.asSequence()
                                        .mapNotNull { moveFilesRegex.find(it)?.groupValues?.get(1) }

                                    finalPaths = (fromAfterMove + fromSplitChapters + fromMoveFiles)
                                        .map { o -> o.trim().removeSurrounding("'").removeSurrounding("\"") }
                                        .map { o -> o.fixYtdlpPath() }
                                        .filter { o -> o.isNotBlank() }
                                        .distinct()
                                        .toMutableList()
                                }

                                // YTindir: no | / , or emojis in file names, nothing Windows can't open
                                if (cleanFileNames) {
                                    runCatching {
                                        if (writeDirectly) {
                                            finalPaths = finalPaths.map { p -> FileNameCleaner.cleanDownloadedFile(p) }.toMutableList()
                                        } else {
                                            FileNameCleaner.cleanFolder(tempFileDir.absoluteFile)
                                        }
                                    }
                                }

                                // YTindir: cut or cropped downloads are saved as "Title_1", "Title_2"...
                                val isCutOrCropped = downloadItem.type != DownloadType.command && (
                                    downloadItem.downloadSections.isNotBlank() ||
                                        (downloadItem.type == DownloadType.video && downloadItem.videoPreferences.cropValues.isNotBlank()))
                                if (isCutOrCropped) {
                                    runCatching {
                                        if (writeDirectly) {
                                            finalPaths = finalPaths.map { p -> FileNameCleaner.numberDownloadedFile(p) }.toMutableList()
                                        } else {
                                            val existingNames = runCatching {
                                                File(FileUtil.formatPath(downloadLocation)).list()?.toList()
                                                    ?: DocumentFile.fromTreeUri(context, downloadLocation.toUri())
                                                        ?.listFiles()?.mapNotNull { f -> f.name }
                                            }.getOrNull().orEmpty()
                                            FileNameCleaner.numberFolder(tempFileDir.absoluteFile, existingNames)
                                        }
                                    }
                                }

                                //move files manually if external provider
                                try {
                                    val otherPaths = withContext(Dispatchers.IO) {
                                        FileUtil.moveFile(
                                            tempFileDir.absoluteFile,
                                            context,
                                            downloadLocation,
                                            keepCache
                                        ) { p ->
                                            WorkerEventBus.post(
                                                WorkerProgress(
                                                    p,
                                                    "Moving file to ${
                                                        FileUtil.formatPath(downloadLocation)
                                                    }",
                                                    downloadItem.id,
                                                    downloadItem.logID
                                                )
                                            )
                                        }
                                    }.filter { p -> !p.matches("\\.(description)|(txt)\$".toRegex()) }
                                        .toMutableList()

                                    if (otherPaths.isNotEmpty()) {
                                        WorkerEventBus.post(
                                            WorkerProgress(
                                                100,
                                                "Moved file to ${
                                                    FileUtil.formatPath(
                                                        downloadLocation
                                                    )
                                                }",
                                                downloadItem.id,
                                                downloadItem.logID
                                            )
                                        )
                                    }

                                    finalPaths.addAll(otherPaths)

                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    if (e.message?.isNotBlank() == true) {
                                        handler.postDelayed({
                                            Toast.makeText(
                                                context,
                                                e.message,
                                                Toast.LENGTH_SHORT
                                            )
                                                .show()
                                        }, 1000)
                                    }

                                }

                                val nonMediaExtensions = mutableListOf<String>().apply {
                                    addAll(context.getStringArray(R.array.thumbnail_containers_values))
                                    addAll(
                                        context.getStringArray(R.array.sub_formats_values)
                                            .filter { it.isNotBlank() })
                                    add("description")
                                    add("txt")
                                }
                                finalPaths = finalPaths.filter { path ->
                                    !nonMediaExtensions.any {
                                        path.endsWith(it)
                                    }
                                }.distinct().toMutableList()
                                finalPaths.sortBy { p -> File(p).lastModified() }

                                FileUtil.scanMedia(finalPaths, context)

                                FileUtil.deleteConfigFiles(request)

                                //put download in history
                                if (!downloadItem.incognito) {
                                    if (request.hasOption("--download-archive") && finalPaths.isEmpty()) {
                                        handler.postDelayed({
                                            Toast.makeText(
                                                context,
                                                resources.getString(R.string.download_already_exists),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }, 100)
                                    } else {
                                        if (finalPaths.isNotEmpty()) {
                                            val unixTime = System.currentTimeMillis() / 1000
                                            finalPaths.first().apply {
                                                val file = File(this)
                                                var duration = downloadItem.duration
                                                val d = file.getMediaDuration(context)
                                                if (d > 0) duration = d.toStringDuration(Locale.US)

                                                downloadItem.format.filesize = file.length()
                                                downloadItem.format.container = file.extension
                                                downloadItem.duration = duration
                                            }

                                            val historyItem = HistoryItem(
                                                0,
                                                downloadItem.url,
                                                downloadItem.title.ifEmpty { downloadItem.playlistTitle },
                                                downloadItem.author,
                                                downloadItem.duration,
                                                downloadItem.thumb,
                                                downloadItem.type,
                                                unixTime,
                                                finalPaths,
                                                downloadItem.website,
                                                downloadItem.format,
                                                downloadItem.format.filesize,
                                                downloadItem.id,
                                                commandString
                                            )
                                            historyDao.insert(historyItem)
                                        }
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                                    notificationUtil.createDownloadFinished(
                                        downloadItem.id,
                                        downloadItem.title,
                                        downloadItem.type,
                                        if (finalPaths.isEmpty()) null else finalPaths,
                                        resources
                                    )
                                }

                                //                            if (wasQuickDownloaded && createResultItem){
                                //                                runCatching {
                                //                                    eventBus.post(WorkerProgress(100, "Creating Result Items", downloadItem.id))
                                //                                    runBlocking {
                                //                                        infoUtil.getFromYTDL(downloadItem.url).forEach { res ->
                                //                                            if (res != null) {
                                //                                                resultDao.insert(res)
                                //                                            }
                                //                                        }
                                //                                    }
                                //                                }
                                //                            }

                                dao.delete(downloadItem.id)

                                if (logDownloads) {
                                    logRepo.update(initialLogDetails + it.out, logItem.id, true)
                                }
                            }

                        }.onFailure {
                            notificationUtil.clearNotificationThrottleState(downloadItem.id.toInt())
                            FileUtil.deleteConfigFiles(request)
                            withContext(Dispatchers.Main) {
                                notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                            }
                            if (this@DownloadWorker.isStopped) return@onFailure
                            if (it is RuntimeManager.CanceledException) return@onFailure

                            // YTindir: self-recovery. Most YouTube breakages are fixed by a newer yt-dlp,
                            // so update it once and re-queue the item. Only retries when an update was
                            // actually installed, and only once per item, so it can never loop.
                            if (sharedPreferences.getBoolean("auto_update_ytdlp", true)
                                && recoveryAttempted.add(downloadItem.id)
                                && dao.getActiveDownloadsList().count { a -> a.id != downloadItem.id } == 0) {
                                val updateRes = runCatching { UpdateUtil(context).updateYTDL() }.getOrNull()
                                if (updateRes?.status == UpdateUtil.YTDLPUpdateStatus.DONE) {
                                    tempFileDir.delete()
                                    val note = "\n[YTindir] ${it.message?.lines()?.lastOrNull { l -> l.isNotBlank() } ?: "Error"}\n" +
                                        "[YTindir] ${updateRes.message}. Retrying download...\n"
                                    if (logDownloads) {
                                        logRepo.update(note, logItem.id)
                                    }
                                    downloadItem.status = DownloadRepository.Status.Queued.toString()
                                    dao.update(downloadItem)
                                    return@onFailure
                                }
                            }
                            if (it.message?.contains("JSONDecodeError") == true) {
                                val cachePath = FileUtil.getInfoJsonPath(context)
                                val infoJsonName = MessageDigest.getInstance("MD5")
                                    .digest(downloadItem.url.toByteArray()).toHexString()
                                FileUtil.deleteFile("${cachePath}/${infoJsonName}.info.json")
                            }

                            if (logDownloads) {
                                logRepo.update(it.message ?: "", logItem.id)
                            } else {
                                logString.append("${it.message ?: it.stackTraceToString()}\n")
                                logItem.content = logString.toString()
                                val logID = logRepo.insert(logItem)
                                downloadItem.logID = logID
                            }


                            tempFileDir.delete()

                            Log.e(TAG, context.getString(R.string.failed_download), it)
                            notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())

                            downloadItem.status = DownloadRepository.Status.Error.toString()
                            runBlocking {
                                dao.update(downloadItem)
                            }

                            notificationUtil.createDownloadErrored(
                                downloadItem.id,
                                downloadItem.title.ifEmpty { downloadItem.url },
                                it.message,
                                downloadItem.logID,
                                resources
                            )

                            WorkerEventBus.post(
                                WorkerProgress(
                                    100,
                                    it.toString(),
                                    downloadItem.id,
                                    downloadItem.logID
                                )
                            )
                        }
                    }

                    if (hasDownloadDelay) {
                        downloadLock.withLock {
                            processDownloadBlock()
                        }
                    } else {
                        processDownloadBlock()
                    }
                }

            }

            if (eligibleDownloads.isNotEmpty()){
                eligibleDownloads.forEach {
                    it.status = DownloadRepository.Status.Active.toString()
                    priorityItemIDs.remove(it.id)
                }
                dao.updateMultiple(eligibleDownloads)
            }
        }

        return Result.success()
    }



    companion object {
        // YTindir: items that already got one update-and-retry in this process
        private val recoveryAttempted: MutableSet<Long> = java.util.Collections.synchronizedSet(mutableSetOf())
        val runningYTDLInstances: MutableList<Long> = mutableListOf()
        const val TAG = "DownloadWorker"
        private val downloadLock = Mutex()
    }

    class WorkerProgress(
        val progress: Int,
        val output: String,
        val downloadItemID: Long,
        val logItemID: Long?
    )


    private val ffmpegStatsRegex = Regex("""size=\s*(\S+).*?time=\s*(\S+).*?speed=\s*(\S+)""")

    /**
     * ffmpeg's stats as "00:01:23 · 5.2MiB · 1.5x" and how far it is (0-99) through [sectionMs],
     * the length of the part being cut; "" for its "time=N/A" lines; null when [line] isn't one.
     * ffmpeg rewrites its stats line with "\r", so one line can hold many updates: the last counts.
     */
    private fun ffmpegStats(line: String, sectionMs: Long): Pair<String, Int?>? {
        val m = ffmpegStatsRegex.findAll(line).lastOrNull() ?: return null
        if (!line.contains("frame=") && !line.trimStart().startsWith("size=")) return null
        val (size, time, speed) = m.destructured
        if (time.startsWith("N/A") || time.startsWith("-")) return "" to null
        val progress = time.tryConvertToTimestamp()?.takeIf { sectionMs > 0 }?.let {
            (it * 100 / sectionMs).toInt().coerceIn(1, 99)
        }
        // nothing written yet: ffmpeg is still reading its way to the start of the cut
        if (time.tryConvertToTimestamp() == 0L && size.trimStart('0').firstOrNull()?.isDigit() != true) {
            return context.getString(R.string.cut_preparing) to progress
        }
        val text = listOf(time.substringBefore('.'), size, speed)
            .filter { it.isNotBlank() && !it.startsWith("N/A") }
            .joinToString(" · ")
        return text to progress
    }

    // the length of a single "1:10-2:30" cut, 0 when there are several or they are chapters
    private fun sectionLengthMs(sections: String): Long {
        val list = sections.split(";").filter { it.isNotBlank() }
        if (list.size != 1) return 0
        val parts = list[0].trim().split("-")
        if (parts.size != 2) return 0
        val start = parts[0].trim().tryConvertToTimestamp() ?: return 0
        val end = parts[1].trim().tryConvertToTimestamp() ?: return 0
        return (end - start).coerceAtLeast(0)
    }
}
