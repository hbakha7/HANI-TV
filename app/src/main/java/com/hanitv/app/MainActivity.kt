package com.hanitv.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
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
    private var loadingIndicator: ProgressBar? = null
    private var currentOptions: List<StreamSource> = emptyList()
    private var currentServerIndex = 0
    private var failoverCount = 0
    private val playerHandler = Handler(Looper.getMainLooper())
    private var startupTimeout: Runnable? = null
    private var failoverPending = false

    // Do not open a channel from stale local data while the first Firebase read is pending.
    private var initialSourceSyncFinished = false
    private var pendingChannelToOpen: String? = null

    private data class StreamSource(
        val channel: String,
        val serverName: String,
        val url: String,
        val referer: String = "",
        val displayName: String = serverName,
        val cookie: String = "",
        val authorization: String = "",
        val userAgent: String = ""
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
        // Open immediately from the locally cached source list; Firebase sync continues in the background.
        val options = sources.filter { it.channel == channelName }
        if (options.isEmpty()) {
            Toast.makeText(this, "لا توجد روابط محفوظة لهذه القناة. افتح إدارة السيرفرات لإضافة رابط.", Toast.LENGTH_LONG).show()
            return
        }
        launchPlayer(options, 0)
    }

    



private fun launchPlayer(
    options: List<StreamSource>,
    selectedIndex: Int = 0
) {
    if (options.isEmpty()) return

    currentOptions = options
    currentServerIndex = selectedIndex.coerceIn(0, options.lastIndex)
    failoverCount = 0
    failoverPending = false

    openEmbeddedPlayer()
}




    /** Plays channel streams inside HANI TV; the separate 103 IPTV Player is not needed. */
    private fun openEmbeddedPlayer() {
        closeEmbeddedPlayer()
        val source = currentOptions.getOrNull(currentServerIndex) ?: return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        embeddedDialog = dialog

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setBackgroundColor(Color.BLACK)
        }
        embeddedPlayerView = video
        root.addView(video, FrameLayout.LayoutParams(-1, -1))

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = source.channel
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        val close = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dialog.dismiss() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(48), dp(44)))
        overlay.addView(top)

        val serverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (currentOptions.size > 1) {
            serverRow.addView(TextView(this).apply {
                text = "السيرفرات"
                textSize = 13f
                setTextColor(Color.WHITE)
            })
            currentOptions.forEachIndexed { index, item ->
                val button = Button(this).apply {
                    text = item.displayName.ifBlank { item.serverName }.ifBlank { "${index + 1}" }
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(if (index == currentServerIndex) Color.rgb(25, 150, 235) else Color.DKGRAY)
                    setOnClickListener {
                        currentServerIndex = index
                        openEmbeddedPlayer()
                    }
                }
                serverRow.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                })
            }
            overlay.addView(serverRow)
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            contentDescription = "جارٍ تحميل البث"
        }
        loadingIndicator = spinner
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        dialog.setContentView(root)
        dialog.setOnDismissListener { releaseEmbeddedPlayer() }
        dialog.show()

        val headers = mutableMapOf<String, String>()
        if (source.referer.isNotBlank()) headers["Referer"] = source.referer
        // Use the same kind of HTTP setup as HTV PLAYER, while keeping HANI TV's
        // per-source Referer. A default User-Agent helps servers that reject an
        // empty Android client identity; a saved custom value takes precedence.
        val userAgent = prefs.getString("add_user_agent", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "103IPTVPlayer/1.0"
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(6000)
            .setReadTimeoutMs(12000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(httpFactory)
            )
            .build()
        embeddedPlayer = exo
        video.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                loadingIndicator?.visibility = when (playbackState) {
                    Player.STATE_BUFFERING, Player.STATE_IDLE -> View.VISIBLE
                    Player.STATE_READY, Player.STATE_ENDED -> View.GONE
                    else -> loadingIndicator?.visibility ?: View.GONE
                }
                if (playbackState == Player.STATE_READY) {
                    startupTimeout?.let { playerHandler.removeCallbacks(it) }
                    startupTimeout = null
                    failoverCount = 0
                } else if (playbackState == Player.STATE_BUFFERING && currentOptions.size > 1) {
                    startupTimeout?.let { playerHandler.removeCallbacks(it) }
                    val timeout = Runnable {
                        if (embeddedPlayer === exo && exo.playbackState != Player.STATE_READY && !exo.isPlaying) {
                            tryNextServer(exo)
                        }
                    }
                    startupTimeout = timeout
                    // Try the next server sooner when the current stream never becomes ready.
                    playerHandler.postDelayed(timeout, 5000L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                loadingIndicator?.visibility = View.VISIBLE
                if (currentOptions.size > 1) {
                    tryNextServer(exo)
                } else {
                    // Show the actual Media3 error code to help identify whether
                    // the server rejected the request or the stream format is unsupported.
                    Toast.makeText(
                        this@MainActivity,
                        "تعذر التشغيل: ${error.errorCodeName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
        val mediaItemBuilder = MediaItem.Builder().setUri(source.url)
        // Match HTV PLAYER's HLS fallback for providers that use an index.html URL.
        val normalizedPath = try {
            android.net.Uri.parse(source.url).path.orEmpty().lowercase()
        } catch (_: Exception) {
            ""
        }
        if (normalizedPath.endsWith("/index.html") || normalizedPath == "index.html") {
            mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }
        exo.setMediaItem(mediaItemBuilder.build())
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun tryNextServer(failedPlayer: ExoPlayer) {
        if (embeddedPlayer !== failedPlayer || currentOptions.size < 2 || failoverPending) return
        failoverPending = true
        startupTimeout?.let { playerHandler.removeCallbacks(it) }
        playerHandler.postDelayed({
            failoverPending = false
            if (embeddedPlayer !== failedPlayer || currentOptions.isEmpty()) return@postDelayed
            currentServerIndex = (currentServerIndex + 1) % currentOptions.size
            failoverCount++
            openEmbeddedPlayer()
        }, 250L)
    }

    private fun closeEmbeddedPlayer() {
        embeddedDialog?.let { dialog ->
            if (dialog.isShowing && embeddedPlayer != null) {
                // Rebuilding for a server change dismisses the old dialog; guard cleanup below.
                dialog.setOnDismissListener(null)
                dialog.dismiss()
            }
        }
        releaseEmbeddedPlayer()
    }

    private fun releaseEmbeddedPlayer() {
        startupTimeout?.let { playerHandler.removeCallbacks(it) }
        startupTimeout = null
        embeddedPlayerView?.player = null
        embeddedPlayer?.release()
        embeddedPlayer = null
        embeddedPlayerView = null
        loadingIndicator = null
        embeddedDialog = null
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
        val cookieInput = field("Cookie (اختياري)", existing?.cookie ?: "")
        val authorizationInput = field("Authorization (اختياري)", existing?.authorization ?: "")
        val userAgentInput = field("User-Agent (اختياري)", existing?.userAgent ?: "")
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
                        val cookie = cookieInput.text.toString().trim()
                        val authorization = authorizationInput.text.toString().trim()
                        val userAgent = userAgentInput.text.toString().trim()
                        if (channel.isBlank() || server.isBlank() || url.isBlank() ||
                            !(url.startsWith("http://") || url.startsWith("https://"))) {
                            Toast.makeText(this, "أدخل اسم القناة والسيرفر ورابطًا يبدأ بـ http:// أو https://", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        val item = StreamSource(
                            channel, server, url, referer, displayName.ifBlank { server },
                            cookie, authorization, userAgent
                        )
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
                    item.optString("displayName", item.optString("serverName")),
                    item.optString("cookie"), item.optString("authorization"), item.optString("userAgent")
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
                put("cookie", source.cookie)
                put("authorization", source.authorization)
                put("userAgent", source.userAgent)
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
                                    ?: child.child("serverName").getValue(String::class.java).orEmpty(),
                                child.child("cookie").getValue(String::class.java).orEmpty(),
                                child.child("authorization").getValue(String::class.java).orEmpty(),
                                child.child("userAgent").getValue(String::class.java).orEmpty()
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
                "displayName" to source.displayName,
                "cookie" to source.cookie,
                "authorization" to source.authorization,
                "userAgent" to source.userAgent
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

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        showHome()
    }
}
