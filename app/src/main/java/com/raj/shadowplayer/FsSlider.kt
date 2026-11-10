package com.raj.shadowplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.view.View

/**
 * Thin horizontal line with a ring-shaped thumb (full screen video: position bar and volume bar).
 * Values are fractions 0..1. [onStart] / [onChange] / [onEnd] only fire for the user's finger.
 */
class FsSlider(ctx: Context) : View(ctx) {
    var onStart: (() -> Unit)? = null
    var onChange: ((Float) -> Unit)? = null
    var onEnd: ((Float) -> Unit)? = null
    var dragging = false
        private set

    /** Fires when the finger taps a star / bookmark marker (subtitle index), instead of scrubbing. */
    var onMarkerTap: ((Int) -> Unit)? = null

    private var marks: List<SentenceEngine.Mark> = emptyList()
    private var marksSig = 0
    private var durMs = 0L
    private var pendingMark = -1
    private var downX = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markRect = RectF()

    private var frac = 0f
    private var leftText = ""
    private var rightText = ""
    private val d = ctx.resources.displayMetrics.density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * d
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(190, 0, 0, 0)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 255, 255, 255)
        textSize = 12f * ctx.resources.displayMetrics.scaledDensity
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    private fun pad(): Float = 18f * d

    fun setFraction(f: Float) {
        if (dragging) return
        val n = f.coerceIn(0f, 1f)
        if (n != frac) {
            frac = n
            invalidate()
        }
    }

    /** Star / bookmark markers drawn on the bar. Only redraws when something changed. */
    fun setMarks(list: List<SentenceEngine.Mark>) {
        var sig = list.size
        for (m in list) sig = sig * 31 + m.index * 4 + (if (m.star) 1 else 0) + (if (m.bookmark) 2 else 0)
        if (sig == marksSig && list.size == marks.size) return
        marksSig = sig
        marks = list
        invalidate()
    }

    fun setDuration(ms: Long) {
        if (ms != durMs) {
            durMs = ms
            invalidate()
        }
    }

    private fun markX(m: SentenceEngine.Mark): Float {
        val x0 = pad()
        val x1 = width - pad()
        val f = if (durMs > 0L) (m.ms.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else 0f
        return x0 + (x1 - x0) * f
    }

    /** The marker closest to [x], if the finger is within reach of it. */
    private fun markNear(x: Float): Int {
        if (durMs <= 0L) return -1
        var best = -1
        var bestD = 16f * d
        for (m in marks) {
            val dist = Math.abs(markX(m) - x)
            if (dist <= bestD) {
                bestD = dist
                best = m.index
            }
        }
        return best
    }

    fun setLabels(l: String, r: String) {
        if (l != leftText || r != rightText) {
            leftText = l
            rightText = r
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val x0 = pad()
        val x1 = w - pad()
        val cy = h * 0.64f
        val tx = x0 + (x1 - x0) * frac
        track.color = Color.argb(95, 255, 255, 255)
        c.drawLine(x0, cy, x1, cy, track)
        track.color = Color.argb(235, 255, 255, 255)
        c.drawLine(x0, cy, tx, cy, track)
        // star (yellow) and bookmark (blue) markers; both on one sentence = yellow top / blue bottom
        if (durMs > 0L) {
            val halfW = 2.6f * d
            val halfH = 9f * d
            for (m in marks) {
                val mx = markX(m)
                if (m.star && m.bookmark) {
                    markPaint.color = Color.parseColor("#FFD54A")
                    markRect.set(mx - halfW, cy - halfH, mx + halfW, cy)
                    c.drawRoundRect(markRect, halfW, halfW, markPaint)
                    markPaint.color = Color.parseColor("#6EC1FF")
                    markRect.set(mx - halfW, cy, mx + halfW, cy + halfH)
                    c.drawRoundRect(markRect, halfW, halfW, markPaint)
                } else {
                    markPaint.color = if (m.star) Color.parseColor("#FFD54A") else Color.parseColor("#6EC1FF")
                    markRect.set(mx - halfW, cy - halfH, mx + halfW, cy + halfH)
                    c.drawRoundRect(markRect, halfW, halfW, markPaint)
                }
            }
        }
        val r = (if (dragging) 10f else 8f) * d
        c.drawCircle(tx, cy, r, fill)
        c.drawCircle(tx, cy, r, ring)
        val ty = cy - 16f * d
        text.textAlign = Paint.Align.LEFT
        if (leftText.isNotEmpty()) c.drawText(leftText, x0, ty, text)
        text.textAlign = Paint.Align.RIGHT
        if (rightText.isNotEmpty()) c.drawText(rightText, x1, ty, text)
    }

    private fun setFromX(x: Float) {
        val x0 = pad()
        val x1 = width - pad()
        frac = if (x1 > x0) ((x - x0) / (x1 - x0)).coerceIn(0f, 1f) else 0f
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val hit = markNear(e.x)
                if (hit >= 0) {
                    // on a marker: wait to see whether this is a tap (jump there) or the start of a slide
                    pendingMark = hit
                    downX = e.x
                    return true
                }
                pendingMark = -1
                dragging = true
                setFromX(e.x)
                onStart?.invoke()
                onChange?.invoke(frac)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pendingMark >= 0) {
                    if (Math.abs(e.x - downX) > slop) {
                        pendingMark = -1
                        dragging = true
                        setFromX(e.x)
                        onStart?.invoke()
                        onChange?.invoke(frac)
                    }
                    return true
                }
                if (dragging) {
                    setFromX(e.x)
                    onChange?.invoke(frac)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pendingMark >= 0) {
                    val idx = pendingMark
                    pendingMark = -1
                    onMarkerTap?.invoke(idx)
                    return true
                }
                if (dragging) {
                    setFromX(e.x)
                    dragging = false
                    invalidate()
                    onEnd?.invoke(frac)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingMark = -1
                if (dragging) {
                    dragging = false
                    invalidate()
                    onEnd?.invoke(frac)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
