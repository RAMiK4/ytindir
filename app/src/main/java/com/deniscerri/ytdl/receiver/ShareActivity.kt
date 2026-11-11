package com.deniscerri.ytdl.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.CookieViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.HistoryViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.util.Extensions.extractURL
import com.deniscerri.ytdl.util.Extensions.isYoutubeURL
import com.deniscerri.ytdl.util.FormatUtil
import com.deniscerri.ytdl.util.QuickFormats
import com.deniscerri.ytdl.util.YoutubeThumbnail
import com.deniscerri.ytdl.util.ThemeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.Delegates


class ShareActivity : BaseActivity() {

    lateinit var context: Context
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var historyViewModel: HistoryViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var cookieViewModel: CookieViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var navController: NavController
    private var quickDownload by Delegates.notNull<Boolean>()

    private lateinit var wm: WindowManager
    private lateinit var myView: View


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && queueInBackground(intent)) return
        ThemeUtil.updateTheme(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            v.setPadding(0, 0, 0, 0)
            insets
        }

        if (Settings.canDrawOverlays(this)){
            val params = WindowManager.LayoutParams(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                },
                PixelFormat.TRANSLUCENT
            )
            wm = getSystemService(WINDOW_SERVICE) as WindowManager

            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            myView = inflater.inflate(R.layout.activity_share, null)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            wm.addView(myView, params)

            // window.addFlags(
            //     WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            //             or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            //             or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // )
            //
            // val params = window.attributes
            // params.alpha = 0f
            // window.attributes = params
            setContentView(R.layout.activity_share)

        }else{
            window.run {
                setBackgroundDrawable(ColorDrawable(0))
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                } else {
                    setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
                }
            }

            setContentView(R.layout.activity_share)
        }

        context = baseContext
        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]
        historyViewModel = ViewModelProvider(this)[HistoryViewModel::class.java]
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        cookieViewModel = ViewModelProvider(this)[CookieViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(this)[DownloadCardViewModel::class.java]
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        cookieViewModel.updateCookiesFile()
        val intent = intent
        handleIntents(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntents(intent)
    }

    private fun handleIntents(intent: Intent) {
        askPermissions()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.frame_layout) as NavHostFragment
        navController = navHostFragment.findNavController()
        navController.addOnDestinationChangedListener(object: NavController.OnDestinationChangedListener{
            @SuppressLint("RestrictedApi")
            override fun onDestinationChanged(
                controller: NavController,
                destination: NavDestination,
                arguments: Bundle?
            ) {
                navController.removeOnDestinationChangedListener(this)
                CoroutineScope(SupervisorJob()).launch {
                    navController.currentBackStack.collectLatest {
                        if (it.isEmpty()){
                            this@ShareActivity.finish()
                        }
                    }
                }
            }
        })

        val action = intent.action
        Log.e("aa", intent.toString())
        if (Intent.ACTION_SEND == action || Intent.ACTION_VIEW == action) {
            if (intent.getStringExtra(Intent.EXTRA_TEXT) == null && Intent.ACTION_SEND == action){
                intent.setClass(this, MainActivity::class.java)
                // the card can live in the sharing app's task, so the main screen opens in its own
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
                return
            }

            runCatching { supportFragmentManager.popBackStack() }

            quickDownload = intent.getBooleanExtra("quick_download", sharedPreferences.getBoolean("quick_download", false) || sharedPreferences.getString("preferred_download_type", "video") == "command")
            val data = when(action){
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)!!
                else -> intent.dataString!!
            }

            val inputQuery = data.extractURL()
            val ai = packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)

            val type = intent.getStringExtra("TYPE")
            val background = intent.getBooleanExtra("BACKGROUND", ai.metaData?.getBoolean("quick_run_background", false) == true)

            lifecycleScope.launch {
                val result: ResultItem
                val existingResults = withContext(Dispatchers.IO){
                    resultViewModel.getAllByURL(inputQuery)
                }

                if (existingResults.isEmpty() || existingResults.size > 1) {
                    resultViewModel.deleteAll()
                    result = downloadViewModel.createEmptyResultItem(inputQuery)
                }else{
                    result = existingResults.first()
                }

                val downloadType = DownloadType.valueOf(type ?: downloadViewModel.getDownloadType(url = result.url).toString())
                if (sharedPreferences.getBoolean("download_card", true) && !background){

                    downloadCardViewModel.setResultItem(result)
                    downloadCardViewModel.setDownloadItem(null)
                    val bundle = Bundle()
                    bundle.putSerializable("type", downloadType)
                    // YTindir: FORMAT_LIST=all opens the card on the full format list instead of the quick list
                    // (All formats in the quick download menu of YouTube)
                    bundle.putBoolean("all_formats", intent.getStringExtra("FORMAT_LIST") == "all")
                    navController.setGraph(R.navigation.share_nav_graph, bundle)
                }else{
                    Toast.makeText(this@ShareActivity, "${getString(R.string.downloading)} $inputQuery", Toast.LENGTH_SHORT).show()

                    lifecycleScope.launch(Dispatchers.IO){
                        val downloadItem = downloadViewModel.createDownloadItemFromResult(
                            result = result,
                            givenType = downloadType)

                        downloadViewModel.queueDownloads(listOf(downloadItem))
                    }
                    this@ShareActivity.finish()
                }
            }
        }
    }
    /**
     * Quick download: another app (e.g. a YouTube download menu) sends the link with
     * BACKGROUND=true and optionally TYPE=audio|video. The download is queued with the
     * user's default settings and no window is shown, so the calling app stays in front.
     */
    private fun queueInBackground(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND || !intent.getBooleanExtra("BACKGROUND", false)) return false
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
        val url = text.extractURL()
        // YTindir: TYPE=thumbnail saves only the thumbnail (the quick download menu of YouTube)
        val isThumbnail = intent.getStringExtra("TYPE") == "thumbnail"
        val requestedType = if (isThumbnail) DownloadType.video else intent.getStringExtra("TYPE")?.let { type ->
            runCatching { DownloadType.valueOf(type) }.getOrNull()
        }
        // optional caps from the caller, e.g. 1080 (p) and 192 (kbps); codec and container stay the user's defaults
        val videoHeight = intent.getIntExtra("VIDEO_QUALITY", 0)
        val audioKbps = intent.getIntExtra("AUDIO_BITRATE", 0)
        // optional: the codec of the video (h264, or av1 for the better of AV1 and VP9) and the audio file type
        // (opus, m4a or mp3), as in the quick list of the download card
        val videoCodec = when (intent.getStringExtra("VIDEO_CODEC")?.lowercase()) {
            "h264", "avc" -> QuickFormats.CODEC_H264
            "av1", "vp9", "av1_vp9" -> QuickFormats.CODEC_AV1_VP9
            else -> null
        }
        val audioFormat = intent.getStringExtra("AUDIO_FORMAT")?.lowercase()?.let { container ->
            QuickFormats.audioFormats().firstOrNull { it.container == container }
        }

        val app = application
        val viewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        val downloadType = viewModel.getDownloadType(requestedType, url)
        val typeLabel = getString(when {
            isThumbnail -> R.string.quick_thumbnail
            downloadType == DownloadType.audio -> R.string.audio
            else -> R.string.video
        })
        Toast.makeText(app, "${getString(R.string.downloading)} · $typeLabel", Toast.LENGTH_SHORT).show()

        // runs in its own scope: this invisible activity finishes right away
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                var item = viewModel.createDownloadItemFromResult(
                    result = viewModel.createEmptyResultItem(url),
                    givenType = downloadType
                )
                val formatUtil = FormatUtil(app)
                if (isThumbnail && url.isYoutubeURL()) {
                    // YouTube's own JPG, saved right away
                    val saved = runCatching { YoutubeThumbnail.save(app, url, "", item.downloadPath) }
                    withContext(Dispatchers.Main) {
                        saved.onSuccess { Toast.makeText(app, app.getString(R.string.quick_thumbnail_saved), Toast.LENGTH_SHORT).show() }
                            .onFailure { e -> Toast.makeText(app, e.message ?: app.getString(R.string.errored), Toast.LENGTH_LONG).show() }
                    }
                    return@runCatching DownloadViewModel.QueueDownloadsResult("", listOf())
                }
                if (isThumbnail) {
                    val thumbnailFormat = PreferenceManager.getDefaultSharedPreferences(app).getString("thumbnail_format", "jpg").orEmpty()
                    item = QuickFormats.thumbnailItem(item, item.downloadPath, thumbnailFormat)
                } else if (downloadType == DownloadType.video && videoHeight > 0) {
                    val genericFormat = formatUtil.getGenericVideoFormats(app.resources)
                        .firstOrNull { it.format_id == "${videoHeight}p_ytdlnisgeneric" }
                    if (genericFormat != null) {
                        item.format = if (videoCodec != null) {
                            QuickFormats.videoFormat(videoHeight, genericFormat.container, videoCodec)
                        } else genericFormat
                    }
                }
                if (!isThumbnail && downloadType == DownloadType.audio) {
                    audioFormat?.let {
                        item.format = it
                        item.container = it.container
                    }
                    if (audioKbps > 0) {
                        // the bitrate is the output (e.g. mp3) bitrate; the source stream follows the
                        // audio codec setting (Opus by default, YouTube's best audio)
                        item.audioPreferences.bitrate = "${audioKbps}k"
                    }
                }
                viewModel.queueDownloads(listOf(item), ignoreDuplicates = isThumbnail)
            }.onSuccess { result ->
                if (result.duplicateDownloadIDs.isNotEmpty()) withContext(Dispatchers.Main) {
                    Toast.makeText(app, app.getString(R.string.download_already_exists), Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, e.message ?: app.getString(R.string.errored), Toast.LENGTH_LONG).show()
                }
            }
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // the card just lays itself out again; it opens over the sharing app,
        // so rotating or resizing must not throw the user into the main screen
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::wm.isInitialized && ::myView.isInitialized) {
            try {
                wm.removeView(myView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
    }
}
