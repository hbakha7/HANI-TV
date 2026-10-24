package com.hanitv.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
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
import android.widget.CheckBox
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
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
    private val brandImageExecutor = Executors.newFixedThreadPool(3)
    private var adminApiKey: String? = null
    private var updateBannerEnabled = false
    private var updateBannerText = "يتوفر إصدار جديد من HANI TV — اضغط للتحميل"
    private var updateVersion = ""
    private var updateApkUrl = ""
    private var adminActionPending = false
    private var showingServerManager = false
    private var embeddedPlayer: ExoPlayer? = null
    private var embeddedQualityControl: TextView? = null
    private var embeddedHttpFactory: DefaultHttpDataSource.Factory? = null
    private var embeddedPlayerView: PlayerView? = null
    private var embeddedDialog: Dialog? = null
    private var embeddedChannelTitle: TextView? = null
    private val embeddedServerButtons = mutableListOf<TextView>()
    private var embeddedServerContent: LinearLayout? = null
    private var serverRefreshRunnable: Runnable? = null
    private val embeddedChannelCards = linkedMapOf<String, View>()
    private var loadingIndicator: ProgressBar? = null
    private var scheduleOverlayHideAction: (() -> Unit)? = null
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
        val displayName: String = serverName,
        val userAgent: String = "",
        val cookie: String = "",
        val authorization: String = "",
        val extraHeaders: String = "",
        val licenseUrl: String = "",
        val scheme: String = "",
        val drmScheme: String = "",
        val playerType: String = "Exo Player"
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
        "ثمانية" to listOf(
            "ثمانية 1" to "T1",
            "ثمانية 2" to "T2",
            "ثمانية 3" to "T3"
        ),
        "STARZPLAY" to listOf(
            "STARZPLAY 1" to "S1",
            "STARZPLAY 2" to "S2",
            "STARZPLAY 3" to "S3"
        ),
        "ALWAN" to listOf(
            "ALWAN 1" to "A1",
            "ALWAN 2" to "A2",
            "ALWAN 3" to "A3",
            "ALWAN 4" to "A4",
            "ALWAN 5" to "A5",
            "ALWAN 6" to "A6",
            "ALWAN 7" to "A7",
            "ALWAN 8" to "A8",
            "ALWAN 9" to "A9",
            "ALWAN 10" to "A10"
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

        // Remote update ticker: hidden by default and controlled from the admin panel.
        if (updateBannerEnabled) {
            val tickerText = buildString {
                append("  📢  ")
                append(updateBannerText.ifBlank { "يتوفر تحديث جديد من HANI TV" })
                if (updateVersion.isNotBlank()) append("   •   الإصدار ").append(updateVersion)
                if (updateApkUrl.isNotBlank()) append("   •   اضغط للتحميل  ")
            }
            val updateTicker = TextView(this).apply {
                text = tickerText
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.rgb(55, 18, 100), Color.rgb(17, 28, 62))
                ).apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.rgb(112, 70, 220))
                }
                if (updateApkUrl.isNotBlank()) {
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(updateApkUrl)))
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "تعذر فتح رابط التحميل.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            content.addView(updateTicker, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
            ).apply { bottomMargin = dp(14) })
        }

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
            setOnClickListener { showSportsSections() }
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

        // Three sports brand tiles shown side-by-side.
        val sportsBrands = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val brandItems = listOf(
            Triple("beIN\nSPORTS", "beIN SPORTS", Color.rgb(177, 105, 235)),
            Triple("ثمانية", "ثمانية", Color.rgb(245, 245, 250)),
            Triple("ALWAN", "ALWAN", Color.rgb(88, 220, 150)),
            Triple("STARZPLAY\nSPORTS ③", "STARZPLAY", Color.rgb(90, 205, 225))
        )

        brandItems.forEach { brand ->
            val brandCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(10), dp(5), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(22, 20, 31))
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), cardStroke)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    when (brand.second) {
                        "beIN SPORTS" -> showChannels("القنوات الرياضية")
                        "ثمانية" -> showChannels("ثمانية")
                        "STARZPLAY" -> showChannels("STARZPLAY")
                        "ALWAN" -> showChannels("ALWAN")
                    }
                }
            }

            applyBrandPhotoBackground(brandCard, brand.second)

            brandCard.addView(
                channelLogoView(brand.second, brand.first, dp(100), dp(44), plainBrandFallback = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            )

            brandCard.addView(TextView(this).apply {
                text = brand.second
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                maxLines = 1
                setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))

            sportsBrands.addView(brandCard, LinearLayout.LayoutParams(
                0, dp(91), 1f
            ).apply { setMargins(dp(4), dp(2), dp(4), dp(5)) })
        }
        content.addView(sportsBrands)

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
                setOnClickListener {
                    if (category == "القنوات الرياضية") showSportsSections() else showChannels(category)
                }
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

    private fun showSportsSections() {
        showingServerManager = false
        content.removeAllViews()
        content.setPadding(dp(14), dp(16), dp(14), dp(24))
        addText("القنوات الرياضية ⚽", 23f, white, true)
        addText("اختر القسم الذي تريد مشاهدته", 14f, gray, false, 12)

        val brands = listOf(
            Triple("beIN\nSPORTS", "beIN SPORTS", Color.rgb(177, 105, 235)),
            Triple("ثمانية", "ثمانية", Color.rgb(245, 245, 250)),
            Triple("ALWAN", "ALWAN", Color.rgb(88, 220, 150)),
            Triple("STARZPLAY\nSPORTS ③", "STARZPLAY", Color.rgb(90, 205, 225))
        )
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        var index = 0
        while (index < brands.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            for (offset in 0 until 3) {
                val brandIndex = index + offset
                if (brandIndex >= brands.size) break
                val brand = brands[brandIndex]
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(5), dp(12), dp(5), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(25, 24, 30))
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), Color.rgb(43, 58, 88))
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        when (brand.second) {
                            "beIN SPORTS" -> showChannels("القنوات الرياضية")
                            "ثمانية" -> showChannels("ثمانية")
                            "STARZPLAY" -> showChannels("STARZPLAY")
                            "ALWAN" -> showChannels("ALWAN")
                        }
                    }
                }
                applyBrandPhotoBackground(card, brand.second)

                card.addView(
                    channelLogoView(brand.second, brand.first, dp(100), dp(52), plainBrandFallback = true),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                )
                card.addView(TextView(this).apply {
                    text = brand.second
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    setPadding(0, dp(8), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))
                row.addView(card, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                    setMargins(dp(4), dp(3), dp(4), dp(7))
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            index += 3
        }
        content.addView(grid)
        addBackButton()
    }

    private fun showChannels(category: String) {
        ensureBuiltInBrandChannels()
        showingServerManager = false
        content.removeAllViews()
        addText(category, 23f, white, true)
        addText("القنوات المتاحة في هذا القسم", 14f, gray, false)

        val predefinedChannels = channelGroups.values.flatten().distinctBy { it.first }
        val configuredChannels = sources.map { it.channel.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { name ->
                val fallback = channelGroups.values.flatten().firstOrNull { it.first == name }?.second
                    ?: name.take(3).ifBlank { "TV" }
                name to fallback
            }
        val channels = (predefinedChannels + configuredChannels)
            .distinctBy { it.first }
            .filter { (name, _) -> (builtInBrandCategoryFor(name) ?: channelCategories[name] ?: defaultCategoryFor(name)) == category }

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
        if (channels.isEmpty()) {
            addText("لا توجد قنوات مضافة لهذا القسم بعد.", 13f, gray, false, 8)
        }
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
        // Use cached Hostinger sources immediately; never wait for the cloud refresh
        // when this channel already has locally saved server links.
        val options = sources.filter { it.channel == channelName }

        if (options.isNotEmpty()) {
            pendingChannelToOpen = null
            launchPlayer(options, 0)
            return
        }

        // If this channel is missing locally, allow the initial Hostinger sync
        // to finish and then retry opening the requested channel.
        if (!initialSourceSyncFinished) {
            pendingChannelToOpen = channelName
            Toast.makeText(this, "جارٍ تحميل روابط القناة…", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "لا تتوفر روابط لهذه القناة حاليًا.", Toast.LENGTH_SHORT).show()
    }

    


private fun launchPlayer(options: List<StreamSource>, selectedIndex: Int = 0) {
    if (options.isEmpty()) return

    val playerAlreadyOpen = embeddedDialog?.isShowing == true && embeddedPlayer != null

    // Update the active channel's sources. When the player is already open, keep the
    // same dialog and PlayerView alive so the underlying screen/dialog cannot flash
    // between channel taps.
    currentOptions = options
    currentServerIndex = selectedIndex.coerceIn(0, options.lastIndex)

    if (playerAlreadyOpen) {
        // Update the selected channel card without rebuilding the entire player UI.
        embeddedChannelCards.forEach { (name, card) ->
            card.setBackgroundColor(
                if (name == options.first().channel) Color.rgb(28, 105, 165)
                else Color.TRANSPARENT
            )
        }

        // Rebuild only the circular server choices for the newly selected channel.
        embeddedServerContent?.let { serverContent ->
            if (serverContent.childCount > 1) {
                serverContent.removeViews(1, serverContent.childCount - 1)
            }
            embeddedServerButtons.clear()
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
        }

        // Clear the previous frame while the next channel starts, preventing a brief
        // flash of the old channel's picture in the middle of the transition.
        embeddedPlayerView?.setKeepContentOnPlayerReset(false)
        embeddedPlayerView?.setShutterBackgroundColor(Color.BLACK)
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
        video.setKeepContentOnPlayerReset(false)
        video.setShutterBackgroundColor(Color.BLACK)
        // Do not add extra zoom; only use the selected resize mode.
        video.scaleX = 1.0f
        video.scaleY = 1.0f
        videoFrame.clipChildren = true
        videoFrame.clipToPadding = true
        videoFrame.addView(video, FrameLayout.LayoutParams(-1, -1))

        // Fill the player area without any additional scaling.
        video.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL

        // Player overlays auto-hide after 3 seconds. Tapping the video toggles
        // them immediately: visible -> hide now, hidden -> show and restart timer.
        var overlaysVisible = true
        val overlayViews = mutableListOf<View>()
        val hideOverlaysRunnable = Runnable {
            overlaysVisible = false
            overlayViews.forEach { it.visibility = View.GONE }
        }
        fun hideOverlaysNow() {
            playerHandler.removeCallbacks(hideOverlaysRunnable)
            overlaysVisible = false
            overlayViews.forEach { it.visibility = View.GONE }
        }
        fun scheduleOverlayHide() {
            playerHandler.removeCallbacks(hideOverlaysRunnable)
            playerHandler.postDelayed(hideOverlaysRunnable, 3_000L)
        }
        scheduleOverlayHideAction = { scheduleOverlayHide() }
        fun showOverlaysAndScheduleHide() {
            overlaysVisible = true
            overlayViews.forEach { it.visibility = View.VISIBLE }
            scheduleOverlayHide()
        }
        val tapLayer = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                if (overlaysVisible) hideOverlaysNow()
                else showOverlaysAndScheduleHide()
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
                setOnClickListener { switchServerInsidePlayer(index); scheduleOverlayHide() }
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
            background = null
        }
        
fun playerControl(symbol: String, description: String, action: () -> Unit): TextView {
    val qualitySymbols = setOf("HD", "FHD", "4K", "SD", "—", "…")

    val buttonBackground = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(45, 255, 255, 255))
        setStroke(dp(1), Color.argb(85, 255, 255, 255))
    }

    val ripple = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(
            Color.argb(100, 105, 228, 255)
        ),
        buttonBackground,
        null
    )

    val control = TextView(this).apply {
        text = symbol
        contentDescription = description
        textSize = if (symbol in qualitySymbols) 15f else 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = ripple
        includeFontPadding = false
        isClickable = true
        isFocusable = true
        setOnClickListener {
            action()
            scheduleOverlayHide()
        }
    }

    controls.addView(
        control,
        LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            setMargins(dp(3), dp(2), dp(3), dp(2))
        }
    )

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
        val qualityControl = playerControl("…", "جودة الفيديو غير محددة؛ اضغط لاختيار الجودة") {
            embeddedPlayer?.let { player ->
                TrackSelectionDialogBuilder(this, "جودة الفيديو", player, C.TRACK_TYPE_VIDEO)
                    .setAllowAdaptiveSelections(true)
                    .build()
                    .show()
            }
        }
        embeddedQualityControl = qualityControl
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
                setOnClickListener {
                    scheduleOverlayHideAction?.invoke()
                    openChannel(channel.first)
                }
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
        scheduleOverlayHide()
        scheduleServerRefreshWhilePlaying()
        dialog.window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        // Detect the active connection so the player can be more conservative on mobile data.
        // Requires ACCESS_NETWORK_STATE in AndroidManifest.xml.
        val usingMobileData = isUsingMobileData()

        val headers = buildSourceHeaders(source)
        val userAgent = source.userAgent.takeIf { it.isNotBlank() }
            ?: prefs.getString("add_user_agent", "")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "103IPTVPlayer/1.0"

        // Mobile networks can have higher latency and more variable throughput.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(if (usingMobileData) 8000 else 6000)
            .setReadTimeoutMs(if (usingMobileData) 15000 else 12000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        embeddedHttpFactory = httpFactory

        // On mobile data, cap adaptive video selection to a moderate bitrate to reduce
        // data consumption. This works when the stream provides multiple quality tracks.
        // Wi-Fi keeps the normal unrestricted quality selection.
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setMaxVideoBitrate(if (usingMobileData) 1_200_000 else Int.MAX_VALUE)
                .build()
        }

        // Keep the buffer moderate on cellular connections so startup is responsive,
        // while allowing a larger safety buffer on Wi-Fi.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (usingMobileData) 12000 else 20000,
                if (usingMobileData) 25000 else 50000,
                1000,
                3000
            )
            .build()

        val exo = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .build()
        embeddedPlayer = exo
        video.player = exo
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val label = classifyVideoQuality(videoSize.width, videoSize.height)
                qualityControl.text = label
                qualityControl.contentDescription = if (label == "—") {
                    "جودة الفيديو غير محددة؛ اضغط لاختيار الجودة"
                } else {
                    "الجودة المكتشفة $label؛ اضغط لاختيار الجودة"
                }
            }

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
                    playerHandler.postDelayed(timeout, if (usingMobileData) 15_000L else 10_000L)
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
                    Toast.makeText(this@MainActivity, "تعذر تشغيل القناة حاليًا. يرجى المحاولة مجددًا.", Toast.LENGTH_LONG).show()
                }
            }
        })
        // Build the media item with both format detection and the configured DRM settings.
        exo.setMediaItem(buildMediaItem(source))
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

    /**
     * Build per-server HTTP headers used by the embedded player.
     * Extra headers are entered one per line as "Name: Value".
     */
    private fun buildSourceHeaders(source: StreamSource): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        if (source.referer.isNotBlank()) headers["Referer"] = source.referer
        if (source.cookie.isNotBlank()) headers["Cookie"] = source.cookie
        if (source.authorization.isNotBlank()) headers["Authorization"] = source.authorization

        source.extraHeaders.lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.isNotBlank() && value.isNotBlank() &&
                    !name.equals("Host", ignoreCase = true) &&
                    !name.equals("Content-Length", ignoreCase = true)
                ) {
                    headers[name] = value
                }
            }
        }
        return headers
    }

    private fun isUsingMobileData(): Boolean {
        return try {
            val connectivityManager =
                getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: SecurityException) {
            // If network-state permission is absent, use the normal player configuration.
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun classifyVideoQuality(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "—"
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        return when {
            longSide >= 3000 -> "4K"
            longSide >= 1800 || (longSide >= 1400 && shortSide >= 1000) -> "FHD"
            longSide >= 1200 && shortSide >= 600 -> "HD"
            else -> "SD"
        }
    }

    private fun formatPlayerTime(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }


    /**
     * Detect HLS even when a proxy URL hides the real playlist path in a
     * Base64 "stream" query parameter, for example proxy.php?stream=...
     */
    private fun isHlsStreamUrl(url: String): Boolean {
        val uri = try {
            android.net.Uri.parse(url)
        } catch (_: Exception) {
            return false
        }

        val path = uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
        if (path.endsWith(".m3u8") || path.endsWith(".css")) return true

        val encodedStream = uri.getQueryParameter("stream")?.trim().orEmpty()
        if (encodedStream.isBlank()) return false

        val decoded = sequenceOf(
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            android.util.Base64.DEFAULT
        ).mapNotNull { flags ->
            try {
                String(android.util.Base64.decode(encodedStream, flags), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }.firstOrNull { it.isNotBlank() }.orEmpty()

        return decoded.substringBefore('?').substringBefore('#')
            .lowercase(java.util.Locale.ROOT).endsWith(".m3u8")
    }
private fun isDashStreamUrl(url: String): Boolean {
    return try {
        val uri = android.net.Uri.parse(url)
        val path = uri.path.orEmpty()
            .lowercase(java.util.Locale.ROOT)

        path.endsWith(".mpd") ||
            uri.getQueryParameter("type")
                ?.equals("mpd", ignoreCase = true) == true ||
            uri.getQueryParameter("format")
                ?.equals("mpd", ignoreCase = true) == true
    } catch (_: Exception) {
        false
    }
}
    /**
     * Creates a Media3 item using the source's stream format and DRM metadata.
     * DRM is enabled only when a supported DRM scheme and a license-server URL are configured.
     * ClearKey keys are not embedded here; this path uses a compatible license endpoint.
     */
    private fun buildMediaItem(source: StreamSource): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(source.url)
            .setMediaId(source.channel)

        val normalizedPath = try {
            android.net.Uri.parse(source.url).path.orEmpty()
                .lowercase(java.util.Locale.ROOT)
        } catch (_: Exception) {
            ""
        }

        when {
            isDashStreamUrl(source.url) ->
                builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)

            isHlsStreamUrl(source.url) ||
                normalizedPath.endsWith("/index.html") ||
                normalizedPath == "index.html" ->
                builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }

        val licenseUrl = source.licenseUrl.trim()
        val schemeName = source.scheme.trim().lowercase(java.util.Locale.ROOT)
        val drmName = source.drmScheme.trim().lowercase(java.util.Locale.ROOT)

        val explicitUuid = try {
            java.util.UUID.fromString(source.drmScheme.trim())
        } catch (_: Exception) {
            null
        }

        val drmUuid = explicitUuid ?: when {
            schemeName.contains("widevine") || drmName.contains("widevine") -> C.WIDEVINE_UUID
            schemeName.contains("clearkey") || schemeName.contains("clear key") ||
                drmName.contains("clearkey") || drmName.contains("clear key") -> C.CLEARKEY_UUID
            schemeName.contains("playready") || drmName.contains("playready") -> C.PLAYREADY_UUID
            else -> null
        }

        if (licenseUrl.isNotBlank() && drmUuid != null &&
            (licenseUrl.startsWith("https://", true) || licenseUrl.startsWith("http://", true))) {
            builder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(drmUuid)
                    .setLicenseUri(android.net.Uri.parse(licenseUrl))
                    .build()
            )
        }

        return builder.build()
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
        embeddedQualityControl?.text = "…"
        embeddedQualityControl?.contentDescription = "جارٍ اكتشاف جودة الفيديو"
        loadingIndicator?.visibility = View.VISIBLE
        val selectedUserAgent = source.userAgent.takeIf { it.isNotBlank() }
            ?: prefs.getString("add_user_agent", "")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "103IPTVPlayer/1.0"
        embeddedHttpFactory?.setUserAgent(selectedUserAgent)
        embeddedHttpFactory?.setDefaultRequestProperties(buildSourceHeaders(source))
        // Apply the selected server's own format and DRM settings on every switch/failover.
        val mediaItem = buildMediaItem(source)
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
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
        embeddedQualityControl = null
        embeddedHttpFactory = null
        embeddedPlayerView = null
        loadingIndicator = null
        scheduleOverlayHideAction = null
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
        val purple = Color.rgb(169, 112, 245)
        val darkCard = Color.rgb(25, 24, 32)
        val lineColor = Color.rgb(58, 48, 71)
        addText("إدارة السيرفرات", 24f, white, true)
        addText("إدارة موحّدة حسب الفئة والقناة — جميع السيرفرات متساوية.", 13f, gray, false)

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(label: String, value: String): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply { setColor(Color.rgb(33, 23, 47)); cornerRadius = dp(10).toFloat() }
            addView(TextView(this@MainActivity).apply { text = value; textSize = 19f; setTextColor(white); setTypeface(null, Typeface.BOLD) })
            addView(TextView(this@MainActivity).apply { text = label; textSize = 11f; setTextColor(purple) })
        }
        stats.addView(stat("الفئات", allCategories().size.toString()), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(5), 0) })
        stats.addView(stat("القنوات", sources.map { it.channel }.distinct().size.toString()), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        stats.addView(stat("السيرفرات", sources.size.toString()), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, 0, 0) })
        content.addView(stats, fullWidthParams().apply { topMargin = dp(10); bottomMargin = dp(10) })

        val search = EditText(this).apply {
            hint = "بحث بالقناة أو السيرفر أو الرابط..."
            setTextColor(white); setHintTextColor(gray); textSize = 14f; setSingleLine(true)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply { setColor(darkCard); cornerRadius = dp(10).toFloat(); setStroke(dp(1), lineColor) }
        }
        content.addView(search, fullWidthParams().apply { bottomMargin = dp(8) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val add = makeButton("＋ إضافة سيرفر")
        add.setOnClickListener { requireAdmin { showAddSourceDialog() } }
        val manageImages = makeButton("إدارة الفئات والصور")
        manageImages.setOnClickListener { requireAdmin { showChannelImageManager() } }
        actions.addView(add, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(4), 0) })
        actions.addView(manageImages, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, 0, 0) })
        content.addView(actions, fullWidthParams().apply { bottomMargin = dp(10) })

        val updateBannerAdmin = makeButton("إدارة شريط التحديثات والإصدار")
        updateBannerAdmin.setOnClickListener { requireAdmin { showUpdateBannerManager() } }
        content.addView(updateBannerAdmin, fullWidthParams().apply { bottomMargin = dp(10) })

        val categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val categoryScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(categoryRow) }
        content.addView(categoryScroll, fullWidthParams().apply { bottomMargin = dp(10) })
        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listHost, fullWidthParams())
        addBackButton()

        var selectedCategory = "الكل"
        val expandedChannels = mutableSetOf<String>()
        fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(white)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply { setColor(if (selected) Color.rgb(117, 65, 183) else darkCard); cornerRadius = dp(9).toFloat(); setStroke(dp(1), if (selected) purple else lineColor) }
            setOnClickListener { onClick() }
        }
        fun renderList() {
            categoryRow.removeAllViews()
            (listOf("الكل") + allCategories()).distinct().forEach { category ->
                categoryRow.addView(chip(category, category == selectedCategory) { selectedCategory = category; renderList() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            listHost.removeAllViews()
            val query = search.text.toString().trim().lowercase(java.util.Locale.ROOT)
            val filtered = sources.withIndex().filter { (_, item) ->
                val cat = channelCategories[item.channel] ?: defaultCategoryFor(item.channel)
                (selectedCategory == "الكل" || cat == selectedCategory) &&
                    (query.isBlank() || item.channel.lowercase().contains(query) || item.serverName.lowercase().contains(query) || item.displayName.lowercase().contains(query) || item.url.lowercase().contains(query) || cat.lowercase().contains(query))
            }
            if (filtered.isEmpty()) {
                listHost.addView(TextView(this).apply { text = if (sources.isEmpty()) "لا توجد سيرفرات محفوظة بعد." else "لا توجد نتائج مطابقة."; setTextColor(gray); textSize = 14f; setPadding(dp(8), dp(18), dp(8), dp(18)) })
                return
            }
            val channelOrder = filtered.map { it.value.channel }.distinct()
            channelOrder.forEach { channel ->
                val channelItems = filtered.filter { it.value.channel == channel }
                val category = channelCategories[channel] ?: defaultCategoryFor(channel)
                val channelCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply { setColor(darkCard); cornerRadius = dp(12).toFloat(); setStroke(dp(1), lineColor) }
                }
                val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                header.addView(TextView(this).apply { text = "▦"; textSize = 20f; setTextColor(purple); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(30), dp(42)))
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(this).apply { text = channel; textSize = 15f; setTextColor(white); setTypeface(null, Typeface.BOLD) })
                info.addView(TextView(this).apply { text = "$category · ${channelItems.size} سيرفر"; textSize = 11f; setTextColor(gray) })
                header.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(TextView(this).apply { text = if (expandedChannels.contains(channel)) "⌃" else "⌄"; textSize = 20f; setTextColor(purple); gravity = Gravity.CENTER; setPadding(dp(10), dp(5), dp(10), dp(5)); setOnClickListener { if (!expandedChannels.add(channel)) expandedChannels.remove(channel); renderList() } })
                header.setOnClickListener { if (!expandedChannels.add(channel)) expandedChannels.remove(channel); renderList() }
                channelCard.addView(header)
                if (expandedChannels.contains(channel) || query.isNotBlank()) {
                    channelItems.forEach { indexed ->
                        val originalIndex = indexed.index
                        val source = indexed.value
                        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(9), dp(4), dp(7)) }
                        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        top.addView(TextView(this).apply { text = "⠿"; textSize = 20f; setTextColor(purple); setPadding(dp(3), 0, dp(10), 0); contentDescription = "اسحب لإعادة الترتيب" })
                        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        details.addView(TextView(this).apply { text = source.displayName.ifBlank { source.serverName }; textSize = 13f; setTextColor(white); setTypeface(null, Typeface.BOLD) })
                        details.addView(TextView(this).apply { text = source.url; textSize = 10f; setTextColor(gray); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                        top.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                        val copy = TextView(this).apply { text = "نسخ"; textSize = 11f; setTextColor(purple); setPadding(dp(7), dp(8), dp(7), dp(8)); setOnClickListener { val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Stream URL", source.url)); Toast.makeText(this@MainActivity, "تم نسخ الرابط", Toast.LENGTH_SHORT).show() } }
                        top.addView(copy)
                        row.addView(top)
                        val rowActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        val edit = TextView(this).apply { text = "تعديل الإعدادات"; textSize = 11f; setTextColor(purple); setPadding(dp(8), dp(7), dp(12), dp(4)); setOnClickListener { requireAdmin { val idx = sources.indexOf(source); if (idx >= 0) showAddSourceDialog(sources[idx], idx) } } }
                        val moveUp = TextView(this).apply { text = "↑"; textSize = 18f; setTextColor(white); setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { requireAdmin { val idx = sources.indexOf(source); val prev = (idx - 1).coerceAtLeast(0); if (idx > 0 && sources[prev].channel == source.channel) { java.util.Collections.swap(sources, idx, prev); saveSources(); saveSourcesToCloud(); renderList() } } } }
                        val moveDown = TextView(this).apply { text = "↓"; textSize = 18f; setTextColor(white); setPadding(dp(8), dp(4), dp(8), dp(4)); setOnClickListener { requireAdmin { val idx = sources.indexOf(source); val next = (idx + 1).coerceAtMost(sources.lastIndex); if (idx >= 0 && next < sources.size && sources[next].channel == source.channel) { java.util.Collections.swap(sources, idx, next); saveSources(); saveSourcesToCloud(); renderList() } } } }
                        val delete = TextView(this).apply { text = "حذف"; textSize = 11f; setTextColor(Color.rgb(255, 110, 125)); setPadding(dp(8), dp(7), dp(8), dp(4)); setOnClickListener { requireAdmin { AlertDialog.Builder(this@MainActivity).setMessage("حذف ${source.serverName} من $channel؟").setPositiveButton("حذف") { _, _ -> val idx = sources.indexOf(source); if (idx >= 0) { sources.removeAt(idx); saveSources(); saveSourcesToCloud(); renderList() } }.setNegativeButton("إلغاء", null).show() } } }
                        val check = TextView(this).apply {
                            text = "فحص"; textSize = 11f; setTextColor(Color.rgb(65, 210, 160)); setPadding(dp(8), dp(7), dp(8), dp(4))
                            setOnClickListener {
                                isEnabled = false
                                text = "..."
                                networkExecutor.execute {
                                    val result = try {
                                        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
                                            requestMethod = "GET"; connectTimeout = 5000; readTimeout = 5000
                                            setRequestProperty("Range", "bytes=0-0")
                                            if (source.userAgent.isNotBlank()) setRequestProperty("User-Agent", source.userAgent)
                                            if (source.referer.isNotBlank()) setRequestProperty("Referer", source.referer)
                                            if (source.cookie.isNotBlank()) setRequestProperty("Cookie", source.cookie)
                                            if (source.authorization.isNotBlank()) setRequestProperty("Authorization", source.authorization)
                                        }
                                        val code = connection.responseCode
                                        connection.disconnect()
                                        if (code in 200..299) "السيرفر يستجيب." else "السيرفر غير متاح حاليًا."
                                    } catch (_: Exception) { "تعذر الاتصال بهذا السيرفر حاليًا." }
                                    runOnUiThread {
                                        isEnabled = true; text = "فحص"
                                        Toast.makeText(this@MainActivity, "${source.serverName}: $result", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                        rowActions.addView(edit); rowActions.addView(check); rowActions.addView(moveUp); rowActions.addView(moveDown); rowActions.addView(delete)
                        row.addView(rowActions)
                        row.setOnLongClickListener {
                            val sourceIndex = sources.indexOf(source)
                            if (sourceIndex >= 0) {
                                val clip = android.content.ClipData.newPlainText("source_index", sourceIndex.toString())
                                row.startDragAndDrop(clip, View.DragShadowBuilder(row), source, 0)
                            }
                            true
                        }
                        row.setOnDragListener { _, event ->
                            when (event.action) {
                                android.view.DragEvent.ACTION_DRAG_STARTED -> true
                                android.view.DragEvent.ACTION_DROP -> {
                                    val from = (event.localState as? StreamSource)?.let { sources.indexOf(it) } ?: -1
                                    val to = sources.indexOf(source)
                                    if (from >= 0 && to >= 0 && from != to && sources[from].channel == sources[to].channel) {
                                        val moved = sources.removeAt(from)
                                        sources.add(to, moved)
                                        saveSources()
                                        saveSourcesToCloud()
                                        renderList()
                                    }
                                    true
                                }
                                android.view.DragEvent.ACTION_DRAG_ENDED -> true
                                else -> true
                            }
                        }
                        channelCard.addView(row)
                        channelCard.addView(View(this).apply { setBackgroundColor(lineColor) }, LinearLayout.LayoutParams(-1, dp(1)))
                    }
                    val addForChannel = TextView(this).apply { text = "＋ إضافة سيرفر لهذه القناة"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(purple); setPadding(dp(8), dp(10), dp(8), dp(4)); setOnClickListener { requireAdmin { showAddSourceDialog(channelName = channel) } } }
                    channelCard.addView(addForChannel)
                }
                listHost.addView(channelCard, fullWidthParams().apply { bottomMargin = dp(8) })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderList() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        renderList()
    }


    private fun allCategories(): List<String> {
        val defaults = channelGroups.keys.toList()
        val remote = channelCategories.values.map { it.trim() }.filter { it.isNotBlank() }
        return (remote + defaults).distinct()
    }

    private fun builtInBrandCategoryFor(channelName: String): String? = when (channelName) {
        "ثمانية 1", "ثمانية 2", "ثمانية 3" -> "ثمانية"
        "STARZPLAY 1", "STARZPLAY 2", "STARZPLAY 3" -> "STARZPLAY"
        "ALWAN 1", "ALWAN 2", "ALWAN 3", "ALWAN 4", "ALWAN 5",
        "ALWAN 6", "ALWAN 7", "ALWAN 8", "ALWAN 9", "ALWAN 10" -> "ALWAN"
        else -> null
    }

    private fun ensureBuiltInBrandChannels() {
        val builtIns = linkedMapOf(
            "ثمانية" to listOf(
                "ثمانية 1" to "T1",
                "ثمانية 2" to "T2",
                "ثمانية 3" to "T3"
            ),
            "STARZPLAY" to listOf(
                "STARZPLAY 1" to "S1",
                "STARZPLAY 2" to "S2",
                "STARZPLAY 3" to "S3"
            ),
            "ALWAN" to listOf(
                "ALWAN 1" to "A1",
                "ALWAN 2" to "A2",
                "ALWAN 3" to "A3",
                "ALWAN 4" to "A4",
                "ALWAN 5" to "A5",
                "ALWAN 6" to "A6",
                "ALWAN 7" to "A7",
                "ALWAN 8" to "A8",
                "ALWAN 9" to "A9",
                "ALWAN 10" to "A10"
            )
        )
        builtIns.forEach { (category, defaults) ->
            val merged = channelGroups[category].orEmpty().toMutableList()
            defaults.forEach { item ->
                if (merged.none { existing -> existing.first == item.first }) merged.add(item)
            }
            channelGroups[category] = merged
        }
    }

    private fun defaultCategoryFor(channelName: String): String =
        builtInBrandCategoryFor(channelName)
            ?: channelGroups.entries.firstOrNull { entry -> entry.value.any { it.first == channelName } }?.key
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

    /**
     * Premium glass-style brand cards matching the approved sports-channel reference.
     * Uses local gradients and borders, so logos/cards appear immediately without
     * downloading stadium photos or exposing network errors in the UI.
     */
    private fun applyBrandPhotoBackground(view: View, brandName: String) {
        val glow = when {
            brandName.contains("bein", ignoreCase = true) -> Color.rgb(185, 58, 255)
            brandName.contains("ثمانية") -> Color.rgb(54, 238, 177)
            brandName.contains("ALWAN", ignoreCase = true) -> Color.rgb(55, 190, 255)
            brandName.contains("STARZPLAY", ignoreCase = true) -> Color.rgb(40, 215, 255)
            else -> Color.rgb(100, 130, 255)
        }
        val deep = when {
            brandName.contains("bein", ignoreCase = true) -> Color.rgb(35, 4, 65)
            brandName.contains("ثمانية") -> Color.rgb(0, 42, 30)
            brandName.contains("ALWAN", ignoreCase = true) -> Color.rgb(0, 28, 48)
            brandName.contains("STARZPLAY", ignoreCase = true) -> Color.rgb(0, 32, 52)
            else -> Color.rgb(16, 20, 38)
        }
        view.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(glow, deep, deep)
        ).apply {
            cornerRadius = dp(15).toFloat()
            setStroke(dp(2), glow)
        }
        view.clipToOutline = true
        view.elevation = dp(3).toFloat()
    }

    private fun channelLogoView(
        channelName: String,
        fallback: String,
        width: Int,
        height: Int,
        plainBrandFallback: Boolean = false
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

            // Built-in wordmark fallbacks keep every channel card branded even before
            // an administrator supplies an official image URL from the image manager.
            val logoText = when {
                channelName.contains("ثمانية", ignoreCase = true) -> "ثمانية"
                channelName.contains("STARZPLAY", ignoreCase = true) && plainBrandFallback -> "STARZPLAY\nSPORTS ③"
                channelName.contains("STARZPLAY", ignoreCase = true) -> "STARZPLAY"
                channelName.contains("ALWAN", ignoreCase = true) -> "ALWAN"
                channelName.contains("bein", ignoreCase = true) -> "beIN\nSPORTS"
                else -> fallback.ifBlank { channelName.take(3) }
            }
            val logoColor = when {
                channelName.contains("ثمانية", ignoreCase = true) -> Color.WHITE
                channelName.contains("STARZPLAY", ignoreCase = true) -> Color.rgb(90, 205, 225)
                channelName.contains("ALWAN", ignoreCase = true) -> Color.rgb(88, 220, 150)
                channelName.contains("bein", ignoreCase = true) -> Color.rgb(171, 105, 235)
                else -> Color.rgb(177, 145, 255)
            }
            val logo = TextView(this).apply {
                text = logoText
                gravity = Gravity.CENTER
                setTextColor(logoColor)
                textSize = when {
                    channelName.contains("STARZPLAY", ignoreCase = true) -> 11f
                    channelName.contains("ALWAN", ignoreCase = true) -> 15f
                    channelName.contains("ثمانية", ignoreCase = true) -> 16f
                    channelName.contains("bein", ignoreCase = true) -> 15f
                    else -> 18f
                }
                setTypeface(null, Typeface.BOLD)
                includeFontPadding = false
                setPadding(dp(3), dp(3), dp(3), dp(3))
                if (plainBrandFallback) {
                    // Brand tiles use one clean photo card: no second rectangle behind the wordmark.
                    setBackgroundColor(Color.TRANSPARENT)
                    setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
                } else {
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(25, 24, 32))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.rgb(65, 65, 82))
                    }
                }
                contentDescription = "$channelName logo"
            }
            return logo
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
                message = if (success) "تم حفظ صور القنوات بنجاح." else "تعذر حفظ صور القنوات حاليًا. تحقق من الاتصال وحاول مجددًا."
            } catch (_: Exception) { message = "تعذر الاتصال بالخدمة حاليًا. تحقق من الاتصال وحاول مجددًا." }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                onComplete?.invoke()
            }
        }
    }

    private fun showAddSourceDialog(existing: StreamSource? = null, editIndex: Int = -1, channelName: String? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(hint: String, value: String = "", multiline: Boolean = false): EditText {
            return EditText(this).apply {
                this.hint = hint
                setText(value)
                setTextColor(white)
                setHintTextColor(gray)
                textSize = 15f
                setSingleLine(!multiline)
                if (multiline) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 2
                    maxLines = 4
                    gravity = Gravity.TOP or Gravity.START
                }
            }.also { box.addView(it) }
        }
        val channelNames = channelGroups.values.flatten().map { it.first }.distinct()
        val channelInput = field("اسم القناة (مثال: SPORT 1)", existing?.channel ?: channelName ?: channelNames.firstOrNull().orEmpty())
        val serverInput = field("اسم السيرفر للإدارة (مثال: السيرفر الاحتياطي)", existing?.serverName ?: "السيرفر الأساسي")
        val displayNameInput = field("الاسم الذي يظهر داخل المشغّل", existing?.displayName ?: existing?.serverName ?: "السيرفر الأساسي")
        val urlInput = field("رابط البث الكامل", existing?.url ?: "")
        val refererInput = field("Referer (اختياري)", existing?.referer ?: "")
        val userAgentInput = field("User-Agent (اختياري)", existing?.userAgent ?: "")
        val cookieInput = field("Cookie (اختياري)", existing?.cookie ?: "")
        val authorizationInput = field("Authorization (اختياري)", existing?.authorization ?: "")
        val extraHeadersInput = field(
            "Headers إضافية: اسم: قيمة (كل سطر منفصل)",
            existing?.extraHeaders ?: "",
            multiline = true
        )
        val licenseUrlInput = field("License URL (اختياري)", existing?.licenseUrl ?: "")
        val schemeInput = field("Scheme (مثال: widevine / clearkey)", existing?.scheme ?: "")
        val drmSchemeInput = field("DRM Scheme / UUID (اختياري)", existing?.drmScheme ?: "")
        val playerTypeInput = field("نوع المشغّل: Exo Player أو Web Player", existing?.playerType ?: "Exo Player")
        val formScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(box)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "إضافة رابط" else "تعديل الرابط")
            .setView(formScroll)
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
                        val userAgent = userAgentInput.text.toString().trim()
                        val cookie = cookieInput.text.toString().trim()
                        val authorization = authorizationInput.text.toString().trim()
                        val extraHeaders = extraHeadersInput.text.toString().trim()
                        val licenseUrl = licenseUrlInput.text.toString().trim()
                        val scheme = schemeInput.text.toString().trim()
                        val drmScheme = drmSchemeInput.text.toString().trim()
                        val playerType = playerTypeInput.text.toString().trim().ifBlank { "Exo Player" }
                        if (channel.isBlank() || server.isBlank() || url.isBlank() ||
                            !(url.startsWith("http://") || url.startsWith("https://"))) {
                            Toast.makeText(this, "أدخل اسم القناة والسيرفر ورابطًا يبدأ بـ http:// أو https://", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        val item = StreamSource(
                            channel = channel,
                            serverName = server,
                            url = url,
                            referer = referer,
                            displayName = displayName.ifBlank { server },
                            userAgent = userAgent,
                            cookie = cookie,
                            authorization = authorization,
                            extraHeaders = extraHeaders,
                            licenseUrl = licenseUrl,
                            scheme = scheme,
                            drmScheme = drmScheme,
                            playerType = playerType
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
                    item.optString("userAgent", ""),
                    item.optString("cookie", ""),
                    item.optString("authorization", ""),
                    item.optString("extraHeaders", ""),
                    item.optString("licenseUrl", ""),
                    item.optString("scheme", ""),
                    item.optString("drmScheme", ""),
                    item.optString("playerType", "Exo Player")
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
                put("userAgent", source.userAgent)
                put("cookie", source.cookie)
                put("authorization", source.authorization)
                put("extraHeaders", source.extraHeaders)
                put("licenseUrl", source.licenseUrl)
                put("scheme", source.scheme)
                put("drmScheme", source.drmScheme)
                put("playerType", source.playerType)
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
                    val remoteSettings = json.optJSONObject("settings") ?: JSONObject()
                    val remoteUpdateBannerEnabled = remoteSettings.optString("updateBannerEnabled", "0") == "1"
                    val remoteUpdateBannerText = remoteSettings.optString("updateBannerText", "يتوفر إصدار جديد من HANI TV — اضغط للتحميل")
                    val remoteUpdateVersion = remoteSettings.optString("updateVersion", "")
                    val remoteUpdateApkUrl = remoteSettings.optString("updateApkUrl", "")
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
                            item.optString("displayName", server),
                            item.optString("userAgent", ""),
                            item.optString("cookie", ""),
                            item.optString("authorization", ""),
                            item.optString("extraHeaders", ""),
                            item.optString("licenseUrl", ""),
                            item.optString("scheme", ""),
                            item.optString("drmScheme", ""),
                            item.optString("playerType", "Exo Player")
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
                        updateBannerEnabled = remoteUpdateBannerEnabled
                        updateBannerText = remoteUpdateBannerText
                        updateVersion = remoteUpdateVersion
                        updateApkUrl = remoteUpdateApkUrl
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
                                ensureBuiltInBrandChannels()
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
                        Toast.makeText(this, "تعذر تحميل القنوات حاليًا. تحقق من اتصالك بالإنترنت وحاول مجددًا.", Toast.LENGTH_LONG).show()
                    }
                    onComplete?.invoke()
                }
            }
        }
    }

    /** Admin-only editor for the shared update ticker and APK download link. */
    private fun showUpdateBannerManager() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val enabled = CheckBox(this).apply {
            text = "إظهار شريط التحديثات لجميع المستخدمين"
            setTextColor(white)
            isChecked = updateBannerEnabled
        }
        fun field(hintText: String, value: String, singleLine: Boolean = true): EditText = EditText(this).apply {
            hint = hintText
            setText(value)
            setTextColor(white)
            setHintTextColor(gray)
            setSingleLine(singleLine)
            if (!singleLine) minLines = 2
        }
        val messageInput = field("نص الإعلان المتحرك", updateBannerText, false)
        val versionInput = field("رقم الإصدار مثل 2.1.0", updateVersion)
        val urlInput = field("رابط تنزيل APK يبدأ بـ https://", updateApkUrl)
        box.addView(enabled)
        box.addView(messageInput, fullWidthParams())
        box.addView(versionInput, fullWidthParams())
        box.addView(urlInput, fullWidthParams())
        AlertDialog.Builder(this)
            .setTitle("إدارة شريط التحديثات")
            .setMessage("التغييرات تُحفظ على Hostinger وتظهر للمستخدمين عند مزامنة التطبيق.")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val message = messageInput.text.toString().trim()
                val version = versionInput.text.toString().trim()
                val apkUrl = urlInput.text.toString().trim()
                if (apkUrl.isNotBlank() && !apkUrl.startsWith("https://", true) && !apkUrl.startsWith("http://", true)) {
                    Toast.makeText(this, "رابط التحميل يجب أن يبدأ بـ https:// أو http://", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                saveUpdateBannerSettingsToCloud(enabled.isChecked, message, version, apkUrl)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun saveUpdateBannerSettingsToCloud(
        enabled: Boolean,
        message: String,
        version: String,
        apkUrl: String
    ) {
        val key = adminApiKey
        if (key.isNullOrBlank()) {
            Toast.makeText(this, "سجّل دخول الإدارة أولاً.", Toast.LENGTH_LONG).show()
            return
        }
        val settings = JSONObject().apply {
            put("updateBannerEnabled", if (enabled) "1" else "0")
            put("updateBannerText", message)
            put("updateVersion", version)
            put("updateApkUrl", apkUrl)
        }
        val payload = JSONObject().put("settings", settings).toString()
        networkExecutor.execute {
            var success = false
            try {
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
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
                success = code in 200..299 && JSONObject(response).optBoolean("ok")
            } catch (_: Exception) {
                success = false
            }
            runOnUiThread {
                if (success) {
                    updateBannerEnabled = enabled
                    updateBannerText = message
                    updateVersion = version
                    updateApkUrl = apkUrl
                    Toast.makeText(this, "تم حفظ إعدادات شريط التحديثات.", Toast.LENGTH_SHORT).show()
                    showHome()
                } else {
                    Toast.makeText(this, "تعذر حفظ الإعدادات. تحقق من الاتصال ومفتاح الإدارة.", Toast.LENGTH_LONG).show()
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
                put("userAgent", source.userAgent)
                put("cookie", source.cookie)
                put("authorization", source.authorization)
                put("extraHeaders", source.extraHeaders)
                put("licenseUrl", source.licenseUrl)
                put("scheme", source.scheme)
                put("drmScheme", source.drmScheme)
                put("playerType", source.playerType)
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
                if (!success) message = "تعذر حفظ التعديلات حاليًا. تحقق من الاتصال وحاول مجددًا."
            } catch (_: Exception) {
                message = "تعذر حفظ التعديلات حاليًا. تحقق من الاتصال وحاول مجددًا."
            }
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "تم حفظ روابط القنوات بنجاح.", Toast.LENGTH_SHORT).show()
                    onComplete?.invoke()
                } else {
                    Toast.makeText(this, "تعذر حفظ التعديلات حاليًا. تحقق من الاتصال وحاول مجددًا.", Toast.LENGTH_LONG).show()
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
