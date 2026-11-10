package com.raj.shadowplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Link between the player screen and the floating subtitle window. Lives in the app process; the window itself is drawn by
 * [FloatService]. All calls happen on the main thread.
 */
object FloatBridge {
    private var ui: PlayerUi? = null
    var text: String = ""
        private set
    var playing: Boolean = false
        private set
    /** True while the player screen is on screen: the floating window is not needed then. */
    var appInFront: Boolean = false
        private set
    var service: FloatService? = null

    fun attach(p: PlayerUi) { ui = p }

    fun detach(p: PlayerUi) {
        if (ui === p) {
            ui = null
            text = ""
            playing = false
            service?.refresh()
        }
    }

    fun onSubtitle(t: String, isPlaying: Boolean) {
        text = t
        playing = isPlaying
        service?.refresh()
    }

    fun setFront(front: Boolean) {
        if (appInFront == front) return
        appInFront = front
        service?.refresh()
    }

    /** prev / next / toggle sent by the buttons of the floating window. */
    fun cmd(op: String) {
        ui?.engine?.cmd(op)
    }

    fun hasPlayer(): Boolean = ui != null
}

/**
 * Floating subtitle window (like a music player's mini bar): only the current subtitle line with previous / play-pause / next,
 * drawn over other apps while the player is minimized. Tap the sentence to copy it. The notification has a Hide / Show switch.
 */
class FloatService : Service() {
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var sentence: TextView? = null
    private var playBtn: ImageView? = null
    private var added = false
    private var lastNotifHidden: Boolean? = null     // the notification only changes when Hide / Show changes

    private fun prefs() = getSharedPreferences("shadowplayer", Context.MODE_PRIVATE)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    private fun manualHidden(): Boolean = prefs().getBoolean(PREF_HIDDEN, false)

    private fun notification(): Notification {
        val hidden = manualHidden()
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this, 3, Intent(this, FloatService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val close = PendingIntent.getService(
            this, 4, Intent(this, FloatService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Floating subtitle window")
            .setContentText(if (hidden) "Hidden. Tap Show to bring it back." else "Shows over other apps while ShadowPlayer is minimized")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (hidden) "Show" else "Hide", toggle)
            .addAction(0, "Turn off", close)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = nm()
        if (n.getNotificationChannel(CHANNEL) == null) {
            n.createNotificationChannel(NotificationChannel(CHANNEL, "Floating subtitle window", NotificationManager.IMPORTANCE_LOW))
        }
        try {
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE -> prefs().edit().putBoolean(PREF_HIDDEN, !manualHidden()).apply()
            ACTION_CLOSE -> {
                prefs().edit().putBoolean(PREF_ON, false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            // permission was taken away: nothing can be drawn
            stopSelf()
            return START_NOT_STICKY
        }
        FloatBridge.service = this
        ensureWindow()
        refresh()
        return START_NOT_STICKY
    }

    private fun ensureWindow() {
        if (root != null) return
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(4), dp(12), dp(8))
        box.background = GradientDrawable().apply {
            setColor(Color.argb(235, 20, 27, 52))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }

        // drag handle
        val handleHolder = LinearLayout(this)
        handleHolder.gravity = Gravity.CENTER
        val pill = View(this)
        pill.background = GradientDrawable().apply {
            setColor(Color.argb(150, 255, 255, 255))
            cornerRadius = dp(2).toFloat()
        }
        handleHolder.addView(pill, LinearLayout.LayoutParams(dp(40), dp(4)))
        box.addView(handleHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))

        val tv = TextView(this)
        tv.setTextColor(Color.parseColor("#FFE24A"))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.maxLines = 5
        tv.setPadding(dp(4), dp(2), dp(4), dp(6))
        tv.contentDescription = "Current subtitle. Tap to copy."
        tv.setOnClickListener { copySentence() }
        box.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sentence = tv

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        fun btn(res: Int, desc: String, op: () -> Unit): ImageView {
            val b = ImageView(this)
            b.setImageResource(res)
            b.setColorFilter(Color.WHITE)
            b.setPadding(dp(10), dp(10), dp(10), dp(10))
            b.contentDescription = desc
            b.setOnClickListener { op() }
            row.addView(b, LinearLayout.LayoutParams(dp(52), dp(48)))
            return b
        }
        btn(R.drawable.ic_prev, "Previous sentence") { FloatBridge.cmd("prev") }
        playBtn = btn(R.drawable.ic_play, "Play or pause") { FloatBridge.cmd("toggle") }
        btn(R.drawable.ic_next, "Next sentence") { FloatBridge.cmd("next") }
        btn(R.drawable.ic_close, "Hide the floating window") { hideByUser() }
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dm = resources.displayMetrics
        val w = minOf((dm.widthPixels * 0.94f).toInt(), dp(560))
        val params = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = prefs().getInt(PREF_Y, dp(120)).coerceIn(0, (dm.heightPixels - dp(120)).coerceAtLeast(0))
        lp = params

        // moving the window: drag the handle at the top
        var startY = 0
        var touchY = 0f
        handleHolder.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxY = (resources.displayMetrics.heightPixels - dp(100)).coerceAtLeast(0)
                    params.y = (startY + (e.rawY - touchY)).toInt().coerceIn(0, maxY)
                    try { if (added) manager.updateViewLayout(box, params) } catch (ex: Exception) { }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> prefs().edit().putInt(PREF_Y, params.y).apply()
            }
            true
        }
        root = box
    }

    private fun copySentence() {
        val t = FloatBridge.text.trim()
        if (t.isEmpty()) {
            Toast.makeText(applicationContext, "No subtitle to copy", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("subtitle", t))
            Toast.makeText(applicationContext, "Sentence copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "Could not copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideByUser() {
        prefs().edit().putBoolean(PREF_HIDDEN, true).apply()
        refresh()
    }

    /** Redraws the text / play icon and shows or hides the window: visible only while minimized and not hidden from the notification. */
    fun refresh() {
        val box = root ?: return
        val manager = wm ?: return
        val params = lp ?: return
        val t = FloatBridge.text
        sentence?.text = if (t.isEmpty()) "ShadowPlayer \u2022 waiting for the next sentence..." else t
        playBtn?.setImageResource(if (FloatBridge.playing) R.drawable.ic_pause else R.drawable.ic_play)
        val show = FloatBridge.hasPlayer() && !FloatBridge.appInFront && !manualHidden()
        try {
            if (show && !added) {
                manager.addView(box, params)
                added = true
            } else if (!show && added) {
                manager.removeView(box)
                added = false
            }
        } catch (e: Exception) {
            // overlay permission gone or window refused
            added = false
        }
        val hiddenNow = manualHidden()
        if (lastNotifHidden != hiddenNow) {
            lastNotifHidden = hiddenNow
            try { nm().notify(NOTIF_ID, notification()) } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        FloatBridge.service = null
        val box = root
        val manager = wm
        if (added && box != null && manager != null) {
            try { manager.removeView(box) } catch (e: Exception) { }
        }
        added = false
        root = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "shadowplayer_float"
        private const val NOTIF_ID = 4740
        private const val ACTION_TOGGLE = "com.raj.shadowplayer.FLOAT_TOGGLE"
        private const val ACTION_CLOSE = "com.raj.shadowplayer.FLOAT_CLOSE"
        const val PREF_ON = "float_on"          // the learner turned the floating window on
        const val PREF_HIDDEN = "float_hidden"  // hidden with the X or the notification switch
        private const val PREF_Y = "float_y"

        fun wanted(ctx: Context): Boolean =
            ctx.applicationContext.getSharedPreferences("shadowplayer", Context.MODE_PRIVATE).getBoolean(PREF_ON, false)

        fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, FloatService::class.java))
            } catch (e: Exception) {
                // Android refused (app not in the foreground)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.applicationContext.stopService(Intent(ctx.applicationContext, FloatService::class.java))
            } catch (e: Exception) {
                // already stopped
            }
        }
    }
}
