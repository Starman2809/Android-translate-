package com.kostysetinin.gametranslate.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.kostysetinin.gametranslate.prefs.CaptureRegion
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen touch layer drawn over the game so the player can outline the
 * dialogue or subtitle area. The rest of the screen stays visible underneath.
 */
class RegionPicker(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var host: LinearLayout? = null

    fun show(onResult: (CaptureRegion) -> Unit, onCancel: () -> Unit) {
        if (host != null) return
        val draw = SelectionView(context)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#F212140F"))
        }
        val cancel = button("Отмена") {
            dismiss()
            onCancel()
        }
        val full = button("Весь экран") {
            dismiss()
            onResult(CaptureRegion.FULL)
        }
        val done = button("Готово") {
            val region = draw.regionOrNull()
            dismiss()
            if (region == null) onCancel() else onResult(region)
        }
        bar.addView(cancel, weightParams())
        bar.addView(full, weightParams())
        bar.addView(done, weightParams())
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(draw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        windowManager.addView(root, params)
        host = root
    }

    fun dismiss() {
        val view = host ?: return
        runCatching { windowManager.removeView(view) }
        host = null
    }

    private fun button(label: String, click: () -> Unit): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setOnClickListener { click() }
        }
    }

    private fun weightParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

private class SelectionView(context: Context) : View(context) {
    private val shade = Paint().apply { color = Color.parseColor("#6612140F") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33E2B657") }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2B657")
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    private val selection = RectF()
    private var active = false
    private var startX = 0f
    private var startY = 0f

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun regionOrNull(): CaptureRegion? {
        if (selection.width() < dp(32f) || selection.height() < dp(32f)) return null
        val location = IntArray(2)
        getLocationOnScreen(location)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            Rect(0, 0, point.x, point.y)
        }
        val screenWidth = bounds.width().toFloat().coerceAtLeast(1f)
        val screenHeight = bounds.height().toFloat().coerceAtLeast(1f)
        val left = ((location[0] + selection.left) / screenWidth).coerceIn(0f, 1f)
        val top = ((location[1] + selection.top) / screenHeight).coerceIn(0f, 1f)
        val right = ((location[0] + selection.right) / screenWidth).coerceIn(0f, 1f)
        val bottom = ((location[1] + selection.bottom) / screenHeight).coerceIn(0f, 1f)
        if (right - left < 0.02f || bottom - top < 0.02f) return null
        return CaptureRegion(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        if (!selection.isEmpty) {
            canvas.drawRect(selection, fill)
            canvas.drawRect(selection, border)
        }
        canvas.drawText(
            "Обведите область с текстом игры",
            width / 2f,
            dp(36f),
            hint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true
                startX = event.x
                startY = event.y
                selection.set(startX, startY, startX, startY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return false
                selection.set(
                    min(startX, event.x),
                    min(startY, event.y),
                    max(startX, event.x),
                    max(startY, event.y),
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
