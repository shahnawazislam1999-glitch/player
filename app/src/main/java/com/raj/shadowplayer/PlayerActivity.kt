package com.raj.shadowplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** The full screen player. Opens in landscape (or portrait if you chose that last time) with every control hidden. */
class PlayerActivity : AppCompatActivity() {
    private lateinit var ui: PlayerUi
    private var pendingKind = "main"
    private var leavingForPicker = false     // a file picker / settings screen is opening: pause as usual instead of playing on

    private var openNotifSettingsIfDenied = false
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // asked from the floating-window screen and refused for good: send the learner to the app's notification settings
        if (!granted && openNotifSettingsIfDenied) openNotificationSettings()
        openNotifSettingsIfDenied = false
    }

    private val subsPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) ui.onSubsPicked(pendingKind, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive++
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui = PlayerUi(this)
        FloatBridge.attach(ui)
        ui.requestVideo = { delta -> openRelative(delta) }
        requestedOrientation = ui.fsOrientation()
        setContentView(ui.panel)
        hideBars()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!ui.handleBack()) finish()
            }
        })

        val item = resolveItem(intent)
        if (item == null) {
            finish()
            return
        }
        ui.open(item)
        // smart pen + background play: keep the app alive while minimized
        if (ui.backgroundPlayOn()) PenService.start(this)
        ui.applyFloat()
        val sp = getSharedPreferences("shadowplayer", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !sp.getBoolean("asked_notif", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            sp.edit().putBoolean("asked_notif", true).apply()
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // smart pen / page turner keys while the app is in front (next / previous sentence)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 && ui.handlePenKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun resolveItem(i: Intent?): VideoItem? {
        val uri = i?.data
        if (i != null && i.action == Intent.ACTION_VIEW && uri != null) {
            // opened from a file manager / another app
            var name = "video"
            var size = 0L
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0) name = c.getString(ni) ?: name
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (e: Exception) {
                // keep defaults
            }
            if (name == "video") name = uri.lastPathSegment ?: name
            val path = if (uri.scheme == "file") uri.path else null
            val item = VideoItem("u" + uri.toString().hashCode(), uri, name, 0L, size, "", 0L, path)
            Playlist.items = listOf(item)
            Playlist.index = 0
            return item
        }
        return Playlist.items.getOrNull(Playlist.index)
    }

    /** Previous (-1) / next (+1) video of the list. */
    private fun openRelative(delta: Int): Boolean {
        val n = Playlist.index + delta
        if (n < 0 || n >= Playlist.items.size) return false
        Playlist.index = n
        val item = Playlist.items[n]
        ui.panel.post { ui.open(item) }
        return true
    }

    fun launchSubsPicker(kind: String) {
        pendingKind = kind
        leavingForPicker = true
        subsPicker.launch(arrayOf("*/*"))     // .srt has no Android MIME type, so never filter
    }

    fun askAllFilesAccess() {
        leavingForPicker = true
        com.raj.shadowplayer.askAllFilesAccess(this)
    }

    // ---- permissions for the floating subtitle window (opened from the 3-dot menu > Floating subtitle window)

    fun askOverlayPermission() {
        leavingForPicker = true
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            leavingForPicker = false
            Toast.makeText(this, "Open Settings > Apps > ShadowPlayer > Display over other apps", Toast.LENGTH_LONG).show()
        }
    }

    fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun askNotificationPermission() {
        if (notificationsAllowed()) return
        openNotifSettingsIfDenied = true
        askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        leavingForPicker = true
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (e: Exception) {
            leavingForPicker = false
        }
    }

    fun batteryUnrestricted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    fun askBatteryExemption() {
        leavingForPicker = true
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            leavingForPicker = false
            try {
                leavingForPicker = true
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                leavingForPicker = false
            }
        }
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // portrait <-> landscape: the activity is not recreated, so re-place the subtitle for the new screen shape
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        ui.panel.post { ui.relayout() }
        hideBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onStart() {
        super.onStart()
        FloatBridge.setFront(true)      // the player is on screen: the floating subtitle window steps aside
    }

    override fun onStop() {
        // minimized (not just a picker / settings screen opening): the floating subtitle window may show
        if (!leavingForPicker) FloatBridge.setFront(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        leavingForPicker = false
        hideBars()
        ui.onResume()
        ui.applyFloat()     // e.g. back from the "Display over other apps" settings page
    }

    override fun onPause() {
        // minimized or screen off: keep playing when "Play when minimized" is on; leaving for good (back) or opening a picker pauses
        if (ui.backgroundPlayOn() && !isFinishing && !leavingForPicker) ui.onBackground() else ui.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        FloatBridge.detach(ui)
        ui.release()
        alive--
        if (alive <= 0) {
            alive = 0
            PenService.stop(this)
            FloatService.stop(this)
            FloatBridge.setFront(false)
        }
        super.onDestroy()
    }

    companion object {
        private var alive = 0      // player screens that exist; the background service stops with the last one
    }
}
