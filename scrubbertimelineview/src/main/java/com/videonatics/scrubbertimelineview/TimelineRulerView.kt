package com.videonatics.scrubbertimelineview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.*
import android.widget.OverScroller
import androidx.core.view.doOnLayout
import kotlin.math.*

/**
 * TimelineRulerView - Infinite scroll + pinch-to-zoom (playhead-anchored)
 *
 * - originTimeMs: time that maps to screen X = 0
 * - pxPerMs: pixels per millisecond (zoom)
 *
 * Drawing only uses visible range based on these transforms so you can "scroll infinitely"
 * (in practice limited by Long range).
 */
class TimelineRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Paints
    private val paintTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; strokeWidth = 2f }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 28f }
    private val paintPlayhead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 4f }
    private val paintBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Time units
    private val YEAR_MS = 365L * 24 * 60 * 60 * 1000
    private val DAY_MS = 24L * 60 * 60 * 1000
    private val HOUR_MS = 60L * 60 * 1000
    private val MIN_MS = 60L * 1000

    // Model
    var detectionBars: List<DetectionBar> = emptyList()
    var currentTimeMs: Long = System.currentTimeMillis()
        set(value) {
            field = value
            // keep playhead's time in view (optional)
            ensureTimeVisible(value)
            invalidate()
        }

    var onScrubChanged: ((Long) -> Unit)? = null

    // INTERNAL state: originTimeMs is double for fractional calculations
    // originTimeMs = time in ms that corresponds to screen X = 0
    private var originTimeMs: Double = (System.currentTimeMillis() - YEAR_MS / 2).toDouble()

    // pixels per ms (zoom)
    private var pxPerMs: Double = 0.00005  // default — will be adjusted in onSizeChanged
    private var minPxPerMs: Double = 0.0
    private var maxPxPerMs: Double = 0.0

    // touch tracking
    private var lastTouchX = 0f
    private var isPanning = false

    // scale detector
    private val scaleGesture = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            performScale(detector.scaleFactor.toDouble(), detector.focusX)
            return true
        }
    })

    // Gesture detector (for single-tap, double tap, fling)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val tappedTime = screenXToTime(e.x)
            currentTimeMs = tappedTime
            onScrubChanged?.invoke(tappedTime)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Toggle zoom: if zoomed out -> zoom in around playhead, else reset to fit a day
            val target = if (pxPerMs < (minPxPerMs + maxPxPerMs) / 2.0) {
                // zoom in near max
                maxPxPerMs.coerceAtMost(pxPerMs * 8.0)
            } else {
                // zoom out to show ~1 day if fits, else min
                minPxPerMs.coerceAtLeast(pxPerMs / 8.0)
            }
            animateZoomTo(target.toFloat(), e.x)
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            startFling(-velocityX) // convert finger velocityX to content pixels/sec
            return true
        }
    })

    private fun animateZoomTo(targetPxPerMs: Float, focalX: Float = width / 2f) {
        val startPxPerMs = pxPerMs
        val endPxPerMs = targetPxPerMs.toDouble().coerceIn(minPxPerMs, maxPxPerMs)
        if (abs(endPxPerMs - startPxPerMs) < 1e-9) return

        val duration = 250L
        val startTime = System.currentTimeMillis()

        val startOrigin = originTimeMs
        // Keep focal time constant
        val focalTime = originTimeMs + focalX.toDouble() / startPxPerMs

        val animator = object : Runnable {
            override fun run() {
                val t = ((System.currentTimeMillis() - startTime).toFloat() / duration).coerceIn(0f, 1f)
                val interp = 0.5f - 0.5f * cos(t * Math.PI).toFloat() // smooth easing
                pxPerMs = startPxPerMs + (endPxPerMs - startPxPerMs) * interp
                originTimeMs = focalTime - focalX.toDouble() / pxPerMs

                invalidate()

                if (t < 1f) {
                    postOnAnimation(this)
                }
            }
        }

        postOnAnimation(animator)
    }

    // Fling support
    private val scroller = OverScroller(context)
    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                // scroller.currX is in pixels; we treat it as originPx
                val originPx = scroller.currX.toDouble()
                // convert originPx back to originTimeMs: originTimeMs = originPx / pxPerMs
                // but pxPerMs may change later; here we use current pxPerMs (approx)
                if (pxPerMs != 0.0) {
                    originTimeMs = originPx / pxPerMs
                }
                invalidate()
                postOnAnimation(this)
            }
        }
    }

    init {
        isFocusable = true
        isClickable = true
        // set initial pxPerMs when view size becomes available
        doOnLayout {
            setupInitialScale()
        }
    }

    private fun setupInitialScale() {
        // default behavior: show ~7 days across view width if possible; else fit full year
        val w = width.coerceAtLeast(1)
        val pxPerMsFitYear = w.toDouble() / YEAR_MS.toDouble()
        val pxPerMs7Days = w.toDouble() / (7.0 * DAY_MS.toDouble())
        // allow zoom range: min = fit full year, max = many px per ms (very zoomed in)
        minPxPerMs = pxPerMsFitYear
        maxPxPerMs = max(minPxPerMs * 5000.0, 0.001) // tune as required (so 1 second can be many px)
        pxPerMs = pxPerMs7Days.coerceIn(minPxPerMs, maxPxPerMs)

        // center origin so currentTimeMs is at centerX
        originTimeMs = currentTimeMs - (width / 2.0) / pxPerMs
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // recalc min/max and keep originTime consistent with center
        if (w > 0) {
            val centerTime = screenXToTime(w / 2f)
            setupInitialScale()
            // try to keep centerTime same
            originTimeMs = centerTime - (w / 2.0) / pxPerMs
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val lineY = h / 2f

        // compute visible time range (ms)
        val visibleStartMs = screenXToTime(0f)
        val visibleEndMs = screenXToTime(w)

        // draw baseline
        canvas.drawLine(0f, lineY, w, lineY, paintTick)

        // draw detection bars (only those intersecting visible)
        for (bar in detectionBars) {
            val left = timeToScreenX(bar.startTimeMillis)
            val right = timeToScreenX(bar.endTimeMillis)
            if (right >= -100f && left <= w + 100f) {
                paintBar.color = bar.color
                val top = lineY - 28f
                val bottom = lineY + 28f
                canvas.drawRect(left, top, right, bottom, paintBar)
            }
        }

        // draw ticks adaptively
        drawTicks(canvas, visibleStartMs, visibleEndMs, lineY)

        // draw playhead: centerX
        val playheadX = w / 2f
        canvas.drawLine(playheadX, 0f, playheadX, h, paintPlayhead)
    }

    private fun drawTicks(canvas: Canvas, visibleStartMs: Long, visibleEndMs: Long, lineY: Float) {
        val rangeMs = (visibleEndMs - visibleStartMs).coerceAtLeast(1L)
        val tickInterval = chooseTickInterval(rangeMs)

        // align first tick to interval boundary
        var t = (visibleStartMs / tickInterval) * tickInterval
        if (t < visibleStartMs) t += tickInterval

        val labelY = lineY - 36f
        val w = width.toFloat()

        while (t <= visibleEndMs) {
            val x = timeToScreenX(t)
            if (x >= -50f && x <= w + 50f) {
                canvas.drawLine(x, lineY - 12f, x, lineY + 12f, paintTick)
                val label = formatLabelForRange(t, rangeMs)
                val textWidth = paintText.measureText(label)
                val drawX = when {
                    x + textWidth / 2f > w -> w - textWidth
                    x - textWidth / 2f < 0 -> 0f
                    else -> x - textWidth / 2f
                }
                canvas.drawText(label, drawX, labelY, paintText)
            }
            t += tickInterval
        }
    }

    private fun chooseTickInterval(rangeMs: Long): Long {
        return when {
            rangeMs <= 10_000L -> 1_000L         // 1s
            rangeMs <= 60_000L -> 5_000L         // 5s
            rangeMs <= 300_000L -> 10_000L       // 10s
            rangeMs <= 3_600_000L -> 60_000L     // 1m
            rangeMs <= 86_400_000L -> 60 * 60_000L // 1h
            rangeMs <= 7 * 86_400_000L -> 24 * 60 * 60_000L // 1d
            else -> 30 * DAY_MS
        }
    }

    private fun formatLabelForRange(tickMs: Long, visibleRangeMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = tickMs }
        return when {
            visibleRangeMs <= 60_000L -> { // <= 1 minute -> show seconds
                String.format("%02d:%02d", cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
            }
            visibleRangeMs <= 3_600_000L -> { // <= 1 hour -> HH:mm
                String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            }
            visibleRangeMs <= DAY_MS -> {
                String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            }
            visibleRangeMs <= 7 * DAY_MS -> {
                String.format("%02d %s", cal.get(java.util.Calendar.DAY_OF_MONTH),
                    cal.getDisplayName(java.util.Calendar.MONTH, java.util.Calendar.SHORT, java.util.Locale.getDefault()))
            }
            visibleRangeMs <= 90L * DAY_MS -> {
                String.format("%s %02d", cal.getDisplayName(java.util.Calendar.MONTH, java.util.Calendar.SHORT, java.util.Locale.getDefault()),
                    cal.get(java.util.Calendar.DAY_OF_MONTH))
            }
            else -> {
                cal.getDisplayName(java.util.Calendar.MONTH, java.util.Calendar.SHORT, java.util.Locale.getDefault()) ?: ""
            }
        }
    }

    // ---------- coordinate transforms ----------
    private fun screenXToTime(x: Float): Long {
        // time = originTimeMs + (x / pxPerMs)
        if (pxPerMs == 0.0) return System.currentTimeMillis()
        val t = originTimeMs + x.toDouble() / pxPerMs
        // round to long ms
        return t.roundToLong()
    }

    private fun timeToScreenX(timeMs: Long): Float {
        // x = (time - originTimeMs) * pxPerMs
        val x = (timeMs.toDouble() - originTimeMs) * pxPerMs
        return x.toFloat()
    }

    // ---------- touch handling ----------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // First pass to scale detector
        scaleGesture.onTouchEvent(event)

        // If scaling in progress, we prefer pinch gestures (don't pan)
        if (scaleGesture.isInProgress) {
            // reset pan state
            isPanning = false
            lastTouchX = 0f
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                // nothing
            }
            return true
        }

        // Gesture detector for tap/fling/doubletap
        gestureDetector.onTouchEvent(event)

        // Manual panning (drag)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // stop any fling
                if (!scroller.isFinished) {
                    scroller.forceFinished(true)
                }
                isPanning = true
                lastTouchX = event.x
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning) {
                    val dx = event.x - lastTouchX
                    lastTouchX = event.x
                    // Update originTimeMs so that content follows finger:
                    // moving finger right (dx > 0) should move timeline right (we show earlier times),
                    // so originTimeMs decreases by dx/pxPerMs. Using subtraction gives natural feel.
                    if (pxPerMs != 0.0) {
                        originTimeMs -= dx.toDouble() / pxPerMs
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                lastTouchX = 0f
            }
        }
        return true
    }

    // ---------- scaling anchored at playhead (centerX by default) ----------
    private fun performScale(scaleFactor: Double, focalX: Float) {
        if (pxPerMs == 0.0) return
        val oldPxPerMs = pxPerMs
        val newPxPerMs = (pxPerMs * scaleFactor).coerceIn(minPxPerMs.takeIf { it > 0 } ?: 0.0, maxPxPerMs.takeIf { it > 0 } ?: Double.MAX_VALUE)
        if (newPxPerMs == oldPxPerMs) return

        // keep the time at focalX stable: focalTime = originTimeMs + focalX / oldPxPerMs
        val focalTime = originTimeMs + focalX.toDouble() / oldPxPerMs

        pxPerMs = newPxPerMs

        // recompute originTimeMs so focalTime remains under same screen X
        originTimeMs = focalTime - focalX.toDouble() / pxPerMs

        // optional: clamp originTimeMs to avoid overflow (not strictly necessary)
        // originTimeMs = originTimeMs.coerceIn(Long.MIN_VALUE + 1_000_000L.toDouble(), Long.MAX_VALUE.toDouble() - 1_000_000L.toDouble())

        invalidate()
    }

    // ---------- fling (pixel-domain) ----------
    private fun startFling(velocityX: Float) {
        // compute current originPx = originTimeMs * pxPerMs
        val originPx = (originTimeMs * pxPerMs).toInt()
        // set fling range very large to emulate infinite
        val minX = Int.MIN_VALUE / 4
        val maxX = Int.MAX_VALUE / 4
        scroller.fling(
            originPx, 0,
            velocityX.toInt(), 0,
            minX, maxX,
            0, 0
        )
        // run fling runner
        if (isAttachedToWindow) {
            postOnAnimation(flingRunnable)
        }
    }

    // ---------- helpers ----------
    private fun ensureTimeVisible(timeMs: Long) {
        // Center the view on timeMs (optional). Called when currentTimeMs changed programmatically.
        val w = width.takeIf { it > 0 } ?: return
        val centerX = w / 2f
        val targetOrigin = timeMs - (centerX / pxPerMs).toLong()
        originTimeMs = targetOrigin.toDouble()
    }

    // ---------- API helpers ----------
    fun setRange(centerTimeMs: Long, rangeMs: Long) {
        // optional helper: set the visible center and approximate scale to show range across width
        val w = width.takeIf { it > 0 } ?: return
        pxPerMs = w.toDouble() / rangeMs.toDouble()
        originTimeMs = centerTimeMs - (w / 2.0) / pxPerMs
        // update bounds
        minPxPerMs = (w.toDouble() / YEAR_MS.toDouble())
        maxPxPerMs = max(minPxPerMs * 5000.0, pxPerMs * 10.0)
        invalidate()
    }

    fun seekTo(timeMs: Long) {
        currentTimeMs = timeMs
        onScrubChanged?.invoke(timeMs)
    }
}
