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

class MainActivity : Activity() {

    private val bg = Color.rgb(12, 12, 15)
    private val panel = Color.rgb(25, 25, 30)
    private val red = Color.rgb(210, 20, 35)
    private val white = Color.WHITE
    private val gray = Color.rgb(175, 175, 185)

    private lateinit var content: LinearLayout

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
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "القناة تجريبية: ${channel.first}",
                    Toast.LENGTH_SHORT
                ).show()
            }
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
