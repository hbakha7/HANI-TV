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
import android.app.AlertDialog
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import com.google.firebase.analytics.FirebaseAnalytics

class MainActivity : Activity() {

    private val bg = Color.rgb(12, 12, 15)
    private val panel = Color.rgb(25, 25, 30)
    private val red = Color.rgb(210, 20, 35)
    private val white = Color.WHITE
    private val gray = Color.rgb(175, 175, 185)

    private lateinit var content: LinearLayout
    private val prefs by lazy { getSharedPreferences("hani_tv_sources", MODE_PRIVATE) }
    private val sources = mutableListOf<StreamSource>()

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
    }

    private fun showHome() {
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
        val options = sources.filter { it.channel == channelName }
        if (options.isEmpty()) {
            Toast.makeText(this, "لا توجد روابط محفوظة لهذه القناة. افتح إدارة السيرفرات لإضافة رابط.", Toast.LENGTH_LONG).show()
            return
        }
        launchPlayer(options, 0)
    }

    private fun launchPlayer(options: List<StreamSource>, selectedIndex: Int = 0) {
        val safeIndex = selectedIndex.coerceIn(0, options.lastIndex)
        val source = options[safeIndex]
        val intent = Intent().apply {
            setClassName("com.service103.iptvplayer", "com.service103.iptvplayer.MainActivity")
            putExtra("url", source.url)
            putExtra("name", source.channel)
            if (source.referer.isNotBlank()) putExtra("referer", source.referer)
            putExtra("referrer", source.referer)
            putStringArrayListExtra("server_names", ArrayList(options.map { it.displayName.ifBlank { it.serverName } }))
            putStringArrayListExtra("server_urls", ArrayList(options.map { it.url }))
            putStringArrayListExtra("server_referers", ArrayList(options.map { it.referer }))
            putExtra("selected_server_index", safeIndex)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح 103 IPTV Player. تأكد من تثبيته وأن اسم الحزمة صحيح.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showServerManager() {
        content.removeAllViews()
        addText("إدارة السيرفرات", 24f, white, true)
        addText("أضف رابطًا لكل قناة ولكل سيرفر. تُحفظ الروابط على هذا الجهاز.", 14f, gray, false)
        val add = makeButton("＋ إضافة سيرفر / رابط قناة")
        add.setOnClickListener { showAddSourceDialog() }
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
            edit.setOnClickListener { showAddSourceDialog(source, index) }
            val delete = makeButton("حذف")
            delete.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("حذف رابط ${source.channel} من ${source.serverName}؟")
                    .setPositiveButton("حذف") { _, _ -> sources.removeAt(index); saveSources(); showServerManager() }
                    .setNegativeButton("إلغاء", null).show()
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
