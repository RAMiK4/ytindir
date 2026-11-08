package com.deniscerri.ytdl.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.deniscerri.ytdl.R
import com.google.android.material.color.MaterialColors

/**
 * YTindir: frosted glass for the floating bottom navigation (Android 12+): the screen content
 * under the nav, blurred, with a light tint and edge over it.
 *
 * The content view is drawn again into a [RenderNode] with a blur effect, at the nav's place,
 * only when the content changed (scrolled, loaded an image...) or the nav moved, so a still
 * screen draws no extra frames. Older Android versions keep the plain see-through background.
 */
@RequiresApi(Build.VERSION_CODES.S)
class GlassNavBackground private constructor(
    private val host: View,
    private val source: View,
) : Drawable() {

    private val density = host.resources.displayMetrics.density
    private val node = RenderNode("ytindr_glass_nav").apply {
        val radius = 22 * density
        setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }
    // what is under the content where it has no background of its own
    private val backdrop = MaterialColors.getColor(host, android.R.attr.colorBackground)
    private val tint = GradientDrawable().apply {
        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, host.resources.displayMetrics)
        setColor(ContextCompat.getColor(host.context, R.color.ytindr_nav_glass))
        setStroke(density.toInt().coerceAtLeast(1), ContextCompat.getColor(host.context, R.color.ytindr_nav_glass_stroke))
    }
    private val hostLocation = IntArray(2)
    private val sourceLocation = IntArray(2)
    private var failed = false

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (!failed && canvas.isHardwareAccelerated && source.isAttachedToWindow && b.width() > 0 && b.height() > 0) {
            try {
                host.getLocationInWindow(hostLocation)
                source.getLocationInWindow(sourceLocation)
                node.setPosition(0, 0, b.width(), b.height())
                val recording = node.beginRecording(b.width(), b.height())
                try {
                    recording.drawColor(backdrop)
                    recording.translate(
                        (sourceLocation[0] - hostLocation[0] - b.left).toFloat(),
                        (sourceLocation[1] - hostLocation[1] - b.top).toFloat()
                    )
                    source.draw(recording)
                } finally {
                    node.endRecording()
                }
                canvas.save()
                canvas.translate(b.left.toFloat(), b.top.toFloat())
                canvas.drawRenderNode(node)
                canvas.restore()
            } catch (e: Throwable) {
                // never break the nav: without the blur it still has its tint
                failed = true
            }
        }
        tint.bounds = b
        tint.draw(canvas)
    }

    override fun onBoundsChange(bounds: Rect) {
        tint.bounds = bounds
    }

    override fun getOutline(outline: Outline) {
        tint.bounds = bounds
        tint.getOutline(outline)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Puts the glass behind [nav], blurring [source] (the screen content under it) */
        fun install(nav: View, source: View) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val glass = GlassNavBackground(nav, source)
            nav.background = glass
            nav.clipToOutline = true
            val last = IntArray(2)
            val now = IntArray(2)
            nav.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (nav.visibility != View.VISIBLE) return true
                    nav.getLocationInWindow(now)
                    val moved = !now.contentEquals(last)
                    // isDirty: something in the content was invalidated since it was last drawn
                    if (moved || source.isDirty) {
                        now.copyInto(last)
                        glass.invalidateSelf()
                    }
                    return true
                }
            })
        }
    }
}
