package com.deniscerri.ytdl.ui.more.downloadLogs

import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.content.SharedPreferences
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.viewmodel.LogViewModel
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.FloatingNavUtil
import com.deniscerri.ytdl.util.WorkerEventBus
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadLogFragment : Fragment() {

    // YTindir: the log is plain text in a TextView. It used to be a WebView, which put the app's
    // resources back on the system's light mode at moments no workaround could catch, so on a
    // light phone with the app set to Dark this screen and the ones after it came out half light.
    private lateinit var logScroll: NestedScrollView
    private lateinit var logHScroll: HorizontalScrollView
    private lateinit var logText: TextView
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var copyLog: ExtendedFloatingActionButton
    private lateinit var mainActivity: MainActivity
    private lateinit var logViewModel: LogViewModel
    private lateinit var sharedPreferences: SharedPreferences
    private var logID: Long? = null

    private var autoScroll: Boolean = true
    private var scrollDownBtn: MenuItem? = null
    private var rawLogContent: String = ""
    private var shownLogContent: String? = null
    private var updatePending = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        mainActivity = activity as MainActivity
        mainActivity.hideBottomNavigation()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        // a WebView elsewhere (the cookies browser) may still have left the app on the system's mode
        FloatingNavUtil.restoreNightMode(activity)
        return inflater.inflate(R.layout.fragment_download_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        topAppBar = view.findViewById(R.id.title)
        topAppBar.setNavigationOnClickListener {
            mainActivity.onBackPressedDispatcher.onBackPressed()
        }

        logScroll = view.findViewById(R.id.log_scroll)
        logHScroll = view.findViewById(R.id.log_hscroll)
        logText = view.findViewById(R.id.log_text)
        val bottomAppBar = view.findViewById<BottomAppBar>(R.id.bottomAppBar)
        copyLog = view.findViewById(R.id.copy_log)
        val slider = view.findViewById<Slider>(R.id.textsize_seekbar)

        setWrap(sharedPreferences.getBoolean("wrap_text_log", false))
        setFontSize(sharedPreferences.getFloat("log_zoom", 2f))

        topAppBar.setOnClickListener {
            autoScroll = false
            logScroll.smoothScrollTo(0, 0)
        }

        // the scroll down button shows while there is more below; at the bottom the log follows new lines
        logScroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
            val canScrollDown = v.canScrollVertically(1)
            if (scrollY < oldScrollY && canScrollDown) autoScroll = false
            if (!canScrollDown) autoScroll = true
            scrollDownBtn?.isVisible = canScrollDown
        })

        copyLog.setOnClickListener {
            val clipboard: ClipboardManager =
                mainActivity.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setText("YTindir ${com.deniscerri.ytdl.BuildConfig.VERSION_NAME} (${com.deniscerri.ytdl.BuildConfig.VERSION_CODE})\n\n" + rawLogContent)
            Snackbar.make(bottomAppBar, getString(R.string.copied_to_clipboard), Snackbar.LENGTH_LONG)
                .setAnchorView(bottomAppBar)
                .show()
        }

        logID = arguments?.getLong("logID")
        if (logID == null || logID == 0L) {
            mainActivity.onBackPressedDispatcher.onBackPressed()
            return
        }

        logViewModel = ViewModelProvider(this)[LogViewModel::class.java]

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val logItem = logViewModel.getItemById(logID!!) ?: throw Exception()
                withContext(Dispatchers.Main) {
                    topAppBar.title = logItem.title
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Log is deleted!", Toast.LENGTH_SHORT).show()
                    mainActivity.onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        scrollDownBtn = bottomAppBar?.menu?.children?.firstOrNull { it.itemId == R.id.scroll_down }
        scrollDownBtn?.isVisible = false

        bottomAppBar?.setOnMenuItemClickListener { m: MenuItem ->
            when (m.itemId) {
                R.id.wrap -> {
                    val newWrap = !sharedPreferences.getBoolean("wrap_text_log", false)
                    sharedPreferences.edit().putBoolean("wrap_text_log", newWrap).apply()
                    setWrap(newWrap)
                }

                R.id.scroll_down -> {
                    m.isVisible = false
                    autoScroll = true
                    scrollToBottom()
                }

                R.id.text_size -> {
                    slider?.isVisible = !(slider?.isVisible ?: false)
                }

                R.id.export_file -> {
                    logViewModel.exportToFile(logID!!) { f ->
                        if (f == null) {
                            Snackbar.make(bottomAppBar, getString(R.string.couldnt_parse_file), Snackbar.LENGTH_LONG)
                                .setAnchorView(bottomAppBar)
                                .show()
                        } else {
                            val snack = Snackbar.make(bottomAppBar, getString(R.string.backup_created_successfully), Snackbar.LENGTH_LONG)
                            snack.setAnchorView(bottomAppBar)
                            snack.setAction(R.string.share) {
                                FileUtil.shareFileIntent(requireContext(), listOf(f.absolutePath))
                            }
                            snack.show()
                        }
                    }
                }
            }
            true
        }

        slider?.apply {
            valueFrom = 0f
            valueTo = 10f
            value = sharedPreferences.getFloat("log_zoom", 2f).coerceIn(0f, 10f)
            addOnChangeListener { _, valChoice, _ ->
                setFontSize(valChoice)
                sharedPreferences.edit(true) {
                    putFloat("log_zoom", valChoice)
                }
            }
        }

        // Real-time Log Updates
        logViewModel.getLogFlowByID(logID!!).observe(viewLifecycleOwner) { logItem ->
            if (logItem != null && logItem.content.isNotBlank()) {
                rawLogContent = logItem.content
                scheduleLogUpdate()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkerEventBus.events.collectLatest { event ->
                    val progressBar = requireView().findViewById<LinearProgressIndicator>(R.id.progress)
                    if (event.logItemID == logID) {
                        progressBar.isVisible = event.progress < 100
                        progressBar.setProgressCompat(event.progress, true)
                    }
                }
            }
        }
    }

    // a running download adds a line at a time; the text is laid out again at most every 300 ms
    private fun scheduleLogUpdate() {
        if (updatePending) return
        updatePending = true
        logText.postDelayed({
            updatePending = false
            if (view == null || rawLogContent == shownLogContent) return@postDelayed
            shownLogContent = rawLogContent
            logText.text = rawLogContent
            if (autoScroll) logText.post { scrollToBottom() }
            else logText.post { scrollDownBtn?.isVisible = logScroll.canScrollVertically(1) }
        }, if (shownLogContent == null) 0L else 300L)
    }

    private fun scrollToBottom() {
        logScroll.post {
            logScroll.scrollTo(logScroll.scrollX, logText.height)
            scrollDownBtn?.isVisible = false
        }
    }

    private fun setFontSize(zoom: Float) {
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, zoom + 10f)
    }

    // wrapped: the text is as wide as the screen; not wrapped: long lines scroll sideways
    private fun setWrap(wrap: Boolean) {
        logHScroll.isFillViewport = wrap
        logText.layoutParams = logText.layoutParams.apply {
            width = if (wrap) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        logText.setHorizontallyScrolling(!wrap)
        if (!wrap) logHScroll.scrollTo(0, 0)
        logText.requestLayout()
    }

    override fun onResume() {
        super.onResume()
        FloatingNavUtil.restoreNightMode(activity)
    }
}
