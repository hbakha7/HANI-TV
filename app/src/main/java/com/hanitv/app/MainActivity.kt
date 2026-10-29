package com.hanitv.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.ColorFilter
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.drawable.Drawable
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.widget.Spinner
import android.widget.ArrayAdapter
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import java.util.UUID
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

/** Local ClearKey callback: turns a provider-supplied hexadecimal KID/KEY pair into the ClearKey JSON response expected by Media3. */
private class HaniInlineClearKeyCallback(private val kidHex: String, private val keyHex: String) : MediaDrmCallback {
    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
        throw UnsupportedOperationException("ClearKey does not require provisioning")
    }

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        fun base64Url(bytes: ByteArray): String = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        val response = JSONObject()
            .put("keys", JSONArray().put(JSONObject()
                .put("kty", "oct")
                .put("kid", base64Url(hexBytes(kidHex)))
                .put("k", base64Url(hexBytes(keyHex)))))
            .put("type", "temporary")
        return response.toString().toByteArray(Charsets.UTF_8)
    }
}

private fun parseHaniClearKey(kidValue: String, keyValue: String): Pair<String, String>? {
    fun normalize(value: String) = value.trim().removePrefix("0x").removePrefix("0X")
        .replace(Regex("\\s+"), "")
    val kid = normalize(kidValue)
    val key = normalize(keyValue)
    val hex = Regex("^[0-9a-fA-F]{32}$")
    if (!hex.matches(kid) || !hex.matches(key)) return null
    return kid.lowercase(java.util.Locale.ROOT) to key.lowercase(java.util.Locale.ROOT)
}

private fun parseHaniClearKeyPair(value: String): Pair<String, String>? {
    val parts = value.trim().split(":", limit = 2)
    return if (parts.size == 2) parseHaniClearKey(parts[0], parts[1]) else null
}

class MainActivity : Activity() {

    private val bg = Color.BLACK
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
    // Quality adaptation runs locally in HANI TV; it never calls Hostinger.
    private var autoQualityEnabled = false
    private var lastAutoQualitySwitchAt = 0L
    private var embeddedHttpFactory: DefaultHttpDataSource.Factory? = null
    private var embeddedPlayerView: PlayerView? = null
    private var embeddedDialog: Dialog? = null
    private var embeddedChannelTitle: TextView? = null
    private val embeddedServerButtons = mutableListOf<TextView>()
    private var embeddedServerContent: LinearLayout? = null
    private var serverRefreshRunnable: Runnable? = null
    private val embeddedChannelCards = linkedMapOf<String, View>()
    private var embeddedChannelScroll: HorizontalScrollView? = null
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
    private var isHomeScreenVisible = false

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
        val playerType: String = "Exo Player",
        val keyId: String = "",
        val key: String = "",
        // Optional quality-to-URL map for one server entry (SD/HD/FHD/4K).
        // Empty means this is a legacy single-URL server and behaves exactly as before.
        val qualityUrls: String = ""
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
            "beIN SPORTS NEWS" to "BN",
            "قنوات الكأس 1" to "KAS1",
            "قنوات الكأس 2" to "KAS2",
            "قنوات الكأس 3" to "KAS3",
            "قنوات الكأس 4" to "KAS4",
            "قنوات الكأس 5" to "KAS5",
            "قنوات الكأس 6" to "KAS6",
            "قنوات الكأس 7" to "KAS7",
            "قنوات الكأس 8" to "KAS8",
            "أبو ظبي الرياضية 1" to "AD1",
            "أبو ظبي الرياضية 2" to "AD2",
            "أبو ظبي الرياضية بريميوم 1" to "ADP1",
            "أبو ظبي الرياضية بريميوم 2" to "ADP2",
            "شاهد Sports 1" to "SH1",
            "شاهد Sports 2" to "SH2",
            "شاهد Sports 3" to "SH3",
            "شاهد Sports 4" to "SH4",
            "شاهد Sports 5" to "SH5",
            "دبي الرياضية 1" to "DUB1",
            "دبي الرياضية 2" to "DUB2"
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
        "القنوات الجزائرية" to listOf(
            // Mixed lineup by design; keep all Algerian channels in this section.
            "Ennahar" to "ENNAHAR",
            "Algeria 5" to "ALGERIA5",
            "AL24 News" to "AL24",
            "Algeria 2" to "ALGERIA2",
            "El Heddaf" to "ELHEDDAF",
            "Algeria 1" to "ALGERIA1",
            "Algeria 6" to "ALGERIA6",
            "Algeria 3" to "ALGERIA3"
        ),
        "القنوات المغربية" to listOf(
            "Al Aoula" to "ALAOULA",
            "Laayoune" to "LAAYOUNE",
            "Arryadia" to "ARRYADIA",
            "2M" to "2M"
        ),
        "القنوات العربية والثقافية" to listOf(
            "MBC 1" to "MBC1",
            "MBC 2" to "MBC2",
            "MBC 3" to "MBC3",
            "MBC 4" to "MBC4",
            "MBC 5" to "MBC5",
            "MBC Action" to "ACTION",
            "MBC Max" to "MAX",
            "MBC Drama" to "DRAMA",
            "MBC Masr 1" to "MASR1",
            "MBC Masr 2" to "MASR2",
            "MBC Iraq" to "IRAQ",
            "MBC Bollywood" to "BOLLY",
            "National Geographic" to "NATGEO",
            "Al Jazeera Documentary" to "AJDOC",
            // Egyptian sports channels are kept in the built-in channel registry,
            // but are displayed only inside the Egypt country section.
            "ON Sport" to "ONSPORT",
            "ON Sport Plus" to "ONSPORTPLUS",
            "ON Sport Max" to "ONSPORTMAX",
            "قناة عربية 1" to "AR",
            "قناة عربية 2" to "A2",
            "قناة عربية 3" to "A3",
            "قناة عربية 4" to "A4"
        ),
        "القنوات الإخبارية" to listOf(
            "Sky News Arabia" to "SKY",
            "العربية" to "ALAR",
            "الجزيرة" to "JAZ",
            "DW" to "DW",
            "BBC News عربي" to "BBCNA",
            "أخبار 1" to "N1",
            "أخبار 2" to "N2",
            "أخبار 3" to "N3"
        ),
        "قنوات الأطفال" to listOf(
            "Disney OSN" to "DISNEYOSN",
            "Spacetoon" to "SPACETOON",
            "Cartoon Network" to "CN",
            // MBC 3 stays in the MBC section and is also listed here for children.
            "MBC 3" to "MBC3"
        ),
        "قنوات الترفيه" to listOf(
            "MBC Action" to "ACTION",
            "Star Films" to "STARFILMS",
            "MBC 2" to "MBC2",
            "Movies Thriller" to "MOVIETHRILLER",
            "MBC Max" to "MAX",
            "Movies Action" to "MOVIESACTION",
            "Star Action" to "STARACTION"
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
            setBackgroundColor(Color.BLACK)
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

        // Render the home screen immediately from bundled/local data so startup never
        // waits for a network request. The cloud sync updates the data afterward.
        showHome()
        syncFromCloud { syncedSuccessfully -> finishInitialSourceSync(syncedSuccessfully) }
    }

    private fun finishInitialSourceSync(refreshHome: Boolean) {
        initialSourceSyncFinished = true
        val pendingChannel = pendingChannelToOpen
        pendingChannelToOpen = null
        if (!pendingChannel.isNullOrBlank()) {
            openChannel(pendingChannel)
        } else if (refreshHome && isHomeScreenVisible) {
            showHome()
        }
    }

    private fun showHome() {
        isHomeScreenVisible = true
        // Home-screen redesign only. The existing header, player, and server-management
        // functions are intentionally left unchanged.
        showingServerManager = false
        content.removeAllViews()
        // Use the same quiet graphite atmosphere across the home page and channel pages.
        // This changes only the surface styling; navigation and data flows stay untouched.
        content.background = ChannelAtmosphereDrawable()
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
                intArrayOf(Color.rgb(47, 27, 70), Color.rgb(27, 29, 43), Color.rgb(18, 19, 25))
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.argb(150, 130, 83, 190))
            }
            elevation = dp(2).toFloat()
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

        // Continuous, seamless sports-brand carousel. All logos are bundled locally.
        val sportsSlider = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipToPadding = false
            setPadding(0, 0, 0, 0)
        }
        val sportsBrands = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val brandItems = listOf(
            Triple("beIN\nSPORTS", "beIN SPORTS", Color.rgb(177, 105, 235)),
            Triple("ثمانية", "ثمانية", Color.rgb(245, 245, 250)),
            Triple("ALWAN", "ALWAN", Color.rgb(88, 220, 150)),
            Triple("STARZPLAY", "STARZPLAY", Color.rgb(90, 205, 225)),
            Triple("قنوات الكأس", "قنوات الكأس", Color.rgb(242, 190, 62)),
            Triple("أبو ظبي الرياضية", "أبو ظبي الرياضية", Color.rgb(70, 175, 245)),
            Triple("أبو ظبي بريميوم", "أبو ظبي الرياضية بريميوم", Color.rgb(242, 190, 62)),
            Triple("شاهد VIP\nSPORTS", "شاهد Sports", Color.rgb(0, 190, 160)),
            Triple("DUBAI\nSPORTS", "دبي الرياضية", Color.rgb(220, 30, 50))
        )

        // Duplicate the same sequence so the slider can wrap without a visible pause.
        (brandItems + brandItems).forEach { brand ->
            val brandCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(8), dp(5), dp(8))
                isClickable = true
                isFocusable = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(15).toFloat())
                    }
                }
                clipToOutline = true
                foreground = GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), Color.argb(95, 220, 222, 232))
                }
                elevation = dp(2).toFloat()
                setOnClickListener {
                    when (brand.second) {
                        "beIN SPORTS" -> showSportsBrandChannels("beIN SPORTS")
                        "ثمانية" -> showChannels("ثمانية")
                        "STARZPLAY" -> showChannels("STARZPLAY")
                        "ALWAN" -> showChannels("ALWAN")
                        "قنوات الكأس", "أبو ظبي الرياضية", "أبو ظبي الرياضية بريميوم",
                        "شاهد Sports", "دبي الرياضية" -> showSportsBrandChannels(brand.second)
                    }
                }
            }

            val brandArtwork = ChannelPosterDrawable(brand.second)
            val isThamaniaBrand = brand.second.contains("ثمانية", ignoreCase = true)
            val brandLogoHeight = dp(48)

            // Use the same generated, rounded brand-card background as the other logos.
            // Thamania's transparent logo sits centered above it; never stretch/crop it
            // to fill the card, because that creates a black rectangular inset.
            brandCard.background = brandArtwork
            if (isThamaniaBrand) {
                brandCard.setPadding(dp(5), dp(8), dp(5), dp(8))
                brandCard.addView(
                    ImageView(this).apply {
                        setImageResource(R.drawable.thamania)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "شعار ثمانية"
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        brandLogoHeight
                    )
                )
            } else {
                val brandLogo = channelLogoView(
                    brand.second,
                    brand.first,
                    dp(92),
                    brandLogoHeight,
                    plainBrandFallback = true
                )
                bitmapFromLogoView(brandLogo)?.let { brandArtwork.updateFromLogo(it) }
                brandCard.addView(
                    brandLogo,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, brandLogoHeight)
                )
            }
            brandCard.addView(TextView(this).apply {
                text = when (brand.second) {
                    "أبو ظبي الرياضية بريميوم" -> "أبو ظبي بريميوم"
                    else -> brand.second
                }
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                maxLines = 1
                setPadding(0, dp(4), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))

            sportsBrands.addView(brandCard, LinearLayout.LayoutParams(
                dp(104), dp(91)
            ).apply { setMargins(dp(4), dp(2), dp(4), dp(5)) })
        }
        sportsSlider.addView(sportsBrands, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(sportsSlider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Smooth endless movement; reset by exactly one set's width after the duplicate passes.
        val sliderHandler = Handler(Looper.getMainLooper())
        val sliderStep = object : Runnable {
            override fun run() {
                if (!sportsSlider.isAttachedToWindow) return
                val oneSetWidth = sportsBrands.width / 2
                if (oneSetWidth > 0) {
                    if (sportsSlider.scrollX >= oneSetWidth) {
                        sportsSlider.scrollTo(sportsSlider.scrollX - oneSetWidth, 0)
                    } else {
                        sportsSlider.scrollBy(dp(1), 0)
                    }
                }
                sliderHandler.postDelayed(this, 24L)
            }
        }
        sportsSlider.post { sliderHandler.post(sliderStep) }

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
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(31, 33, 41), Color.rgb(18, 19, 24))
                ).apply {
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.argb(155, 61, 64, 76))
                }
                elevation = dp(1).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (category == "القنوات الرياضية") showSportsSections() else showChannels(category)
                }
            }

            val iconText = when (category) {
                "القنوات الرياضية" -> "⚽"
                "القنوات العربية والثقافية" -> "📺"
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
                    setColor(Color.rgb(25, 26, 32))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.rgb(57, 59, 69))
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
        isHomeScreenVisible = false
        showingServerManager = false
        content.removeAllViews()
        applyChannelPageTheme()
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
            background = graphiteCardDrawable(accent = Color.rgb(105, 108, 118), radius = 13)
            elevation = dp(2).toFloat()
            clipToOutline = true
            isClickable = true
            isFocusable = true
            setOnClickListener { showChannels(category) }
        }

        val icon = TextView(this).apply {
            text = when (category) {
                "القنوات الرياضية" -> "beIN\nSPORTS"
                "القنوات العربية والثقافية" -> "📺"
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
        isHomeScreenVisible = false
        showingServerManager = false
        content.removeAllViews()
        applyChannelPageTheme()
        addText("القنوات الرياضية ⚽", 23f, white, true)
        addText("اختر القسم الذي تريد مشاهدته", 14f, gray, false, 12)

        // Use the same cinematic poster-card language as the individual channel pages.
        // The existing local logo assets, click actions, and channel/category mappings remain unchanged.
        val brands = listOf(
            Triple("beIN\nSPORTS", "beIN SPORTS", Color.rgb(177, 105, 235)),
            Triple("ثمانية", "ثمانية", Color.rgb(245, 245, 250)),
            Triple("ALWAN", "ALWAN", Color.rgb(88, 220, 150)),
            Triple("STARZPLAY\nSPORTS ③", "STARZPLAY", Color.rgb(90, 205, 225)),
            Triple("قنوات الكأس", "قنوات الكأس", Color.rgb(242, 190, 62)),
            Triple("أبو ظبي الرياضية", "أبو ظبي الرياضية", Color.rgb(230, 45, 65)),
            Triple("أبو ظبي الرياضية\nPREMIUM", "أبو ظبي الرياضية بريميوم", Color.rgb(242, 190, 62)),
            Triple("شاهد VIP\nSPORTS", "شاهد Sports", Color.rgb(0, 190, 160)),
            Triple("DUBAI\nSPORTS", "دبي الرياضية", Color.rgb(220, 30, 50))
        )

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        var index = 0
        while (index < brands.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

            for (offset in 0 until 2) {
                val brandIndex = index + offset
                if (brandIndex >= brands.size) break
                val brand = brands[brandIndex]
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    setPadding(dp(3), dp(3), dp(3), dp(8))
                    setBackgroundColor(Color.TRANSPARENT)
                    isClickable = true
                    isFocusable = true
                    contentDescription = brand.second
                    setOnClickListener {
                        when (brand.second) {
                            "beIN SPORTS", "قنوات الكأس", "أبو ظبي الرياضية", "أبو ظبي الرياضية بريميوم",
                            "شاهد Sports", "دبي الرياضية" -> showSportsBrandChannels(brand.second)
                            "ثمانية" -> showChannels("ثمانية")
                            "STARZPLAY" -> showChannels("STARZPLAY")
                            "ALWAN" -> showChannels("ALWAN")
                        }
                    }
                }

                val isThamaniaBrand = brand.second.contains("ثمانية", ignoreCase = true)
                val posterArtwork = ChannelPosterDrawable(brand.second)
                val brandLogo = if (isThamaniaBrand) null else channelLogoView(
                    brand.second,
                    brand.first,
                    dp(142),
                    dp(76),
                    plainBrandFallback = true
                )
                if (brandLogo != null) bitmapFromLogoView(brandLogo)?.let { posterArtwork.updateFromLogo(it) }
                val poster = FrameLayout(this).apply {
                    background = posterArtwork
                    foreground = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1), Color.argb(70, 235, 238, 245))
                    }
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                        }
                    }
                    clipToOutline = true
                    elevation = dp(2).toFloat()
                }
                if (isThamaniaBrand) {
                    poster.addView(ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageResource(R.drawable.thamania)
                        contentDescription = "شعار ثمانية"
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                } else if (brandLogo != null) {
                    poster.addView(
                        brandLogo,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ).apply {
                            setMargins(dp(10), dp(7), dp(10), dp(7))
                            gravity = Gravity.CENTER
                        }
                    )
                }
                card.addView(
                    poster,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(108))
                )
                card.addView(TextView(this).apply {
                    text = brand.second
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(245, 245, 247))
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(7), dp(2), 0)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                card.addView(TextView(this).apply {
                    text = "قنوات رياضية"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(158, 160, 169))
                    maxLines = 1
                    setPadding(dp(2), dp(3), dp(2), 0)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                row.addView(
                    card,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(5), 0, dp(5), dp(12))
                    }
                )
            }

            // Keep the grid balanced when the final row has only one brand.
            if (brands.size - index == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    setMargins(dp(5), 0, dp(5), 0)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            index += 2
        }
        content.addView(grid)
        addBackButton()
    }

    private fun showSportsBrandChannels(brand: String) {
        isHomeScreenVisible = false
        ensureBuiltInBrandChannels()
        showingServerManager = false
        content.removeAllViews()
        applyChannelPageTheme()
        addText(brand, 23f, white, true)
        addText("القنوات المتاحة في هذا القسم", 14f, gray, false)

        // Sports channel names are defined in the app, not inferred from server rows.
        val allChannels = channelGroups["القنوات الرياضية"].orEmpty().distinctBy { it.first }

        val channels = allChannels.filter { (name, _) ->
            when (brand) {
                "beIN SPORTS" -> name.startsWith("beIN SPORTS", ignoreCase = true)
                "قنوات الكأس" -> name.startsWith("قنوات الكأس") || name.startsWith("الكأس")
                "أبو ظبي الرياضية" -> name.startsWith("أبو ظبي الرياضية") &&
                    !name.contains("بريميوم") && !name.contains("premium", ignoreCase = true)
                "أبو ظبي الرياضية بريميوم" -> name.startsWith("أبو ظبي الرياضية بريميوم") ||
                    name.startsWith("أبو ظبي الرياضية Premium", ignoreCase = true)
                "شاهد Sports" -> name.startsWith("شاهد Sports", ignoreCase = true) ||
                    name.startsWith("Shahid Sports", ignoreCase = true)
                "دبي الرياضية" -> name.startsWith("دبي الرياضية") ||
                    name.startsWith("Dubai Sports", ignoreCase = true)
                else -> false
            }
        }

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var index = 0
        while (index < channels.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addChannelCard(row, channels[index])
            if (index + 1 < channels.size) addChannelCard(row, channels[index + 1])
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
            index += 2
        }
        content.addView(grid)
        if (channels.isEmpty()) addText("لا توجد قنوات مضافة لهذا القسم بعد.", 13f, gray, false, 8)
        addBackButton()
    }

    private fun showChannels(category: String) {
        isHomeScreenVisible = false
        ensureBuiltInBrandChannels()
        showingServerManager = false
        content.removeAllViews()
        applyChannelPageTheme()
        addText(category, 23f, white, true)
        addText("القنوات المتاحة في هذا القسم", 14f, gray, false)

        val isArabicCategory = category == "القنوات العربية والثقافية"

        val predefinedChannels = channelGroups.values.flatten().distinctBy { it.first }
        // Entertainment has its own mixed lineup. The three MBC channels also remain
        // available in the Arabic/MBC section; listing them here does not move or remove them.
        val entertainmentChannels = listOf(
            "MBC Action" to "ACTION",
            "Star Films" to "STARFILMS",
            "MBC 2" to "MBC2",
            "Movies Thriller" to "MOVIETHRILLER",
            "MBC Max" to "MAX",
            "Movies Action" to "MOVIESACTION",
            "Star Action" to "STARACTION"
        )
        val channels = when (category) {
            // Keep the built-in country channels visible regardless of server settings.
            "القنوات الجزائرية" -> listOf(
                // Deliberately mixed rather than numerically sorted, as requested.
                "Ennahar" to "ENNAHAR",
                "Algeria 5" to "ALGERIA5",
                "AL24 News" to "AL24",
                "Algeria 2" to "ALGERIA2",
                "El Heddaf" to "ELHEDDAF",
                "Algeria 1" to "ALGERIA1",
                "Algeria 6" to "ALGERIA6",
                "Algeria 3" to "ALGERIA3"
            )
            "القنوات المغربية" -> listOf(
                "Al Aoula" to "ALAOULA",
                "Laayoune" to "LAAYOUNE",
                "Arryadia" to "ARRYADIA",
                "2M" to "2M"
            )
            "القنوات المصرية" -> listOf(
                "ON Sport" to "ONSPORT",
                "ON Sport Plus" to "ONSPORTPLUS",
                "ON Sport Max" to "ONSPORTMAX"
            )
            "قنوات الترفيه" -> entertainmentChannels
            // Explicit child lineup is needed because MBC 3 is intentionally shared
            // with the MBC section and the global channel registry removes duplicates.
            "قنوات الأطفال" -> channelGroups["قنوات الأطفال"].orEmpty()
            else -> {
                predefinedChannels
                    .filter { (name, _) ->
                        val belongsToCategory =
                            (builtInBrandCategoryFor(name) ?: channelCategories[name] ?: defaultCategoryFor(name)) == category
                        belongsToCategory && !(category == "القنوات العربية والثقافية" &&
                            (isMbcChannel(name) || isArabicPlaceholderChannel(name) || isSportsChannel(name)))
                    }
            }
        }

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var index = 0
        if (isArabicCategory) {
            // The MBC group is a normal two-column poster tile, matching the channel cards.
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            addMbcSectionCard(row)
            if (channels.isNotEmpty()) {
                addChannelCard(row, channels[0])
                index = 1
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })

            // Dedicated Algeria section, displayed as the next matching two-column poster card.
            val algeriaRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            addAlgeriaSectionCard(algeriaRow)
            addCountrySectionCard(algeriaRow, "القنوات المغربية", R.drawable.morocco, "قنوات المغرب")
            grid.addView(algeriaRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })

            // Egypt gets the same two-column country card style. Pair it with the next
            // regular channel card so the grid keeps its existing layout.
            val egyptRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            addCountrySectionCard(egyptRow, "القنوات المصرية", R.drawable.egypt, "قنوات مصر")
            if (index < channels.size) {
                addChannelCard(egyptRow, channels[index])
                index++
            }
            grid.addView(egyptRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        while (index < channels.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addChannelCard(row, channels[index])
            if (index + 1 < channels.size) addChannelCard(row, channels[index + 1])
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
            index += 2
        }

        content.addView(grid)
        if (channels.isEmpty() && !isArabicCategory) {
            addText("لا توجد قنوات مضافة لهذا القسم بعد.", 13f, gray, false, 8)
        }
        addBackButton()
    }

    private fun isMbcChannel(name: String): Boolean =
        name.trim().startsWith("MBC", ignoreCase = true)

    private fun isArabicPlaceholderChannel(name: String): Boolean =
        Regex("^قناة عربية\\s*[1-4]$", RegexOption.IGNORE_CASE).matches(name.trim())

    private fun isSportsChannel(name: String): Boolean {
        val value = name.trim().lowercase(java.util.Locale.ROOT)
        return value.contains("sports") || value.contains("sport") ||
            value.contains("bein") || value.contains("be in") ||
            value.contains("رياضية") || value.contains("الكأس") ||
            value.contains("ثمانية") || value.contains("alwan") ||
            value.contains("starzplay") || value.contains("دبي الرياضية") ||
            value.contains("أبو ظبي الرياضية") || value.contains("شاهد sports")
    }

    private fun addMbcSectionCard(row: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            contentDescription = "قنوات MBC"
            setOnClickListener { showMbcChannels() }
        }

        val poster = FrameLayout(this).apply {
            background = ChannelPosterDrawable("MBC 1")
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
            foreground = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.argb(70, 235, 238, 245))
            }
            elevation = dp(2).toFloat()
        }
        poster.addView(ImageView(this).apply {
            setImageResource(R.drawable.mbc_1)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "شعار MBC"
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        card.addView(poster, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(108)
        ))
        card.addView(TextView(this).apply {
            text = "قنوات MBC"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(245, 245, 247))
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            setPadding(dp(2), dp(7), dp(2), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(TextView(this).apply {
            text = "12 قناة"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(158, 160, 169))
            maxLines = 1
            setPadding(dp(2), dp(3), dp(2), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        row.addView(card, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { setMargins(dp(5), 0, dp(5), 0) })
    }

    private fun addAlgeriaSectionCard(row: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            contentDescription = "القنوات الجزائرية"
            setOnClickListener { showChannels("القنوات الجزائرية") }
        }

        val poster = FrameLayout(this).apply {
            background = ChannelPosterDrawable("القنوات الجزائرية")
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
            foreground = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.argb(70, 235, 238, 245))
            }
            elevation = dp(2).toFloat()
        }
        poster.addView(ImageView(this).apply {
            setImageResource(R.drawable.algeria)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "علم الجزائر"
            setPadding(0, 0, 0, 0)
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        card.addView(poster, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(108)
        ))
        card.addView(TextView(this).apply {
            text = "القنوات الجزائرية"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(245, 245, 247))
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            setPadding(dp(2), dp(7), dp(2), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(TextView(this).apply {
            text = "قنوات الجزائر"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(158, 160, 169))
            maxLines = 1
            setPadding(dp(2), dp(3), dp(2), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        row.addView(card, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { setMargins(dp(5), 0, dp(5), 0) })
    }

    private fun addCountrySectionCard(
        row: LinearLayout,
        title: String,
        imageRes: Int,
        subtitle: String
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { showChannels(title) }
        }

        val poster = FrameLayout(this).apply {
            background = ChannelPosterDrawable(title)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
            foreground = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.argb(70, 235, 238, 245))
            }
            elevation = dp(2).toFloat()
        }
        poster.addView(ImageView(this).apply {
            setImageResource(imageRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = title
            setPadding(0, 0, 0, 0)
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        card.addView(poster, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(108)
        ))
        card.addView(TextView(this).apply {
            text = title
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(245, 245, 247))
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            setPadding(dp(2), dp(7), dp(2), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(158, 160, 169))
            maxLines = 1
            setPadding(dp(2), dp(3), dp(2), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        row.addView(card, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { setMargins(dp(5), 0, dp(5), 0) })
    }

    private fun showMbcChannels() {
        isHomeScreenVisible = false
        showingServerManager = false
        content.removeAllViews()
        applyChannelPageTheme()
        addText("قنوات MBC", 23f, white, true)
        addText("اختر القناة التي تريد مشاهدتها", 14f, gray, false, 12)

        val channels = channelGroups["القنوات العربية والثقافية"].orEmpty().filter { isMbcChannel(it.first) }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var index = 0
        while (index < channels.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            addChannelCard(row, channels[index])
            if (index + 1 < channels.size) addChannelCard(row, channels[index + 1])
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
            index += 2
        }
        content.addView(grid)
        val back = makeButton("العودة إلى القنوات العربية والثقافية")
        back.setOnClickListener { showChannels("القنوات العربية والثقافية") }
        content.addView(back, fullWidthParams().apply { topMargin = dp(18) })
    }

    private fun addChannelCard(
        row: LinearLayout,
        channel: Pair<String, String>
    ) {
        val name = channel.first
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            contentDescription = displayChannelName(name)
            setOnClickListener { openChannel(name) }
        }

        // Thamania uses a complete edge-to-edge 16:9 card image. Other channels
        // retain the existing generated artwork and their original logo treatment.
        val isThamaniaChannel = name.contains("ثمانية", ignoreCase = true) ||
            name.contains("thmanyah", ignoreCase = true)
        val logoView = if (isThamaniaChannel) null else channelLogoView(name, channel.second, dp(142), dp(76))
        val posterArtwork = ChannelPosterDrawable(name)
        if (logoView != null) bitmapFromLogoView(logoView)?.let { posterArtwork.updateFromLogo(it) }
        val poster = FrameLayout(this).apply {
            background = posterArtwork
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
            foreground = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.argb(70, 235, 238, 245))
            }
            elevation = dp(2).toFloat()
        }
        if (isThamaniaChannel) {
            poster.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(R.drawable.thamania)
                contentDescription = "شعار ثمانية"
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        } else if (logoView != null) {
            poster.addView(
                logoView,
                FrameLayout.LayoutParams(dp(142), dp(76), Gravity.CENTER)
            )
        }
        card.addView(
            poster,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(108))
        )

        card.addView(TextView(this).apply {
            text = displayChannelName(name)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(245, 245, 247))
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(7), dp(2), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(TextView(this).apply {
            text = channelPosterSubtitle(name)
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(158, 160, 169))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(3), dp(2), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        row.addView(
            card,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(5), 0, dp(5), 0)
            }
        )
    }

    private fun channelPosterSubtitle(name: String): String {
        val value = name.lowercase(java.util.Locale.ROOT)
        return when {
            value.contains("bein") || value.contains("رياض") || value.contains("sports") ||
                value.contains("الكأس") || value.contains("ثمانية") || value.contains("shahid sports") -> "رياضة"
            value.contains("mbc") || value.contains("روتانا") || value.contains("العربية") -> "قنوات عربية"
            value.contains("طفل") || value.contains("كرتون") || value.contains("kids") ||
                value.contains("toons") || value.contains("spacetoon") -> "الأطفال"
            value.contains("news") || value.contains("أخبار") || value.contains("الجزيرة") ||
                value.contains("dw") || value.contains("العراقية") -> "إخباري وثقافي"
            value.contains("document") || value.contains("جيوغرافيك") || value.contains("nat geo") -> "وثائقي"
            value.contains("starzplay") || value.contains("max") || value.contains("film") ||
                value.contains("movies") || value.contains("star action") ||
                value.contains("سينما") || value.contains("أفلام") -> "أفلام وترفيه"
            else -> defaultCategoryFor(name).takeIf { it.isNotBlank() } ?: "قنوات مباشرة"
        }
    }

    /** Artwork-only thumbnail background: channel logo assets remain unchanged and on top. */
    private inner class ChannelPosterDrawable(private val channelName: String) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val key = channelName.lowercase(java.util.Locale.ROOT)
        private var palette: IntArray = when {
            key.contains("mbc 1") -> intArrayOf(Color.rgb(116, 24, 35), Color.rgb(51, 15, 25), Color.rgb(18, 15, 22))
            key.contains("mbc 2") -> intArrayOf(Color.rgb(20, 91, 139), Color.rgb(13, 42, 72), Color.rgb(13, 17, 29))
            key.contains("mbc 3") -> intArrayOf(Color.rgb(67, 139, 38), Color.rgb(28, 66, 30), Color.rgb(13, 22, 23))
            key.contains("mbc 4") -> intArrayOf(Color.rgb(27, 123, 151), Color.rgb(17, 53, 72), Color.rgb(14, 18, 27))
            key.contains("mbc 5") -> intArrayOf(Color.rgb(0, 133, 169), Color.rgb(10, 49, 72), Color.rgb(13, 17, 29))
            key.contains("mbc action") -> intArrayOf(Color.rgb(151, 105, 22), Color.rgb(66, 43, 24), Color.rgb(20, 17, 24))
            key.contains("mbc max") -> intArrayOf(Color.rgb(117, 72, 37), Color.rgb(51, 32, 29), Color.rgb(17, 16, 23))
            key.contains("mbc drama") -> intArrayOf(Color.rgb(83, 43, 112), Color.rgb(38, 24, 53), Color.rgb(17, 16, 24))
            key.contains("mbc masr 2") -> intArrayOf(Color.rgb(112, 26, 39), Color.rgb(53, 17, 28), Color.rgb(18, 15, 22))
            key.contains("mbc iraq") -> intArrayOf(Color.rgb(24, 112, 91), Color.rgb(16, 54, 49), Color.rgb(15, 19, 27))
            key.contains("mbc bollywood") -> intArrayOf(Color.rgb(151, 42, 54), Color.rgb(70, 23, 44), Color.rgb(20, 15, 28))
            key.contains("mbc masr") -> intArrayOf(Color.rgb(176, 28, 39), Color.rgb(67, 17, 28), Color.rgb(18, 15, 22))
            key.contains("bein") -> intArrayOf(Color.rgb(79, 34, 123), Color.rgb(34, 10, 57), Color.rgb(15, 12, 28))
            key.contains("alwan") || key.contains("ألوان") -> intArrayOf(Color.rgb(12, 102, 103), Color.rgb(5, 57, 60), Color.rgb(7, 27, 37))
            key.contains("ثمانية") || key.contains("shahid sports") -> intArrayOf(Color.rgb(0, 94, 76), Color.rgb(10, 42, 45), Color.rgb(13, 23, 32))
            key.contains("starzplay") || key.contains("film") || key.contains("سينما") -> intArrayOf(Color.rgb(47, 77, 119), Color.rgb(24, 38, 65), Color.rgb(15, 17, 28))
            key.contains("mbc") || key.contains("روتانا") -> intArrayOf(Color.rgb(94, 47, 54), Color.rgb(44, 27, 34), Color.rgb(20, 19, 24))
            key.contains("طفل") || key.contains("كرتون") || key.contains("kids") || key.contains("toons") -> intArrayOf(Color.rgb(103, 39, 127), Color.rgb(53, 23, 76), Color.rgb(24, 18, 37))
            key.contains("news") || key.contains("أخبار") || key.contains("الجزيرة") || key.contains("dw") -> intArrayOf(Color.rgb(83, 48, 54), Color.rgb(39, 30, 37), Color.rgb(19, 22, 28))
            key.contains("national geographic") || key.contains("جيوغرافيك") || key.contains("document") || key.contains("nat geo") -> intArrayOf(Color.rgb(68, 139, 105), Color.rgb(35, 83, 66), Color.rgb(13, 20, 26))
            key.contains("دبي") || key.contains("أبو ظبي") || key.contains("الكأس") || key.contains("رياض") -> intArrayOf(Color.rgb(26, 67, 99), Color.rgb(20, 34, 53), Color.rgb(14, 19, 30))
            else -> {
                val palettes = arrayOf(
                    intArrayOf(Color.rgb(65, 43, 83), Color.rgb(32, 26, 43), Color.rgb(18, 19, 26)),
                    intArrayOf(Color.rgb(34, 76, 77), Color.rgb(23, 43, 47), Color.rgb(16, 20, 27)),
                    intArrayOf(Color.rgb(78, 48, 55), Color.rgb(38, 28, 35), Color.rgb(18, 19, 25)),
                    intArrayOf(Color.rgb(35, 55, 83), Color.rgb(24, 31, 48), Color.rgb(16, 19, 28))
                )
                palettes[(channelName.hashCode().ushr(1) % palettes.size)]
            }
        }

        /**
         * Builds a restrained, premium background palette from the logo's own pixels.
         * The source logo is never changed; only its background palette is generated.
         */
        fun updateFromLogo(bitmap: android.graphics.Bitmap) {
            if (bitmap.width <= 0 || bitmap.height <= 0) return

            // Bounded sampling keeps palette extraction inexpensive for large assets.
            val hueBins = 36
            val weights = FloatArray(hueBins)
            val hueSin = FloatArray(hueBins)
            val hueCos = FloatArray(hueBins)
            val saturationSum = FloatArray(hueBins)
            val sampleStepX = maxOf(1, bitmap.width / 72)
            val sampleStepY = maxOf(1, bitmap.height / 72)
            val hsv = FloatArray(3)

            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val alpha = Color.alpha(pixel)
                    if (alpha >= 150) {
                        Color.colorToHSV(pixel, hsv)
                        val saturation = hsv[1]
                        val value = hsv[2]

                        // Ignore transparent pixels, neutral text, highlights and deep shadows.
                        if (saturation >= 0.22f && value in 0.16f..0.92f) {
                            val bin = ((hsv[0] / 360f) * hueBins)
                                .toInt().coerceIn(0, hueBins - 1)
                            val chromaWeight = 0.35f + saturation * 0.65f
                            val midToneWeight = (1f - kotlin.math.abs(value - 0.53f) * 0.75f)
                                .coerceIn(0.35f, 1f)
                            val weight = (alpha / 255f) * chromaWeight * midToneWeight

                            weights[bin] += weight
                            val angle = Math.toRadians(hsv[0].toDouble())
                            hueSin[bin] += kotlin.math.sin(angle).toFloat() * weight
                            hueCos[bin] += kotlin.math.cos(angle).toFloat() * weight
                            saturationSum[bin] += saturation * weight
                        }
                    }
                    x += sampleStepX
                }
                y += sampleStepY
            }

            val best = weights.indices.maxByOrNull { weights[it] } ?: return
            if (weights[best] < 1.5f) return

            // Circular hue averaging handles colors near the red / 360-degree boundary.
            val hue = (Math.toDegrees(
                kotlin.math.atan2(hueSin[best].toDouble(), hueCos[best].toDouble())
            ) + 360.0) % 360.0
            val sampledSaturation = saturationSum[best] / weights[best]

            // Preserve the logo's color identity while making it calm and cinematic.
            val backgroundSaturation = (sampledSaturation * 0.56f).coerceIn(0.18f, 0.40f)
            val primary = Color.HSVToColor(
                floatArrayOf(hue.toFloat(), backgroundSaturation, 0.31f)
            )
            val secondary = Color.HSVToColor(
                floatArrayOf(
                    hue.toFloat(),
                    (backgroundSaturation * 0.88f).coerceIn(0.16f, 0.35f),
                    0.205f
                )
            )
            val deep = Color.HSVToColor(
                floatArrayOf(
                    hue.toFloat(),
                    (backgroundSaturation * 0.72f).coerceIn(0.12f, 0.29f),
                    0.085f
                )
            )

            palette = intArrayOf(primary, secondary, deep)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return

            val w = b.width().toFloat()
            val h = b.height().toFloat()

            // Gently desaturate the base palette as a safeguard for fallback palettes too.
            // The channel logo itself is drawn separately and is never altered.
            val softPalette = palette.mapIndexed { index, color ->
                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                hsv[1] = (hsv[1] * 0.72f).coerceIn(0.08f, 0.58f)
                hsv[2] = (hsv[2] * when (index) {
                    0 -> 0.88f
                    1 -> 0.92f
                    else -> 0.98f
                }).coerceIn(0.045f, 0.42f)
                Color.HSVToColor(Color.alpha(color), hsv)
            }.toIntArray()

            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                softPalette, floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(b, paint)
            paint.shader = null

            // A very soft central glow adds depth without decorative lines or patterns.
            // A consistent, subtle neutral glow complements the extracted logo color.
            val glowColor = Color.argb(18, 245, 245, 255)
            paint.shader = RadialGradient(
                b.left + w * 0.48f,
                b.top + h * 0.42f,
                maxOf(w, h) * 0.78f,
                intArrayOf(glowColor, Color.argb(7, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(b, paint)
            paint.shader = null

            // Intentionally no curved rings, streaks, or line textures:
            // keep every channel card clean, smooth, and easy on the eyes.
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated on Android")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
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
        fun channelKey(value: String): String = value.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(java.util.Locale.ROOT)

        val requestedKey = channelKey(channelName)
        val options = sources.filter { channelKey(it.channel) == requestedKey }

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
    // Grouped-quality sources start in Auto mode by default. Legacy single-URL
    // sources retain their previous behavior and use ExoPlayer's track selector.
    autoQualityEnabled = currentOptions.getOrNull(currentServerIndex)?.qualityUrls?.isNotBlank() == true
    lastAutoQualitySwitchAt = 0L

    if (playerAlreadyOpen) {
        // Update the selected channel card without rebuilding the entire player UI.
        val selectedChannelName = options.first().channel
        embeddedChannelCards.forEach { (name, card) ->
            card.setBackgroundColor(
                if (name == selectedChannelName) Color.rgb(28, 105, 165)
                else Color.TRANSPARENT
            )
        }
        // Keep the global channel order unchanged; scroll the strip to the selected card.
        embeddedChannelScroll?.let { strip ->
            strip.post {
                val card = embeddedChannelCards[selectedChannelName] ?: return@post
                val targetX = (card.left + card.width / 2 - strip.width / 2).coerceAtLeast(0)
                strip.smoothScrollTo(targetX, 0)
            }
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
        val qualityControl = playerControl("Auto", "اختيار الجودة التلقائية أو اليدوية") {
            val activeSource = currentOptions.getOrNull(currentServerIndex)
            val groupedQualities = activeSource?.let { parseQualityUrls(it.qualityUrls) }.orEmpty()
            if (groupedQualities.isNotEmpty()) {
                // Use a native, track-selection-style dialog (title, selectable rows,
                // and explicit OK/Cancel actions) instead of the old plain text list.
                val labels = listOf("Auto") + supportedQualityLabels.filter { groupedQualities.containsKey(it) }
                val displayLabels = labels.map { label ->
                    if (label == "Auto") "Auto  ·  حسب سرعة الإنترنت" else "$label  ·  ${qualityResolution(label)}"
                }.toTypedArray()
                val activeUrl = activeSource?.url.orEmpty()
                val selectedIndex = if (autoQualityEnabled) 0 else labels.indexOfFirst { label ->
                    label != "Auto" && groupedQualities[label] == activeUrl
                }.let { if (it >= 0) it else 0 }
                var pendingSelection = selectedIndex
                AlertDialog.Builder(this)
                    .setTitle("جودة الفيديو")
                    .setSingleChoiceItems(displayLabels, selectedIndex) { _, which ->
                        pendingSelection = which
                    }
                    .setPositiveButton("OK") { _, _ ->
                        val selectedLabel = labels.getOrNull(pendingSelection) ?: "Auto"
                        if (selectedLabel == "Auto") {
                            autoQualityEnabled = true
                            applyAutomaticQuality(force = true)
                        } else {
                            autoQualityEnabled = false
                            applyGroupedQuality(selectedLabel)
                        }
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            } else {
                embeddedPlayer?.let { player ->
                    TrackSelectionDialogBuilder(this, "جودة الفيديو", player, C.TRACK_TYPE_VIDEO)
                        .setAllowAdaptiveSelections(true)
                        .build()
                        .show()
                }
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
        embeddedChannelScroll = channelScroll
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
        // The selected channel stays at its original index. Move only the viewport
        // so that channel is visible immediately when the player opens.
        channelScroll.post {
            val selectedCard = embeddedChannelCards[source.channel] ?: return@post
            val targetX = (selectedCard.left + selectedCard.width / 2 - channelScroll.width / 2)
                .coerceAtLeast(0)
            channelScroll.scrollTo(targetX, 0)
        }
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

        val defaultDrmProvider = DefaultDrmSessionManagerProvider().apply {
            setDrmHttpDataSourceFactory(httpFactory)
        }
        val drmProvider = DrmSessionManagerProvider { mediaItem ->
            val drmConfig = mediaItem.localConfiguration?.drmConfiguration
            // Resolve DRM credentials against the exact source record, not just its URL.
            // This keeps each server's own KID/Key paired with its MPD when URLs repeat.
            val sourceForItem = (currentOptions + sources).firstOrNull {
                sourceMediaId(it) == mediaItem.mediaId
            }
            val inlineKeys = if (drmConfig?.scheme == C.CLEARKEY_UUID && sourceForItem != null) {
                parseHaniClearKey(sourceForItem.keyId, sourceForItem.key)
                    ?: parseHaniClearKeyPair(sourceForItem.licenseUrl)
            } else null
            if (inlineKeys != null) {
                DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(HaniInlineClearKeyCallback(inlineKeys.first, inlineKeys.second))
            } else {
                defaultDrmProvider.get(mediaItem)
            }
        }
        // Allow Media3 to fall back to another compatible decoder if the preferred
        // hardware decoder cannot initialize this stream reliably.
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(httpFactory)
                    .setDrmSessionManagerProvider(drmProvider)
            )
            .build()
        embeddedPlayer = exo
        video.player = exo
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val label = classifyVideoQuality(videoSize.width, videoSize.height)
                qualityControl.text = if (autoQualityEnabled) "Auto" else label
                qualityControl.contentDescription = if (autoQualityEnabled) {
                    "الجودة التلقائية مفعلة؛ الجودة الحالية $label"
                } else if (label == "—") {
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
                    if (autoQualityEnabled && lastAutoQualitySwitchAt == 0L) {
                        // Wait until the stream is ready before changing to the locally
                        // estimated best URL, avoiding extra work during initial setup.
                        playerHandler.postDelayed({
                            if (embeddedPlayer === exo && embeddedDialog?.isShowing == true && autoQualityEnabled) {
                                applyAutomaticQuality(force = true)
                            }
                        }, 1200L)
                    }
                    startupTimeout?.let { playerHandler.removeCallbacks(it) }
                    startupTimeout = null
                    failoverCount = 0
                    sameServerRetryUsed = false
                } else if (playbackState == Player.STATE_BUFFERING) {
                    // If separate quality URLs are used, a sustained buffering event can
                    // lower quality locally without contacting Hostinger.
                    if (autoQualityEnabled) {
                        playerHandler.postDelayed({
                            if (embeddedPlayer === exo && exo.playbackState == Player.STATE_BUFFERING) {
                                applyAutomaticQuality(force = false, preferLower = true)
                            }
                        }, 4500L)
                    }
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
        val mediaItem = buildMediaItem(source) ?: run {
            closeEmbeddedPlayer()
            return
        }
        exo.setMediaItem(mediaItem)
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

    /** Apply a user-selected quality URL; all decisions and switching stay on-device. */
    private fun applyGroupedQuality(label: String) {
        val source = currentOptions.getOrNull(currentServerIndex) ?: return
        val url = parseQualityUrls(source.qualityUrls)[label] ?: return
        if (source.url != url) {
            currentOptions = currentOptions.toMutableList().also { it[currentServerIndex] = source.copy(url = url) }
            switchServerInsidePlayer(currentServerIndex)
        }
        embeddedQualityControl?.text = label
        embeddedQualityControl?.contentDescription = "الجودة المحددة يدويًا: $label"
    }

    /**
     * Estimate available downstream capacity from Android's active network capabilities.
     * This is a device-side estimate only; it performs no speed-test requests and makes
     * no calls to Hostinger. During buffering Auto can conservatively step down a level.
     */
    private fun estimatedDownstreamKbps(): Int {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return 0
            val network = cm.activeNetwork ?: return 0
            val caps = cm.getNetworkCapabilities(network) ?: return 0
            caps.linkDownstreamBandwidthKbps.coerceAtLeast(0)
        } catch (_: Exception) { 0 }
    }

    private fun applyAutomaticQuality(force: Boolean = false, preferLower: Boolean = false) {
        val source = currentOptions.getOrNull(currentServerIndex) ?: return
        val qualities = parseQualityUrls(source.qualityUrls)
        if (qualities.isEmpty()) return
        val labels = supportedQualityLabels.filter { qualities.containsKey(it) }
        if (labels.isEmpty()) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastAutoQualitySwitchAt < 20_000L) return

        val bandwidthKbps = estimatedDownstreamKbps()
        // Conservative target bitrates with headroom for audio, protocol overhead and fluctuations.
        val preferred = when {
            bandwidthKbps >= 25000 -> "4K"
            bandwidthKbps >= 8000 -> "FHD"
            bandwidthKbps >= 3000 -> "HD"
            bandwidthKbps >= 1200 -> "SD"
            bandwidthKbps >= 600 -> "360p"
            else -> "240p"
        }
        val preferredIndex = supportedQualityLabels.indexOf(preferred).coerceAtLeast(0)
        val currentLabel = labels.firstOrNull { qualities[it] == source.url }
        val target = if (preferLower && currentLabel != null) {
            val currentIndex = supportedQualityLabels.indexOf(currentLabel).coerceAtLeast(0)
            labels.filter { supportedQualityLabels.indexOf(it) < currentIndex }.maxByOrNull {
                supportedQualityLabels.indexOf(it)
            } ?: labels.first()
        } else {
            labels.filter { supportedQualityLabels.indexOf(it) <= preferredIndex }.maxByOrNull {
                supportedQualityLabels.indexOf(it)
            } ?: labels.first()
        }
        val targetUrl = qualities[target] ?: return
        if (source.url != targetUrl) {
            currentOptions = currentOptions.toMutableList().also { it[currentServerIndex] = source.copy(url = targetUrl) }
            lastAutoQualitySwitchAt = now
            switchServerInsidePlayer(currentServerIndex)
        }
        embeddedQualityControl?.text = "Auto"
        embeddedQualityControl?.contentDescription = "الجودة التلقائية مفعلة؛ الجودة المختارة $target"
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
     * Detect HLS/DASH even when a proxy URL wraps the actual manifest in a query
     * parameter, e.g. worker.php?url=https%3A%2F%2Fhost%2Flive%2Findex.m3u8.
     * Android Uri.getQueryParameter() already percent-decodes query values.
     */
    private fun isHlsStreamUrl(url: String): Boolean {
        fun inspect(candidate: String, depth: Int): Boolean {
            if (depth > 4) return false
            val uri = try {
                android.net.Uri.parse(candidate)
            } catch (_: Exception) {
                return false
            }

            val path = uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
            if (path.endsWith(".m3u8") || path.endsWith(".css")) return true

            // Common proxy/wrapper parameter names. Keep the original URL for playback;
            // this is format detection only, not URL rewriting.
            for (name in listOf("url", "stream", "src", "file", "source", "link", "target", "playlist", "m3u8")) {
                val nested = uri.getQueryParameter(name)?.trim().orEmpty()
                if (nested.isBlank()) continue
                if (inspect(nested, depth + 1)) return true

                // Some proxy endpoints put a Base64-encoded manifest URL in `stream`.
                if (name == "stream") {
                    val decoded = sequenceOf(
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                        android.util.Base64.DEFAULT
                    ).mapNotNull { flags ->
                        try {
                            String(android.util.Base64.decode(nested, flags), Charsets.UTF_8)
                        } catch (_: Exception) {
                            null
                        }
                    }.firstOrNull { it.isNotBlank() }
                    if (!decoded.isNullOrBlank() && inspect(decoded, depth + 1)) return true
                }
            }
            return false
        }
        return inspect(url, 0)
    }

    private fun isDashStreamUrl(url: String): Boolean {
        fun inspect(candidate: String, depth: Int): Boolean {
            if (depth > 4) return false
            val uri = try {
                android.net.Uri.parse(candidate)
            } catch (_: Exception) {
                return false
            }
            val path = uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
            if (path.endsWith(".mpd") ||
                uri.getQueryParameter("type")?.equals("mpd", ignoreCase = true) == true ||
                uri.getQueryParameter("format")?.equals("mpd", ignoreCase = true) == true
            ) return true

            for (name in listOf("url", "stream", "src", "file", "source", "link", "target", "playlist", "mpd")) {
                val nested = uri.getQueryParameter(name)?.trim().orEmpty()
                if (nested.isNotBlank() && inspect(nested, depth + 1)) return true
            }
            return false
        }
        return inspect(url, 0)
    }

    // A stable per-source media ID prevents two server rows that share a URL from
    // accidentally using each other's DRM credentials.
    private fun sourceMediaId(source: StreamSource): String = listOf(
        source.channel, source.serverName, source.url,
        source.keyId.hashCode().toString(), source.key.hashCode().toString(),
        source.licenseUrl.hashCode().toString(), source.scheme, source.drmScheme
    ).joinToString("\u001f")

    /** Creates a Media3 item using this server row's format and DRM settings. */
    private fun buildMediaItem(source: StreamSource): MediaItem? {
        val builder = MediaItem.Builder()
            .setUri(source.url)
            .setMediaId(sourceMediaId(source))

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
                normalizedPath.endsWith("/index.html") || normalizedPath == "index.html" ->
                builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }

        val licenseUrl = source.licenseUrl.trim()
        val schemeName = source.scheme.trim().lowercase(java.util.Locale.ROOT)
        val drmName = source.drmScheme.trim().lowercase(java.util.Locale.ROOT)
        val clearKeyRequested = schemeName.contains("clearkey") || schemeName.contains("clear key") ||
            drmName.contains("clearkey") || drmName.contains("clear key")
        // Prefer separate Key ID/Key fields; also accept KID:KEY pasted into License URL.
        val inlineKeys = if (clearKeyRequested) {
            parseHaniClearKey(source.keyId, source.key)
                ?: parseHaniClearKeyPair(licenseUrl)
        } else null
        val drmRequested = licenseUrl.isNotBlank() || schemeName.isNotBlank() ||
            drmName.isNotBlank() || source.keyId.isNotBlank() || source.key.isNotBlank()

        if (inlineKeys != null) {
            // Local ClearKey does not need a network License URL.
            builder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build()
            )
        } else if (drmRequested) {
            if (licenseUrl.isBlank() ||
                !(licenseUrl.startsWith("https://", true) || licenseUrl.startsWith("http://", true))) {
                Toast.makeText(
                    this,
                    "لـ ClearKey أدخل Key ID وKey الصحيحين (32 خانة hex لكل منهما)، أو استخدم License URL الرسمي لنوع DRM الآخر.",
                    Toast.LENGTH_LONG
                ).show()
                return null
            }

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

            if (drmUuid == null) {
                Toast.makeText(
                    this,
                    "نوع DRM غير معروف. استخدم widevine أو clearkey أو playready أو UUID صحيحًا.",
                    Toast.LENGTH_LONG
                ).show()
                return null
            }

            builder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(drmUuid)
                    .setLicenseUri(android.net.Uri.parse(licenseUrl))
                    .setLicenseRequestHeaders(buildSourceHeaders(source))
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
        val mediaItem = buildMediaItem(source) ?: return
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
        autoQualityEnabled = false
        lastAutoQualitySwitchAt = 0L
        embeddedHttpFactory = null
        embeddedPlayerView = null
        loadingIndicator = null
        scheduleOverlayHideAction = null
        embeddedDialog = null
        embeddedChannelTitle = null
        embeddedServerButtons.clear()
        embeddedChannelCards.clear()
        embeddedChannelScroll = null
        failoverPending = false
        sameServerRetryUsed = false
        if (!keepScreenOnWasSet) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        keepScreenOnWasSet = false
    }


    private fun showServerManager() {
        isHomeScreenVisible = false
        showingServerManager = true
        content.removeAllViews()
        content.setBackgroundColor(Color.TRANSPARENT)
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
        val managedChannelCount = (channelGroups.values.flatten().map { it.first } + sources.map { it.channel.trim() })
            .filter { it.isNotBlank() }.distinct().size
        stats.addView(stat("القنوات", managedChannelCount.toString()), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
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
        content.addView(actions, fullWidthParams().apply { bottomMargin = dp(8) })

        val addQualityGroup = makeButton("＋ إضافة سيرفر واحد بعدة جودات")
        addQualityGroup.setOnClickListener { requireAdmin { showAddQualitySourceDialog() } }
        content.addView(addQualityGroup, fullWidthParams().apply { bottomMargin = dp(10) })

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
            // Include every channel compiled into the app, plus any channel that already
            // has a saved source row. A channel remains manageable even before a server is added.
            val allKnownChannels = (
                channelGroups.values.flatten().map { it.first } + sources.map { it.channel.trim() }
            ).filter { it.isNotBlank() }.distinct()

            fun categoryForManager(channel: String): String =
                channelGroups.entries.firstOrNull { entry -> entry.value.any { it.first == channel } }?.key
                    ?: channelCategories[channel]
                    ?: defaultCategoryFor(channel)

            val channelOrder = allKnownChannels.filter { channel ->
                val cat = categoryForManager(channel)
                val nameMatches = query.isBlank() || channel.lowercase(java.util.Locale.ROOT).contains(query) ||
                    cat.lowercase(java.util.Locale.ROOT).contains(query)
                val sourceMatches = sources.any { item ->
                    item.channel.equals(channel, ignoreCase = true) &&
                        (item.serverName.lowercase(java.util.Locale.ROOT).contains(query) ||
                         item.displayName.lowercase(java.util.Locale.ROOT).contains(query) ||
                         item.url.lowercase(java.util.Locale.ROOT).contains(query))
                }
                (selectedCategory == "الكل" || cat == selectedCategory) &&
                    (query.isBlank() || nameMatches || sourceMatches)
            }
            if (channelOrder.isEmpty()) {
                listHost.addView(TextView(this).apply { text = "لا توجد نتائج مطابقة."; setTextColor(gray); textSize = 14f; setPadding(dp(8), dp(18), dp(8), dp(18)) })
                return
            }
            channelOrder.forEach { channel ->
                val category = categoryForManager(channel)
                val channelNameMatches = query.isBlank() || channel.lowercase(java.util.Locale.ROOT).contains(query) ||
                    category.lowercase(java.util.Locale.ROOT).contains(query)
                val channelItems = sources.withIndex().filter { (_, item) ->
                    item.channel.equals(channel, ignoreCase = true) &&
                        (channelNameMatches || item.serverName.lowercase(java.util.Locale.ROOT).contains(query) ||
                         item.displayName.lowercase(java.util.Locale.ROOT).contains(query) ||
                         item.url.lowercase(java.util.Locale.ROOT).contains(query))
                }
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
                        details.addView(TextView(this).apply {
                            val qualityLabels = parseQualityUrls(source.qualityUrls).keys.joinToString(" · ")
                            text = if (qualityLabels.isNotBlank()) "الجودات: $qualityLabels" else source.url
                            textSize = 10f; setTextColor(gray); maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        })
                        top.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                        val copy = TextView(this).apply { text = "نسخ"; textSize = 11f; setTextColor(purple); setPadding(dp(7), dp(8), dp(7), dp(8)); setOnClickListener { val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Stream URL", source.url)); Toast.makeText(this@MainActivity, "تم نسخ الرابط", Toast.LENGTH_SHORT).show() } }
                        top.addView(copy)
                        row.addView(top)
                        val rowActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        val edit = TextView(this).apply { text = "تعديل الإعدادات"; textSize = 11f; setTextColor(purple); setPadding(dp(8), dp(7), dp(12), dp(4)); setOnClickListener { requireAdmin { val idx = sources.indexOf(source); if (idx >= 0) { if (sources[idx].qualityUrls.isNotBlank()) showAddQualitySourceDialog(sources[idx], idx) else showAddSourceDialog(sources[idx], idx) } } } }
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
                }
                val addForChannel = TextView(this).apply {
                    text = "＋ إضافة سيرفر لهذه القناة"
                    textSize = 12f; gravity = Gravity.CENTER; setTextColor(purple)
                    setPadding(dp(8), dp(10), dp(8), dp(4))
                    setOnClickListener { requireAdmin { showAddSourceDialog(channelName = channel) } }
                }
                channelCard.addView(addForChannel)
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


    private fun normalizeCategoryName(category: String): String =
        if (category.trim() == "القنوات العربية") "القنوات العربية والثقافية" else category.trim()

    private fun allCategories(): List<String> {
        val defaults = channelGroups.keys.map { normalizeCategoryName(it) }
        val remote = channelCategories.values.map { normalizeCategoryName(it) }.filter { it.isNotBlank() }
        val available = (defaults + remote)
            .filter { it.isNotBlank() }
            .distinct()

        // Keep the home screen's main sections in a stable order even when the cloud
        // API returns channels/categories in a different order during synchronization.
        val preferredOrder = listOf(
            "القنوات الرياضية",
            "القنوات العربية والثقافية",
            "القنوات الجزائرية",
            "القنوات المغربية",
            "القنوات الإخبارية",
            "قنوات الأطفال",
            "قنوات الترفيه",
            "ثمانية",
            "STARZPLAY",
            "ALWAN"
        )
        return preferredOrder + available.filterNot { it in preferredOrder }
    }

    private fun builtInBrandCategoryFor(channelName: String): String? = when (channelName) {
        "MBC 1", "MBC 2", "MBC 3", "MBC 4", "MBC 5", "MBC Action", "MBC Max",
        "MBC Drama", "MBC Masr 1", "MBC Masr 2", "MBC Iraq", "MBC Bollywood" -> "القنوات العربية والثقافية"
        "National Geographic", "Al Jazeera Documentary" -> "القنوات العربية والثقافية"
        "DW", "BBC News عربي" -> "القنوات الإخبارية"
        "Algeria 1", "Algeria 2", "Algeria 3", "Algeria 5", "Algeria 6",
        "AL24 News", "El Heddaf", "Ennahar" -> "القنوات الجزائرية"
        "Al Aoula", "Laayoune", "Arryadia", "2M" -> "القنوات المغربية"
        "ON Sport", "ON Sport Plus", "ON Sport Max" -> "القنوات المصرية"
        "Sky News Arabia", "العربية", "الجزيرة" -> "القنوات الإخبارية"
        "ثمانية 1", "ثمانية 2", "ثمانية 3" -> "ثمانية"
        "STARZPLAY 1", "STARZPLAY 2", "STARZPLAY 3" -> "STARZPLAY"
        "ALWAN 1", "ALWAN 2", "ALWAN 3", "ALWAN 4", "ALWAN 5",
        "ALWAN 6", "ALWAN 7", "ALWAN 8", "ALWAN 9", "ALWAN 10" -> "ALWAN"
        in listOf(
            "قنوات الكأس 1", "قنوات الكأس 2", "قنوات الكأس 3", "قنوات الكأس 4",
            "قنوات الكأس 5", "قنوات الكأس 6", "قنوات الكأس 7", "قنوات الكأس 8",
            "أبو ظبي الرياضية 1", "أبو ظبي الرياضية 2",
            "أبو ظبي الرياضية بريميوم 1", "أبو ظبي الرياضية بريميوم 2",
            "شاهد Sports 1", "شاهد Sports 2", "شاهد Sports 3", "شاهد Sports 4", "شاهد Sports 5",
            "دبي الرياضية 1", "دبي الرياضية 2"
        ) -> "القنوات الرياضية"
        else -> null
    }

    private fun ensureBuiltInBrandChannels() {
        val builtIns = linkedMapOf(
            "القنوات العربية والثقافية" to listOf(
                "MBC 1" to "MBC1",
                "MBC 2" to "MBC2",
                "MBC 3" to "MBC3",
                "MBC 4" to "MBC4",
                "MBC 5" to "MBC5",
                "MBC Action" to "ACTION",
                "MBC Max" to "MAX",
                "MBC Drama" to "DRAMA",
                "MBC Masr 1" to "MASR1",
                "MBC Masr 2" to "MASR2",
                "MBC Iraq" to "IRAQ",
                "MBC Bollywood" to "BOLLY",
                "National Geographic" to "NATGEO",
                "Al Jazeera Documentary" to "AJDOC"
            ),
            "القنوات الإخبارية" to listOf(
                "Sky News Arabia" to "SKY",
                "العربية" to "ALAR",
                "الجزيرة" to "JAZ",
                "DW" to "DW",
                "BBC News عربي" to "BBCNA"
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
            "القنوات الرياضية" to listOf(
                "قنوات الكأس 1" to "KAS1",
                "قنوات الكأس 2" to "KAS2",
                "قنوات الكأس 3" to "KAS3",
                "قنوات الكأس 4" to "KAS4",
                "قنوات الكأس 5" to "KAS5",
                "قنوات الكأس 6" to "KAS6",
                "قنوات الكأس 7" to "KAS7",
                "قنوات الكأس 8" to "KAS8",
                "أبو ظبي الرياضية 1" to "AD1",
                "أبو ظبي الرياضية 2" to "AD2",
                "أبو ظبي الرياضية بريميوم 1" to "ADP1",
                "أبو ظبي الرياضية بريميوم 2" to "ADP2",
                "شاهد Sports 1" to "SH1",
                "شاهد Sports 2" to "SH2",
                "شاهد Sports 3" to "SH3",
                "شاهد Sports 4" to "SH4",
                "شاهد Sports 5" to "SH5",
                "دبي الرياضية 1" to "DUB1",
                "دبي الرياضية 2" to "DUB2"
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
            ?: "القنوات العربية والثقافية"

    private fun showChannelImageManager() {
        isHomeScreenVisible = false
        showingServerManager = true
        content.removeAllViews()
        content.setBackgroundColor(Color.TRANSPARENT)
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

    /** Apply the graphite-luxury visual system only to channel-browsing screens. */
    private fun applyChannelPageTheme() {
        content.setPadding(dp(14), dp(16), dp(14), dp(24))
        content.background = ChannelAtmosphereDrawable()
    }

    private fun graphiteCardDrawable(accent: Int = Color.rgb(83, 86, 96), radius: Int = 14): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(39, 40, 46), Color.rgb(24, 25, 30), Color.rgb(19, 20, 24))
        ).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), Color.argb(210, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }

    /** A restrained graphite card used by the category tiles, with no bright glass glow. */
    private fun applyChannelGlassCard(view: View) {
        view.background = graphiteCardDrawable(Color.rgb(88, 91, 101), 13)
        view.elevation = dp(2).toFloat()
        view.clipToOutline = true
    }

    /**
     * Logo surface selection changes only the panel behind the asset, never the original PNG.
     * Black/dark wordmarks receive a pearl surface; white, neon and saturated marks receive
     * a graphite surface; unclassified logos get a neutral mid-silver surface.
     */
    private fun makeAdaptiveLogoStage(channelName: String, width: Int, height: Int): FrameLayout {
        val name = channelName.trim().lowercase(java.util.Locale.ROOT)
        val surfaceMode = when {
            name == "mbc max" || name == "mbc drama" || name == "mbc 1" ||
                name == "قنوات الكأس" || name == "الكأس" -> "light"
            name.contains("ثمانية") || name.contains("thmanyah") ||
                name.contains("bein") || name.contains("starzplay") || name.contains("alwan") ||
                name.contains("ألوان") || name.contains("mbc 3") || name.contains("mbc 4") ||
                name.contains("mbc 5") || name.contains("mbc action") || name.contains("masr") ||
                name.contains("شاهد") || name.contains("shahid") || name.contains("دبي الرياضية") ||
                name.contains("أبو ظبي") || name.contains("abu dhabi") -> "dark"
            else -> "neutral"
        }
        val colors = when (surfaceMode) {
            "light" -> intArrayOf(Color.rgb(245, 245, 247), Color.rgb(211, 213, 218))
            "dark" -> intArrayOf(Color.rgb(38, 39, 45), Color.rgb(20, 21, 26))
            else -> intArrayOf(Color.rgb(171, 174, 181), Color.rgb(123, 127, 136))
        }
        val stroke = when (surfaceMode) {
            "light" -> Color.rgb(255, 255, 255)
            "dark" -> Color.rgb(76, 79, 89)
            else -> Color.rgb(211, 213, 218)
        }
        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), stroke)
            }
            clipToOutline = true
            elevation = dp(1).toFloat()
            contentDescription = "$channelName logo background"
        }
    }

    /** Pure black app wallpaper matching the requested reference; card artwork remains unchanged. */
    private inner class ChannelAtmosphereDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            paint.shader = null
            paint.color = Color.BLACK
            paint.alpha = 255
            canvas.drawColor(Color.BLACK)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated on Android")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
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
            brandName.contains("شاهد", ignoreCase = true) || brandName.contains("shahid", ignoreCase = true) -> Color.rgb(0, 190, 160)
            brandName.contains("دبي", ignoreCase = true) || brandName.contains("dubai", ignoreCase = true) -> Color.rgb(220, 30, 50)
            else -> Color.rgb(100, 130, 255)
        }
        val deep = when {
            brandName.contains("bein", ignoreCase = true) -> Color.rgb(35, 4, 65)
            brandName.contains("ثمانية") -> Color.rgb(0, 42, 30)
            brandName.contains("ALWAN", ignoreCase = true) -> Color.rgb(0, 28, 48)
            brandName.contains("STARZPLAY", ignoreCase = true) -> Color.rgb(0, 32, 52)
            brandName.contains("شاهد", ignoreCase = true) || brandName.contains("shahid", ignoreCase = true) -> Color.rgb(0, 42, 38)
            brandName.contains("دبي", ignoreCase = true) || brandName.contains("dubai", ignoreCase = true) -> Color.rgb(48, 8, 18)
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

    private fun bitmapFromLogoView(view: View): android.graphics.Bitmap? {
        val image = view as? ImageView ?: return null
        val drawable = image.drawable ?: return null
        return try {
            if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val width = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(512)
                val height = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(512)
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val oldBounds = RectF(drawable.bounds)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                drawable.setBounds(oldBounds.left.toInt(), oldBounds.top.toInt(), oldBounds.right.toInt(), oldBounds.bottom.toInt())
                bitmap
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun channelLogoView(
        channelName: String,
        fallback: String,
        width: Int,
        height: Int,
        plainBrandFallback: Boolean = false
    ): View {
        // Official sports-brand logos are bundled in res/drawable-nodpi.
        // Resolve them locally first so they appear immediately, without any network request.
        val localLogoRes = when {
            channelName.equals("MBC 1", ignoreCase = true) -> R.drawable.mbc_1
            channelName.equals("MBC 2", ignoreCase = true) -> R.drawable.mbc_2
            channelName.equals("MBC 3", ignoreCase = true) -> R.drawable.mbc_3
            channelName.equals("MBC 4", ignoreCase = true) -> R.drawable.mbc_4
            channelName.equals("MBC 5", ignoreCase = true) -> R.drawable.mbc_5
            channelName.equals("MBC Action", ignoreCase = true) -> R.drawable.mbc_action
            channelName.equals("MBC Max", ignoreCase = true) -> R.drawable.mbc_max
            channelName.equals("Movies Action", ignoreCase = true) -> R.drawable.movies_action
            channelName.equals("Movies Thriller", ignoreCase = true) -> R.drawable.movies_thriller
            channelName.equals("Star Action", ignoreCase = true) -> R.drawable.star_action
            channelName.equals("Star Films", ignoreCase = true) -> R.drawable.star_films
            channelName.equals("MBC Drama", ignoreCase = true) -> R.drawable.mbc_drama
            channelName.equals("MBC Masr 1", ignoreCase = true) -> R.drawable.mbc_masr
            channelName.equals("MBC Masr 2", ignoreCase = true) -> R.drawable.mbc_masr_2
            channelName.equals("MBC Iraq", ignoreCase = true) -> R.drawable.mbc_iraq
            channelName.equals("MBC Bollywood", ignoreCase = true) -> R.drawable.mbc_bollywood
            channelName.equals("Disney OSN", ignoreCase = true) -> R.drawable.disney_osn
            channelName.equals("Spacetoon", ignoreCase = true) -> R.drawable.stoon_logo
            channelName.equals("Cartoon Network", ignoreCase = true) -> R.drawable.cn
            channelName.equals("National Geographic", ignoreCase = true) -> R.drawable.national_geographic
            channelName.equals("Al Jazeera Documentary", ignoreCase = true) -> R.drawable.al_jazeera_documentary
            channelName.equals("DW", ignoreCase = true) -> R.drawable.dw
            channelName.equals("BBC News عربي", ignoreCase = true) -> R.drawable.bbc_news_arabi
            channelName.equals("Algeria 1", ignoreCase = true) -> R.drawable.algeria_1
            channelName.equals("Algeria 2", ignoreCase = true) -> R.drawable.algeria_2
            channelName.equals("Algeria 3", ignoreCase = true) -> R.drawable.algeria_3
            channelName.equals("Algeria 5", ignoreCase = true) -> R.drawable.algeria_5
            channelName.equals("Algeria 6", ignoreCase = true) -> R.drawable.algeria_6
            channelName.equals("AL24 News", ignoreCase = true) -> R.drawable.al24_news
            channelName.equals("El Heddaf", ignoreCase = true) -> R.drawable.el_heddaf
            channelName.equals("Ennahar", ignoreCase = true) -> R.drawable.ennahar
            channelName.equals("Al Aoula", ignoreCase = true) -> R.drawable.al_aoula
            channelName.equals("Laayoune", ignoreCase = true) -> R.drawable.laayoune
            channelName.equals("Arryadia", ignoreCase = true) -> R.drawable.arryadia
            channelName.equals("2M", ignoreCase = true) -> R.drawable.channel_2m
            channelName.equals("ON Sport", ignoreCase = true) -> R.drawable.on_sport_final
            channelName.equals("ON Sport Plus", ignoreCase = true) -> R.drawable.on_sport_plus_final
            channelName.equals("ON Sport Max", ignoreCase = true) -> R.drawable.on_sport_max_final
            channelName.equals("Sky News Arabia", ignoreCase = true) -> R.drawable.skynewsarabia
            channelName.equals("العربية", ignoreCase = true) -> R.drawable.alarabiya
            channelName.equals("الجزيرة", ignoreCase = true) -> R.drawable.aljazeera
            channelName.contains("ثمانية", ignoreCase = true) ||
                channelName.contains("thmanyah", ignoreCase = true) -> R.drawable.thamania
            channelName.contains("STARZPLAY", ignoreCase = true) -> R.drawable.logo_starzplay
            channelName.contains("ALWAN", ignoreCase = true) ||
                channelName.contains("ألوان", ignoreCase = true) -> R.drawable.logo_alwan
            channelName.contains("bein", ignoreCase = true) ||
                channelName.contains("beIN", ignoreCase = true) -> R.drawable.bein_sports
            channelName.contains("قنوات الكأس") || channelName.contains("الكأس") -> R.drawable.al_kass_sports
            (channelName.contains("شاهد", ignoreCase = true) || channelName.contains("shahid", ignoreCase = true)) &&
                channelName.contains("sports", ignoreCase = true) -> R.drawable.shahid_sports_vip
            channelName.contains("دبي الرياضية") || channelName.contains("dubai sports", ignoreCase = true) -> R.drawable.dubai_sports
            channelName.contains("بريميوم") || channelName.contains("premium", ignoreCase = true) -> when {
                channelName.endsWith("2") -> R.drawable.abu_dhabi_premium_2
                else -> R.drawable.abu_dhabi_premium_1
            }
            channelName.contains("أبو ظبي الرياضية") || channelName.contains("abu dhabi", ignoreCase = true) -> R.drawable.abu_dhabi_sports_logo
            else -> 0
        }

        if (localLogoRes != 0) {
            return ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(width, height)
                val isThmanyah = channelName.contains("ثمانية", ignoreCase = true) ||
                    channelName.contains("thmanyah", ignoreCase = true)
                scaleType = if (isThmanyah) ImageView.ScaleType.MATRIX else ImageView.ScaleType.FIT_CENTER
                contentDescription = "$channelName logo"
                setBackgroundColor(Color.TRANSPARENT)
                setImageResource(localLogoRes)
                adjustViewBounds = true

                // The Thmanyah PNG has substantial empty/transparent margins around its mark.
                // Zoom the bundled drawable inside the existing logo slot so its visible mark
                // is comparable in size to the other sports logos, without changing card layout.
                if (isThmanyah) {
                    post {
                        val d = drawable
                        if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0 && width > 0 && height > 0) {
                            val fit = minOf(
                                width.toFloat() / d.intrinsicWidth.toFloat(),
                                height.toFloat() / d.intrinsicHeight.toFloat()
                            )
                            val dx = (width - d.intrinsicWidth * fit) / 2f
                            val dy = (height - d.intrinsicHeight * fit) / 2f
                            imageMatrix = android.graphics.Matrix().apply {
                                setScale(fit, fit)
                                postTranslate(dx, dy)
                                postScale(2.35f, 2.35f, width / 2f, height / 2f)
                            }
                        }
                    }
                }
            }
        }

        val imageUrl = channelImageUrls[channelName].orEmpty().trim()

        // Non-brand channel logos may still use the existing remote image manager.
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
                channelName.equals("MBC Iraq", ignoreCase = true) -> "MBC\nIRAQ"
                channelName.equals("MBC Bollywood", ignoreCase = true) -> "MBC\nBOLLYWOOD"
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
                channelName.contains("MBC", ignoreCase = true) -> Color.rgb(245, 225, 230)
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
                        val poster = imageView.parent as? FrameLayout
                        (poster?.background as? ChannelPosterDrawable)?.updateFromLogo(loadedBitmap)
                    }
                }
            }
        }

        // Poster/brand tiles must remain a single full-bleed artwork surface.
        // Do not draw a second inset rectangle behind the logo inside each card.
        imageView.background = if (plainBrandFallback) {
            ColorDrawable(Color.TRANSPARENT)
        } else {
            GradientDrawable().apply {
                setColor(Color.rgb(25, 25, 30))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.rgb(65, 65, 75))
            }
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

    // Keep the quality order low-to-high so Auto can step down gradually.
    private val supportedQualityLabels = listOf("240p", "360p", "SD", "HD", "FHD", "4K")

    private fun qualityResolution(label: String): String = when (label) {
        "240p" -> "240p"
        "360p" -> "360p"
        "SD" -> "480p"
        "HD" -> "720p"
        "FHD" -> "1080p"
        "4K" -> "2160p"
        else -> label
    }

    private fun parseQualityUrls(raw: String): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        if (raw.isBlank()) return result
        try {
            val json = JSONObject(raw)
            supportedQualityLabels.forEach { label ->
                val url = json.optString(label, "").trim()
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) result[label] = url
            }
        } catch (_: Exception) {
            // Invalid/old data is ignored; the ordinary single URL remains usable.
        }
        return result
    }

    /** New, separate editor for a single server record that contains several quality URLs. */
    private fun showAddQualitySourceDialog(existing: StreamSource? = null, editIndex: Int = -1, channelName: String? = null) {
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
        val channelNames = (
            channelGroups.values.flatten().map { it.first } +
                listOfNotNull(existing?.channel?.trim(), channelName?.trim())
            ).filter { it.isNotBlank() }.distinct()

        box.addView(TextView(this).apply {
            text = "اسم القناة — اختر من القائمة"
            textSize = 13f
            setTextColor(gray)
            setPadding(0, dp(8), 0, dp(4))
        })
        val channelInput = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, channelNames).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val selectedName = existing?.channel ?: channelName
            val selectedIndex = channelNames.indexOfFirst { it == selectedName }
            setSelection(if (selectedIndex >= 0) selectedIndex else 0)
        }
        box.addView(channelInput, fullWidthParams())

        val serverInput = field("اسم السيرفر (مثال: السيرفر متعدد الجودات)", existing?.serverName ?: "السيرفر متعدد الجودات")
        val displayNameInput = field("الاسم الذي يظهر داخل المشغّل", existing?.displayName ?: existing?.serverName ?: "السيرفر متعدد الجودات")
        box.addView(TextView(this).apply {
            text = "روابط الجودات لهذا السيرفر الواحد"
            textSize = 13f
            setTextColor(Color.rgb(169, 112, 245))
            setPadding(0, dp(12), 0, dp(3))
        })
        val oldQualities = parseQualityUrls(existing?.qualityUrls.orEmpty())
        val qualityInputs = linkedMapOf<String, EditText>()
        supportedQualityLabels.forEach { label ->
            qualityInputs[label] = field("رابط $label (${qualityResolution(label)})", oldQualities[label] ?: "")
        }
        val refererInput = field("Referer (اختياري)", existing?.referer ?: "")
        val userAgentInput = field("User-Agent (اختياري)", existing?.userAgent ?: "")
        val cookieInput = field("Cookie (اختياري)", existing?.cookie ?: "")
        val authorizationInput = field("Authorization (اختياري)", existing?.authorization ?: "")
        val extraHeadersInput = field("Headers إضافية: اسم: قيمة (كل سطر منفصل)", existing?.extraHeaders ?: "", multiline = true)
        val licenseUrlInput = field("License URL (اختياري)", existing?.licenseUrl ?: "")
        val schemeInput = field("Scheme (مثال: widevine / clearkey)", existing?.scheme ?: "")
        val keyIdInput = field("Key ID (اختياري)", existing?.keyId ?: "")
        val keyInput = field("Key (اختياري)", existing?.key ?: "")
        val playerTypeInput = field("نوع المشغّل: Exo Player أو Web Player", existing?.playerType ?: "Exo Player")
        val formScroll = ScrollView(this).apply { isFillViewport = true; addView(box) }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "إضافة سيرفر متعدد الجودات" else "تعديل السيرفر متعدد الجودات")
            .setView(formScroll)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val channel = channelInput.selectedItem?.toString()?.trim().orEmpty()
                        val server = serverInput.text.toString().trim()
                        val displayName = displayNameInput.text.toString().trim()
                        val qualityJson = JSONObject()
                        for ((label, input) in qualityInputs) {
                            val value = input.text.toString().trim()
                            if (value.isNotBlank()) {
                                if (!(value.startsWith("http://", true) || value.startsWith("https://", true))) {
                                    Toast.makeText(this, "رابط جودة $label يجب أن يبدأ بـ http:// أو https://", Toast.LENGTH_LONG).show()
                                    return@setOnClickListener
                                }
                                qualityJson.put(label, value)
                            }
                        }
                        if (channel.isBlank() || server.isBlank() || qualityJson.length() == 0) {
                            Toast.makeText(this, "اختر القناة واسم السيرفر وأدخل رابط جودة واحدًا على الأقل.", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        val qualities = parseQualityUrls(qualityJson.toString())
                        val defaultUrl = qualities["HD"] ?: qualities["FHD"] ?: qualities["SD"] ?: qualities["360p"] ?: qualities["240p"] ?: qualities["4K"] ?: qualities.values.firstOrNull().orEmpty()
                        val item = StreamSource(
                            channel = channel,
                            serverName = server,
                            url = defaultUrl,
                            referer = refererInput.text.toString().trim(),
                            displayName = displayName.ifBlank { server },
                            userAgent = userAgentInput.text.toString().trim(),
                            cookie = cookieInput.text.toString().trim(),
                            authorization = authorizationInput.text.toString().trim(),
                            extraHeaders = extraHeadersInput.text.toString().trim(),
                            licenseUrl = licenseUrlInput.text.toString().trim(),
                            scheme = schemeInput.text.toString().trim(),
                            drmScheme = existing?.drmScheme ?: "",
                            playerType = playerTypeInput.text.toString().trim().ifBlank { "Exo Player" },
                            keyId = keyIdInput.text.toString().trim(),
                            key = keyInput.text.toString().trim(),
                            qualityUrls = qualityJson.toString()
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
        // Choose a channel from the app's actual channel list instead of free typing.
        // The selector is based on the app's built-in channel catalogue.
        val channelNames = (
            channelGroups.values.flatten().map { it.first } +
            // Keep the currently edited/selected source channel available without
            // importing the server list as a channel catalogue.
            listOfNotNull(existing?.channel?.trim(), channelName?.trim())
        ).filter { it.isNotBlank() }.distinct()

        box.addView(TextView(this).apply {
            text = "اسم القناة — اختر من القائمة لضمان الربط الصحيح"
            textSize = 13f
            setTextColor(gray)
            setPadding(0, dp(8), 0, dp(4))
        })

        val channelInput = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                channelNames
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val selectedName = existing?.channel ?: channelName
            val selectedIndex = channelNames.indexOfFirst { it == selectedName }
            setSelection(if (selectedIndex >= 0) selectedIndex else 0)
        }
        box.addView(channelInput, fullWidthParams())

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
        val keyIdInput = field("Key ID (اختياري)", existing?.keyId ?: "")
        val keyInput = field("Key (اختياري)", existing?.key ?: "")
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
                        val channel = channelInput.selectedItem?.toString()?.trim().orEmpty()
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
                        val keyId = keyIdInput.text.toString().trim()
                        val key = keyInput.text.toString().trim()
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
                            drmScheme = existing?.drmScheme ?: "",
                            playerType = playerType,
                            keyId = keyId,
                            key = key
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
                    item.optString("playerType", "Exo Player"),
                    item.optString("keyId", ""),
                    item.optString("key", ""),
                    item.optString("qualityUrls", "")
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
                put("keyId", source.keyId)
                put("key", source.key)
                put("qualityUrls", source.qualityUrls)
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

    /** Read shared server/source settings from Hostinger PHP API. Channel catalogue stays local. */
    private fun syncFromCloud(silent: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
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
                            item.optString("playerType", "Exo Player"),
                            item.optString("keyId", ""),
                            item.optString("key", ""),
                            item.optString("qualityUrls", "")
                        ))
                    }
                    // Preserve locally saved DRM fields if Hostinger still returns an older
                    // source row without those fields (for example after a failed cloud save).
                    // Match by channel plus server name, with channel plus URL as a fallback.
                    for (index in downloaded.indices) {
                        val remote = downloaded[index]
                        val local = sources.firstOrNull { candidate ->
                            candidate.channel.equals(remote.channel, ignoreCase = true) &&
                                (candidate.serverName.equals(remote.serverName, ignoreCase = true) ||
                                 candidate.url.equals(remote.url, ignoreCase = true))
                        } ?: continue
                        downloaded[index] = remote.copy(
                            licenseUrl = remote.licenseUrl.ifBlank { local.licenseUrl },
                            scheme = remote.scheme.ifBlank { local.scheme },
                            drmScheme = remote.drmScheme.ifBlank { local.drmScheme },
                            keyId = remote.keyId.ifBlank { local.keyId },
                            key = remote.key.ifBlank { local.key },
                            qualityUrls = remote.qualityUrls.ifBlank { local.qualityUrls }
                        )
                    }

                    runOnUiThread {
                        updateBannerEnabled = remoteUpdateBannerEnabled
                        updateBannerText = remoteUpdateBannerText
                        updateVersion = remoteUpdateVersion
                        updateApkUrl = remoteUpdateApkUrl
                        // Channel names, categories and logos are compiled into this APK.
                        // Hostinger is only a source of stream/server settings; never let a
                        // cloud response clear or replace the app's built-in channel catalogue.
                        // SAFETY: if a local server edit has not yet been confirmed by
                        // Hostinger, never replace that local list with an older cloud list.
                        // This protects the admin's edits across app restarts.
                        val hasPendingLocalSourceChanges =
                            prefs.getBoolean("pending_sources_cloud_save", false)

                        // Keep the local list if the API returns no sources, or while there
                        // are unsynchronized local edits. Once a save succeeds, normal cloud
                        // synchronization resumes for every app installation.
                        if (downloaded.isNotEmpty() && !hasPendingLocalSourceChanges) {
                            val changed = downloaded != sources
                            if (changed) {
                                sources.clear()
                                sources.addAll(downloaded)
                                saveSources()
                                refreshEmbeddedServerOptions()
                            }
                        }
                        if (showingServerManager) {
                            showServerManager()
                        } else if (onComplete == null) {
                            // During initial startup, finishInitialSourceSync() renders
                            // the home screen after server settings have been synchronized.
                            showHome()
                        }
                        onComplete?.invoke(true)
                    }
                } else throw IllegalStateException(json.optString("error", "api_error"))
            } catch (e: Exception) {
                errorMessage = e.message ?: "network_error"
                runOnUiThread {
                    if (!silent) {
                        Toast.makeText(this, "تعذر تحميل القنوات حاليًا. تحقق من اتصالك بالإنترنت وحاول مجددًا.", Toast.LENGTH_LONG).show()
                    }
                    onComplete?.invoke(false)
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
            // The local copy has already been saved by the caller. Mark it as pending
            // so a later cloud read cannot overwrite it before an admin retries saving.
            prefs.edit().putBoolean("pending_sources_cloud_save", true).apply()
            onComplete?.invoke()
            Toast.makeText(this, "حُفظت التعديلات على هذا الجهاز. أدخل مفتاح الإدارة عند التعديل التالي لإرسالها إلى جميع المستخدمين.", Toast.LENGTH_LONG).show()
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
                put("keyId", source.keyId)
                put("key", source.key)
                put("qualityUrls", source.qualityUrls)
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
                    // Only clear the pending marker after the API explicitly confirms
                    // a successful response. The server list is now shared via Hostinger.
                    prefs.edit().putBoolean("pending_sources_cloud_save", false).apply()
                    Toast.makeText(this, "تم حفظ روابط القنوات على الخادم بنجاح، وستظهر للمستخدمين بعد المزامنة.", Toast.LENGTH_LONG).show()
                    onComplete?.invoke()
                } else {
                    // Keep local edits protected from a stale cloud download.
                    prefs.edit().putBoolean("pending_sources_cloud_save", true).apply()
                    Toast.makeText(
                        this,
                        "حُفظت التعديلات على هذا الجهاز، لكن لم يؤكد الخادم حفظها. لن نستبدلها بالقائمة القديمة. تحقق من API ومفتاح الإدارة ثم أعد الحفظ.",
                        Toast.LENGTH_LONG
                    ).show()
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
