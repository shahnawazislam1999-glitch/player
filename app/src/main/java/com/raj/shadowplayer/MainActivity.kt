package com.raj.shadowplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Size
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** The library: scans the phone for videos (all formats Android knows), lists them, opens the player. */
class MainActivity : AppCompatActivity() {

    private class Row(val folder: String?, val count: Int, val video: VideoItem?)

    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8L).toInt().coerceAtLeast(1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val prefs by lazy { getSharedPreferences("shadowplayer", Context.MODE_PRIVATE) }

    private var all: List<VideoItem> = emptyList()
    private var rows: List<Row> = emptyList()
    private var openFolder: String? = null
    private var foldersMode = false
    private var query = ""
    private var sortMode = 0          // 0 newest, 1 name, 2 size, 3 length
    private var scanning = false
    private var filterMode = 0        // 0 all videos, 1 recently played, 2 favorites
    private var favs: Set<String> = emptySet()

    private lateinit var titleView: TextView
    private lateinit var searchBox: EditText
    private lateinit var list: RecyclerView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var emptyBtn: Button
    private lateinit var foldersBtn: ImageView
    private lateinit var continueCard: LinearLayout
    private lateinit var contThumb: ImageView
    private lateinit var contTitle: TextView
    private lateinit var contMeta: TextView
    private lateinit var contProgress: ProgressBar
    private val chips = ArrayList<LinearLayout>()
    private val adapter = Adapter()

    private val askNotifDl = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val askPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasPerm()) scan() else showEmpty("File access is needed to list your videos.", true)
    }

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasPerm(): Boolean = neededPerms().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun iconBtn(iconRes: Int, desc: String, onClick: (View) -> Unit): ImageView {
        val t = ImageView(this)
        t.setImageResource(iconRes)
        t.setColorFilter(Color.WHITE)
        t.scaleType = ImageView.ScaleType.CENTER_INSIDE
        t.setPadding(dp(11), dp(11), dp(11), dp(11))
        t.contentDescription = desc
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        t.background = ta.getDrawable(0)
        ta.recycle()
        t.setOnClickListener { onClick(it) }
        return t
    }

    private fun makeChip(iconRes: Int, label: String, mode: Int): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(12), 0, dp(14), 0)
        val ic = ImageView(this)
        ic.setImageResource(iconRes)
        ic.tag = "ic"
        c.addView(ic, LinearLayout.LayoutParams(dp(18), dp(18)))
        val tv = TextView(this)
        tv.text = label
        tv.tag = "tx"
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.setPadding(dp(6), 0, 0, 0)
        c.addView(tv)
        c.contentDescription = label
        c.setOnClickListener { setFilter(mode) }
        return c
    }

    private fun styleChips() {
        for ((i, c) in chips.withIndex()) {
            val on = i == filterMode
            val bg = GradientDrawable()
            bg.cornerRadius = dp(20).toFloat()
            if (on) {
                bg.setColor(ContextCompat.getColor(this, R.color.accent))
            } else {
                bg.setColor(ContextCompat.getColor(this, R.color.surface))
                bg.setStroke(dp(1), ContextCompat.getColor(this, R.color.border))
            }
            c.background = bg
            val fg = if (on) Color.parseColor("#1A1020") else ContextCompat.getColor(this, R.color.text)
            (c.findViewWithTag<ImageView>("ic")).setColorFilter(if (i == 2 && !on) Color.parseColor("#FF5C7C") else fg)
            (c.findViewWithTag<TextView>("tx")).setTextColor(fg)
        }
    }

    private fun setFilter(mode: Int) {
        filterMode = mode
        openFolder = null
        rebuild()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sortMode = prefs.getInt("lib_sort", 0)
        foldersMode = prefs.getBoolean("lib_folders", false)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), dp(8), dp(8), dp(8))
        titleView = TextView(this)
        titleView.text = "ShadowPlayer"
        titleView.setTextColor(Color.WHITE)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        titleView.setTypeface(titleView.typeface, android.graphics.Typeface.BOLD)
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconBtn(R.drawable.ic_youtube, "Play a YouTube link") { showYoutubeDialog() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(iconBtn(R.drawable.ic_search, "Search") { toggleSearch() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        foldersBtn = iconBtn(R.drawable.ic_folder, "Folders / all videos") { toggleFolders() }
        bar.addView(foldersBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(iconBtn(R.drawable.ic_sort, "Sort") { showSortMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(iconBtn(R.drawable.ic_more_vert, "More") { showMoreMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        searchBox = EditText(this)
        searchBox.hint = "Search videos"
        searchBox.setHintTextColor(Color.argb(120, 255, 255, 255))
        searchBox.setTextColor(Color.WHITE)
        searchBox.setSingleLine(true)
        searchBox.visibility = View.GONE
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                rebuild()
            }
        })
        val sl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sl.setMargins(dp(16), 0, dp(16), dp(4))
        root.addView(searchBox, sl)

        // filter chips: All / Recent / Favorites
        chips.add(makeChip(R.drawable.ic_video_all, "All", 0))
        chips.add(makeChip(R.drawable.ic_history, "Recent", 1))
        chips.add(makeChip(R.drawable.ic_heart_filled, "Favorites", 2))
        val chipRow = LinearLayout(this)
        chipRow.orientation = LinearLayout.HORIZONTAL
        chipRow.setPadding(dp(16), dp(2), dp(16), dp(8))
        for (c in chips) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
            lp.setMargins(0, 0, dp(8), 0)
            chipRow.addView(c, lp)
        }
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chipRow)
        root.addView(chipScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // "Continue watching" card: one tap resumes the last video
        continueCard = LinearLayout(this)
        continueCard.orientation = LinearLayout.HORIZONTAL
        continueCard.gravity = Gravity.CENTER_VERTICAL
        continueCard.setPadding(dp(10), dp(10), dp(14), dp(10))
        val cbg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#2A1F3D"), Color.parseColor("#1C2547")))
        cbg.cornerRadius = dp(16).toFloat()
        cbg.setStroke(dp(1), Color.parseColor("#5A3A4E"))
        continueCard.background = cbg
        continueCard.visibility = View.GONE
        val cbox = FrameLayout(this)
        val cthumbBg = GradientDrawable()
        cthumbBg.setColor(Color.parseColor("#141B34"))
        cthumbBg.cornerRadius = dp(10).toFloat()
        cbox.background = cthumbBg
        cbox.clipToOutline = true
        contThumb = ImageView(this)
        contThumb.scaleType = ImageView.ScaleType.CENTER_CROP
        cbox.addView(contThumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val scrim = View(this)
        scrim.setBackgroundColor(Color.argb(70, 0, 0, 0))
        cbox.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val playDisc = FrameLayout(this)
        val pdBg = GradientDrawable()
        pdBg.shape = GradientDrawable.OVAL
        pdBg.setColor(ContextCompat.getColor(this, R.color.accent))
        playDisc.background = pdBg
        val playIc = ImageView(this)
        playIc.setImageResource(R.drawable.ic_play)
        playIc.setColorFilter(Color.parseColor("#1A1020"))
        playDisc.addView(playIc, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        cbox.addView(playDisc, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        continueCard.addView(cbox, LinearLayout.LayoutParams(dp(112), dp(63)))
        val ccol = LinearLayout(this)
        ccol.orientation = LinearLayout.VERTICAL
        val cLabel = TextView(this)
        cLabel.text = "CONTINUE WATCHING"
        cLabel.setTextColor(ContextCompat.getColor(this, R.color.accent))
        cLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        cLabel.setTypeface(cLabel.typeface, android.graphics.Typeface.BOLD)
        cLabel.letterSpacing = 0.08f
        contTitle = TextView(this)
        contTitle.setTextColor(Color.WHITE)
        contTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        contTitle.maxLines = 1
        contTitle.ellipsize = TextUtils.TruncateAt.END
        contTitle.setPadding(0, dp(2), 0, 0)
        contProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        contProgress.max = 1000
        contProgress.progressDrawable = run {
            val track = GradientDrawable().also { it.setColor(Color.argb(60, 255, 255, 255)); it.cornerRadius = dp(2).toFloat() }
            val fill = GradientDrawable().also { it.setColor(ContextCompat.getColor(this, R.color.accent)); it.cornerRadius = dp(2).toFloat() }
            android.graphics.drawable.LayerDrawable(arrayOf(track, android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL))).also {
                it.setId(0, android.R.id.background)
                it.setId(1, android.R.id.progress)
            }
        }
        contMeta = TextView(this)
        contMeta.setTextColor(Color.argb(190, 255, 255, 255))
        contMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        contMeta.maxLines = 1
        contMeta.setPadding(0, dp(4), 0, 0)
        ccol.addView(cLabel)
        ccol.addView(contTitle)
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        plp.setMargins(0, dp(6), 0, 0)
        ccol.addView(contProgress, plp)
        ccol.addView(contMeta)
        val cclp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        cclp.setMargins(dp(12), 0, 0, 0)
        continueCard.addView(ccol, cclp)
        val ccardLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ccardLp.setMargins(dp(14), dp(2), dp(14), dp(6))
        root.addView(continueCard, ccardLp)
        styleChips()

        val body = FrameLayout(this)
        list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        emptyBox = LinearLayout(this)
        emptyBox.orientation = LinearLayout.VERTICAL
        emptyBox.gravity = Gravity.CENTER
        emptyBox.setPadding(dp(32), dp(32), dp(32), dp(32))
        emptyText = TextView(this)
        emptyText.setTextColor(Color.argb(200, 255, 255, 255))
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        emptyText.gravity = Gravity.CENTER
        emptyBtn = Button(this)
        emptyBtn.text = "Allow file access"
        emptyBtn.isAllCaps = false
        emptyBtn.setOnClickListener { askPerm.launch(neededPerms()) }
        emptyBox.addView(emptyText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        emptyBox.addView(emptyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        emptyBox.visibility = View.GONE
        body.addView(emptyBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchBox.visibility == View.VISIBLE) {
                    toggleSearch()
                } else if (filterMode != 0) {
                    setFilter(0)
                } else if (openFolder != null && foldersMode) {
                    openFolder = null
                    rebuild()
                } else {
                    finish()
                }
            }
        })

        if (hasPerm()) scan() else askPerm.launch(neededPerms())
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    // ------------------------------------------------------------------ YouTube links

    /** A YouTube link shared to ShadowPlayer (Share menu) or opened with it. */
    private fun handleIncoming(i: Intent?) {
        if (i == null) return
        val text: String = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        } ?: return
        val id = YouTube.extractId(text)
        if (id != null) {
            playYoutube(id, null)
        } else if (i.action == Intent.ACTION_SEND) {
            Toast.makeText(this, "No YouTube link found in the shared text", Toast.LENGTH_LONG).show()
        }
    }

    private fun playYoutube(id: String, title: String?) {
        val item = VideoItem("yt_$id", Uri.parse(YouTube.pageUrl(id)), title ?: "YouTube video", 0L, 0L, "YouTube", System.currentTimeMillis(), null)
        Playlist.items = listOf(item)
        Playlist.index = 0
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun clipboardLink(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: ""
            if (YouTube.extractId(t) != null) t else ""
        } catch (e: Exception) {
            ""
        }
    }

    // ===== YT_DIALOG_BEGIN: "Play a YouTube link" window (thumbnails, Recent + Favourites) =====

    /** A rounded 16:9 thumbnail with a YouTube-icon placeholder (shown until/unless the picture loads) and a small play disc. */
    private fun ytThumb(id: String, discDp: Int): FrameLayout {
        val box = FrameLayout(this)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#141B34"))
        bg.cornerRadius = dp(10).toFloat()
        box.background = bg
        box.clipToOutline = true
        val ph = ImageView(this)
        ph.setImageResource(R.drawable.ic_youtube)
        ph.setColorFilter(Color.argb(90, 255, 255, 255))
        box.addView(ph, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        box.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        YtThumbs.load(this, id, img)
        val scrim = View(this)
        scrim.setBackgroundColor(Color.argb(60, 0, 0, 0))
        box.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val disc = FrameLayout(this)
        val dbg = GradientDrawable()
        dbg.shape = GradientDrawable.OVAL
        dbg.setColor(ContextCompat.getColor(this, R.color.accent))
        disc.background = dbg
        val pi = ImageView(this)
        pi.setImageResource(R.drawable.ic_play)
        pi.setColorFilter(Color.parseColor("#1A1020"))
        disc.addView(pi, FrameLayout.LayoutParams(dp(discDp * 2 / 3), dp(discDp * 2 / 3), Gravity.CENTER))
        box.addView(disc, FrameLayout.LayoutParams(dp(discDp), dp(discDp), Gravity.CENTER))
        return box
    }

    private fun showYoutubeDialog() {
        val act = this
        val textC = ContextCompat.getColor(act, R.color.text)
        val subC = ContextCompat.getColor(act, R.color.sub)
        val accent = ContextCompat.getColor(act, R.color.accent)
        val surf = ContextCompat.getColor(act, R.color.surface)
        val surf2 = ContextCompat.getColor(act, R.color.surface2)
        val borderC = ContextCompat.getColor(act, R.color.border)
        val pink = Color.parseColor("#FF5C7C")
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun shape(fill: Int, stroke: Int?, radiusDp: Int): GradientDrawable {
            val g = GradientDrawable()
            g.setColor(fill)
            g.cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) g.setStroke(dp(1), stroke)
            return g
        }
        fun heartIcon(h: ImageView, on: Boolean) {
            h.setImageResource(if (on) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            h.setColorFilter(if (on) pink else Color.argb(170, 255, 255, 255))
        }
        fun knownTitle(id: String): String? =
            (YouTube.history(act) + YouTube.favorites(act)).firstOrNull { it.id == id }?.title

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(18), dp(18), dp(6))

        // header: YouTube icon + title
        val header = LinearLayout(act)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val hIc = ImageView(act)
        hIc.setImageResource(R.drawable.ic_youtube)
        hIc.setColorFilter(Color.parseColor("#FF3B30"))
        header.addView(hIc, LinearLayout.LayoutParams(dp(30), dp(30)))
        val hTx = TextView(act)
        hTx.text = "Play a YouTube link"
        hTx.setTextColor(textC)
        hTx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        hTx.setTypeface(hTx.typeface, android.graphics.Typeface.BOLD)
        hTx.setPadding(dp(10), 0, 0, 0)
        header.addView(hTx)
        col.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // link field with a clear button
        val input = EditText(act)
        input.hint = "Paste a YouTube link"
        input.setSingleLine(true)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        input.background = null
        input.setTextColor(textC)
        input.setHintTextColor(subC)
        input.setPadding(dp(4), dp(12), dp(4), dp(12))
        input.setText(clipboardLink())
        input.setSelection(input.text.length)
        val inputRow = LinearLayout(act)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        inputRow.setPadding(dp(12), 0, dp(6), 0)
        inputRow.background = shape(surf2, borderC, 14)
        inputRow.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        val clearBtn = ImageView(act)
        clearBtn.setImageResource(R.drawable.ic_close)
        clearBtn.setColorFilter(subC)
        clearBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
        clearBtn.contentDescription = "Clear"
        clearBtn.setOnClickListener { input.setText("") }
        inputRow.addView(clearBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        val inLp = LinearLayout.LayoutParams(MATCH, WRAP)
        inLp.setMargins(0, dp(14), 0, 0)
        col.addView(inputRow, inLp)

        // preview of the pasted link (thumbnail + title + heart)
        val preview = LinearLayout(act)
        preview.orientation = LinearLayout.HORIZONTAL
        preview.gravity = Gravity.CENTER_VERTICAL
        preview.setPadding(dp(8), dp(8), dp(4), dp(8))
        preview.background = shape(Color.parseColor("#1A2347"), borderC, 14)
        preview.visibility = View.GONE
        val prevThumb = FrameLayout(act)
        preview.addView(prevThumb, LinearLayout.LayoutParams(dp(96), dp(54)))
        val prevTitle = TextView(act)
        prevTitle.setTextColor(textC)
        prevTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        prevTitle.maxLines = 2
        prevTitle.ellipsize = TextUtils.TruncateAt.END
        val ptLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        ptLp.setMargins(dp(12), 0, dp(4), 0)
        preview.addView(prevTitle, ptLp)
        val prevHeart = ImageView(act)
        prevHeart.setPadding(dp(10), dp(10), dp(10), dp(10))
        prevHeart.contentDescription = "Add to favourites"
        preview.addView(prevHeart, LinearLayout.LayoutParams(dp(44), dp(44)))
        val pvLp = LinearLayout.LayoutParams(MATCH, WRAP)
        pvLp.setMargins(0, dp(10), 0, 0)
        col.addView(preview, pvLp)

        // picture quality pill (same behaviour as before)
        val heights = intArrayOf(144, 240, 360, 480, 720, 1080)
        val qBtn = TextView(act)
        qBtn.gravity = Gravity.CENTER
        qBtn.setTextColor(textC)
        qBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        qBtn.setPadding(dp(12), dp(12), dp(12), dp(12))
        qBtn.background = shape(surf2, borderC, 14)
        var onQualityChanged: () -> Unit = {}
        fun qText() { qBtn.text = "Picture quality: up to " + YouTube.maxHeight(act) + "p   (tap to change)" }
        qText()
        qBtn.setOnClickListener {
            val cur = YouTube.maxHeight(act)
            val next = heights[(heights.indexOf(cur) + 1).let { if (it <= 0) 0 else it % heights.size }]
            YouTube.setMaxHeight(act, next)
            qText()
            onQualityChanged()
        }
        val qLp = LinearLayout.LayoutParams(MATCH, WRAP)
        qLp.setMargins(0, dp(10), 0, 0)
        col.addView(qBtn, qLp)

        var dialogRef: AlertDialog? = null
        // offline: save the video (at the quality above) + its subtitles, and the list of saved videos
        val dlBtn = TextView(act)
        dlBtn.gravity = Gravity.CENTER
        dlBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        dlBtn.setTypeface(dlBtn.typeface, android.graphics.Typeface.BOLD)
        dlBtn.setPadding(dp(12), dp(12), dp(12), dp(12))
        dlBtn.setTextColor(Color.parseColor("#1A1020"))
        dlBtn.background = shape(accent, null, 14)
        val offBtn = TextView(act)
        offBtn.gravity = Gravity.CENTER
        offBtn.setTextColor(textC)
        offBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        offBtn.setPadding(dp(12), dp(12), dp(12), dp(12))
        offBtn.background = shape(surf2, borderC, 14)
        fun offText() {
            val n = Offline.list(act).size
            offBtn.text = if (n == 0) "Offline videos (none saved)" else "Offline videos ($n saved, " + fmtSize(Offline.totalBytes(act)) + ")"
        }
        offText()
        fun dlText(id: String?) {
            val saved = id != null && Offline.get(act, id) != null
            dlBtn.text = when {
                id == null -> "Download offline (" + YouTube.maxHeight(act) + "p + subtitles)"
                saved -> "Saved offline \u2713  (tap to download again at " + YouTube.maxHeight(act) + "p)"
                DownloadService.isBusy(id) -> "Downloading... (see the notification)"
                else -> "Download offline (" + YouTube.maxHeight(act) + "p + subtitles)"
            }
        }
        dlText(null)
        onQualityChanged = { dlText(YouTube.extractId(input.text.toString())) }
        dlBtn.setOnClickListener {
            val id = YouTube.extractId(input.text.toString())
            if (id == null) {
                Toast.makeText(act, "Paste a YouTube link first", Toast.LENGTH_SHORT).show()
            } else if (DownloadService.isBusy(id)) {
                Toast.makeText(act, "Already downloading this video", Toast.LENGTH_SHORT).show()
            } else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    askNotifDl.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                try {
                    DownloadService.start(act, id, knownTitle(id) ?: "YouTube video", YouTube.maxHeight(act))
                    Toast.makeText(act, "Saving " + YouTube.maxHeight(act) + "p video + subtitles for offline play...", Toast.LENGTH_LONG).show()
                    dlText(id)
                } catch (e: Exception) {
                    Toast.makeText(act, "Could not start the download", Toast.LENGTH_LONG).show()
                }
            }
        }
        offBtn.setOnClickListener {
            showOfflineDialog({ e ->
                dialogRef?.dismiss()
                playYoutube(e.id, e.title)
            }) { offText(); dlText(YouTube.extractId(input.text.toString())) }
        }
        val dlLp = LinearLayout.LayoutParams(MATCH, WRAP)
        dlLp.setMargins(0, dp(10), 0, 0)
        col.addView(dlBtn, dlLp)
        val offLp = LinearLayout.LayoutParams(MATCH, WRAP)
        offLp.setMargins(0, dp(10), 0, 0)
        col.addView(offBtn, offLp)

        // tabs: Recent / Favourites
        var tab = if (YouTube.history(act).isNotEmpty() || YouTube.favorites(act).isEmpty()) 0 else 1
        val tabsRow = LinearLayout(act)
        tabsRow.orientation = LinearLayout.HORIZONTAL
        val tabViews = ArrayList<LinearLayout>()
        val listBox = LinearLayout(act)
        listBox.orientation = LinearLayout.VERTICAL
        val tip = TextView(act)
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tip.setTextColor(subC)
        tip.alpha = 0.8f
        tip.setPadding(0, dp(4), 0, 0)

        var prevId: String? = null
        lateinit var renderList: () -> Unit

        fun styleTabs() {
            for ((i, t) in tabViews.withIndex()) {
                val on = i == tab
                t.background = if (on) shape(accent, null, 20) else shape(surf2, borderC, 20)
                val fg = if (on) Color.parseColor("#1A1020") else textC
                (t.findViewWithTag<ImageView>("ic")).setColorFilter(if (i == 1 && !on) pink else fg)
                (t.findViewWithTag<TextView>("tx")).setTextColor(fg)
            }
        }
        fun tabChip(iconRes: Int, label: String, idx: Int): LinearLayout {
            val c = LinearLayout(act)
            c.orientation = LinearLayout.HORIZONTAL
            c.gravity = Gravity.CENTER_VERTICAL
            c.setPadding(dp(12), 0, dp(14), 0)
            val ic = ImageView(act)
            ic.setImageResource(iconRes)
            ic.tag = "ic"
            c.addView(ic, LinearLayout.LayoutParams(dp(16), dp(16)))
            val tv = TextView(act)
            tv.text = label
            tv.tag = "tx"
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.setPadding(dp(6), 0, 0, 0)
            c.addView(tv)
            c.setOnClickListener { tab = idx; renderList() }
            return c
        }
        tabViews.add(tabChip(R.drawable.ic_history, "Recent", 0))
        tabViews.add(tabChip(R.drawable.ic_heart_filled, "Favourites", 1))
        for (t in tabViews) {
            val lp = LinearLayout.LayoutParams(WRAP, dp(34))
            lp.setMargins(0, 0, dp(8), 0)
            tabsRow.addView(t, lp)
        }
        val tabLp = LinearLayout.LayoutParams(MATCH, WRAP)
        tabLp.setMargins(0, dp(16), 0, dp(6))
        col.addView(tabsRow, tabLp)
        col.addView(listBox, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(tip)

        renderList = {
            val hist = YouTube.history(act).take(12)
            val favList = YouTube.favorites(act)
            val show = if (tab == 0) hist else favList
            val favIds = favList.map { it.id }.toSet()
            tabsRow.visibility = if (hist.isEmpty() && favList.isEmpty()) View.GONE else View.VISIBLE
            styleTabs()
            listBox.removeAllViews()
            if (show.isEmpty()) {
                val e = TextView(act)
                e.text = if (tab == 0) "No recent links yet." else "No favourites yet. Tap the heart on any video to add it."
                e.setTextColor(subC)
                e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                e.setPadding(dp(2), dp(10), dp(2), dp(10))
                if (tabsRow.visibility == View.VISIBLE) listBox.addView(e)
            }
            for (h in show) {
                val row = LinearLayout(act)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(4), dp(6), dp(2), dp(6))
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                row.background = ta.getDrawable(0)
                ta.recycle()
                row.addView(ytThumb(h.id, 26), LinearLayout.LayoutParams(dp(96), dp(54)))
                val txt = LinearLayout(act)
                txt.orientation = LinearLayout.VERTICAL
                val t1 = TextView(act)
                t1.text = h.title
                t1.setTextColor(textC)
                t1.maxLines = 2
                t1.ellipsize = TextUtils.TruncateAt.END
                t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                txt.addView(t1)
                if (h.uploader.isNotEmpty()) {
                    val t2 = TextView(act)
                    t2.text = h.uploader
                    t2.setTextColor(subC)
                    t2.maxLines = 1
                    t2.ellipsize = TextUtils.TruncateAt.END
                    t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    t2.setPadding(0, dp(2), 0, 0)
                    txt.addView(t2)
                }
                val tl = LinearLayout.LayoutParams(0, WRAP, 1f)
                tl.setMargins(dp(12), 0, dp(4), 0)
                row.addView(txt, tl)
                val heart = ImageView(act)
                heart.setPadding(dp(10), dp(10), dp(10), dp(10))
                heart.contentDescription = "Favourite"
                heartIcon(heart, favIds.contains(h.id))
                heart.setOnClickListener {
                    if (YouTube.isFavorite(act, h.id)) {
                        YouTube.removeFavorite(act, h.id)
                        Toast.makeText(act, "Removed from favourites", Toast.LENGTH_SHORT).show()
                    } else {
                        YouTube.addFavorite(act, h.id, h.title, h.uploader)
                        Toast.makeText(act, "Added to favourites", Toast.LENGTH_SHORT).show()
                    }
                    if (prevId == h.id) heartIcon(prevHeart, YouTube.isFavorite(act, h.id))
                    renderList()
                }
                row.addView(heart, LinearLayout.LayoutParams(dp(44), dp(44)))
                row.setOnClickListener {
                    dialogRef?.dismiss()
                    playYoutube(h.id, h.title)
                }
                row.setOnLongClickListener {
                    if (tab == 0) {
                        YouTube.removeHistory(act, h.id)
                        Toast.makeText(act, "Removed from the list", Toast.LENGTH_SHORT).show()
                    } else {
                        YouTube.removeFavorite(act, h.id)
                        Toast.makeText(act, "Removed from favourites", Toast.LENGTH_SHORT).show()
                    }
                    if (prevId == h.id) heartIcon(prevHeart, YouTube.isFavorite(act, h.id))
                    renderList()
                    true
                }
                listBox.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            tip.text = if (show.isEmpty()) "" else "Tap the heart to favourite. Hold a title to remove it."
        }

        fun updatePreview() {
            clearBtn.visibility = if (input.text.isNotEmpty()) View.VISIBLE else View.INVISIBLE
            val id = YouTube.extractId(input.text.toString())
            dlText(id)
            if (id == null) {
                prevId = null
                preview.visibility = View.GONE
                return
            }
            if (id == prevId) return
            prevId = id
            prevThumb.removeAllViews()
            prevThumb.addView(ytThumb(id, 28), FrameLayout.LayoutParams(MATCH, MATCH))
            prevTitle.text = knownTitle(id) ?: "YouTube video"
            heartIcon(prevHeart, YouTube.isFavorite(act, id))
            preview.visibility = View.VISIBLE
        }
        prevHeart.setOnClickListener {
            val id = prevId ?: return@setOnClickListener
            if (YouTube.isFavorite(act, id)) {
                YouTube.removeFavorite(act, id)
                Toast.makeText(act, "Removed from favourites", Toast.LENGTH_SHORT).show()
            } else {
                YouTube.addFavorite(act, id, knownTitle(id) ?: "YouTube video", "")
                Toast.makeText(act, "Added to favourites", Toast.LENGTH_SHORT).show()
            }
            heartIcon(prevHeart, YouTube.isFavorite(act, id))
            renderList()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        })
        updatePreview()
        renderList()

        val scroll = ScrollView(act)
        scroll.addView(col)
        val dlg = AlertDialog.Builder(act)
            .setView(scroll)
            .setPositiveButton("Play", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialogRef = dlg
        dlg.setOnShowListener {
            try { dlg.window?.setBackgroundDrawable(shape(surf, borderC, 22)) } catch (e: Exception) { }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent)
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = YouTube.extractId(input.text.toString())
                if (id == null) {
                    Toast.makeText(act, "That is not a YouTube link", Toast.LENGTH_SHORT).show()
                } else {
                    dlg.dismiss()
                    playYoutube(id, knownTitle(id))
                }
            }
        }
        dlg.show()
    }

    /** The videos saved for offline play: tap = play, bin = delete one, "Delete all" clears everything (video + subtitles). */
    private fun showOfflineDialog(onPlay: (Offline.Entry) -> Unit, onChanged: () -> Unit) {
        val act = this
        val textC = ContextCompat.getColor(act, R.color.text)
        val subC = ContextCompat.getColor(act, R.color.sub)
        val surf = ContextCompat.getColor(act, R.color.surface)
        val borderC = ContextCompat.getColor(act, R.color.border)
        val accent = ContextCompat.getColor(act, R.color.accent)
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val listBox = LinearLayout(act)
        listBox.orientation = LinearLayout.VERTICAL
        listBox.setPadding(dp(14), dp(10), dp(10), dp(6))
        val scroll = ScrollView(act)
        scroll.addView(listBox)
        val dlg = AlertDialog.Builder(act)
            .setTitle("Offline videos")
            .setView(scroll)
            .setNeutralButton("Delete all", null)
            .setNegativeButton("Close", null)
            .create()

        fun render() {
            listBox.removeAllViews()
            val items = Offline.list(act)
            dlg.setTitle(if (items.isEmpty()) "Offline videos" else "Offline videos  \u2022  " + fmtSize(Offline.totalBytes(act)))
            if (items.isEmpty()) {
                val e = TextView(act)
                e.text = "Nothing saved yet. Paste a YouTube link, choose the quality (144p saves the most space) and tap Download offline."
                e.setTextColor(subC)
                e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                e.setPadding(dp(4), dp(10), dp(4), dp(10))
                listBox.addView(e)
                return
            }
            for (en in items) {
                val row = LinearLayout(act)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(2), dp(8), dp(2), dp(8))
                val txt = LinearLayout(act)
                txt.orientation = LinearLayout.VERTICAL
                val t1 = TextView(act)
                t1.text = en.title
                t1.setTextColor(textC)
                t1.maxLines = 2
                t1.ellipsize = TextUtils.TruncateAt.END
                t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                txt.addView(t1)
                val t2 = TextView(act)
                t2.text = (if (en.height > 0) en.height.toString() + "p" else "video") + "  \u2022  " + fmtSize(en.bytes) +
                    "  \u2022  " + (if (en.hasSubs) "with subtitles" else "no subtitles")
                t2.setTextColor(subC)
                t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                t2.setPadding(0, dp(2), 0, 0)
                txt.addView(t2)
                row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
                val bin = ImageView(act)
                bin.setImageResource(R.drawable.ic_delete)
                bin.setColorFilter(Color.parseColor("#FF5C7C"))
                bin.setPadding(dp(10), dp(10), dp(10), dp(10))
                bin.contentDescription = "Delete this offline video"
                bin.setOnClickListener {
                    AlertDialog.Builder(act)
                        .setTitle("Delete offline video?")
                        .setMessage(en.title + "\n\nThe saved video and its subtitle file are removed from this phone (" + fmtSize(en.bytes) + ").")
                        .setPositiveButton("Delete") { _, _ ->
                            Offline.delete(act, en.id)
                            Toast.makeText(act, "Deleted", Toast.LENGTH_SHORT).show()
                            render()
                            onChanged()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                row.addView(bin, LinearLayout.LayoutParams(dp(44), dp(44)))
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                row.background = ta.getDrawable(0)
                ta.recycle()
                row.setOnClickListener {
                    dlg.dismiss()
                    onPlay(en)
                }
                listBox.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
        render()
        dlg.setOnShowListener {
            try {
                val g = GradientDrawable()
                g.setColor(surf)
                g.cornerRadius = dp(22).toFloat()
                g.setStroke(dp(1), borderC)
                dlg.window?.setBackgroundDrawable(g)
            } catch (e: Exception) { }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.parseColor("#FF5C7C"))
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent)
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val n = Offline.list(act).size
                if (n == 0) {
                    Toast.makeText(act, "No offline videos to delete", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(act)
                        .setTitle("Delete all offline videos?")
                        .setMessage("This removes " + n + " saved video" + (if (n == 1) "" else "s") + " and their subtitle files (" + fmtSize(Offline.totalBytes(act)) + "). Your stars and bookmarks stay.")
                        .setPositiveButton("Delete all") { _, _ ->
                            Offline.deleteAll(act)
                            Toast.makeText(act, "All offline videos deleted", Toast.LENGTH_SHORT).show()
                            render()
                            onChanged()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
        dlg.show()
    }

    // ===== YT_DIALOG_END =====

    override fun onResume() {
        super.onResume()
        if (hasPerm() && all.isNotEmpty() && !scanning) scan()   // picks up new videos and refreshes "resume" times
        if (all.isNotEmpty()) rebuild()                           // refreshes the Continue card / Recent order right away
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
    }

    // ------------------------------------------------------------------ scanning

    private fun scan() {
        if (scanning) return
        scanning = true
        if (all.isEmpty()) showEmpty("Looking for videos\u2026", false)
        Thread {
            val found = ArrayList<VideoItem>()
            try {
                val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.DATA
                )
                contentResolver.query(base, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val iBucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    val iData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        found.add(
                            VideoItem(
                                "v$id",
                                ContentUris.withAppendedId(base, id),
                                c.getString(iName) ?: "video",
                                c.getLong(iDur),
                                c.getLong(iSize),
                                c.getString(iBucket) ?: "Other",
                                c.getLong(iDate),
                                c.getString(iData)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // an empty list is shown below
            }
            runOnUiThread {
                scanning = false
                if (!isFinishing) {
                    all = found
                    rebuild()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ list

    private fun showEmpty(msg: String, askButton: Boolean) {
        emptyText.text = msg
        emptyBtn.visibility = if (askButton) View.VISIBLE else View.GONE
        emptyBox.visibility = View.VISIBLE
        list.visibility = View.GONE
    }

    private fun sorted(src: List<VideoItem>): List<VideoItem> = when (sortMode) {
        1 -> src.sortedBy { it.name.lowercase() }
        2 -> src.sortedByDescending { it.size }
        3 -> src.sortedByDescending { it.durationMs }
        else -> src.sortedByDescending { it.dateAdded }
    }

    private fun rebuild() {
        val q = query.trim().lowercase()
        favs = Library.favorites(this)
        val src = all.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        val out = ArrayList<Row>()
        if (filterMode == 1) {
            val vs = src.filter { Library.lastPlayed(this, it.key) > 0L }.sortedByDescending { Library.lastPlayed(this, it.key) }
            for (v in vs) out.add(Row(null, 0, v))
            titleView.text = "ShadowPlayer"
        } else if (filterMode == 2) {
            for (v in sorted(src.filter { favs.contains(it.key) })) out.add(Row(null, 0, v))
            titleView.text = "ShadowPlayer"
        } else if (foldersMode && openFolder == null && q.isEmpty()) {
            val groups = src.groupBy { it.folder }
            for (e in groups.entries.sortedBy { it.key.lowercase() }) out.add(Row(e.key, e.value.size, null))
            titleView.text = "ShadowPlayer"
        } else {
            val of = openFolder
            val vs = sorted(if (of != null && foldersMode) src.filter { it.folder == of } else src)
            for (v in vs) out.add(Row(null, 0, v))
            titleView.text = if (of != null && foldersMode) of else "ShadowPlayer"
        }
        rows = out
        foldersBtn.alpha = if (foldersMode && filterMode == 0) 1f else 0.6f
        styleChips()
        updateContinueCard(q)
        adapter.notifyDataSetChanged()
        if (out.isEmpty()) {
            if (!hasPerm()) showEmpty("File access is needed to list your videos.", true)
            else if (all.isEmpty()) showEmpty("No videos found on this phone.", false)
            else if (q.isNotEmpty()) showEmpty("Nothing matches your search.", false)
            else if (filterMode == 1) showEmpty("Nothing played yet.\nVideos you watch show up here.", false)
            else if (filterMode == 2) showEmpty("No favorites yet.\nTap the heart on any video to add it.", false)
            else showEmpty("Nothing matches your search.", false)
        } else {
            emptyBox.visibility = View.GONE
            list.visibility = View.VISIBLE
        }
    }

    /** Shows the most recently played video as a one-tap "Continue watching" card (All tab, no search). */
    private fun updateContinueCard(q: String) {
        var last: VideoItem? = null
        var lastAt = 0L
        if (filterMode == 0 && q.isEmpty()) {
            for (v in all) {
                val t = Library.lastPlayed(this, v.key)
                if (t > lastAt) { lastAt = t; last = v }
            }
        }
        val v = last
        if (v == null) {
            continueCard.visibility = View.GONE
            return
        }
        continueCard.visibility = View.VISIBLE
        contTitle.text = v.name
        val pos = prefs.getLong("pos_" + v.key, 0L)
        if (v.durationMs > 0L && pos > 5000L) {
            contProgress.visibility = View.VISIBLE
            contProgress.progress = ((pos * 1000L) / v.durationMs).toInt().coerceIn(0, 1000)
            contMeta.text = "Resume at " + fmtDuration(pos) + "  \u2022  " + fmtDuration((v.durationMs - pos).coerceAtLeast(0L)) + " left"
        } else {
            contProgress.visibility = View.INVISIBLE
            contMeta.text = if (v.durationMs > 0L) "Play again  \u2022  " + fmtDuration(v.durationMs) else "Play again"
        }
        loadThumb(v, contThumb)
        continueCard.setOnClickListener { playSingle(v) }
    }

    /** Plays [item] with the whole library as its next/previous list. */
    private fun playSingle(item: VideoItem) {
        val vids = ArrayList<VideoItem>(sorted(all))
        Playlist.items = vids
        Playlist.index = vids.indexOf(item).coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toggleSearch() {
        if (searchBox.visibility == View.VISIBLE) {
            searchBox.setText("")
            searchBox.visibility = View.GONE
            query = ""
            rebuild()
        } else {
            searchBox.visibility = View.VISIBLE
            searchBox.requestFocus()
        }
    }

    private fun toggleFolders() {
        foldersMode = !foldersMode
        filterMode = 0
        openFolder = null
        prefs.edit().putBoolean("lib_folders", foldersMode).apply()
        rebuild()
    }

    private fun showSortMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 0, 0, "Newest first")
        m.menu.add(0, 1, 1, "Name")
        m.menu.add(0, 2, 2, "Size")
        m.menu.add(0, 3, 3, "Length")
        m.setOnMenuItemClickListener {
            sortMode = it.itemId
            prefs.edit().putInt("lib_sort", sortMode).apply()
            rebuild()
            true
        }
        m.show()
    }

    private fun showMoreMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 0, 0, "Rescan videos")
        m.menu.add(0, 1, 1, "Find .srt files next to videos (all files access)")
        m.setOnMenuItemClickListener {
            if (it.itemId == 0) {
                if (hasPerm()) scan() else askPerm.launch(neededPerms())
            } else {
                askAllFilesAccess(this)
            }
            true
        }
        m.show()
    }

    private fun openVideo(item: VideoItem) {
        val vids = ArrayList<VideoItem>()
        for (r in rows) r.video?.let { vids.add(it) }
        Playlist.items = vids
        Playlist.index = vids.indexOf(item).coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ------------------------------------------------------------------ thumbnails

    private fun loadThumb(item: VideoItem, iv: ImageView) {
        iv.tag = item.key
        val cached = thumbs.get(item.key)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageDrawable(null)
        try {
            pool.execute {
                val bmp: Bitmap? = try {
                    contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) {
                    thumbs.put(item.key, bmp)
                    runOnUiThread { if (iv.tag == item.key) iv.setImageBitmap(bmp) }
                }
            }
        } catch (e: Exception) {
            // pool is shut down while leaving
        }
    }

    // ------------------------------------------------------------------ adapter

    private inner class VH(
        val root: LinearLayout,
        val thumb: ImageView,
        val glyph: TextView,
        val duration: TextView,
        val title: TextView,
        val meta: TextView,
        val heart: ImageView
    ) : RecyclerView.ViewHolder(root)

    private fun bindHeart(iv: ImageView, on: Boolean) {
        if (on) {
            iv.setImageResource(R.drawable.ic_heart_filled)
            iv.setColorFilter(Color.parseColor("#FF5C7C"))
            iv.contentDescription = "Remove from favorites"
        } else {
            iv.setImageResource(R.drawable.ic_heart)
            iv.setColorFilter(Color.argb(170, 255, 255, 255))
            iv.contentDescription = "Add to favorites"
        }
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(parent.context)
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(dp(14), dp(7), dp(14), dp(7))
            root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            val box = FrameLayout(parent.context)
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#1C2547"))
            bg.cornerRadius = dp(8).toFloat()
            box.background = bg
            box.clipToOutline = true
            val thumb = ImageView(parent.context)
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            box.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val glyph = TextView(parent.context)
            glyph.text = "\uD83D\uDCC1"
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            glyph.gravity = Gravity.CENTER
            glyph.visibility = View.GONE
            box.addView(glyph, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val dur = TextView(parent.context)
            dur.setTextColor(Color.WHITE)
            dur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            dur.setPadding(dp(5), dp(1), dp(5), dp(1))
            val dbg = GradientDrawable()
            dbg.setColor(Color.argb(170, 0, 0, 0))
            dbg.cornerRadius = dp(4).toFloat()
            dur.background = dbg
            val dlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            dlp.setMargins(0, 0, dp(4), dp(4))
            box.addView(dur, dlp)
            root.addView(box, LinearLayout.LayoutParams(dp(128), dp(72)))

            val col = LinearLayout(parent.context)
            col.orientation = LinearLayout.VERTICAL
            val title = TextView(parent.context)
            title.setTextColor(Color.WHITE)
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.END
            val meta = TextView(parent.context)
            meta.setTextColor(Color.argb(170, 255, 255, 255))
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            meta.maxLines = 1
            meta.ellipsize = TextUtils.TruncateAt.END
            meta.setPadding(0, dp(3), 0, 0)
            col.addView(title)
            col.addView(meta)
            val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            clp.setMargins(dp(12), 0, 0, 0)
            root.addView(col, clp)

            val heart = ImageView(parent.context)
            heart.scaleType = ImageView.ScaleType.CENTER_INSIDE
            heart.setPadding(dp(11), dp(11), dp(11), dp(11))
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            heart.background = ta.getDrawable(0)
            ta.recycle()
            val hlp = LinearLayout.LayoutParams(dp(46), dp(46))
            hlp.setMargins(dp(4), 0, 0, 0)
            root.addView(heart, hlp)
            return VH(root, thumb, glyph, dur, title, meta, heart)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            val v = r.video
            if (v == null) {
                h.thumb.setImageDrawable(null)
                h.thumb.tag = null
                h.glyph.visibility = View.VISIBLE
                h.heart.visibility = View.GONE
                h.duration.visibility = View.GONE
                h.title.text = r.folder ?: ""
                h.meta.text = r.count.toString() + (if (r.count == 1) " video" else " videos")
                h.root.setOnClickListener {
                    openFolder = r.folder
                    rebuild()
                }
            } else {
                h.glyph.visibility = View.GONE
                h.heart.visibility = View.VISIBLE
                bindHeart(h.heart, favs.contains(v.key))
                h.heart.setOnClickListener {
                    val now = Library.toggleFavorite(this@MainActivity, v.key)
                    favs = Library.favorites(this@MainActivity)
                    bindHeart(h.heart, now)
                    if (filterMode == 2 && !now) rebuild()
                }
                h.duration.visibility = if (v.durationMs > 0L) View.VISIBLE else View.GONE
                h.duration.text = fmtDuration(v.durationMs)
                h.title.text = v.name
                val resume = prefs.getLong("pos_" + v.key, 0L)
                val sb = StringBuilder()
                sb.append(fmtSize(v.size))
                if (!(foldersMode && openFolder != null && filterMode == 0)) sb.append("  \u2022  ").append(v.folder)
                if (resume > 5000L) sb.append("  \u2022  \u25B6 ").append(fmtDuration(resume))
                h.meta.text = sb.toString()
                loadThumb(v, h.thumb)
                h.root.setOnClickListener { openVideo(v) }
            }
        }
    }
}
