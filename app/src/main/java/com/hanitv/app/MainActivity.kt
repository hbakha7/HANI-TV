package com.hanitv.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import android.text.InputType
import android.app.AlertDialog
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class MainActivity : Activity() {

    private val bg = Color.rgb(12, 12, 15)
    private val panel = Color.rgb(25, 25, 30)
    private val red = Color.rgb(210, 20, 35)
    private val white = Color.WHITE
    private val gray = Color.rgb(175, 175, 185)

    private lateinit var content: LinearLayout
    private val prefs by lazy { getSharedPreferences("hani_tv_sources", MODE_PRIVATE) }
    private val sources = mutableListOf<StreamSource>()
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val remoteSources by lazy {
        FirebaseDatabase.getInstance("https://hani-tv-b159f-default-rtdb.firebaseio.com")
            .reference.child("sources")
    }
    // This UID identifies the single Firebase account allowed to change the shared list.
    private val adminUid = "MDbUZT4SdPfxBqqfj5MNeNBtAW23"
    private var showingServerManager = false
    private var embeddedPlayer: ExoPlayer? = null
    private var embeddedPlayerView: PlayerView? = null
    private var embeddedDialog: Dialog? = null
    private var embeddedChannelTitle: TextView? = null
    private val embeddedServerButtons = mutableListOf<TextView>()
    private val embeddedChannelCards = linkedMapOf<String, View>()
    private var loadingIndicator: ProgressBar? = null
    private var progressUpdater: Runnable? = null
    private var currentOptions: List<StreamSource> = emptyList()
    private var currentServerIndex = 0
    private var failoverCount = 0
    private val playerHandler = Handler(Looper.getMainLooper())
    private var startupTimeout: Runnable? = null
    private var failoverPending = false
    private var orientationBeforePlayer = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var systemUiBeforePlayer = 0

    // Do not open a channel from stale local data while the first Firebase read is pending.
    private var initialSourceSyncFinished = false
    private var pendingChannelToOpen: String? = null

    private data class StreamSource(
        val channel: String,
        val serverName: String,
        val url: String,
        val referer: String = "",
        val displayName: String = serverName
    )

    private val channelGroups = linkedMapOf(
        "القنوات الرياضية" to listOf(
            "SPORT 1" to "S1",
            "SPORT 2" to "S2",
            "SPORT 3" to "S3",
            "SPORT 4" to "S4"
        ),
        "القنوات العربية" to listOf(
            "قناة عربية 1" to "AR",
            "قناة عربية 2" to "A2",
            "قناة عربية 3" to "A3",
            "قناة عربية 4" to "A4"
        ),
        "القنوات الإخبارية" to listOf(
            "أخبار 1" to "N1",
            "أخبار 2" to "N2",
            "أخبار 3" to "N3"
        ),
        "قنوات الأطفال" to listOf(
            "أطفال 1" to "K1",
            "أطفال 2" to "K2",
            "أطفال 3" to "K3"
        ),
        "قنوات الترفيه" to listOf(
            "ترفيه 1" to "E1",
            "ترفيه 2" to "E2",
            "ترفيه 3" to "E3"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Record an app-open event for HANI TV Analytics.
        FirebaseAnalytics.getInstance(this).logEvent("hani_tv_app_open", null)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
            setBackgroundColor(Color.rgb(18, 18, 22))
        }

        header.addView(TextView(this).apply {
            text = "HANI "
            textSize = 26f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
        })

        header.addView(TextView(this).apply {
            text = "TV"
            textSize = 26f
            setTextColor(red)
            setTypeface(null, Typeface.BOLD)
        })

        val spacer = View(this)
        header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        header.addView(TextView(this).apply {
            text = "LIVE ●"
            textSize = 12f
            setTextColor(red)
            setTypeface(null, Typeface.BOLD)
        })

        root.addView(header)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        loadSources()
        // Keep the currently configured SPORT 1 source as a starting example.
        if (sources.none { it.channel == "SPORT 1" }) {
            sources.add(StreamSource("SPORT 1", "السيرفر الأساسي", "http://mega.leptis.live/live/SQ2EJ94G/Q7VWF5PH/1236874.ts"))
            saveSources()
        }
        showHome()
        // All installs authenticate for read access; only the configured admin UID can write.
        ensureReadAuthentication()
    }

    private fun ensureReadAuthentication() {
        if (firebaseAuth.currentUser != null) {
            syncFromCloud { finishInitialSourceSync() }
            return
        }
        firebaseAuth.signInAnonymously()
            .addOnSuccessListener { syncFromCloud { finishInitialSourceSync() } }
            .addOnFailureListener { error ->
                Toast.makeText(this, "تعذر الاتصال بقاعدة البيانات: ${error.message}", Toast.LENGTH_LONG).show()
                // Keep locally saved channels usable if authentication/network access fails.
                finishInitialSourceSync()
            }
    }

    private fun finishInitialSourceSync() {
        initialSourceSyncFinished = true
        val pendingChannel = pendingChannelToOpen
        pendingChannelToOpen = null
        if (!pendingChannel.isNullOrBlank()) openChannel(pendingChannel)
    }

    private fun showHome() {
        showingServerManager = false
        content.removeAllViews()

        addText("مرحباً بك في HANI TV", 23f, white, true)
        addText(
            "قنواتك المفضلة في مكان واحد",
            14f, gray, false
        )

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(75, 12, 22))
        }

        banner.addView(TextView(this).apply {
            text = "HANI TV"
            textSize = 30f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
        })

        banner.addView(TextView(this).apply {
            text = "اكتشف أقسام القنوات"
            textSize = 17f
            setTextColor(white)
            setPadding(0, dp(8), 0, dp(14))
        })

        val browse = makeButton("عرض جميع القنوات")
        browse.setOnClickListener { showAllChannels() }
        banner.addView(browse)

        content.addView(
            banner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(22)
            }
        )

        addText("أقسام القنوات", 21f, white, true)

        channelGroups.keys.forEach { category ->
            addCategoryCard(category)
        }
        addSectionCard("⚙️", "إدارة السيرفرات وروابط القنوات") { showServerManager() }

        addText("الأفلام والمسلسلات", 21f, white, true, 12)
        addSectionCard("🎬", "تصفح الأفلام والمسلسلات") {
            Toast.makeText(
                this,
                "سيتم إضافة محتوى هذا القسم لاحقاً",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showAllChannels() {
        showingServerManager = false
        content.removeAllViews()
        addText("جميع القنوات", 24f, white, true, 8)

        channelGroups.keys.forEach { category ->
            addCategoryCard(category)
        }
        addSectionCard("⚙️", "إدارة السيرفرات وروابط القنوات") { showServerManager() }
        addBackButton()
    }

    private fun addCategoryCard(category: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(16))
            setBackgroundColor(panel)
            isClickable = true
            isFocusable = true
            setOnClickListener { showChannels(category) }
        }

        val icon = TextView(this).apply {
            text = when (category) {
                "القنوات الرياضية" -> "⚽"
                "القنوات العربية" -> "📺"
                "القنوات الإخبارية" -> "🌍"
                "قنوات الأطفال" -> "🧸"
                else -> "🎭"
            }
            textSize = 26f
            gravity = Gravity.CENTER
        }

        card.addView(icon, LinearLayout.LayoutParams(dp(48), dp(45)))

        card.addView(TextView(this).apply {
            text = category
            textSize = 17f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        card.addView(TextView(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(red)
        })

        content.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )
    }

    private fun showChannels(category: String) {
        showingServerManager = false
        content.removeAllViews()
        addText(category, 23f, white, true)
        addText("القنوات المتاحة في هذا القسم", 14f, gray, false)

        val channels = channelGroups[category] ?: emptyList()

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var index = 0
        while (index < channels.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            addChannelCard(row, channels[index])

            if (index + 1 < channels.size) {
                addChannelCard(row, channels[index + 1])
            }

            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            )

            index += 2
        }

        content.addView(grid)
        addText(
            "هذه بطاقات تجريبية. أضف مصادر القنوات المصرح بها لتفعيل التشغيل.",
            13f, gray, false, 8
        )
        addBackButton()
    }

    private fun addChannelCard(
        row: LinearLayout,
        channel: Pair<String, String>
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(16))
            setBackgroundColor(panel)
            isClickable = true
            isFocusable = true
            setOnClickListener { openChannel(channel.first) }
        }

        val logo = TextView(this).apply {
            text = channel.second
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(red)
        }

        card.addView(
            logo,
            LinearLayout.LayoutParams(dp(66), dp(66))
        )

        card.addView(TextView(this).apply {
            text = channel.first
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(0, dp(10), 0, 0)
        })

        row.addView(
            card,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        )
    }

    private fun openChannel(channelName: String) {
        if (!initialSourceSyncFinished) {
            // Queue the tap and launch automatically as soon as local/cloud source selection is settled.
            pendingChannelToOpen = channelName
            return
        }
        val options = sources.filter { it.channel == channelName }
        if (options.isEmpty()) {
            Toast.makeText(this, "لا توجد روابط محفوظة لهذه القناة. افتح إدارة السيرفرات لإضافة رابط.", Toast.LENGTH_LONG).show()
            return
        }
        launchPlayer(options, 0)
    }

    


private fun launchPlayer(options: List<StreamSource>, selectedIndex: Int = 0) {
    if (options.isEmpty()) return
    currentOptions = options
    currentServerIndex = selectedIndex.coerceIn(0, options.lastIndex)
    if (embeddedDialog?.isShowing == true && embeddedPlayer != null) {
        // If a channel is selected while the player is already open, keep the player visible.
        embeddedChannelTitle?.text = currentOptions[currentServerIndex].channel
        embeddedChannelCards.forEach { (name, card) ->
            card.setBackgroundColor(if (name == currentOptions[currentServerIndex].channel) Color.rgb(28, 105, 165) else Color.TRANSPARENT)
        }
        switchServerInsidePlayer(currentServerIndex)
    } else {
        openEmbeddedPlayer()
    }
}



    /** Embedded sports player: video, circular servers, controls, and channel carousel. */
     private fun openFullscreen() {
    val original = playerView ?: return
    if (fullscreenDialog?.isShowing == true) return

    val parent = original.parent as? ViewGroup ?: return
    val oldOrientation = requestedOrientation
    val oldIndex = parent.indexOfChild(original)
    val oldParams = original.layoutParams
    parent.removeView(original)

    val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

    val fullPlayer = PlayerView(this).apply {
        useController = false
        player = this@MainActivity.player
        // Make "ملء الصورة" the default mode whenever fullscreen playback opens.
        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        keepScreenOn = true
        setShutterBackgroundColor(Color.BLACK)
        setBackgroundColor(Color.BLACK)
    }
    frame.addView(fullPlayer, FrameLayout.LayoutParams(-1, -1))
    fullscreenView = fullPlayer

    // Centered spinner shown until playback is ready and whenever buffering resumes.
    val spinner = ProgressBar(this).apply {
        isIndeterminate = true
        visibility = View.VISIBLE
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        contentDescription = "جارٍ تحميل البث"
    }
    loadingIndicator = spinner
    val spinnerSize = dp(48)
    frame.addView(spinner, FrameLayout.LayoutParams(spinnerSize, spinnerSize, Gravity.CENTER))

    val handler = Handler(Looper.getMainLooper())
    var controlsVisible = true

    fun textButton(symbol: String, size: Float = 25f): TextView = TextView(this).apply {
        text = symbol
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(2), dp(4), dp(2))
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
        isClickable = true
        isFocusable = true
    }

    val topBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }
    val back = textButton("‹", 36f).apply {
        contentDescription = "رجوع"
        setOnClickListener { dialog.dismiss() }
    }
    topBar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
    topBar.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f))
    frame.addView(topBar, FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP))

    // Server circles overlay the video without a long dark bar behind them.
    var serverButtonsContainer: LinearLayout? = null
    if (incomingServerUrls.size > 1 && incomingServerNames.size == incomingServerUrls.size) {
        val serverBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        serverButtonsContainer = serverBar
        serverButtonsBar = serverBar
        incomingServerNames.forEachIndexed { index, serverName ->
            val serverButton = TextView(this).apply {
                text = serverName.ifBlank { "S${index + 1}" }
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (index == incomingSelectedServer) Color.rgb(25, 190, 165) else Color.argb(135, 35, 35, 35))
                    setStroke(dp(1), if (index == incomingSelectedServer) Color.WHITE else Color.LTGRAY)
                }
                setOnClickListener {
                    if (index == incomingSelectedServer) return@setOnClickListener
                    incomingSelectedServer = index
                    val targetUrl = incomingServerUrls.getOrNull(index) ?: return@setOnClickListener
                    val targetReferer = incomingServerReferers.getOrNull(index).orEmpty()
                    val channel = Channel(incomingChannelName.ifBlank { "بث مباشر" }, targetUrl,
                        "مباشر", type = "live", referer = targetReferer)
                    channels.removeAll { it.name == channel.name && it.group == "مباشر" }
                    channels.add(channel)
                    play(channel)
                    fullPlayer.player = player
                    updateServerButtonSelection()
                }
            }
            val params = LinearLayout.LayoutParams(dp(48), dp(48))
            params.marginEnd = dp(8)
            serverBar.addView(serverButton, params)
        }
        val serverParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT)
        serverParams.topMargin = dp(54)
        serverParams.leftMargin = dp(12)
        frame.addView(serverBar, serverParams)
    }

    var showControls: () -> Unit = {}

    val pauseButton = textButton("Ⅱ", 31f).apply {
        contentDescription = "تشغيل أو إيقاف مؤقت"
        setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
            text = if (p.isPlaying) "Ⅱ" else "▶"
            showControls()
        }
    }

    val rewindButton = textButton("↶\n10", 15f).apply {
        contentDescription = "رجوع 10 ثوانٍ"
        setOnClickListener {
            val p = player ?: return@setOnClickListener
            p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
            showControls()
        }
    }
    val forwardButton = textButton("↷\n10", 15f).apply {
        contentDescription = "تقديم 10 ثوانٍ"
        setOnClickListener {
            val p = player ?: return@setOnClickListener
            val d = p.duration
            val target = p.currentPosition + 10_000L
            p.seekTo(if (d > 0 && d != C.TIME_UNSET) target.coerceAtMost(d) else target)
            showControls()
        }
    }

    val bottomPanel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(2), dp(12), dp(4))
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT)
        )
    }

    val actionBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    fun addControl(view: View, width: Int = 42) {
        actionBar.addView(view, LinearLayout.LayoutParams(dp(width), dp(46)))
    }

    val cast = textButton("▣", 25f).apply {
        contentDescription = "البث إلى شاشة أخرى"
        setOnClickListener {
            message("لإضافة Chromecast يلزم ربط مكتبة Google Cast بالتطبيق.")
            showControls()
        }
    }
    val audioTracks = textButton("◉", 24f).apply {
        contentDescription = "مسارات الصوت والترجمة"
        setOnClickListener {
            val p = player
            if (p == null) return@setOnClickListener
            val tracks = p.currentTracks
            val details = tracks.groups.joinToString("\n") { group ->
                val type = when (group.type) {
                    C.TRACK_TYPE_AUDIO -> "صوت"
                    C.TRACK_TYPE_TEXT -> "ترجمة"
                    C.TRACK_TYPE_VIDEO -> "فيديو"
                    else -> "مسار"
                }
                "$type: ${group.length} خيار"
            }.ifBlank { "لا توجد مسارات إضافية متاحة." }
            message(details)
            showControls()
        }
    }
    val settings = textButton("⚙", 25f).apply {
        contentDescription = "إعدادات المشغل"
        setOnClickListener {
            val popup = PopupMenu(this@MainActivity, this)
            listOf("ملاءمة الشاشة", "ملء الصورة", "تكبير الصورة", "معلومات البث").forEach { popup.menu.add(it) }
            popup.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "ملاءمة الشاشة" -> fullPlayer.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    "ملء الصورة" -> fullPlayer.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    "تكبير الصورة" -> fullPlayer.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    "معلومات البث" -> message("${player?.currentMediaItem?.mediaId.orEmpty()}\n${player?.videoFormat?.width ?: 0} × ${player?.videoFormat?.height ?: 0}")
                }
                true
            }
            popup.show()
            showControls()
        }
    }
    val fullscreen = textButton("⛶", 27f).apply {
        contentDescription = "إغلاق ملء الشاشة"
        setOnClickListener { dialog.dismiss() }
    }
    val display = textButton("▭", 26f).apply {
        contentDescription = "تغيير نمط عرض الفيديو"
        setOnClickListener {
            fullPlayer.resizeMode = when (fullPlayer.resizeMode) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL ->
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            showControls()
        }
    }

    // One single control row, in the same order and near the bottom as the reference.
    addControl(rewindButton, 48)
    addControl(pauseButton, 48)
    addControl(forwardButton, 48)
    addControl(cast, 48)
    addControl(audioTracks, 48)
    addControl(settings, 48)
    addControl(fullscreen, 48)
    addControl(display, 48)
    bottomPanel.addView(actionBar)

    val timeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    fun timeText(ms: Long): String {
        if (ms < 0 || ms == C.TIME_UNSET) return "00:00"
        val total = ms / 1000
        return String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60)
    }
    val elapsed = TextView(this).apply {
        text = "00:00"
        textSize = 14f
        setTextColor(Color.LTGRAY)
        setTypeface(null, Typeface.BOLD)
    }
    val duration = TextView(this).apply {
        text = "00:00"
        textSize = 14f
        setTextColor(Color.LTGRAY)
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
    }
    val seek = SeekBar(this).apply {
        max = 1000
        // Match the reference image: white played track and white circular thumb,
        // with a faint translucent track for the remaining duration.
        progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(115, 220, 230, 240))
        secondaryProgressTintList = android.content.res.ColorStateList.valueOf(Color.argb(150, 255, 255, 255))
        splitTrack = false
        setPadding(0, 0, 0, 0)
    }
    timeRow.addView(elapsed, LinearLayout.LayoutParams(dp(58), dp(28)))
    timeRow.addView(seek, LinearLayout.LayoutParams(0, dp(28), 1f))
    timeRow.addView(duration, LinearLayout.LayoutParams(dp(58), dp(28)))
    bottomPanel.addView(timeRow)
    frame.addView(bottomPanel, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                val d = player?.duration ?: C.TIME_UNSET
                if (d > 0 && d != C.TIME_UNSET) player?.seekTo(d * progress / 1000L)
            }
        }
        override fun onStartTrackingTouch(bar: SeekBar?) {}
        override fun onStopTrackingTouch(bar: SeekBar?) {}
    })

    lateinit var hideControls: Runnable
    showControls = {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomPanel.visibility = View.VISIBLE
        serverButtonsContainer?.visibility = View.VISIBLE
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 4500)
    }
    hideControls = Runnable {
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomPanel.visibility = View.GONE
        serverButtonsContainer?.visibility = View.GONE
    }
    val updateProgress = object : Runnable {
        override fun run() {
            if (dialog.isShowing) {
                val p = player
                if (p != null) {
                    val actualDuration = p.duration
                    var d = actualDuration
                    var position = p.currentPosition
                    var buffered = p.bufferedPosition

                    // Some live streams expose their duration through the current timeline
                    // window even when player.duration is TIME_UNSET.
                    if ((d <= 0L || d == C.TIME_UNSET) && !p.currentTimeline.isEmpty) {
                        val timelineWindow = androidx.media3.common.Timeline.Window()
                        p.currentTimeline.getWindow(p.currentMediaItemIndex, timelineWindow)
                        if (timelineWindow.durationMs > 0L &&
                            timelineWindow.durationMs != C.TIME_UNSET) {
                            d = timelineWindow.durationMs
                        }
                    }

                    elapsed.text = timeText(position)
                    duration.text = timeText(d)
                    if (d > 0L && d != C.TIME_UNSET && !seek.isPressed) {
                        // Move the white thumb and played track with the playback position.
                        seek.progress = ((position.coerceAtLeast(0L) * 1000L) / d)
                            .toInt().coerceIn(0, 1000)
                        buffered = buffered.coerceAtLeast(position)
                        seek.secondaryProgress = ((buffered.coerceAtMost(d) * 1000L) / d)
                            .toInt().coerceIn(seek.progress, 1000)
                    } else if (d <= 0L || d == C.TIME_UNSET) {
                        // A truly unbounded live stream has no measurable percentage timeline.
                        seek.progress = 0
                        seek.secondaryProgress = 0
                    }
                    pauseButton.text = if (p.isPlaying) "Ⅱ" else "▶"
                }
                handler.postDelayed(this, 500)
            }
        }
    }

    frame.setOnClickListener { if (controlsVisible) hideControls.run() else showControls() }
    fullPlayer.setOnClickListener { if (controlsVisible) hideControls.run() else showControls() }
    dialog.setContentView(frame)
    dialog.setOnDismissListener {
        handler.removeCallbacks(updateProgress)
        handler.removeCallbacks(hideControls)
        loadingIndicator = null
        fullPlayer.player = null
        (fullPlayer.parent as? ViewGroup)?.removeView(fullPlayer)
        if (directLinkPlayback) {
            directLinkPlayback = false
            original.player = null
            playerView = null
            requestedOrientation = oldOrientation
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            fullscreenDialog = null
            // Returning from an external app launch should return to HANI TV,
            // not show the player's library page.
            finish()
            return@setOnDismissListener
        }
        if (original.parent == null) parent.addView(original, oldIndex.coerceIn(0, parent.childCount), oldParams)
        original.player = player
        original.useController = true
        requestedOrientation = oldOrientation
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        fullscreenDialog = null
    }

    fullscreenDialog = dialog
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    dialog.show()
    dialog.window?.let { fullscreenWindow ->
        fullscreenWindow.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        fullscreenWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Allow the video surface to extend into the landscape display cutout area.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            fullscreenWindow.attributes = fullscreenWindow.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            fullscreenWindow.setDecorFitsSystemWindows(false)
        }
    }
    dialog.window?.decorView?.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    )
    handler.post(updateProgress)
    handler.postDelayed(hideControls, 4500)
}
                
            
        
        

    private fun formatPlayerTime(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun switchServerInsidePlayer(index: Int) {
        if (index !in currentOptions.indices) return
        currentServerIndex = index
        embeddedServerButtons.forEachIndexed { buttonIndex, button ->
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(45, 0, 0, 0))
                setStroke(dp(3), if (buttonIndex == currentServerIndex) Color.rgb(25, 155, 240) else Color.LTGRAY)
            }
        }
        val source = currentOptions[index]
        val player = embeddedPlayer
        if (player == null || embeddedDialog?.isShowing != true) {
            openEmbeddedPlayer()
            return
        }
        // Keep the existing dialog and PlayerView alive while replacing only the media item.
        // This prevents a brief return to the channel/category screen during server switching.
        startupTimeout?.let { playerHandler.removeCallbacks(it) }
        startupTimeout = null
        failoverPending = false
        loadingIndicator?.visibility = View.VISIBLE
        val mediaItemBuilder = MediaItem.Builder().setUri(source.url)
        val normalizedPath = try {
            android.net.Uri.parse(source.url).path.orEmpty().lowercase()
        } catch (_: Exception) {
            ""
        }
        if (normalizedPath.endsWith("/index.html") || normalizedPath == "index.html") {
            mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(mediaItemBuilder.build())
        player.prepare()
        player.playWhenReady = true
    }

    private fun tryNextServer(failedPlayer: ExoPlayer) {
        if (embeddedPlayer !== failedPlayer || currentOptions.size < 2 || failoverPending) return
        failoverPending = true
        startupTimeout?.let { playerHandler.removeCallbacks(it) }
        playerHandler.postDelayed({
            failoverPending = false
            if (embeddedPlayer !== failedPlayer || currentOptions.isEmpty()) return@postDelayed
            val nextIndex = (currentServerIndex + 1) % currentOptions.size
            failoverCount++
            switchServerInsidePlayer(nextIndex)
        }, 250L)
    }

    private fun closeEmbeddedPlayer() {
        embeddedDialog?.let { dialog ->
            // Detach cleanup before dismissal so a stale dialog cannot release a newer player.
            dialog.setOnDismissListener(null)
            if (dialog.isShowing) dialog.dismiss()
        }
        releaseEmbeddedPlayer()
    }

    private fun releaseEmbeddedPlayer() {
        progressUpdater?.let { playerHandler.removeCallbacks(it) }
        progressUpdater = null
        startupTimeout?.let { playerHandler.removeCallbacks(it) }
        startupTimeout = null
        embeddedPlayerView?.player = null
        embeddedPlayer?.release()
        embeddedPlayer = null
        embeddedPlayerView = null
        loadingIndicator = null
        embeddedDialog = null
        embeddedChannelTitle = null
        embeddedServerButtons.clear()
        embeddedChannelCards.clear()
        failoverPending = false
    }


    private fun showServerManager() {
        showingServerManager = true
        content.removeAllViews()
        addText("إدارة السيرفرات", 24f, white, true)
        addText("تتم مزامنة السيرفرات بين نسخ HANI TV. التعديل لحساب الإدارة فقط.", 14f, gray, false)
        val add = makeButton("＋ إضافة سيرفر / رابط قناة")
        add.setOnClickListener { requireAdmin { showAddSourceDialog() } }
        content.addView(add, fullWidthParams())

        if (sources.isEmpty()) addText("لا توجد روابط محفوظة بعد.", 14f, gray, false, 12)
        sources.forEachIndexed { index, source ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(panel)
            }
            card.addView(TextView(this).apply {
                text = "${source.channel} — ${source.serverName}"
                textSize = 16f
                setTextColor(white)
                setTypeface(null, Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = source.url
                textSize = 12f
                setTextColor(gray)
                setPadding(0, dp(6), 0, dp(8))
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val edit = makeButton("تعديل")
            edit.setOnClickListener {
                requireAdmin {
                    val currentIndex = sources.indexOf(source)
                    if (currentIndex >= 0) showAddSourceDialog(sources[currentIndex], currentIndex)
                    else {
                        Toast.makeText(this, "تغيرت قائمة السيرفرات؛ افتح الإدارة مجددًا.", Toast.LENGTH_LONG).show()
                        showServerManager()
                    }
                }
            }
            val delete = makeButton("حذف")
            delete.setOnClickListener {
                requireAdmin {
                    val currentIndex = sources.indexOf(source)
                    if (currentIndex >= 0) {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("حذف رابط ${source.channel} من ${source.serverName}؟")
                            .setPositiveButton("حذف") { _, _ ->
                                sources.removeAt(currentIndex)
                                saveSources()
                                saveSourcesToCloud()
                                showServerManager()
                            }
                            .setNegativeButton("إلغاء", null).show()
                    } else {
                        Toast.makeText(this, "تغيرت قائمة السيرفرات؛ افتح الإدارة مجددًا.", Toast.LENGTH_LONG).show()
                        showServerManager()
                    }
                }
            }
            actions.addView(edit, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(5), 0) })
            actions.addView(delete, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, 0, 0) })
            card.addView(actions)
            content.addView(card, fullWidthParams().apply { topMargin = dp(10) })
        }
        addBackButton()
    }

    private fun showAddSourceDialog(existing: StreamSource? = null, editIndex: Int = -1) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(hint: String, value: String = ""): EditText {
            return EditText(this).apply {
                this.hint = hint
                setText(value)
                setTextColor(white)
                setHintTextColor(gray)
                textSize = 15f
                setSingleLine(true)
            }.also { box.addView(it) }
        }
        val channelNames = channelGroups.values.flatten().map { it.first }
        val channelInput = field("اسم القناة (مثال: SPORT 1)", existing?.channel ?: channelNames.first())
        val serverInput = field("اسم السيرفر للإدارة (مثال: السيرفر الاحتياطي)", existing?.serverName ?: "السيرفر الأساسي")
        val displayNameInput = field("الاسم الذي يظهر داخل المشغّل", existing?.displayName ?: existing?.serverName ?: "السيرفر الأساسي")
        val urlInput = field("رابط البث الكامل", existing?.url ?: "")
        val refererInput = field("Referer (اختياري)", existing?.referer ?: "")
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "إضافة رابط" else "تعديل الرابط")
            .setView(box)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val channel = channelInput.text.toString().trim()
                        val server = serverInput.text.toString().trim()
                        val displayName = displayNameInput.text.toString().trim()
                        val url = urlInput.text.toString().trim()
                        val referer = refererInput.text.toString().trim()
                        if (channel.isBlank() || server.isBlank() || url.isBlank() ||
                            !(url.startsWith("http://") || url.startsWith("https://"))) {
                            Toast.makeText(this, "أدخل اسم القناة والسيرفر ورابطًا يبدأ بـ http:// أو https://", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        val item = StreamSource(channel, server, url, referer, displayName.ifBlank { server })
                        if (editIndex >= 0 && editIndex < sources.size) sources[editIndex] = item else sources.add(item)
                        saveSources()
                        saveSourcesToCloud()
                        dialog.dismiss()
                        showServerManager()
                    }
                }
                dialog.show()
            }
    }

    private fun fullWidthParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun loadSources() {
        sources.clear()
        val raw = prefs.getString("sources_json", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                sources.add(StreamSource(
                    item.optString("channel"), item.optString("serverName"),
                    item.optString("url"), item.optString("referer"),
                    item.optString("displayName", item.optString("serverName"))
                ))
            }
        } catch (_: Exception) {
            sources.clear()
        }
    }

    private fun saveSources() {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(JSONObject().apply {
                put("channel", source.channel)
                put("serverName", source.serverName)
                put("url", source.url)
                put("referer", source.referer)
                put("displayName", source.displayName)
            })
        }
        prefs.edit().putString("sources_json", array.toString()).apply()
    }

    /** Read the shared list. If the database is still empty, only the signed-in admin may seed it. */
    private fun syncFromCloud(onComplete: (() -> Unit)? = null) {
        remoteSources.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val downloaded = mutableListOf<StreamSource>()
                    for (child in snapshot.children) {
                        if (!child.hasChild("channel")) continue
                        downloaded.add(
                            StreamSource(
                                child.child("channel").getValue(String::class.java).orEmpty(),
                                child.child("serverName").getValue(String::class.java).orEmpty(),
                                child.child("url").getValue(String::class.java).orEmpty(),
                                child.child("referer").getValue(String::class.java).orEmpty(),
                                child.child("displayName").getValue(String::class.java)
                                    ?: child.child("serverName").getValue(String::class.java).orEmpty()
                            )
                        )
                    }
                    sources.clear()
                    sources.addAll(downloaded)
                    saveSources()
                    if (showingServerManager) showServerManager()
                    onComplete?.invoke()
                } else if (firebaseAuth.currentUser?.uid == adminUid) {
                    // First migration: seed the empty cloud database from this admin device's local list.
                    saveSourcesToCloud(onComplete)
                } else {
                    // Keep the existing local list until an admin initializes the shared database.
                    onComplete?.invoke()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MainActivity, "تعذرت مزامنة السيرفرات: ${error.message}", Toast.LENGTH_LONG).show()
                onComplete?.invoke()
            }
        })
    }

    /** Write is accepted by Firebase rules only when the signed-in UID is the configured admin UID. */
    private fun saveSourcesToCloud(onComplete: (() -> Unit)? = null) {
        if (firebaseAuth.currentUser?.uid != adminUid) {
            onComplete?.invoke()
            return
        }
        val payload = linkedMapOf<String, Any>()
        sources.forEachIndexed { index, source ->
            payload[index.toString()] = mapOf(
                "channel" to source.channel,
                "serverName" to source.serverName,
                "url" to source.url,
                "referer" to source.referer,
                "displayName" to source.displayName
            )
        }
        if (payload.isEmpty()) payload["_empty"] = true
        remoteSources.setValue(payload)
            .addOnSuccessListener { onComplete?.invoke() }
            .addOnFailureListener { error ->
                Toast.makeText(this, "لم تُحفظ التعديلات في السحابة: ${error.message}", Toast.LENGTH_LONG).show()
                onComplete?.invoke()
            }
    }

    /** Ask for the admin account only when a user attempts to add, edit, or delete a source. */
    private fun requireAdmin(action: () -> Unit) {
        if (firebaseAuth.currentUser?.uid == adminUid) {
            action()
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val emailInput = EditText(this).apply {
            hint = "البريد الإلكتروني للإدارة"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        val passwordInput = EditText(this).apply {
            hint = "كلمة مرور الإدارة"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        box.addView(emailInput, fullWidthParams())
        box.addView(passwordInput, fullWidthParams())

        val dialog = AlertDialog.Builder(this)
            .setTitle("تسجيل دخول الإدارة")
            .setMessage("يلزم حساب الإدارة لتعديل قائمة السيرفرات. بقية النسخ تستطيع قراءة القائمة فقط.")
            .setView(box)
            .setPositiveButton("دخول", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (email.isBlank() || password.isBlank()) {
                    Toast.makeText(this, "أدخل البريد الإلكتروني وكلمة المرور.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                firebaseAuth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful && firebaseAuth.currentUser?.uid == adminUid) {
                            dialog.dismiss()
                            syncFromCloud { action() }
                        } else {
                            firebaseAuth.signOut()
                            firebaseAuth.signInAnonymously()
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            Toast.makeText(
                                this,
                                if (task.isSuccessful) "هذا الحساب ليس حساب الإدارة المصرح به." else "فشل تسجيل الدخول؛ تحقق من بيانات الحساب.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
            }
        }
        dialog.show()
    }

    private fun addSectionCard(
        emoji: String,
        title: String,
        action: () -> Unit
    ) {
        val card = makeButton("$emoji   $title")
        card.setOnClickListener { action() }

        content.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )
    }

    private fun addText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean,
        top: Int = 0
    ) {
        content.addView(TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(top), 0, dp(8))
        })
    }

    private fun makeButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(red)
            isClickable = true
            isFocusable = true
        }
    }

    private fun addBackButton() {
        val back = makeButton("العودة للرئيسية")
        back.setOnClickListener { showHome() }

        content.addView(
            back,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }


    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        showHome()
    }
}
