package com.kostysetinin.gametranslate.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.kostysetinin.gametranslate.logic.TextBox
import kotlin.math.max
import kotlin.math.min

data class OverlayLine(
    val box: TextBox,
    val translation: String?,
    val showOriginal: Boolean,
)

class TranslationOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val overlayView = OverlayView(context)
    private val bubble = ControlBubble(context)
    private var overlayParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var attached = false

    var onPauseToggle: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null

    fun attach() {
        if (attached) return
        val overlay = fullscreenParams().also { overlayParams = it }
        val controls = bubbleParams().also { bubbleParams = it }
        windowManager.addView(overlayView, overlay)
        windowManager.addView(bubble, controls)
        bubble.onPauseToggle = { onPauseToggle?.invoke() }
        bubble.onStop = { onStop?.invoke() }
        bubble.onDrag = { dx, dy ->
            val params = bubbleParams
            if (params != null) {
            params.x -= dx.toInt()
            params.y += dy.toInt()
                windowManager.updateViewLayout(bubble, params)
            }
        }
        attached = true
    }

    fun update(lines: List<OverlayLine>) {
        overlayView.lines = lines
        overlayView.invalidate()
    }

    fun clearLines() {
        overlayView.lines = emptyList()
        overlayView.invalidate()
    }

    fun setPaused(paused: Boolean) {
        bubble.paused = paused
        bubble.invalidate()
    }

    fun setStatus(status: String) {
        bubble.status = status
        bubble.invalidate()
    }

    fun detach() {
        if (!attached) return
        runCatching { windowManager.removeView(overlayView) }
        runCatching { windowManager.removeView(bubble) }
        attached = false
    }

    private fun fullscreenParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun bubbleParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 180
        }
    }
}

private class OverlayView(context: Context) : View(context) {
    var lines: List<OverlayLine> = emptyList()

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E612140F") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2B657")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val translationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val originalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C8C2B0")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val rect = RectF()
    private val screenLocation = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(screenLocation)
        canvas.save()
        canvas.translate(-screenLocation[0].toFloat(), -screenLocation[1].toFloat())
        val screenWidth = (screenLocation[0] + width).toFloat().coerceAtLeast(1f)
        for (line in lines) {
            val text = line.translation ?: line.box.text
            val pending = line.translation == null
            translationPaint.color = if (pending) Color.parseColor("#F0D48A") else Color.WHITE
            val density = resources.displayMetrics.density
            val preferred = (line.box.height * 0.72f).coerceIn(13f * density, 32f * density)
            translationPaint.textSize = preferred
            originalPaint.textSize = preferred * 0.72f
            val maxWidth = min(screenWidth - 16f, max(line.box.width * 1.7f, preferred * 8f))
            val content = buildLayout(text, translationPaint, maxWidth.toInt().coerceAtLeast(1))
            val original = if (line.showOriginal && line.translation != null) {
                buildLayout(line.box.text, originalPaint, maxWidth.toInt().coerceAtLeast(1))
            } else {
                null
            }
            val pad = dp(6f)
            val blockHeight = content.height + (original?.height ?: 0) + pad * 2
            val blockWidth = max(content.width, original?.width ?: 0) + pad * 2 + dp(4f)
            var left = line.box.left
            var top = line.box.top
            if (left + blockWidth > screenWidth - dp(4f)) {
                left = (screenWidth - blockWidth - dp(4f)).coerceAtLeast(dp(4f))
            }
            val screenHeight = (screenLocation[1] + height).toFloat()
            if (top + blockHeight > screenHeight) {
                top = (screenHeight - blockHeight - dp(4f)).coerceAtLeast(0f)
            }
            rect.set(left, top, left + blockWidth, top + blockHeight)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), background)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), stroke)
            var textTop = top + pad
            original?.let {
                canvas.save()
                canvas.translate(left + pad, textTop)
                it.draw(canvas)
                canvas.restore()
                textTop += it.height
            }
            canvas.save()
            canvas.translate(left + pad, textTop)
            content.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private class ControlBubble(context: Context) : View(context) {
    var paused: Boolean = false
    var status: String = "Перевод"
    var onPauseToggle: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2E2B657") }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1408")
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1408")
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = dp(148f).toInt()
        val height = dp(56f).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(18f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, fill)
        val pauseLeft = dp(18f)
        val midY = height / 2f
        if (paused) {
            val pathLeft = pauseLeft
            canvas.drawLine(pathLeft, midY - dp(8f), pathLeft + dp(8f), midY, ink)
            canvas.drawLine(pathLeft + dp(8f), midY, pathLeft, midY + dp(8f), ink)
        } else {
            canvas.drawLine(pauseLeft, midY - dp(8f), pauseLeft, midY + dp(8f), ink)
            canvas.drawLine(pauseLeft + dp(6f), midY - dp(8f), pauseLeft + dp(6f), midY + dp(8f), ink)
        }
        canvas.drawLine(dp(46f), midY - dp(8f), dp(58f), midY + dp(8f), ink)
        canvas.drawLine(dp(58f), midY - dp(8f), dp(46f), midY + dp(8f), ink)
        val caption = status.take(16)
        canvas.drawText(caption, dp(100f), midY + label.textSize * 0.35f, label)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                if (!dragging && (kotlin.math.abs(event.rawX - downX) > dp(6f) || kotlin.math.abs(event.rawY - downY) > dp(6f))) {
                    dragging = true
                }
                if (dragging) onDrag?.invoke(dx, dy)
                lastX = event.rawX
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    when {
                        event.x < width * 0.28f -> onPauseToggle?.invoke()
                        event.x < width * 0.50f -> onStop?.invoke()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
