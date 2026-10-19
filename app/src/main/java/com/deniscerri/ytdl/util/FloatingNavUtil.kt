package com.deniscerri.ytdl.util

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.deniscerri.ytdl.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import jp.wasabeef.picasso.transformations.BlurTransformation

/**
 * YTindir UI helpers.
 *
 * The bottom navigation floats over the screen content. Every screen shown in the main
 * activity gets extra bottom space on its scrolling list and on its bottom-anchored
 * buttons, so nothing ends up hidden behind the navigation.
 */
object FloatingNavUtil {

    fun register(activity: FragmentActivity) {
        activity.findViewById<View>(R.id.bottomNavigationView)?.let { nav ->
            if (nav is BottomNavigationView) {
                // keep labels and the press ripple inside the rounded pill
                nav.clipToOutline = true
                // the nav floats above the system bar; don't let it grow by the system inset
                ViewCompat.setOnApplyWindowInsetsListener(nav) { _, insets -> insets }
            }
        }
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    v.post { runCatching { applyTo(activity, v) } }
                }
            },
            true
        )
    }

    private fun applyTo(activity: FragmentActivity, root: View) {
        // only screens inside the main window, not bottom sheets or dialogs
        if (!root.isAttachedToWindow || root.rootView !== activity.window.decorView) return
        val nav = activity.findViewById<View>(R.id.bottomNavigationView)
        if (nav !is BottomNavigationView) return

        val margin = (nav.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        val space = if (nav.height > 0) nav.height + margin + dp(activity, 8) else dp(activity, 104)

        padScrollables(root, space)
        liftBottomViews(root, space)
    }

    private fun padScrollables(view: View, space: Int) {
        when (view) {
            is ViewPager2 -> return // its pages are fragments of their own
            is RecyclerView -> {
                val vertical = when (val lm = view.layoutManager) {
                    is LinearLayoutManager -> lm.orientation == RecyclerView.VERTICAL
                    is StaggeredGridLayoutManager -> lm.orientation == RecyclerView.VERTICAL
                    else -> false
                }
                if (vertical) addBottomPadding(view, space)
                return
            }
            is NestedScrollView, is ScrollView -> {
                addBottomPadding(view, space)
                return
            }
            is ViewGroup -> for (i in 0 until view.childCount) padScrollables(view.getChildAt(i), space)
        }
    }

    private fun addBottomPadding(view: View, space: Int) {
        if (view.getTag(R.id.ytindr_nav_padded) == true) return
        view.setTag(R.id.ytindr_nav_padded, true)
        (view as? ViewGroup)?.clipToPadding = false
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom + space)
    }

    /** Floating buttons and other views pinned to the bottom of the screen. */
    private fun liftBottomViews(root: View, space: Int) {
        val group = root as? ViewGroup ?: return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val gravity = when (val lp = child.layoutParams) {
                is CoordinatorLayout.LayoutParams -> lp.gravity
                is FrameLayout.LayoutParams -> lp.gravity
                else -> Gravity.NO_GRAVITY
            }
            if (gravity == Gravity.NO_GRAVITY || (gravity and Gravity.VERTICAL_GRAVITY_MASK) != Gravity.BOTTOM) continue
            if (child.getTag(R.id.ytindr_nav_padded) == true) continue
            child.setTag(R.id.ytindr_nav_padded, true)
            (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin += space
                child.layoutParams = it
            }
        }
    }

    /**
     * Download card: the video's cover, heavily blurred, fills the screen behind the sheet,
     * and the sheet itself gets rounder top corners and a slightly see-through surface.
     */
    fun styleDownloadSheet(dialog: Dialog, sheet: View?, imageUrl: String) {
        runCatching {
            sheet?.let {
                val bg = it.background as? MaterialShapeDrawable
                if (bg != null) {
                    val r = dp(it.context, 32).toFloat()
                    bg.shapeAppearanceModel = bg.shapeAppearanceModel.toBuilder()
                        .setTopLeftCorner(CornerFamily.ROUNDED, r)
                        .setTopRightCorner(CornerFamily.ROUNDED, r)
                        .build()
                    val surface = MaterialColors.getColor(it, com.google.android.material.R.attr.colorSurfaceContainerLow)
                    bg.fillColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(surface, 0xEB))
                }
            }
        }

        if (imageUrl.isBlank()) return
        runCatching {
            val container = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.container) ?: return
            if (container.findViewWithTag<View>(BACKDROP_TAG) != null) return
            val context = dialog.context

            val image = ImageView(context).apply {
                tag = BACKDROP_TAG
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val scrim = View(context).apply {
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.ytindr_backdrop_scrim))
                alpha = 0f
            }
            container.addView(image, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            container.addView(scrim, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val request = Picasso.get().load(imageUrl).resize(240, 0).onlyScaleDown()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                image.setRenderEffect(RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP))
            } else {
                request.transform(BlurTransformation(context, 25, 3))
            }
            request.into(image, object : Callback {
                override fun onSuccess() {
                    image.animate().alpha(1f).setDuration(260).start()
                    scrim.animate().alpha(1f).setDuration(260).start()
                }
                override fun onError(e: Exception?) {
                    container.removeView(image)
                    container.removeView(scrim)
                }
            })
        }
    }

    private const val BACKDROP_TAG = "ytindr_backdrop"

    /**
     * Creating the first WebView of the process resets the resources' configuration
     * (day/night and locale) to the system's, so screens inflated afterwards pick up the
     * wrong theme colours. Create a throwaway WebView once and put the configuration back.
     */
    @Suppress("DEPRECATION")
    fun prewarmWebView(activity: android.app.Activity) {
        if (webViewWarmed) return
        val res = activity.resources
        val appRes = activity.applicationContext.resources
        val saved = android.content.res.Configuration(res.configuration)
        val savedApp = android.content.res.Configuration(appRes.configuration)
        runCatching { android.webkit.WebView(activity).destroy() }
        runCatching {
            res.updateConfiguration(saved, res.displayMetrics)
            appRes.updateConfiguration(savedApp, appRes.displayMetrics)
        }
        webViewWarmed = true
    }

    private var webViewWarmed = false

    /**
     * Text fields inside the download card's pager: a sideways drag moves the text
     * instead of switching between the Audio and Video pages.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun keepHorizontalDrag(view: View?) {
        view ?: return
        var startX = 0f
        var startY = 0f
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.x; startY = e.y
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(e.x - startX)
                    val dy = kotlin.math.abs(e.y - startY)
                    // a mostly vertical drag still scrolls the card
                    if (dy > dx * 1.5f && dy > 12f) v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()
}
