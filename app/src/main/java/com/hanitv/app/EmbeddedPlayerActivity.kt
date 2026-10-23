package com.hanitv.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import android.util.Base64
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
data class Channel(
    val name: String,
    val url: String,
    val group: String = "عام",
    val logo: String = "",
    val type: String = "live",
    val id: String = "",
    val referer: String? = null
)

class EmbeddedPlayerActivity : AppCompatActivity() {

private val bg = Color.rgb(10, 17, 29)  
private val panel = Color.rgb(22, 32, 49)  
private val accent = Color.rgb(25, 190, 165)  
private val white = Color.WHITE  
private val muted = Color.rgb(177, 190, 207)  

private val channels = mutableListOf<Channel>()  
private var player: ExoPlayer? = null  
private var playerView: PlayerView? = null  
private var listContainer: LinearLayout? = null  
private var statusView: TextView? = null  
private var currentType = "live"  
private var currentGroup = "الكل"  
private var currentHost = ""  
private var currentUser = ""  
private var currentPass = ""  
private var fullscreenDialog: Dialog? = null  
private var directLinkPlayback = false
private var incomingServerNames = arrayListOf<String>()
private var incomingServerUrls = arrayListOf<String>()
private var incomingServerReferers = arrayListOf<String>()
private var incomingServerUserAgents = arrayListOf<String>()
private var incomingServerCookies = arrayListOf<String>()
private var incomingServerAuthorizations = arrayListOf<String>()
private var incomingSelectedServer = 0
private var incomingChannelName = ""
// Automatically rotate through the received server URLs after playback errors.
private val serverFailoverHandler = Handler(Looper.getMainLooper())
private var serverFailoverPending = false
private var serverFailoverAttempts = 0
private var serverStartupTimeout: Runnable? = null
// Prevent endless format retries: each URL gets at most one automatic HLS fallback.
private var hlsFallbackAttemptedUrl = ""
private var fullscreenView: PlayerView? = null
private var serverButtonsBar: LinearLayout? = null  
private var savedParent: ViewGroup? = null  
private var savedIndex = -1  
private var savedLayoutParams: ViewGroup.LayoutParams? = null  

private val prefs by lazy {  
    getSharedPreferences("103_player_settings", Context.MODE_PRIVATE)  
}  

override fun onCreate(savedInstanceState: Bundle?) {  
    super.onCreate(savedInstanceState)  
    window.statusBarColor = bg
    window.navigationBarColor = bg
    buildHome()
    handleExternalLink(intent)
}

override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleExternalLink(intent)
}

private fun handleExternalLink(incoming: android.content.Intent?) {
    if (incoming == null) return

    val uriLink = incoming.data?.toString().orEmpty()
    val sharedText = incoming.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty()
    val extraUrl = incoming.getStringExtra("url").orEmpty()
        .ifBlank { incoming.getStringExtra("stream_url").orEmpty() }
    val link = sequenceOf(uriLink, extraUrl, sharedText)
        .map { it.trim() }
        .firstOrNull { it.startsWith("https://", true) || it.startsWith("http://", true) }
        ?: return

    val name = incoming.getStringExtra("name")
        ?: incoming.getStringExtra("channel_name")
        ?: "فيديو مستلم"

    incomingServerNames = incoming.getStringArrayListExtra("server_names") ?: arrayListOf()
    incomingServerUrls = incoming.getStringArrayListExtra("server_urls") ?: arrayListOf()
    incomingServerReferers = incoming.getStringArrayListExtra("server_referers") ?: arrayListOf()
    incomingServerUserAgents = incoming.getStringArrayListExtra("server_user_agents") ?: arrayListOf()
    incomingServerCookies = incoming.getStringArrayListExtra("server_cookies") ?: arrayListOf()
    incomingServerAuthorizations = incoming.getStringArrayListExtra("server_authorizations") ?: arrayListOf()
    incomingSelectedServer = incoming.getIntExtra("selected_server_index", 0)
        .coerceIn(0, (incomingServerUrls.size - 1).coerceAtLeast(0))
    incomingChannelName = name
    val referer = incoming.getStringExtra("referer") ?: incoming.getStringExtra("referrer")

    // Links received from HANI TV are temporary playback links; do not save them.

    channels.removeAll { it.name == name && it.group == "مباشر" }
    channels.add(Channel(name, link, "مباشر", type = "live", referer = referer))
    window.decorView.post { playDirectFullscreen(link) }
}  

private fun dp(v: Int): Int =  
    (v * resources.displayMetrics.density).toInt()  

private fun rounded(color: Int, radius: Int = 14): GradientDrawable =  
    GradientDrawable().apply {  
        setColor(color)  
        cornerRadius = dp(radius).toFloat()  
    }  

private fun label(  
    value: String,  
    size: Float = 15f,  
    color: Int = white,  
    bold: Boolean = false  
) = TextView(this).apply {  
    text = value  
    textSize = size  
    setTextColor(color)  
    gravity = Gravity.CENTER_VERTICAL  
    if (bold) setTypeface(null, Typeface.BOLD)  
    setPadding(dp(10), dp(8), dp(10), dp(8))  
}  

private fun button(  
    value: String,  
    action: () -> Unit  
) = Button(this).apply {  
    text = value  
    isAllCaps = false  
    textSize = 14f  
    setTextColor(white)  
    background = rounded(accent)  
    setOnClickListener { action() }  
    layoutParams = LinearLayout.LayoutParams(  
        ViewGroup.LayoutParams.MATCH_PARENT, dp(48)  
    ).apply {  
        setMargins(dp(4), dp(4), dp(4), dp(4))  
    }  
}  

private fun makeColumn() = LinearLayout(this).apply {  
    orientation = LinearLayout.VERTICAL  
    setPadding(dp(12), dp(10), dp(12), dp(12))  
    setBackgroundColor(bg)  
}


private fun getSavedLinks(): JSONArray {
    return try {
        JSONArray(prefs.getString("saved_home_links", "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }
}

private fun saveHomeLink(name: String, url: String, isM3u: Boolean, referer: String = "") {
    val items = getSavedLinks()
    // Update an existing entry with the same URL instead of creating duplicates.
    val updated = JSONArray()
    var replaced = false
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        if (item.optString("url") == url) {
            if (!replaced) {
                updated.put(JSONObject().put("name", name).put("url", url).put("m3u", isM3u).put("referer", referer.trim()))
                replaced = true
            }
        } else {
            updated.put(item)
        }
    }
    if (!replaced) {
        updated.put(JSONObject().put("name", name).put("url", url).put("m3u", isM3u).put("referer", referer.trim()))
    }
    prefs.edit().putString("saved_home_links", updated.toString()).apply()
}

private fun renderSavedHomeLinks(content: FrameLayout, empty: TextView) {
    content.removeAllViews()
    val items = getSavedLinks()
    if (items.length() == 0) {
        empty.text = "HTV PLAYER"
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        return
    }

    val scroll = ScrollView(this)
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val name = item.optString("name", "رابط محفوظ").ifBlank { "رابط محفوظ" }
        val url = item.optString("url", "")
        val isM3u = item.optBoolean("m3u", false)
        if (url.isBlank()) continue

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.WHITE, 10)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (isM3u) {
                    loadM3u(url)
                } else {
                    channels.clear()
                    channels.add(Channel(name, url, "مباشر", type = "live", referer = item.optString("referer", "")))
                    currentType = "live"
                    directLinkPlayback = true
                    val directPlayerView = PlayerView(this@EmbeddedPlayerActivity).apply {
                        useController = false
                        setBackgroundColor(Color.BLACK)
                        keepScreenOn = true
                    }
                    playerView = directPlayerView
                    val directRoot = FrameLayout(this@EmbeddedPlayerActivity).apply {
                        setBackgroundColor(Color.BLACK)
                        addView(directPlayerView, FrameLayout.LayoutParams(-1, -1))
                    }
                    setContentView(directRoot)
                    directPlayerView.post { play(channels.first()) }
                }
            }
        }

        val playIcon = TextView(this).apply {
            text = "▶"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(85, 55, 15))
        }
        card.addView(playIcon, LinearLayout.LayoutParams(dp(48), dp(64)))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        details.addView(TextView(this@EmbeddedPlayerActivity).apply {
            text = name
            textSize = 16f
            setTextColor(Color.BLACK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        details.addView(TextView(this@EmbeddedPlayerActivity).apply {
            text = url
            textSize = 11f
            setTextColor(Color.GRAY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        card.addView(details, LinearLayout.LayoutParams(0, dp(64), 1f))
        card.addView(TextView(this).apply {
            text = if (isM3u) "M3U" else url.substringAfterLast('.', "").uppercase().take(4).ifBlank { "TS" }
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = rounded(Color.BLACK, 5)
        })
        list.addView(card, LinearLayout.LayoutParams(-1, dp(82)).apply {
            setMargins(0, 0, 0, dp(10))
        })
        card.setOnLongClickListener {
            AlertDialog.Builder(this@EmbeddedPlayerActivity)
                .setTitle(name)
                .setItems(arrayOf("تشغيل", "حذف من الرئيسية")) { _, choice ->
                    if (choice == 0) card.performClick() else {
                        val current = getSavedLinks()
                        val kept = JSONArray()
                        for (j in 0 until current.length()) {
                            val saved = current.optJSONObject(j) ?: continue
                            if (saved.optString("url") != url) kept.put(saved)
                        }
                        prefs.edit().putString("saved_home_links", kept.toString()).apply()
                        renderSavedHomeLinks(content, empty)
                    }
                }.show()
            true
        }
    }
    scroll.addView(list)
    content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
}

private fun buildHome() {
val root = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundColor(Color.rgb(8, 20, 35))
}

val header = LinearLayout(this).apply {  
    orientation = LinearLayout.HORIZONTAL  
    gravity = Gravity.CENTER_VERTICAL  
    setPadding(dp(12), dp(8), dp(12), dp(8))  
    setBackgroundColor(Color.rgb(15, 42, 67))  
}  

val menu = TextView(this).apply {  
    text = "☰"  
    textSize = 28f  
    gravity = Gravity.CENTER  
    setTextColor(Color.WHITE)  
    setPadding(dp(8), dp(4), dp(16), dp(4))  
}  

val title = TextView(this).apply {  
    text = "HTV PLAYER"  
    textSize = 20f  
    gravity = Gravity.CENTER  
    setTextColor(Color.WHITE)  
}  

val search = TextView(this).apply {  
    text = "⌕"  
    textSize = 30f  
    gravity = Gravity.CENTER  
    setTextColor(Color.WHITE)  
    setPadding(dp(16), dp(4), dp(8), dp(4))  
}  

header.addView(menu)  
header.addView(  
    title,  
    LinearLayout.LayoutParams(0, dp(48), 1f)  
)  
header.addView(search)  
root.addView(header)  

val content = FrameLayout(this).apply {  
    setBackgroundColor(Color.rgb(8, 25, 42))  
}  

val empty = TextView(this).apply {  
    text = "HTV PLAYER"  
    textSize = 24f  
    gravity = Gravity.CENTER  
    setTextColor(Color.rgb(25, 190, 165))  
}  

renderSavedHomeLinks(content, empty)  

root.addView(  
    content,  
    LinearLayout.LayoutParams(-1, 0, 1f)  
)  

val navigation = LinearLayout(this).apply {  
    orientation = LinearLayout.HORIZONTAL  
    gravity = Gravity.CENTER  
    setBackgroundColor(Color.rgb(12, 35, 55))  
}  

fun navItem(symbol: String, caption: String, action: () -> Unit) {  
    val item = LinearLayout(this).apply {  
        orientation = LinearLayout.VERTICAL  
        gravity = Gravity.CENTER  
        setPadding(0, dp(8), 0, dp(8))  
        isClickable = true  
        isFocusable = true  
        setOnClickListener { action() }  
    }  

    val icon = TextView(this).apply {  
        text = symbol  
        textSize = 25f  
        gravity = Gravity.CENTER  
        setTextColor(Color.WHITE)  
    }  

    val text = TextView(this).apply {  
        text = caption  
        textSize = 13f  
        gravity = Gravity.CENTER  
        setTextColor(Color.WHITE)  
    }  

    item.addView(icon)  
    item.addView(text)  

    navigation.addView(  
        item,  
        LinearLayout.LayoutParams(0, dp(66), 1f)  
    )  
}  

navItem("◉", "الرئيسية") {
    renderSavedHomeLinks(content, empty)
}  

navItem("▶", "الوسائط المحلية") {  
    val intent = android.content.Intent(  
        android.content.Intent.ACTION_GET_CONTENT  
    ).apply {  
        type = "video/*"  
    }  

    try {  
        startActivityForResult(intent, 103)  
    } catch (_: Exception) {  
        message("تعذر فتح ملفات الفيديو")  
    }  
}  

navItem("+", "إضافة") {  
    showAddLinkForm()  
}  

root.addView(navigation)  
setContentView(root)  

menu.setOnClickListener {  
    AlertDialog.Builder(this)  
        .setTitle("HTV PLAYER")  
        .setItems(
            arrayOf(
                "الرئيسية",
                "إضافة رابط",
                "الروابط المحفوظة",
                "About HTV PLAYER",
                "Privacy Policy"
            )
        ) { _, which ->
            when (which) {
                0 -> renderSavedHomeLinks(content, empty)
                1 -> showAddLinkForm()
                2 -> showSaved()
                3 -> showAbout()
                4 -> showPrivacyPolicy()
            }
        }  
        .show()  
}  

search.setOnClickListener {  
    val input = EditText(this).apply {  
        hint = "ابحث عن قناة أو محتوى"  
        setTextColor(Color.WHITE)  
        setHintTextColor(Color.LTGRAY)  
    }  

    AlertDialog.Builder(this)  
        .setTitle("بحث")  
        .setView(input)  
        .setPositiveButton("بحث") { _, _ ->  
            val query = input.text.toString().trim()  
            val found = channels.filter {  
                it.name.contains(query, ignoreCase = true)  
            }  

            if (found.isEmpty()) {  
                message("لا توجد نتائج مطابقة")  
            } else {  
                AlertDialog.Builder(this)  
                    .setTitle("نتائج البحث")  
                    .setItems(found.map { it.name }.toTypedArray()) { _, i ->  
                        play(found[i])  
                        openFullscreen()  
                    }  
                    .setNegativeButton("إغلاق", null)  
                    .show()  
            }  
        }  
        .setNegativeButton("إلغاء", null)  
        .show()  
}

}

private fun showAddLinkForm() {
val root = makeColumn()
val scroll = ScrollView(this)
val form = makeColumn()

form.addView(  
    label("إضافة رابط جديد", 22f, accent, true).apply {  
        gravity = Gravity.CENTER  
    }  
)  

fun inputField(hintText: String, uri: Boolean = false): EditText {  
    return EditText(this).apply {  
        hint = hintText  
        setTextColor(Color.WHITE)  
        setHintTextColor(Color.LTGRAY)  
        textSize = 16f  
        setSingleLine(true)  
        inputType = if (uri) {  
            InputType.TYPE_CLASS_TEXT or  
                InputType.TYPE_TEXT_VARIATION_URI  
        } else {  
            InputType.TYPE_CLASS_TEXT  
        }  
        setPadding(dp(14), dp(12), dp(14), dp(12))  
        background = rounded(Color.rgb(12, 30, 47), 5)  
    }  
}  

fun addField(field: EditText) {  
    form.addView(  
        field,  
        LinearLayout.LayoutParams(-1, dp(66)).apply {  
            setMargins(0, dp(5), 0, dp(14))  
        }  
    )  
}  

val nameInput = inputField("Name")  
val urlInput = inputField(  
    "Url Link (M3U,M3U8,TS,MP4...)",  
    true  
)  
val userAgentInput = inputField("User-Agent")  
val referrerInput = inputField("Referrer")  
val authInput = inputField("Authentication")  
val cookieInput = inputField("Cookie (name=joe;app=xpola;id=value ...)")  
val licenseInput = inputField("License")

// Restore the last entered values so all headers stay together for the next playback.
nameInput.setText(prefs.getString("add_name", ""))
urlInput.setText(prefs.getString("add_url", ""))
userAgentInput.setText(prefs.getString("add_user_agent", ""))
referrerInput.setText(prefs.getString("add_referrer", ""))
authInput.setText(prefs.getString("add_auth", ""))
cookieInput.setText(prefs.getString("add_cookie", ""))
licenseInput.setText(prefs.getString("add_license", ""))

addField(nameInput)  
addField(urlInput)  
addField(userAgentInput)  
addField(referrerInput)  
addField(authInput)  
addField(cookieInput)  
addField(licenseInput)  

form.addView(label("Scheme", 17f, white))  

val scheme = Spinner(this).apply {  
    adapter = ArrayAdapter(  
        this@EmbeddedPlayerActivity,  
        android.R.layout.simple_spinner_dropdown_item,  
        listOf("الرابط كما هو", "HTTP", "HTTPS")  
    )  
    background = rounded(Color.rgb(12, 30, 47), 5)  
}  

form.addView(  
    scheme,  
    LinearLayout.LayoutParams(-1, dp(54)).apply {  
        setMargins(0, dp(5), 0, dp(12))  
    }  
)  

form.addView(label("DRM Scheme", 17f, white))
val drmChoices = listOf("بدون DRM", "clearkey", "widevine", "playready")
val drmScheme = Spinner(this).apply {
    adapter = ArrayAdapter(
        this@EmbeddedPlayerActivity,
        android.R.layout.simple_spinner_dropdown_item,
        drmChoices
    )
    background = rounded(Color.rgb(12, 30, 47), 5)
    setSelection(drmChoices.indexOf(prefs.getString("add_drm_scheme", "بدون DRM")).let { if (it >= 0) it else 0 })
}
form.addView(
    drmScheme,
    LinearLayout.LayoutParams(-1, dp(54)).apply {
        setMargins(0, dp(5), 0, dp(12))
    }
)

fun option(text: String, checked: Boolean = false) =  
    CheckBox(this).apply {  
        this.text = text  
        isChecked = checked  
        setTextColor(Color.WHITE)  
        textSize = 15f  
        buttonTintList =  
            android.content.res.ColorStateList.valueOf(  
                Color.rgb(190, 235, 240)  
            )  
    }  

val m3uCheck = option("is M3u Link ?")  
val browserCheck = option("WEB BROWSER")  
val exoCheck = option("EXO PLAYER", true)  
val webPlayerCheck = option("WEB PLAYER")  

val row1 = LinearLayout(this).apply {  
    orientation = LinearLayout.HORIZONTAL  
    gravity = Gravity.CENTER_VERTICAL  
    addView(  
        m3uCheck,  
        LinearLayout.LayoutParams(0, dp(48), 1f)  
    )  
    addView(  
        browserCheck,  
        LinearLayout.LayoutParams(0, dp(48), 1f)  
    )  
}  

val row2 = LinearLayout(this).apply {  
    orientation = LinearLayout.HORIZONTAL  
    gravity = Gravity.CENTER_VERTICAL  
    addView(  
        exoCheck,  
        LinearLayout.LayoutParams(0, dp(48), 1f)  
    )  
    addView(  
        webPlayerCheck,  
        LinearLayout.LayoutParams(0, dp(48), 1f)  
    )  
}  

exoCheck.setOnCheckedChangeListener { _, checked ->  
    if (checked) webPlayerCheck.isChecked = false  
}  

webPlayerCheck.setOnCheckedChangeListener { _, checked ->  
    if (checked) exoCheck.isChecked = false  
}  

form.addView(row1)  
form.addView(row2)  

statusView = label("جاهز", 14f, accent)  
form.addView(statusView)  

form.addView(button("تشغيل الرابط") {  
    val name = nameInput.text.toString().trim()
    if (name.isBlank()) {
        message("أدخل اسمًا للرابط حتى يظهر في الرئيسية")
        nameInput.requestFocus()
        return@button
    }

    var link = urlInput.text.toString().trim()  

    if (link.isBlank()) {  
        message("أدخل رابط التشغيل")  
        return@button  
    }  

    when (scheme.selectedItemPosition) {  
        1 -> if (!link.startsWith("http://") &&  
            !link.startsWith("https://")) {  
            link = "http://$link"  
        }  
        2 -> if (!link.startsWith("http://") &&  
            !link.startsWith("https://")) {  
            link = "https://$link"  
        }  
    }  

    if (!link.startsWith("http://") &&  
        !link.startsWith("https://")) {  
        message("الرابط غير صحيح")  
        return@button  
    }  

    // Save all request headers and DRM selection before any playback path is chosen.
    prefs.edit()
        .putString("add_name", name)
        .putString("add_url", link)
        .putString("add_user_agent", userAgentInput.text.toString().trim())
        .putString("add_referrer", referrerInput.text.toString().trim())
        .putString("add_auth", authInput.text.toString().trim())
        .putString("add_cookie", cookieInput.text.toString().trim())
        .putString("add_license", licenseInput.text.toString().trim())
        .putString("add_drm_scheme", drmScheme.selectedItem.toString())
        .apply()

    // حفظ الاسم والرابط في الرئيسية، مع تحديث الرابط بدل تكراره.
    if (!browserCheck.isChecked && !webPlayerCheck.isChecked) {
        saveHomeLink(name, link, m3uCheck.isChecked, referrerInput.text.toString())
    }

    if (browserCheck.isChecked) {  
        try {  
            startActivity(  
                android.content.Intent(  
                    android.content.Intent.ACTION_VIEW,  
                    android.net.Uri.parse(link)  
                )  
            )  
        } catch (_: Exception) {  
            message("تعذر فتح الرابط في المتصفح")  
        }  
        return@button  
    }  

    if (webPlayerCheck.isChecked) {  
        message("مشغل WEB PLAYER غير مفعّل حاليًا")  
        return@button  
    }  

    // حفظ حقول الرابط لإتاحتها عند فتح النموذج مرة أخرى  
    prefs.edit()  
        .putString("add_name", nameInput.text.toString())  
        .putString("add_url", link)  
        .putString("add_user_agent", userAgentInput.text.toString())  
        .putString("add_referrer", referrerInput.text.toString())  
        .putString("add_auth", authInput.text.toString())  
        .putString("add_cookie", cookieInput.text.toString())  
        .putString("add_license", licenseInput.text.toString())
        .putString("add_drm_scheme", drmScheme.selectedItem.toString())
        .apply()  

    if (m3uCheck.isChecked) {  
        prefs.edit().putString("last_m3u", link).apply()  
        loadM3u(link)  
    } else {  
        channels.clear()  
        channels.add(  
            Channel(  
                name = name,  
                url = link,  
                group = "مباشر",  
                type = "live",  
                referer = referrerInput.text.toString().trim()  
            )  
        )  

        currentType = "live"
        currentGroup = "الكل"

        // تجاوز صفحة المكتبة تمامًا: افتح المشغل مباشرة بعد الضغط على "تشغيل الرابط".
        directLinkPlayback = true
        val directPlayerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        playerView = directPlayerView
        val directRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(directPlayerView, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(directRoot)

        playerView?.post {
            channels.firstOrNull()?.let { channel ->
                play(channel)
            }
        }
    }  
})  

form.addView(button("رجوع للرئيسية") {  
    buildHome()  
})  

scroll.addView(form)  
root.addView(  
    scroll,  
    LinearLayout.LayoutParams(-1, 0, 1f)  
)  
setContentView(root)

}

private fun askM3u() {  
    val input = EditText(this).apply {  
        hint = "https://example.com/list.m3u"  
        setTextColor(white)  
        setHintTextColor(muted)  
        inputType = InputType.TYPE_CLASS_TEXT or  
            InputType.TYPE_TEXT_VARIATION_URI  
    }  

    val saved = prefs.getString("last_m3u", "")  
    input.setText(saved)  

    AlertDialog.Builder(this)  
        .setTitle("إضافة قائمة M3U")  
        .setView(input)  
        .setNegativeButton("إلغاء", null)  
        .setPositiveButton("تحميل") { _, _ ->  
            val link = input.text.toString().trim()  
            if (link.startsWith("http://") ||  
                link.startsWith("https://")) {  
                prefs.edit().putString("last_m3u", link).apply()  
                loadM3u(link)  
            } else {  
                message("أدخل رابطًا صحيحًا يبدأ بـ http أو https")  
            }  
        }.show()  
}

private fun askDirect() {
val input = EditText(this).apply {
hint = "رابط فيديو MP4 أو M3U8"
setTextColor(white)
setHintTextColor(muted)
inputType = InputType.TYPE_CLASS_TEXT or
InputType.TYPE_TEXT_VARIATION_URI
}

AlertDialog.Builder(this)  
    .setTitle("تشغيل رابط مباشر")  
    .setView(input)  
    .setNegativeButton("إلغاء", null)  
    .setPositiveButton("تشغيل") { _, _ ->  
        val link = input.text.toString().trim()  

        if (link.startsWith("http://") ||  
            link.startsWith("https://")) {  

            channels.clear()  
            channels.add(  
                Channel("فيديو مباشر", link, "مباشر")  
            )  

            currentType = "live"

playDirectFullscreen(link)

} else {  
            message("الرابط غير صحيح")  
        }  
    }  
    .show()

}

private fun playDirectFullscreen(link: String) {
    
val channel = channels.firstOrNull { it.url == link }
    ?: Channel(
        name = incomingChannelName.ifBlank { "فيديو مباشر" },
        url = link,
        group = "مباشر",
        type = "live",
        referer = incomingServerReferers
            .getOrNull(incomingSelectedServer)
            ?.takeIf { it.isNotBlank() }
            ?: ""
    )
    

    currentType = "live"
    directLinkPlayback = true
    serverFailoverHandler.removeCallbacksAndMessages(null)
    serverStartupTimeout = null
    serverFailoverPending = false
    serverFailoverAttempts = 0

    // Create only a black player surface; do not open the library/home screen.
    val directPlayerView = PlayerView(this).apply {
        useController = false
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
    }
    playerView = directPlayerView
    val directRoot = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        addView(directPlayerView, FrameLayout.LayoutParams(-1, -1))
    }
    setContentView(directRoot)

    directPlayerView.post {
        play(channel)
        openFullscreen()
    }
}

private fun askXtream() {  
    val layout = makeColumn()  

    val host = EditText(this).apply {  
        hint = "Server URL مثل http://server:8080"  
        setTextColor(white)  
        setHintTextColor(muted)  
        inputType = InputType.TYPE_CLASS_TEXT or  
            InputType.TYPE_TEXT_VARIATION_URI  
    }  

    val user = EditText(this).apply {  
        hint = "اسم المستخدم"  
        setTextColor(white)  
        setHintTextColor(muted)  
    }  

    val pass = EditText(this).apply {  
        hint = "كلمة المرور"  
        setTextColor(white)  
        setHintTextColor(muted)  
        inputType = InputType.TYPE_CLASS_TEXT or  
            InputType.TYPE_TEXT_VARIATION_PASSWORD  
    }  

    host.setText(prefs.getString("xt_host", ""))  
    user.setText(prefs.getString("xt_user", ""))  

    layout.addView(host)  
    layout.addView(user)  
    layout.addView(pass)  

    AlertDialog.Builder(this)  
        .setTitle("اتصال Xtream Codes")  
        .setView(layout)  
        .setNegativeButton("إلغاء", null)  
        .setPositiveButton("اتصال وحفظ") { _, _ ->  
            val h = host.text.toString().trim().trimEnd('/')  
            val u = user.text.toString().trim()  
            val p = pass.text.toString()  

            if (!(h.startsWith("http://") ||  
                h.startsWith("https://")) ||  
                u.isBlank() || p.isBlank()) {  
                message("تحقق من الخادم واسم المستخدم وكلمة المرور")  
                return@setPositiveButton  
            }  

            currentHost = h  
            currentUser = u  
            currentPass = p  

            prefs.edit()  
                .putString("xt_host", h)  
                .putString("xt_user", u)  
                .putString("xt_pass", p)  
                .apply()  

            loadXtream("live")  
        }.show()  
}  

private fun showSaved() {  
    val options = arrayOf(  
        "آخر قائمة M3U",  
        "حساب Xtream المحفوظ"  
    )  

    AlertDialog.Builder(this)  
        .setTitle("المحفوظات")  
        .setItems(options) { _, which ->  
            when (which) {  
                0 -> {  
                    val link = prefs.getString("last_m3u", "")  
                    if (!link.isNullOrBlank()) loadM3u(link)  
                    else message("لا توجد قائمة محفوظة")  
                }  
                1 -> {  
                    val h = prefs.getString("xt_host", "") ?: ""  
                    val u = prefs.getString("xt_user", "") ?: ""  
                    val p = prefs.getString("xt_pass", "") ?: ""  
                    if (h.isBlank() || u.isBlank() || p.isBlank()) {  
                        message("لا يوجد حساب Xtream محفوظ")  
                    } else {  
                        currentHost = h  
                        currentUser = u  
                        currentPass = p  
                        loadXtream("live")  
                    }  
                }  
            }  
        }.show()  
}  

private fun loadM3u(link: String) {  
    setStatus("جارٍ تحميل القائمة...")  

    lifecycleScope.launch {  
        try {  
            val data = withContext(Dispatchers.IO) {  
                val conn = URL(link).openConnection()  
                    as HttpURLConnection  
                conn.connectTimeout = 15000  
                conn.readTimeout = 20000  
                conn.setRequestProperty(  
                    "User-Agent", "103IPTVPlayer/1.0"  
                )  
                try {  
                    if (conn.responseCode !in 200..299) {  
                        error("HTTP ${conn.responseCode}")  
                    }  
                    conn.inputStream.bufferedReader().use {  
                        it.readText()  
                    }  
                } finally {  
                    conn.disconnect()  
                }  
            }  

            val parsed = parseM3u(data)  
            if (parsed.isEmpty()) {  
                setStatus("لم نجد روابط صالحة في القائمة")  
                return@launch  
            }  

            channels.clear()  
            channels.addAll(parsed)  
            currentType = "live"  
            showLibrary()  
            setStatus("تم تحميل ${parsed.size} رابطًا")  
        } catch (e: Exception) {  
            setStatus("فشل تحميل القائمة")  
            message("تعذر تحميل القائمة:\n${e.message}")  
        }  
    }  
}  

private fun parseM3u(data: String): List<Channel> {  
    val out = mutableListOf<Channel>()  
    var name = "قناة"  
    var group = "عام"  
    var logo = ""  

    data.lineSequence().forEach { raw ->  
        val line = raw.trim()  

        if (line.startsWith("#EXTINF", true)) {  
            name = line.substringAfter(",", "قناة")  
                .trim().ifBlank { "قناة" }  

            group = Regex(  
                """group-title=["']([^"']+)["']""",  
                RegexOption.IGNORE_CASE  
            ).find(line)?.groupValues?.get(1) ?: "عام"  

            logo = Regex(  
                """tvg-logo=["']([^"']+)["']""",  
                RegexOption.IGNORE_CASE  
            ).find(line)?.groupValues?.get(1) ?: ""  
        } else if (  
            line.startsWith("http://") ||  
            line.startsWith("https://")  
        ) {  
            out.add(  
                Channel(name, line, group, logo, "live")  
            )  
            name = "قناة"  
            group = "عام"  
            logo = ""  
        }  
    }  

    return out  
}  

private fun loadXtream(type: String) {  
    currentType = type  
    setStatus("جارٍ الاتصال بخادم Xtream...")  

    lifecycleScope.launch {  
        try {  
            val safeU = enc(currentUser)  
            val safeP = enc(currentPass)  
            val action = when (type) {  
                "live" -> "get_live_streams"  
                "movie" -> "get_vod_streams"  
                else -> "get_series"  
            }  

            val endpoint =  
                "$currentHost/player_api.php" +  
                "?username=$safeU&password=$safeP&action=$action"  

            val data = withContext(Dispatchers.IO) {  
                val conn = URL(endpoint).openConnection()  
                    as HttpURLConnection  
                conn.connectTimeout = 15000  
                conn.readTimeout = 25000  
                conn.setRequestProperty(  
                    "User-Agent", "103IPTVPlayer/1.0"  
                )  
                try {  
                    if (conn.responseCode !in 200..299) {  
                        error("HTTP ${conn.responseCode}")  
                    }  
                    conn.inputStream.bufferedReader().use {  
                        it.readText()  
                    }  
                } finally {  
                    conn.disconnect()  
                }  
            }  

            val arr = JSONArray(data)  
            val parsed = mutableListOf<Channel>()  

            for (i in 0 until arr.length()) {  
                val obj = arr.optJSONObject(i) ?: continue  
                val id = obj.optString(  
                    if (type == "series") "series_id"  
                    else if (type == "movie") "stream_id"  
                    else "stream_id"  
                )  

                if (id.isBlank() || id == "null") continue  

                val name = obj.optString("name", "محتوى")  
                val group = obj.optString(  
                    "category_name",  
                    if (type == "live") "قنوات مباشرة"  
                    else if (type == "movie") "أفلام"  
                    else "مسلسلات"  
                )  

                val logo = obj.optString(  
                    if (type == "live") "stream_icon"  
                    else if (type == "movie") "stream_icon"  
                    else "cover"  
                ).let { if (it == "null") "" else it }  

                val ext = obj.optString(  
                    "container_extension", "ts"  
                ).ifBlank {  
                    if (type == "movie") "mp4" else "ts"  
                }  

                val url = when (type) {  
                    "live" ->  
                        "$currentHost/live/$safeU/$safeP/$id.$ext"  
                    "movie" ->  
                        "$currentHost/movie/$safeU/$safeP/$id.$ext"  
                    else -> ""  
                }  

                parsed.add(  
                    Channel(name, url, group, logo, type, id)  
                )  
            }  

            if (parsed.isEmpty()) {  
                setStatus("لم يرجع الخادم أي عناصر")  
                message(  
                    "لم يتم العثور على محتوى. تحقق من بيانات الحساب وصلاحياته."  
                )  
                return@launch  
            }  

            channels.clear()  
            channels.addAll(parsed)  
            showLibrary()  
            setStatus("تم تحميل ${parsed.size} عنصرًا")  
        } catch (e: Exception) {  
            setStatus("تعذر الاتصال")  
            message(  
                "فشل اتصال Xtream:\n" +  
                (e.message ?: "تحقق من الخادم والبيانات")  
            )  
        }  
    }  
}  

private fun enc(value: String) =  
    URLEncoder.encode(value, "UTF-8")

private fun getPlaybackHeaders(refererOverride: String? = null): Map<String, String> {
    val headers = mutableMapOf<String, String>()

    val sourceUserAgent = incomingServerUserAgents.getOrNull(incomingSelectedServer).orEmpty().trim()
    val sourceCookie = incomingServerCookies.getOrNull(incomingSelectedServer).orEmpty().trim()
    val sourceAuthorization = incomingServerAuthorizations.getOrNull(incomingSelectedServer).orEmpty().trim()

    (sourceUserAgent.ifBlank { prefs.getString("add_user_agent", "").orEmpty().trim() })
        .takeIf { it.isNotEmpty() }
        ?.let { headers["User-Agent"] = it }

    (refererOverride?.trim()?.takeIf { it.isNotEmpty() }
        ?: prefs.getString("add_referrer", "").orEmpty().trim())
        .takeIf { it.isNotEmpty() }
        ?.let { headers["Referer"] = it }

    (sourceCookie.ifBlank { prefs.getString("add_cookie", "").orEmpty().trim() })
        .takeIf { it.isNotEmpty() }
        ?.let { headers["Cookie"] = it }

    val auth = sourceAuthorization.ifBlank { prefs.getString("add_auth", "").orEmpty().trim() }
    if (auth.isNotEmpty()) {
        headers["Authorization"] = when {
            auth.startsWith("Basic ", true) || auth.startsWith("Bearer ", true) -> auth
            auth.contains(":") -> "Basic " + Base64.encodeToString(auth.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            else -> auth
        }
    }
    return headers
}



private fun createPlaybackPlayer(refererOverride: String? = null): ExoPlayer {
    val headers = getPlaybackHeaders(refererOverride).toMutableMap()

    val userAgent = headers["User-Agent"]
        ?.takeIf { it.isNotBlank() }

    if (userAgent == null) {
        headers.remove("User-Agent")
    }

    val referrer = refererOverride?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: prefs.getString("add_referrer", "").orEmpty().trim()

headers.remove("Referer")
if (referrer.isNotBlank()) {
    headers["Referer"] = referrer
}

    val httpFactory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(headers)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(6000)
        .setReadTimeoutMs(12000)

    if (userAgent != null) {
        httpFactory.setUserAgent(userAgent)
    }

    return ExoPlayer.Builder(this)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpFactory)
        )
        .build()
        .also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(
                    error: PlaybackException
                ) {
                    // Some providers serve HLS behind opaque/non-standard paths such as .css,
                    // .php or index endpoints. First let Media3 try its normal detection; only
                    // if its progressive extractor cannot recognize the response, retry once as HLS.
                    val failedItem = exo.currentMediaItem
                    val failedUrl = failedItem?.localConfiguration?.uri?.toString().orEmpty()
                    val unrecognizedFormat = generateSequence<Throwable>(error) { it.cause }
                        .any { it is UnrecognizedInputFormatException }
                    if (unrecognizedFormat && failedItem != null && failedUrl.isNotBlank() &&
                        failedUrl != hlsFallbackAttemptedUrl &&
                        failedItem.localConfiguration?.mimeType != MimeTypes.APPLICATION_M3U8 &&
                        shouldTryHlsFallback(failedUrl)) {
                        hlsFallbackAttemptedUrl = failedUrl
                        val hlsItem = failedItem.buildUpon()
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .build()
                        exo.setMediaItem(hlsItem)
                        exo.prepare()
                        exo.playWhenReady = true
                        setStatus("تجربة صيغة بث بديلة…")
                        return
                    }

                    val details = generateSequence(error.cause) {
                        it.cause
                    }.filterIsInstance<
                        androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                    >().firstOrNull()

                    val status = details?.responseCode
                    val causeDetails = generateSequence<Throwable>(error) { it.cause }
                        .take(6)
                        .joinToString("\n") { cause ->
                            "${cause.javaClass.simpleName}: ${cause.message ?: "بدون تفاصيل"}"
                        }
                    val responseHeaders = details?.headerFields
                        ?.entries
                        ?.filter { it.key != null && it.key.equals("Content-Type", true) }
                        ?.joinToString { "${it.key}: ${it.value?.joinToString()}" }
                        .orEmpty()

                    val diagnostic = buildString {
                        append("تعذر التشغيل: ${error.errorCodeName}")
                        if (status != null) append("\nHTTP: $status")
                        if (responseHeaders.isNotBlank()) append("\n$responseHeaders")
                        append("\n\nتفاصيل الخطأ:\n$causeDetails")
                        if (error.errorCodeName.contains("PARSING_CONTAINER", true)) {
                            append("\n\nهذا الخطأ لا يثبت أن Referer وحده هو السبب. قد تكون استجابة الخادم غير فيديو أو أن صيغة البث غير مدعومة.")
                        }
                    }
                    // A failed server (including HTTP 401) should not be retried
                    // on the same URL: move directly to the next supplied server.
                    // The next player's request headers are selected from that server's
                    // own Cookie, Authorization, User-Agent and Referer fields.
                    if (directLinkPlayback && incomingServerUrls.size > 1) {
                        scheduleNextServerAfterFailure(exo)
                    } else {
                        message(diagnostic)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player !== exo || !directLinkPlayback || incomingServerUrls.size < 2) return
                    if (playbackState == Player.STATE_READY) {
                        serverStartupTimeout?.let { serverFailoverHandler.removeCallbacks(it) }
                        serverStartupTimeout = null
                        serverFailoverAttempts = 0
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        serverStartupTimeout?.let { serverFailoverHandler.removeCallbacks(it) }
                        val timeout = Runnable {
                            if (player === exo && exo.playbackState != Player.STATE_READY && !exo.isPlaying) {
                                scheduleNextServerAfterFailure(exo)
                            }
                        }
                        serverStartupTimeout = timeout
                        serverFailoverHandler.postDelayed(timeout, 8000L)
                    }
                }
            })
        }
}


/** Move to the next supplied server after the current player fails.
 * Keeps retrying the server list in a loop until a stream becomes playable.
 */
private fun scheduleNextServerAfterFailure(failedPlayer: ExoPlayer) {
    if (player !== failedPlayer || incomingServerUrls.size < 2 || serverFailoverPending) return

    serverFailoverPending = true
    serverFailoverAttempts++
    // Keep failover responsive: a short pause between servers, slightly longer after a full pass.
    val delayMs = if (serverFailoverAttempts % incomingServerUrls.size == 0) 900L else 250L
    serverFailoverHandler.postDelayed({
        serverFailoverPending = false
        // A manual server selection or a newer player may have replaced this failed player.
        if (player !== failedPlayer) return@postDelayed

        incomingSelectedServer = (incomingSelectedServer + 1) % incomingServerUrls.size
        val targetUrl = incomingServerUrls.getOrNull(incomingSelectedServer).orEmpty()
        if (targetUrl.isBlank()) {
            scheduleNextServerAfterFailure(failedPlayer)
            return@postDelayed
        }

        val targetReferer = incomingServerReferers.getOrNull(incomingSelectedServer).orEmpty()
        val channel = Channel(
            name = incomingChannelName.ifBlank { "بث مباشر" },
            url = targetUrl,
            group = "مباشر",
            type = "live",
            referer = targetReferer
        )
        channels.removeAll { it.name == channel.name && it.group == "مباشر" }
        channels.add(channel)
        play(channel)
        playerView?.player = player
        fullscreenView?.player = player
        // Update the selected circle to match the server being tested.
        updateServerButtonSelection()
    }, delayMs)
}

private fun updateServerButtonSelection() {
    val bar = serverButtonsBar ?: return
    for (buttonIndex in 0 until bar.childCount) {
        (bar.getChildAt(buttonIndex) as? TextView)?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                if (buttonIndex == incomingSelectedServer) Color.rgb(25, 190, 165)
                else Color.argb(135, 35, 35, 35)
            )
            setStroke(
                dp(1),
                if (buttonIndex == incomingSelectedServer) Color.WHITE else Color.LTGRAY
            )
        }
    }
}

private fun showLibrary() {  
    val root = makeColumn()  
    val scroll = ScrollView(this)  
    val content = makeColumn()  

    val title = label("HTV PLAYER", 22f, accent, true)  
    title.gravity = Gravity.CENTER  
    content.addView(title)  

    val tabs = LinearLayout(this).apply {  
        orientation = LinearLayout.HORIZONTAL  
    }  

    listOf(  
        "مباشر" to "live",  
        "أفلام" to "movie",  
        "مسلسلات" to "series"  
    ).forEach { (caption, type) ->  
        val b = Button(this).apply {  
            text = caption  
            isAllCaps = false  
            setTextColor(white)  
            background = rounded(panel)  
            setOnClickListener {  
                if (currentHost.isNotBlank()) {  
                    loadXtream(type)  
                } else {  
                    val filtered = channels.filter {  
                        it.type == type ||  
                            (type == "live" && it.type == "live")  
                    }  
                    channels.clear()  
                    channels.addAll(filtered)  
                    currentType = type  
                    renderItems()  
                }  
            }  
        }  
        tabs.addView(  
            b,  
            LinearLayout.LayoutParams(0, dp(48), 1f)  
        )  
    }  
    content.addView(tabs)  

    playerView = PlayerView(this).apply {  
        useController = true  
        setBackgroundColor(Color.BLACK)  
        layoutParams = LinearLayout.LayoutParams(-1, dp(225))  
        keepScreenOn = true  
    }  
    content.addView(playerView)  

    content.addView(button("⛶ ملء الشاشة") {  
        openFullscreen()  
    })  

    content.addView(button("الصوت: رفع / خفض") {  
        showVolume()  
    })  

    val groupLabel = label("المجموعة", 14f, muted)  
    content.addView(groupLabel)  

    val groups = channels.map { it.group }.distinct()  
    val spinner = Spinner(this)  
    spinner.adapter = ArrayAdapter(  
        this,  
        android.R.layout.simple_spinner_dropdown_item,  
        listOf("الكل") + groups  
    )  
    content.addView(spinner)  

    listContainer = LinearLayout(this).apply {  
        orientation = LinearLayout.VERTICAL  
    }  
    content.addView(listContainer)  

    spinner.onItemSelectedListener =  
        object : AdapterView.OnItemSelectedListener {  
            override fun onNothingSelected(  
                parent: AdapterView<*>?  
            ) {}  

            override fun onItemSelected(  
                parent: AdapterView<*>?,  
                view: View?,  
                position: Int,  
                id: Long  
            ) {  
                currentGroup = spinner.selectedItem.toString()  
                renderItems()  
            }  
        }  

    scroll.addView(content)  
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))  
    setContentView(root)  

    if (player == null) {  
player = createPlaybackPlayer()

}
playerView?.player = player
renderItems()

}  

private fun renderItems() {  
    val list = listContainer ?: return  
    list.removeAllViews()  

    val filtered = channels.filter {  
        currentGroup == "الكل" || it.group == currentGroup  
    }.take(200)  

    filtered.forEach { ch ->  
        val row = LinearLayout(this).apply {  
            orientation = LinearLayout.HORIZONTAL  
            gravity = Gravity.CENTER_VERTICAL  
            background = rounded(panel)  
            setPadding(dp(6), dp(4), dp(6), dp(4))  
        }  

        val icon = ImageView(this).apply {  
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))  
            setImageResource(android.R.drawable.ic_media_play)  
            scaleType = ImageView.ScaleType.CENTER_CROP  
        }  

        row.addView(icon)  

        if (ch.logo.startsWith("http")) {  
            lifecycleScope.launch {  
                val bitmap = withContext(Dispatchers.IO) {  
                    try {  
                        URL(ch.logo).openStream().use {  
                            android.graphics.BitmapFactory.decodeStream(it)  
                        }  
                    } catch (_: Exception) {  
                        null  
                    }  
                }  
                if (bitmap != null && !isFinishing) {  
                    icon.setImageBitmap(bitmap)  
                }  
            }  
        }  

        val details = LinearLayout(this).apply {  
            orientation = LinearLayout.VERTICAL  
        }  
        details.addView(label(ch.name, 14f, white, true))  
        details.addView(label(ch.group, 11f, muted))  
        row.addView(  
            details,  
            LinearLayout.LayoutParams(0, -2, 1f)  
        )  

        row.setOnClickListener {  
            if (ch.type == "series") {  
                loadEpisodes(ch)  
            } else {  
                play(ch)  
            }  
        }  

        list.addView(  
            row,  
            LinearLayout.LayoutParams(-1, dp(68)).apply {  
                setMargins(0, dp(3), 0, dp(3))  
            }  
        )  
    }  

    if (channels.size > 200) {  
        list.addView(  
            label(  
                "تظهر أول 200 نتيجة لتقليل استهلاك الذاكرة.",  
                12f, muted  
            )  
        )  
    }  
}  

private fun loadEpisodes(series: Channel) {  
    lifecycleScope.launch {  
        try {  
            val endpoint =  
                "$currentHost/player_api.php" +  
                "?username=${enc(currentUser)}" +  
                "&password=${enc(currentPass)}" +  
                "&action=get_series_info&series_id=${enc(series.id)}"  

            val data = withContext(Dispatchers.IO) {  
                URL(endpoint).openConnection().getInputStream()  
                    .bufferedReader().use { it.readText() }  
            }  

            val info = JSONObject(data)  
            val episodes = info.optJSONObject("episodes")  
                ?: JSONObject()  
            val choices = mutableListOf<Channel>()  

            val keys = episodes.keys()  
            while (keys.hasNext()) {  
                val key = keys.next()  
                val season = episodes.optJSONArray(key)  
                    ?: continue  

                for (i in 0 until season.length()) {  
                    val ep = season.optJSONObject(i) ?: continue  
                    val id = ep.optString("id")  
                    if (id.isBlank() || id == "null") continue  
                    val ext = ep.optString(  
                        "container_extension", "mp4"  
                    )  
                    val name = ep.optString(  
                        "title", "حلقة ${i + 1}"  
                    )  
                    val url =  
                        "$currentHost/series/${enc(currentUser)}" +  
                        "/${enc(currentPass)}/$id.$ext"  
                    choices.add(  
                        Channel(name, url, "الحلقات", "", "episode")  
                    )  
                }  
            }  

            if (choices.isEmpty()) {  
                message("لم يعثر الخادم على حلقات لهذا المسلسل")  
                return@launch  
            }  

            AlertDialog.Builder(this@EmbeddedPlayerActivity)  
                .setTitle(series.name)  
                .setItems(  
                    choices.map { it.name }.toTypedArray()  
                ) { _, which ->  
                    play(choices[which])  
                }  
                .setNegativeButton("إغلاق", null)  
                .show()  
        } catch (e: Exception) {  
            message("تعذر تحميل الحلقات:\n${e.message}")  
        }  
    }  
}


/**
 * Recognize HLS playlists hidden behind proxy.php?stream=<Base64 path>.
 * The URL itself may not end in .m3u8, but the decoded stream path can.
 */
private fun shouldTryHlsFallback(url: String): Boolean {
    val path = try {
        android.net.Uri.parse(url).path.orEmpty().lowercase(java.util.Locale.ROOT)
    } catch (_: Exception) {
        ""
    }
    // Do not reinterpret obvious progressive media files as HLS.
    val knownProgressiveExtensions = listOf(
        ".mp4", ".m4v", ".mkv", ".webm", ".mp3", ".aac", ".m4a", ".wav", ".flac", ".ogg"
    )
    if (knownProgressiveExtensions.any { path.endsWith(it) }) return false
    // Unknown extensions and dynamic endpoints can still return an HLS playlist.
    return true
}

private fun isHlsStreamUrl(url: String): Boolean {
    val uri = try {
        android.net.Uri.parse(url)
    } catch (_: Exception) {
        return false
    }

    val path = uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
    if (path.endsWith(".m3u8")) return true

    val encodedStream = uri.getQueryParameter("stream")?.trim().orEmpty()
    if (encodedStream.isBlank()) return false

    val decoded = sequenceOf(
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        Base64.DEFAULT
    ).mapNotNull { flags ->
        try {
            String(Base64.decode(encodedStream, flags), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }.firstOrNull { it.isNotBlank() }.orEmpty()

    return decoded.substringBefore('?').substringBefore('#')
        .lowercase(java.util.Locale.ROOT).endsWith(".m3u8")
}

private fun play(ch: Channel) {
try {
// Allow one fresh HLS fallback attempt for each new playback request.
hlsFallbackAttemptedUrl = ""
// إنشاء مشغّل جديد لضمان تطبيق رؤوس الطلبات الحالية
playerView?.player = null
player?.release()
player = createPlaybackPlayer(ch.referer)

if (playerView == null) {  
        showLibrary()  
    }  

    playerView?.player = player  

    val itemBuilder = MediaItem.Builder()
        .setUri(ch.url)
        .setMediaId(ch.name)

    // Some providers expose an HLS stream behind an URL ending in index.html
    // or another non-standard path. In that case Media3 otherwise treats it
    // as a progressive file and throws UnrecognizedInputFormatException.
    // Only apply this fallback to the specific index.html-style endpoint;
    // normal .ts/.mp4/.m3u8 URLs keep their usual detection.
    val normalizedPath = try {
        android.net.Uri.parse(ch.url).path.orEmpty().lowercase()
    } catch (_: Exception) {
        ""
    }
    if (isHlsStreamUrl(ch.url) || normalizedPath.endsWith("/index.html") || normalizedPath == "index.html") {
        itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
    }

    val drmName = prefs.getString("add_drm_scheme", "بدون DRM").orEmpty().lowercase()
    val licenseUrl = prefs.getString("add_license", "").orEmpty().trim()
    if (drmName != "بدون drm" && drmName.isNotBlank()) {
        if (licenseUrl.isBlank()) {
            message("اخترت DRM لكن خانة License فارغة. أدخل رابط خادم الترخيص.")
            return
        }
        val drmUuid: UUID = when (drmName) {
            "clearkey" -> C.CLEARKEY_UUID
            "widevine" -> C.WIDEVINE_UUID
            "playready" -> C.PLAYREADY_UUID
            else -> {
                message("نوع DRM غير معروف")
                return
            }
        }
        val licenseHeaders = getPlaybackHeaders(ch.referer)
        itemBuilder.setDrmConfiguration(
            MediaItem.DrmConfiguration.Builder(drmUuid)
                .setLicenseUri(android.net.Uri.parse(licenseUrl))
                .setLicenseRequestHeaders(licenseHeaders)
                .build()
        )
    }
    val item = itemBuilder.build()

    player?.setMediaItem(item)  
    player?.prepare()  
    player?.playWhenReady = true  

    setStatus("قيد التشغيل: ${ch.name}")
    // افتح البث المباشر بالعرض وملء الشاشة مباشرة.
    if (ch.type == "live") {
        playerView?.post { openFullscreen() }
    }
} catch (e: Exception) {  
    message("تعذر بدء التشغيل:\n${e.message}")  
}

}

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
        player = this@EmbeddedPlayerActivity.player
        // Make "ملء الصورة" the default mode whenever fullscreen playback opens.
        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        keepScreenOn = true
        setShutterBackgroundColor(Color.BLACK)
        setBackgroundColor(Color.BLACK)
    }
    frame.addView(fullPlayer, FrameLayout.LayoutParams(-1, -1))
    fullscreenView = fullPlayer

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

    // Remove the middle control-strip background completely; keep every control.
    val bottomPanel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(2), dp(12), dp(4))
        background = null
        foreground = null
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
    }

    val actionBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = null
        foreground = null
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
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
            val popup = PopupMenu(this@EmbeddedPlayerActivity, this)
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
    // Keep the control buttons directly on the video frame, not inside a full-width strip.
    frame.addView(
        actionBar,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
    )

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
        actionBar.visibility = View.VISIBLE
        serverButtonsContainer?.visibility = View.VISIBLE
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 4500)
    }
    hideControls = Runnable {
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomPanel.visibility = View.GONE
        actionBar.visibility = View.GONE
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


private fun showAbout() {
    val body = """HTV PLAYER

Version: 1.0

HTV PLAYER is a media player that lets you play video streams and playlists from sources you provide, including direct video links, M3U/M3U8 playlists, and compatible Xtream Codes services.

The app does not provide or host streaming content. You are responsible for using sources and content that you are authorized to access.

For privacy questions or support, contact: [EMAIL ADDRESS TO BE ADDED]

Read the Privacy Policy for information about locally stored settings and connections to third-party streaming providers.""".trimIndent()
    showInformationPage("About HTV PLAYER", body)
}

private fun showPrivacyPolicy() {
    val body = """PRIVACY POLICY

Last updated: October 18, 2026

1. About this policy
This policy explains how HTV PLAYER handles information when you use the app.

2. Information stored on your device
The app uses Android local preferences to remember settings and information you enter, which may include saved playlist or stream URLs, link names, request headers (such as User-Agent, Referer, Cookie, or Authorization), DRM settings, and Xtream Codes server addresses, usernames, and passwords. This information is stored locally on your device by the app. Do not enter credentials you are not authorized to use. Anyone with access to your unlocked device or app data may be able to access locally stored information.

3. Connections to streaming providers
When you load a playlist, connect to an Xtream Codes service, load channel logos, or play a stream, the app connects to the server or URL you provide. Those third-party servers may receive your IP address, connection details, requested URLs, and request headers, and may process them under their own privacy policies. HTV PLAYER does not control those third-party services.

4. Data sent to the app developer
The app code reviewed for this version does not show an app-owned account service or a developer-operated server for collecting the saved links or credentials. This statement does not cover behavior of third-party services, Android, or any SDKs included elsewhere in the app project.

5. Sharing
The app may send the request information necessary to the streaming provider you choose, as described above. Review the provider's privacy policy before connecting. Do not share your device or saved credentials with people you do not trust.

6. Security and retention
Saved settings and credentials remain on your device until you remove them in the app, clear the app's data, or uninstall the app. Local storage is not a guarantee of encryption. Use a secure device and avoid saving sensitive credentials on shared devices.

7. Children's privacy
HTV PLAYER is not designed to collect personal information from children. Parents or guardians should supervise children's use of streaming services and devices.

8. Your choices and deletion
You can remove saved links through the app's available delete option. You can clear other locally saved settings or credentials by clearing HTV PLAYER's app data in Android settings or uninstalling the app. This does not delete records held by third-party streaming providers.

9. Changes to this policy
This policy may be updated when app features or data handling change. The latest version should be published at the public privacy-policy URL provided for the app.

10. Contact
For privacy questions or requests, contact: [EMAIL ADDRESS TO BE ADDED]

Replace the email placeholder before publishing this policy publicly or submitting the app to Google Play.""".trimIndent()
    showInformationPage("Privacy Policy", body)
}

private fun showInformationPage(pageTitle: String, pageText: String) {
    val textView = TextView(this).apply {
        text = pageText
        textSize = 15f
        setTextColor(Color.WHITE)
        setLineSpacing(dp(3).toFloat(), 1.0f)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        
    }
    val scroll = ScrollView(this).apply {
        setBackgroundColor(bg)
        addView(textView, android.view.ViewGroup.LayoutParams(-1, -2))
    }
    AlertDialog.Builder(this)
        .setTitle(pageTitle)
        .setView(scroll)
        .setPositiveButton("Close", null)
        .show()
}

private fun showVolume() {  
    val audio = getSystemService(AUDIO_SERVICE) as AudioManager  
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)  
    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)  

    val seek = SeekBar(this).apply {  
        this.max = max  
        progress = current  
    }  

    AlertDialog.Builder(this)  
        .setTitle("مستوى الصوت")  
        .setView(seek)  
        .setPositiveButton("تم", null)  
        .show()  

    seek.setOnSeekBarChangeListener(  
        object : SeekBar.OnSeekBarChangeListener {  
            override fun onProgressChanged(  
                bar: SeekBar?, value: Int, fromUser: Boolean  
            ) {  
                if (fromUser) {  
                    audio.setStreamVolume(  
                        AudioManager.STREAM_MUSIC,  
                        value, 0  
                    )  
                }  
            }  
            override fun onStartTrackingTouch(bar: SeekBar?) {}  
            override fun onStopTrackingTouch(bar: SeekBar?) {}  
        }  
    )  
}  

private fun setStatus(value: String) {  
    runOnUiThread {  
        statusView?.text = value  
    }  
}  

private fun message(value: String) {  
    if (isFinishing) return  
    AlertDialog.Builder(this)  
        .setMessage(value)  
        .setPositiveButton("حسنًا", null)  
        .show()  
}  

override fun onDestroy() {  
    fullscreenDialog?.dismiss()  
    playerView?.player = null  
    fullscreenView?.player = null  
    player?.release()  
    player = null  
    super.onDestroy()  
}

}
