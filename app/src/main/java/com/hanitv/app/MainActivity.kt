package com.hanitv.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.graphics.Color
import android.graphics.BitmapFactory
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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
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

class MainActivity : Activity() {

    private val bg = Color.rgb(12, 12, 15)
    private val panel = Color.rgb(25, 25, 30)
    private val red = Color.rgb(210, 20, 35)
    private val white = Color.WHITE
    private val gray = Color.rgb(175, 175, 185)

    private lateinit var content: LinearLayout
    private val prefs by lazy { getSharedPreferences("hani_tv_sources", MODE_PRIVATE) }
    private val sources = mutableListOf<StreamSource>()
    private val channelImageUrls = linkedMapOf<String, String>()
    private val channelCategories = linkedMapOf<String, String>()
    private val apiUrl = "https://103service.store/api.php"
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var adminApiKey: String? = null
    private var adminActionPending = false
    private var showingServerManager = false
    private var embeddedPlayer: ExoPlayer? = null
    private var embeddedPlayerView: PlayerView? = null
    private var embeddedDialog: Dialog? = null
    private var embeddedChannelTitle: TextView? = null
    private val embeddedServerButtons = mutableListOf<TextView>()
    private var embeddedServerContent: LinearLayout? = null
    private var serverRefreshRunnable: Runnable? = null
    private val embeddedChannelCards = linkedMapOf<String, View>()
    private var loadingIndicator: ProgressBar? = null
    private var progressUpdater: Runnable? = null
    private var currentOptions: List<StreamSource> = emptyList()
    private var currentServerIndex = 0
    private var failoverCount = 0
    private val playerHandler = Handler(Looper.getMainLooper())
    private var startupTimeout: Runnable? = null
    private var failoverPending = false
    private var sameServerRetryUsed = false
    private var keepScreenOnWasSet = false
    private var orientationBeforePlayer = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var systemUiBeforePlayer = 0

    // Wait for the first Hostinger API read before opening a channel from stale local data.
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
            "beIN SPORTS 1" to "B1",
            "beIN SPORTS 2" to "B2",
            "beIN SPORTS 3" to "B3",
            "beIN SPORTS 4" to "B4",
            "beIN SPORTS 5" to "B5",
            "beIN SPORTS 6" to "B6",
            "beIN SPORTS 7" to "B7",
            "beIN SPORTS 8" to "B8",
            "beIN SPORTS 9" to "B9",
            "beIN SPORTS 10" to "B10",
            "beIN SPORTS NEWS" to "BN"
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
            setOnLongClickListener(View.OnLongClickListener {
                requireAdmin { showServerManager() }
                true
            })
        })

        val hiddenAdminGesture = View.OnLongClickListener {
            requireAdmin { showServerManager() }
            true
        }
        header.addView(TextView(this).apply {
            text = "TV"
            textSize = 26f
            setTextColor(red)
            setTypeface(null, Typeface.BOLD)
            setOnLongClickListener(hiddenAdminGesture)
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
        showHome()
        syncFromCloud { finishInitialSourceSync() }
    }

    private fun finishInitialSourceSync() {
        initialSourceSyncFinished = true
        val pendingChannel = pendingChannelToOpen
        pendingChannelToOpen = null
        if (!pendingChannel.isNullOrBlank()) {
            openChannel(pendingChannel)
        } else {
            showHome()
        }
    }

    private fun showHome() {
        // Home-screen redesign only. The existing header, player, and server-management
        // functions are intentionally left unchanged.
        showingServerManager = false
        content.removeAllViews()
        content.setPadding(dp(14), dp(16), dp(14), dp(28))

        val purple = Color.rgb(123, 65, 255)
        val deepPurple = Color.rgb(66, 18, 133)
        val navy = Color.rgb(14, 27, 49)
        val cardStroke = Color.rgb(43, 58, 88)
        val softWhite = Color.rgb(224, 231, 245)
        val mint = Color.rgb(72, 220, 164)

        // Welcome text: no second HANI TV logo here because the app header already has it.
        content.addView(TextView(this).apply {
            text = "مرحباً بك في HANI TV"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.START
        })
        content.addView(TextView(this).apply {
            text = "قنواتك المفضلة في مكان واحد"
            textSize = 14f
            setTextColor(gray)
            gravity = Gravity.START
            setPadding(0, dp(4), 0, dp(16))
        })

        // Main sports banner
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(deepPurple, Color.rgb(23, 34, 82), Color.rgb(7, 16, 34))
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(112, 70, 220))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showChannels("القنوات الرياضية") }
        }

        banner.addView(TextView(this).apply {
            text = "⚽  HANI TV SPORTS"
            textSize = 14f
            setTextColor(Color.rgb(218, 203, 255))
            setTypeface(null, Typeface.BOLD)
        })
        banner.addView(TextView(this).apply {
            text = "عِش أجواء المباريات"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.START
            setPadding(0, dp(14), 0, dp(5))
        })
        banner.addView(TextView(this).apply {
            text = "قنواتك الرياضية في مكان واحد"
            textSize = 14f
            setTextColor(softWhite)
            gravity = Gravity.START
        })
        content.addView(banner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(22) })

        // Sports brands section
        val sportsHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        sportsHeading.addView(TextView(this).apply {
            text = "القنوات الرياضية"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sportsHeading.addView(TextView(this).apply {
            text = "⚽"
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        content.addView(sportsHeading)

        val sportsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            // Snap the horizontal slider to a channel card after the user swipes.
            setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP ||
                    event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    view.post {
                        val itemWidth = dp(120) // 112dp card + 8dp horizontal margins
                        val slider = view as HorizontalScrollView
                        val targetX = ((slider.scrollX + itemWidth / 2) / itemWidth) * itemWidth
                        slider.smoothScrollTo(targetX, 0)
                    }
                }
                false
            }
        }
        val sportsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // beIN SPORTS channel strip: logos/cards only for beIN channels.
        // Each card uses the configured channel image when available, with a built-in beIN wordmark fallback.
        val sportsChannels = channelGroups["القنوات الرياضية"].orEmpty()

        sportsChannels.forEach { channel ->
            val brandCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(8), dp(7), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(22, 20, 31))
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), cardStroke)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { showChannels("القنوات الرياضية") }
            }

            brandCard.addView(
                channelLogoView(channel.first, channel.second, dp(88), dp(43)),
                LinearLayout.LayoutParams(dp(88), dp(43))
            )
            brandCard.addView(TextView(this).apply {
                text = displayChannelName(channel.first)
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                maxLines = 1
                setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(dp(100), dp(24)))

            sportsRow.addView(brandCard, LinearLayout.LayoutParams(
                dp(112), dp(91)
            ).apply { setMargins(dp(4), dp(2), dp(4), dp(5)) })
        }
        sportsScroll.addView(sportsRow)
        content.addView(sportsScroll)

        // Main categories
        content.addView(TextView(this).apply {
            text = "الأقسام الرئيسية"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(12))
        })

        allCategories().forEach { category ->
            val categoryCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = GradientDrawable().apply {
                    setColor(navy)
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), cardStroke)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { showChannels(category) }
            }

            val iconText = when (category) {
                "القنوات الرياضية" -> "⚽"
                "القنوات العربية" -> "📺"
                "القنوات الإخبارية" -> "🌍"
                "قنوات الأطفال" -> "🧸"
                "قنوات الترفيه" -> "🎭"
                else -> "📡"
            }
            categoryCard.addView(TextView(this).apply {
                text = iconText
                textSize = 27f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(48), dp(46)))

            categoryCard.addView(TextView(this).apply {
                text = category
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(10), 0, dp(4), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            categoryCard.addView(TextView(this).apply {
                text = "‹"
                textSize = 28f
                setTextColor(purple)
            })

            content.addView(categoryCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) })
        }

        // Streaming platforms row; visual labels only, not new playback sources.
        content.addView(TextView(this).apply {
            text = "منصات عالمية"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(12))
        })
        val platforms = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val platformItems = listOf(
            "NETFLIX" to Color.rgb(240, 40, 52),
            "Disney+" to Color.rgb(80, 145, 255),
            "prime video" to Color.rgb(75, 190, 245)
        )
        platformItems.forEach { item ->
            val tile = TextView(this).apply {
                text = item.first
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(item.second)
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                setPadding(dp(3), dp(12), dp(3), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(13, 24, 41))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), cardStroke)
                }
            }
            platforms.addView(tile, LinearLayout.LayoutParams(
                0, dp(54), 1f
            ).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        content.addView(platforms)

        // Preserve the existing server-management entry point and behavior.
        val manager = makeButton("⚙  إدارة السيرفرات وروابط القنوات")
        manager.setOnClickListener { showServerManager() }
        content.addView(manager, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })
    }

    private fun showAllChannels() {
        showingServerManager = false
        content.removeAllViews()
        addText("جميع القنوات", 24f, white, true, 8)

        allCategories().forEach { category ->
            addCategoryCard(category)
        }
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
                "القنوات الرياضية" -> "beIN\nSPORTS"
                "القنوات العربية" -> "📺"
                "القنوات الإخبارية" -> "🌍"
                "قنوات الأطفال" -> "🧸"
                else -> "🎭"
            }
            textSize = if (category == "القنوات الرياضية") 13f else 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            if (category == "القنوات الرياضية") {
                setTypeface(null, Typeface.BOLD)
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.rgb(100, 20, 150), Color.rgb(220, 20, 75))
                ).apply { cornerRadius = dp(8).toFloat() }
            }
        }

        card.addView(
            icon,
            LinearLayout.LayoutParams(
                if (category == "القنوات الرياضية") dp(76) else dp(48),
                if (category == "القنوات الرياضية") dp(54) else dp(45)
            )
        )

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

        val channels = channelGroups.values.flatten().distinctBy { it.first }
            .filter { (name, _) -> (channelCategories[name] ?: defaultCategoryFor(name)) == category }

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

        card.addView(
            channelLogoView(channel.first, channel.second, dp(66), dp(66)),
            LinearLayout.LayoutParams(dp(66), dp(66))
        )

        card.addView(TextView(this).apply {
            text = displayChannelName(channel.first)
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

private fun displayChannelName(name: String): String {
    val value = name.trim()

    if (
        value.contains("bein", ignoreCase = true) &&
        value.contains("news", ignoreCase = true)
    ) {
        return "beIN SPORTS NEWS"
    }

    val match = Regex(
        """(?i)bein\s*[-_ ]*sports\s*([1-9])"""
    ).find(value)

    if (match != null) {
        return "beIN SPORTS ${match.groupValues[1]}"
    }

    return value
}


    private fun openChannel(channelName: String) {
        if (!initialSourceSyncFinished) {
            // Queue the tap and launch automatically as soon as local/cloud source selection is settled.
            pendingChannelToOpen = channelName
            return
        }
        val options = sources.filter { it.channel == channelName }
        if (options.isEmpty()) {
            Toast.makeText(this, "لا تتوفر روابط لهذه القناة حاليًا.", Toast.LENGTH_SHORT).show()
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
    private fun openEmbeddedPlayer() {
        closeEmbeddedPlayer()
        val source = currentOptions.getOrNull(currentServerIndex) ?: return

        // Keep the screen awake while the player is open.
        keepScreenOnWasSet = (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Open the stream directly in landscape immersive mode.
        orientationBeforePlayer = requestedOrientation
        systemUiBeforePlayer = window.decorView.systemUiVisibility
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        embeddedDialog = dialog

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val videoFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setBackgroundColor(Color.BLACK)
            // Default: stretch the picture to fill the player area, like the requested VLC-style mode.
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        embeddedPlayerView = video
        // Do not add extra zoom; only use the selected resize mode.
        video.scaleX = 1.0f
        video.scaleY = 1.0f
        videoFrame.clipChildren = true
        videoFrame.clipToPadding = true
        videoFrame.addView(video, FrameLayout.LayoutParams(-1, -1))

        // Fill the player area without any additional scaling.
        video.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL

        // Tapping the picture toggles every player overlay; controls can be hidden for viewing
        // and brought back with another tap. The tap layer sits below all interactive controls.
        var overlaysVisible = true
        val overlayViews = mutableListOf<View>()
        val tapLayer = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                overlaysVisible = !overlaysVisible
                val visibility = if (overlaysVisible) View.VISIBLE else View.GONE
                overlayViews.forEach { it.visibility = visibility }
            }
        }
        videoFrame.addView(tapLayer, FrameLayout.LayoutParams(-1, -1))

        // Compact top overlay: remove the duplicate channel title; the channel name remains
        // visible on its card in the bottom channel strip.
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(1), dp(8), dp(4))
            setBackgroundColor(Color.argb(105, 0, 0, 0))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(36), dp(28)))
        overlay.addView(top, LinearLayout.LayoutParams(-1, dp(28)))

        // Circular server selectors, reduced to the same compact size as the reference image.
        embeddedServerButtons.clear()
        val serverContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        embeddedServerContent = serverContent
        serverContent.addView(TextView(this).apply {
            text = "السيرفرات"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(dp(62), dp(50)))
        currentOptions.forEachIndexed { index, item ->
            val label = item.displayName.ifBlank { item.serverName }
                .replace("السيرفر الأساسي", "السيرفر")
                .take(8)
            val button = TextView(this).apply {
                text = "${index + 1}\n$label"
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(45, 0, 0, 0))
                    setStroke(dp(2), if (index == currentServerIndex) Color.rgb(25, 155, 240) else Color.LTGRAY)
                }
                setOnClickListener { switchServerInsidePlayer(index) }
            }
            embeddedServerButtons.add(button)
            serverContent.addView(button, LinearLayout.LayoutParams(dp(50), dp(50)).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        val serverScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(serverContent)
        }
        overlay.addView(serverScroll, LinearLayout.LayoutParams(-1, dp(50)))
        videoFrame.addView(overlay, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        overlayViews.add(overlay)

        // Persistent player controls: these remain visible and each button controls playback.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.argb(145, 0, 0, 0))
        }
        fun playerControl(symbol: String, description: String, action: () -> Unit): TextView {
            val control = TextView(this).apply {
                text = symbol
                contentDescription = description
                textSize = if (symbol == "HD") 16f else 24f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            controls.addView(control, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            })
            return control
        }
        playerControl("⏪₁₀", "الرجوع 10 ثوانٍ") {
            embeddedPlayer?.let { player ->
                if (player.isCurrentMediaItemSeekable) {
                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                } else Toast.makeText(this, "هذا البث لا يدعم الرجوع الزمني", Toast.LENGTH_SHORT).show()
            }
        }
        val soundControl = playerControl("◖))", "كتم الصوت واستعادته") {
            embeddedPlayer?.let { player -> player.volume = if (player.volume > 0f) 0f else 1f }
        }
        playerControl("HD", "اختيار جودة الفيديو") {
            embeddedPlayer?.let { player ->
                TrackSelectionDialogBuilder(this, "جودة الفيديو", player, C.TRACK_TYPE_VIDEO)
                    .setAllowAdaptiveSelections(true)
                    .build()
                    .show()
            }
        }
        playerControl("⚙", "إعدادات سرعة التشغيل") {
            val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
            AlertDialog.Builder(this).setTitle("سرعة التشغيل")
                .setItems(speeds) { _, which ->
                    embeddedPlayer?.setPlaybackSpeed(speeds[which].removeSuffix("x").toFloatOrNull() ?: 1f)
                }.setNegativeButton("إغلاق", null).show()
        }
        val playPauseControl = playerControl("Ⅱ", "إيقاف أو استئناف التشغيل") {
            embeddedPlayer?.let { player -> if (player.isPlaying) player.pause() else player.play() }
        }
        playerControl("⛶", "تمديد الصورة لملء مساحة المشغل") {
            video.scaleX = 1.0f
            video.scaleY = 1.0f
            video.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        playerControl("▣", "خيارات شكل الشاشة") {
            val modes = arrayOf(
                "تمديد الصورة لملء الشاشة (مثل VLC)",
                "الصورة كاملة مع الحفاظ على النسب",
                "تكبير الصورة مع قص الأطراف",
                "ملاءمة العرض",
                "ملاءمة الارتفاع"
            )
            AlertDialog.Builder(this)
                .setTitle("اختر شكل عرض الفيديو")
                .setItems(modes) { _, which ->
                    // Reset any extra scaling before applying the chosen mode.
                    video.scaleX = 1.0f
                    video.scaleY = 1.0f
                    video.resizeMode = when (which) {
                        0 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                        1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        3 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                        else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
        playerControl("⏩₁₀", "التقديم 10 ثوانٍ") {
            embeddedPlayer?.let { player ->
                if (player.isCurrentMediaItemSeekable) {
                    val target = player.currentPosition + 10_000L
                    val duration = player.duration
                    player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                } else Toast.makeText(this, "هذا البث لا يدعم التقديم الزمني", Toast.LENGTH_SHORT).show()
            }
        }
        videoFrame.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ).apply { leftMargin = dp(12); rightMargin = dp(12) })
        overlayViews.add(controls)

        // Timeline is visible permanently. Seek is enabled only for seekable media.
        val timeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.argb(145, 0, 0, 0))
        }
        val elapsedText = TextView(this).apply {
            text = "00:00"; textSize = 12f; setTextColor(Color.WHITE)
        }
        val durationText = TextView(this).apply {
            text = "LIVE"; textSize = 12f; setTextColor(Color.LTGRAY)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val timelineSeek = SeekBar(this).apply {
            max = 1000; progress = 0; isEnabled = false
            contentDescription = "شريط تقدم الفيديو"
            progressTintList = android.content.res.ColorStateList.valueOf(Color.RED)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        timeline.addView(elapsedText, LinearLayout.LayoutParams(dp(48), dp(34)))
        timeline.addView(timelineSeek, LinearLayout.LayoutParams(0, dp(34), 1f))
        timeline.addView(durationText, LinearLayout.LayoutParams(dp(48), dp(34)))
        timelineSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = embeddedPlayer?.duration ?: 0L
                    if (duration > 0L) elapsedText.text = formatPlayerTime(duration * progress / 1000L)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val player = embeddedPlayer ?: return
                val duration = player.duration
                if (duration > 0L && player.isCurrentMediaItemSeekable) {
                    player.seekTo(duration * (seekBar?.progress ?: 0) / 1000L)
                }
            }
        })
        videoFrame.addView(timeline, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38), Gravity.BOTTOM
        ).apply { bottomMargin = dp(96) })
        overlayViews.add(timeline)

        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            contentDescription = "جارٍ تحميل البث"
        }
        loadingIndicator = spinner
        videoFrame.addView(spinner, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        // The video occupies the whole landscape screen; overlays stay inside it.
        root.addView(videoFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        // Horizontal channel strip overlays the bottom of the fullscreen video.
        val channelScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.argb(150, 8, 10, 11))
        }
        val channelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        embeddedChannelCards.clear()
        val allChannels = channelGroups.values.flatten().distinctBy { it.first }
        allChannels.forEach { channel ->
            val selected = channel.first == source.channel
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setBackgroundColor(if (selected) Color.rgb(28, 105, 165) else Color.TRANSPARENT)
                isClickable = true
                isFocusable = true
                setOnClickListener { openChannel(channel.first) }
            }
            card.addView(
                channelLogoView(channel.first, channel.second, dp(76), dp(54)),
                LinearLayout.LayoutParams(dp(76), dp(54))
            )
            card.addView(TextView(this).apply {
                text = channel.first
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                maxLines = 1
            }, LinearLayout.LayoutParams(dp(92), dp(29)))
            channelRow.addView(card, LinearLayout.LayoutParams(dp(100), dp(116)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
            embeddedChannelCards[channel.first] = card
        }
        channelScroll.addView(channelRow)
        videoFrame.addView(
            channelScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(94),
                Gravity.BOTTOM
            )
        )
        overlayViews.add(channelScroll)

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            releaseEmbeddedPlayer()
            window.decorView.systemUiVisibility = systemUiBeforePlayer
            requestedOrientation = orientationBeforePlayer
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        scheduleServerRefreshWhilePlaying()
        dialog.window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        val headers = mutableMapOf<String, String>()
        if (source.referer.isNotBlank()) headers["Referer"] = source.referer
        val userAgent = prefs.getString("add_user_agent", "")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "103IPTVPlayer/1.0"
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(6000)
            .setReadTimeoutMs(12000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
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
                    sameServerRetryUsed = false
                } else if (playbackState == Player.STATE_BUFFERING) {
                    startupTimeout?.let { playerHandler.removeCallbacks(it) }
                    val timeout = Runnable {
                        if (embeddedPlayer === exo && exo.playbackState == Player.STATE_BUFFERING && !exo.isPlaying) {
                            if (!sameServerRetryUsed) {
                                sameServerRetryUsed = true
                                exo.prepare()
                                exo.playWhenReady = true
                            } else if (currentOptions.size > 1) {
                                tryNextServer(exo)
                            }
                        }
                    }
                    startupTimeout = timeout
                    // Give the current server 10 seconds to start or recover before switching.
                    playerHandler.postDelayed(timeout, 10_000L)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                loadingIndicator?.visibility = View.VISIBLE
                startupTimeout?.let { playerHandler.removeCallbacks(it) }
                startupTimeout = null
                if (!sameServerRetryUsed) {
                    sameServerRetryUsed = true
                    playerHandler.postDelayed({
                        if (embeddedPlayer === exo && embeddedDialog?.isShowing == true) {
                            exo.prepare()
                            exo.playWhenReady = true
                        }
                    }, 1500L)
                } else if (currentOptions.size > 1) {
                    playerHandler.postDelayed({ if (embeddedPlayer === exo) tryNextServer(exo) }, 3000L)
                } else {
                    Toast.makeText(this@MainActivity, "تعذر التشغيل: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                }
            }
        })
        val mediaItemBuilder = MediaItem.Builder().setUri(source.url)
        val normalizedPath = try { android.net.Uri.parse(source.url).path.orEmpty().lowercase() } catch (_: Exception) { "" }
        if (normalizedPath.endsWith("/index.html") || normalizedPath == "index.html")
            mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        exo.setMediaItem(mediaItemBuilder.build())
        exo.prepare()
        exo.playWhenReady = true

        val updater = object : Runnable {
            override fun run() {
                if (embeddedPlayer !== exo || embeddedDialog?.isShowing != true) return
                val duration = exo.duration
                val position = exo.currentPosition.coerceAtLeast(0L)
                if (duration > 0L && exo.isCurrentMediaItemSeekable) {
                    timelineSeek.isEnabled = true
                    timelineSeek.progress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
                    elapsedText.text = formatPlayerTime(position)
                    durationText.text = formatPlayerTime(duration)
                } else {
                    timelineSeek.isEnabled = false
                    timelineSeek.progress = 0
                    elapsedText.text = formatPlayerTime(position)
                    durationText.text = "LIVE"
                }
                playPauseControl.text = if (exo.isPlaying) "Ⅱ" else "▶"
                soundControl.text = if (exo.volume > 0f) "◖))" else "◖×"
                playerHandler.postDelayed(this, 500L)
            }
        }
        progressUpdater = updater
        playerHandler.post(updater)
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
        sameServerRetryUsed = false
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
        serverRefreshRunnable?.let { playerHandler.removeCallbacks(it) }
        serverRefreshRunnable = null
        embeddedServerContent = null
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
        sameServerRetryUsed = false
        if (!keepScreenOnWasSet) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        keepScreenOnWasSet = false
    }


    private fun showServerManager() {
        showingServerManager = true
        content.removeAllViews()
        addText("إدارة السيرفرات", 24f, white, true)
        addText("تتم مزامنة السيرفرات بين نسخ HANI TV. التعديل لحساب الإدارة فقط.", 14f, gray, false)
        val add = makeButton("＋ إضافة سيرفر / رابط قناة")
        add.setOnClickListener { requireAdmin { showAddSourceDialog() } }
        content.addView(add, fullWidthParams())
        val manageImages = makeButton("🖼 إدارة صور القنوات وأقسامها")
        manageImages.setOnClickListener { requireAdmin { showChannelImageManager() } }
        content.addView(manageImages, fullWidthParams().apply { topMargin = dp(8) })

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


    private fun allCategories(): List<String> {
        val defaults = channelGroups.keys.toList()
        val remote = channelCategories.values.map { it.trim() }.filter { it.isNotBlank() }
        return (remote + defaults).distinct()
    }

    private fun defaultCategoryFor(channelName: String): String =
        channelGroups.entries.firstOrNull { entry -> entry.value.any { it.first == channelName } }?.key
            ?: "القنوات العربية"

    private fun showChannelImageManager() {
        showingServerManager = true
        content.removeAllViews()
        addText("إدارة صور القنوات", 23f, white, true)
        addText("أدخل رابط صورة مباشر يبدأ بـ https:// لكل قناة. التغييرات تظهر على الأجهزة بعد المزامنة.", 13f, gray, false)
        val allChannels = channelGroups.values.flatten().distinctBy { it.first }
        allChannels.forEach { channel ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(panel)
            }
            row.addView(channelLogoView(channel.first, channel.second, dp(56), dp(42)),
                LinearLayout.LayoutParams(dp(56), dp(42)))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = channel.first
                setTextColor(white); textSize = 14f; setTypeface(null, Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                text = channelCategories[channel.first] ?: defaultCategoryFor(channel.first)
                setTextColor(gray); textSize = 12f
            })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(10); marginEnd = dp(6)
            })
            val edit = makeButton("تعديل")
            edit.setOnClickListener { requireAdmin { showChannelImageDialog(channel.first) } }
            row.addView(edit, LinearLayout.LayoutParams(dp(78), -2))
            content.addView(row, fullWidthParams().apply { bottomMargin = dp(7) })
        }
        addBackButton()
    }

    private fun showChannelImageDialog(channelName: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        fun input(hintText: String, value: String): EditText = EditText(this).apply {
            hint = hintText
            setText(value)
            setTextColor(white)
            setHintTextColor(gray)
            textSize = 14f
            setSingleLine(true)
            box.addView(this)
        }
        val categoryInput = input("اسم القسم", channelCategories[channelName] ?: defaultCategoryFor(channelName))
        val imageInput = input("رابط الصورة المباشر (https://...)", channelImageUrls[channelName].orEmpty())
        val dialog = AlertDialog.Builder(this)
            .setTitle("تعديل $channelName")
            .setView(box)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val category = categoryInput.text.toString().trim()
                val imageUrl = imageInput.text.toString().trim()
                if (category.isBlank()) {
                    Toast.makeText(this, "اكتب اسم القسم.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (imageUrl.isNotBlank() &&
                    !(imageUrl.startsWith("https://", true) || imageUrl.startsWith("http://", true))) {
                    Toast.makeText(this, "رابط الصورة يجب أن يبدأ بـ https:// أو http://", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                channelCategories[channelName] = category
                if (imageUrl.isBlank()) channelImageUrls.remove(channelName)
                else channelImageUrls[channelName] = imageUrl
                saveChannelSettingsToCloud {
                    showChannelImageManager()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun channelLogoView(
        channelName: String,
        fallback: String,
        width: Int,
        height: Int
    ): View {
        val imageUrl = channelImageUrls[channelName].orEmpty().trim()

        // Use a lightweight ImageView and keep a readable fallback when no logo URL exists.
        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = channelName
            setBackgroundColor(Color.TRANSPARENT)
        }

        if (imageUrl.isBlank()) {
            if (channelName.contains("bein", ignoreCase = true)) {
                // Built-in lightweight logo fallback so beIN cards are never empty when
                // no remote image has been configured in the image manager.
                val logo = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    setBackgroundColor(Color.TRANSPARENT)
                    contentDescription = "$channelName logo"
                }
                logo.addView(TextView(this).apply {
                    text = "beIN"
                    textSize = 21f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(171, 105, 235))
                    setTypeface(null, Typeface.NORMAL)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(-2, 0, 1f))
                logo.addView(TextView(this).apply {
                    text = "SPORTS"
                    textSize = 8f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(171, 105, 235))
                    setTypeface(null, Typeface.BOLD)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(-2, dp(13)))
                return logo
            }

            imageView.setImageDrawable(null)
            imageView.setBackground(
                GradientDrawable().apply {
                    setColor(Color.rgb(25, 25, 30))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.rgb(65, 65, 75))
                }
            )
            imageView.setOnClickListener {
                Toast.makeText(this, channelName, Toast.LENGTH_SHORT).show()
            }
            return imageView
        }

        networkExecutor.execute {
            var bitmap: android.graphics.Bitmap? = null
            try {
                val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 10000
                    instanceFollowRedirects = true
                }
                connection.inputStream.use { stream ->
                    bitmap = BitmapFactory.decodeStream(stream)
                }
                connection.disconnect()
            } catch (_: Exception) {
                // Keep the fallback background if the remote logo cannot be loaded.
            }

            val loadedBitmap = bitmap
            if (loadedBitmap != null) {
                runOnUiThread {
                    // Do not update a detached view after a screen change.
                    if (imageView.parent != null) {
                        imageView.setImageBitmap(loadedBitmap)
                        imageView.background = null
                    }
                }
            }
        }

        imageView.background = GradientDrawable().apply {
            setColor(Color.rgb(25, 25, 30))
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Color.rgb(65, 65, 75))
        }
        return imageView
    }

    private fun saveChannelSettingsToCloud(onComplete: (() -> Unit)? = null) {
        val key = adminApiKey
        if (key.isNullOrBlank()) {
            Toast.makeText(this, "سجّل دخول الإدارة أولاً.", Toast.LENGTH_LONG).show()
            onComplete?.invoke()
            return
        }
        val channelList = JSONArray()
        channelGroups.values.flatten().distinctBy { it.first }.forEach { item ->
            channelList.put(JSONObject().apply {
                put("channel", item.first)
                put("category", channelCategories[item.first] ?: defaultCategoryFor(item.first))
                put("imageUrl", channelImageUrls[item.first] ?: "")
            })
        }
        val payload = JSONObject().put("channels", channelList).toString()
        networkExecutor.execute {
            var success = false
            var message = "network_error"
            try {
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 10000; readTimeout = 20000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-ADMIN-KEY", key)
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                connection.disconnect()
                val result = JSONObject(response)
                success = code in 200..299 && result.optBoolean("ok")
                message = if (success) "تم حفظ صور القنوات على Hostinger." else result.optString("error", "HTTP $code")
            } catch (e: Exception) { message = e.message ?: "network_error" }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                onComplete?.invoke()
            }
        }
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
        val channelNames = channelGroups.values.flatten().map { it.first }.distinct()
        val channelInput = field("اسم القناة (مثال: SPORT 1)", existing?.channel ?: channelNames.firstOrNull().orEmpty())
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

    /**
     * Check Hostinger every two minutes only while the embedded player is open.
     * This is a read-only request: it does not add rows or consume Hostinger storage.
     */
    private fun scheduleServerRefreshWhilePlaying() {
        serverRefreshRunnable?.let { playerHandler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (embeddedDialog?.isShowing != true || embeddedPlayer == null) {
                    serverRefreshRunnable = null
                    return
                }
                syncFromCloud(silent = true)
                playerHandler.postDelayed(this, 120_000L)
            }
        }
        serverRefreshRunnable = task
        playerHandler.postDelayed(task, 120_000L)
    }

    /**
     * Update only the server choices for the channel being watched.
     * The currently playing media item is not stopped or replaced.
     */
    private fun refreshEmbeddedServerOptions() {
        if (embeddedDialog?.isShowing != true || embeddedPlayer == null) return

        val channelName = currentOptions.firstOrNull()?.channel ?: return
        val updatedOptions = sources.filter { it.channel == channelName }
        if (updatedOptions.isEmpty()) return

        val playingUrl = embeddedPlayer?.currentMediaItem
            ?.localConfiguration?.uri?.toString()
        val previousSelectedUrl = currentOptions.getOrNull(currentServerIndex)?.url
        currentOptions = updatedOptions
        currentServerIndex = updatedOptions.indexOfFirst {
            it.url == playingUrl || it.url == previousSelectedUrl
        }.let { if (it >= 0) it else 0 }

        val serverContent = embeddedServerContent ?: return
        serverContent.removeAllViews()
        serverContent.addView(TextView(this).apply {
            text = "السيرفرات"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(dp(62), dp(50)))

        embeddedServerButtons.clear()
        currentOptions.forEachIndexed { index, item ->
            val label = item.displayName.ifBlank { item.serverName }
                .replace("السيرفر الأساسي", "السيرفر")
                .take(8)
            val button = TextView(this).apply {
                text = "${index + 1}\\n$label"
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(45, 0, 0, 0))
                    setStroke(dp(2), if (index == currentServerIndex) Color.rgb(25, 155, 240) else Color.LTGRAY)
                }
                setOnClickListener { switchServerInsidePlayer(index) }
            }
            embeddedServerButtons.add(button)
            serverContent.addView(button, LinearLayout.LayoutParams(dp(50), dp(50)).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
    }

    /** Read the shared channel/server list from Hostinger PHP API. */
    private fun syncFromCloud(silent: Boolean = false, onComplete: (() -> Unit)? = null) {
        networkExecutor.execute {
            var errorMessage: String? = null
            try {
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                connection.disconnect()
                if (code !in 200..299) throw IllegalStateException("HTTP $code: $response")
                val json = JSONObject(response)
                if (json.optBoolean("ok")) {
                    val array = json.optJSONArray("sources") ?: JSONArray()
                    val downloaded = mutableListOf<StreamSource>()
                    val channelArray = json.optJSONArray("channels") ?: JSONArray()
                    val downloadedImages = linkedMapOf<String, String>()
                    val downloadedCategories = linkedMapOf<String, String>()
                    val remoteChannelOrder = linkedMapOf<String, Int>()
                    val remoteChannelNames = mutableListOf<String>()
                    for (j in 0 until channelArray.length()) {
                        val channelItem = channelArray.optJSONObject(j) ?: continue
                        val name = channelItem.optString("channel", "").trim()
                        if (name.isNotBlank()) {
                            if (!remoteChannelNames.contains(name)) remoteChannelNames.add(name)
                            val image = channelItem.optString("imageUrl", "").trim()
                            if (image.isNotBlank()) downloadedImages[name] = image
                            val category = channelItem.optString("category", "").trim()
                            if (category.isNotBlank()) downloadedCategories[name] = category
                            remoteChannelOrder[name] = channelItem.optInt("sort_order", j)
                        }
                    }
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val channel = item.optString("channel", "").trim()
                        val url = item.optString("url", "").trim()
                        if (channel.isBlank() || url.isBlank()) continue
                        val server = item.optString("serverName", "السيرفر")
                        downloaded.add(StreamSource(
                            channel, server, url,
                            item.optString("referer", ""),
                            item.optString("displayName", server)
                        ))
                    }
                    // If a channel has sources but no separate channel_settings row yet, still show it.
                    downloaded.forEach { source ->
                        if (source.channel.isNotBlank() && !remoteChannelNames.contains(source.channel)) {
                            remoteChannelNames.add(source.channel)
                            if (!downloadedCategories.containsKey(source.channel)) {
                                downloadedCategories[source.channel] = "القنوات العربية"
                            }
                            remoteChannelOrder[source.channel] = remoteChannelOrder.size
                        }
                    }

                    runOnUiThread {
                        if (channelArray.length() > 0 || remoteChannelNames.isNotEmpty()) {
                            channelImageUrls.clear()
                            channelImageUrls.putAll(downloadedImages)
                            channelCategories.clear()
                            channelCategories.putAll(downloadedCategories)

                            // Build the visible channel groups from Hostinger instead of relying
                            // only on the demo channels compiled into this APK.
                            if (remoteChannelNames.isNotEmpty()) {
                                val grouped = linkedMapOf<String, MutableList<Pair<String, String>>>()
                                remoteChannelNames
                                    .distinct()
                                    .sortedBy { remoteChannelOrder[it] ?: Int.MAX_VALUE }
                                    .forEach { name ->
                                        val category = downloadedCategories[name]?.takeIf { it.isNotBlank() }
                                            ?: "القنوات العربية"
                                        val fallback = name.filter { it.isLetterOrDigit() }.take(3)
                                            .ifBlank { "TV" }.uppercase(java.util.Locale.ROOT)
                                        grouped.getOrPut(category) { mutableListOf() }.add(name to fallback)
                                    }
                                channelGroups.clear()
                                grouped.forEach { (category, channels) -> channelGroups[category] = channels }
                            }
                        }
                        // Keep the last local list if the API returns no sources; otherwise
                        // replace it with the shared Hostinger source list.
                        if (downloaded.isNotEmpty()) {
                            val changed = downloaded != sources
                            if (changed) {
                                sources.clear()
                                sources.addAll(downloaded)
                                saveSources()
                                refreshEmbeddedServerOptions()
                            }
                        }
                        if (showingServerManager) showServerManager()
                        else showHome()
                        onComplete?.invoke()
                    }
                } else throw IllegalStateException(json.optString("error", "api_error"))
            } catch (e: Exception) {
                errorMessage = e.message ?: "network_error"
                runOnUiThread {
                    if (!silent) {
                        Toast.makeText(this, "تعذر تحميل السيرفرات من Hostinger: $errorMessage", Toast.LENGTH_LONG).show()
                    }
                    onComplete?.invoke()
                }
            }
        }
    }

    /** Replace the shared list through the protected Hostinger API. */
    private fun saveSourcesToCloud(onComplete: (() -> Unit)? = null) {
        val key = adminApiKey
        if (key.isNullOrBlank()) {
            onComplete?.invoke()
            Toast.makeText(this, "سجّل دخول الإدارة أولاً لحفظ التعديلات المشتركة.", Toast.LENGTH_LONG).show()
            return
        }
        val list = JSONArray()
        sources.forEach { source ->
            list.put(JSONObject().apply {
                put("channel", source.channel)
                put("serverName", source.serverName)
                put("url", source.url)
                put("referer", source.referer)
                put("displayName", source.displayName)
            })
        }
        val payload = JSONObject().put("sources", list).toString()
        networkExecutor.execute {
            var message: String? = null
            var success = false
            try {
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-ADMIN-KEY", key)
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                connection.disconnect()
                val result = JSONObject(response)
                success = code in 200..299 && result.optBoolean("ok")
                if (!success) message = result.optString("error", "HTTP $code")
            } catch (e: Exception) {
                message = e.message ?: "network_error"
            }
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "تم حفظ روابط القنوات على Hostinger.", Toast.LENGTH_SHORT).show()
                    onComplete?.invoke()
                } else {
                    Toast.makeText(this, "فشل الحفظ المشترك: ${message ?: "خطأ غير معروف"}", Toast.LENGTH_LONG).show()
                    onComplete?.invoke()
                }
            }
        }
    }

    /** Ask for the private API key only when an admin attempts to edit the shared list. */
    private fun requireAdmin(action: () -> Unit) {
        if (!adminApiKey.isNullOrBlank()) {
            action()
            return
        }
        val keyInput = EditText(this).apply {
            hint = "مفتاح الإدارة"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(keyInput, fullWidthParams())
        }
        AlertDialog.Builder(this)
            .setTitle("دخول الإدارة")
            .setMessage("أدخل مفتاح الإدارة الذي ضبطته في api.php. لا تضعه داخل كود التطبيق.")
            .setView(box)
            .setPositiveButton("متابعة") { _, _ ->
                val entered = keyInput.text.toString()
                if (entered.isBlank()) {
                    Toast.makeText(this, "أدخل مفتاح الإدارة.", Toast.LENGTH_SHORT).show()
                } else {
                    adminApiKey = entered
                    action()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
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
