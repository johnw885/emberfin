package com.johnw.emberfin.dream

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.service.dreams.DreamService
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.johnw.emberfin.data.ActiveSession
import com.johnw.emberfin.data.ImageUrls
import com.johnw.emberfin.data.LibraryRepository
import com.johnw.emberfin.data.SessionManager
import com.johnw.emberfin.ui.EmberfinColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.context.GlobalContext
import androidx.compose.ui.graphics.toArgb

/**
 * The screensaver: library backdrops with a slow drift, crossfading every [SLIDE_MS], with the
 * title's clear logo (or its name) bottom-left. Plain Android views rather than Compose: a dream
 * has no lifecycle owner, and this is all it needs. On Google TV it only runs once it's chosen
 * with `adb shell settings put secure screensaver_components com.johnw.emberfin/.dream.EmberfinDream`.
 */
class EmberfinDream : DreamService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var layers: Array<ImageView>
    private lateinit var logo: ImageView
    private lateinit var name: TextView
    private var top = 0
    private val drift = arrayOfNulls<AnimatorSet>(2)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val root = FrameLayout(this).apply { setBackgroundColor(EmberfinColors.Background.toArgb()) }
        layers = Array(2) {
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0f
                root.addView(this, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        }
        // Bottom-left shade for the logo, as one small stretched bitmap (full-screen gradients are slow on this GPU).
        root.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(shade())
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val pad = dp(56)
        logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_START
            adjustViewBounds = true
            alpha = 0f
            root.addView(this, FrameLayout.LayoutParams(dp(360), dp(110), Gravity.BOTTOM or Gravity.START).apply { setMargins(pad, 0, 0, pad) })
        }
        name = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = ResourcesCompat.getFont(this@EmberfinDream, com.johnw.emberfin.ui.R.font.inter_bold)
            maxLines = 2
            alpha = 0f
            root.addView(this, FrameLayout.LayoutParams(dp(520), WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(pad, 0, 0, pad) })
        }
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        scope.launch { run() }
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        scope.coroutineContext.cancelChildren()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private suspend fun run() {
        val koin = GlobalContext.get()
        val session = koin.get<SessionManager>().backgroundSession()
        if (session == null) {
            showText("emberfin")
            return
        }
        val library = koin.get<LibraryRepository>()
        var items = emptyList<BaseItemDto>()
        while (true) {
            if (items.isEmpty()) {
                items = runCatching { library.screensaver(session) }.getOrDefault(emptyList())
                if (items.isEmpty()) {
                    showText(session.account.serverName)
                    delay(60_000)
                    continue
                }
            }
            for (item in items) {
                if (show(session, item)) delay(SLIDE_MS)
            }
            items = emptyList() // A fresh random set each round.
        }
    }

    /** Loads [item]'s art and crossfades to it. False when the backdrop didn't load. */
    private suspend fun show(session: ActiveSession, item: BaseItemDto): Boolean {
        val backdrop = ImageUrls.backdrop(session.api, item, width = 1920)?.url?.let { load(it) } ?: return false
        val logoBitmap = ImageUrls.logo(session.api, item)?.url?.let { load(it) }
        val incoming = layers[1 - top]
        val outgoing = layers[top]
        top = 1 - top
        incoming.setImageBitmap(backdrop)
        // Draw order: outgoing, incoming, then the shade, logo and name.
        val root = incoming.parent as FrameLayout
        root.removeView(incoming)
        root.addView(incoming, 1)
        drift[top]?.cancel()
        incoming.animate().cancel()
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction { outgoing.setImageDrawable(null); outgoing.alpha = 0f }.start()
        // Ken Burns: a slow zoom and drift over the slide.
        drift[top] = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(incoming, View.SCALE_X, 1.04f, 1.12f),
                ObjectAnimator.ofFloat(incoming, View.SCALE_Y, 1.04f, 1.12f),
                ObjectAnimator.ofFloat(incoming, View.TRANSLATION_X, 0f, -incoming.width * 0.025f),
            )
            duration = SLIDE_MS + FADE_MS
            interpolator = LinearInterpolator()
            start()
        }

        logo.animate().alpha(0f).setDuration(400).withEndAction {
            if (logoBitmap != null) {
                logo.setImageBitmap(logoBitmap)
                logo.animate().alpha(1f).setDuration(800).start()
                name.alpha = 0f
            } else {
                logo.setImageDrawable(null)
                name.text = item.name.orEmpty()
                name.animate().alpha(1f).setDuration(800).start()
            }
        }.start()
        if (logoBitmap != null) name.animate().alpha(0f).setDuration(400).start()
        Log.i(TAG, "slide ${item.id}")
        return true
    }

    private fun showText(text: String) {
        name.text = text
        name.animate().alpha(1f).setDuration(800).start()
    }

    private suspend fun load(url: String): Bitmap? {
        val loader = SingletonImageLoader.get(this)
        val request = ImageRequest.Builder(this).data(url).allowHardware(false).build()
        return runCatching { loader.execute(request).image?.toBitmap() }.getOrNull()
    }

    private fun shade(): Bitmap {
        val w = 192
        val h = 108
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply { isDither = true }
        val bg = EmberfinColors.Background.toArgb() and 0x00FFFFFF
        p.shader = LinearGradient(0f, h.toFloat(), w * 0.6f, h * 0.3f, intArrayOf(bg or (0xC0 shl 24), bg), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return bmp
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "EmberfinDream"
        const val SLIDE_MS = 12_000L
        const val FADE_MS = 1_500L
    }
}
