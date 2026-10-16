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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
            setBackgroundColor(Color.rgb(18, 18, 22))
        }

        val logo = TextView(this).apply {
            text = "HANI "
            textSize = 26f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
        }

        val logoTv = TextView(this).apply {
            text = "TV"
            textSize = 26f
            setTextColor(red)
            setTypeface(null, Typeface.BOLD)
        }

        header.addView(logo)
        header.addView(logoTv)
        val spacer = View(this)
        header.addView(
            spacer,
            LinearLayout.LayoutParams(0, 1, 1f)
        )

        val status = TextView(this).apply {
            text = "●"
            textSize = 17f
            setTextColor(red)
        }
        header.addView(status)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

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
            "وجهتك لمتابعة المحتوى المفضل لديك",
            14f, gray, false, 6
        )

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(75, 12, 22))
        }

        hero.addView(TextView(this).apply {
            text = "HANI TV"
            textSize = 32f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
        })

        hero.addView(TextView(this).apply {
            text = "عالم من الترفيه في مكان واحد"
            textSize = 17f
            setTextColor(white)
            setPadding(0, dp(8), 0, dp(14))
        })

        val explore = makeButton("استكشف الأقسام")
        explore.setOnClickListener {
            showCategories()
        }
        hero.addView(explore)

        content.addView(
            hero,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(24)
            }
        )

        addText("الأقسام الرئيسية", 21f, white, true, 4)

        val sections = listOf(
            "⚽" to "القنوات الرياضية",
            "📺" to "القنوات العربية",
            "🌍" to "القنوات الإخبارية",
            "🧸" to "قنوات الأطفال",
            "🎭" to "قنوات الترفيه",
            "🎬" to "الأفلام والمسلسلات"
        )

        sections.forEach { (emoji, title) ->
            addSectionCard(emoji, title) {
                showSection(title)
            }
        }

        addText(
            "اختر قسماً للمتابعة",
            13f, gray, false, 16
        )
    }

    private fun showCategories() {
        content.removeAllViews()
        addText("جميع الأقسام", 24f, white, true, 12)

        val sections = listOf(
            "القنوات الرياضية",
            "القنوات العربية",
            "القنوات الإخبارية",
            "قنوات الأطفال",
            "قنوات الترفيه",
            "الأفلام والمسلسلات"
        )

        sections.forEach { title ->
            addSectionCard("▶", title) {
                showSection(title)
            }
        }

        addBackButton()
    }

    private fun showSection(title: String) {
        content.removeAllViews()
        addText(title, 24f, white, true, 12)

        val message = TextView(this).apply {
            text = "سيُضاف محتوى هذا القسم عند ربط مصادر المحتوى."
            textSize = 16f
            setTextColor(gray)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(panel)
        }

        content.addView(
            message,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
        )

        addText(
            "لا توجد قنوات أو فيديوهات مضافة بعد.",
            14f, gray, false, 8
        )

        addBackButton()
    }

    private fun addSectionCard(
        emoji: String,
        title: String,
        action: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setBackgroundColor(panel)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

        val icon = TextView(this).apply {
            text = emoji
            textSize = 25f
            gravity = Gravity.CENTER
        }

        card.addView(
            icon,
            LinearLayout.LayoutParams(dp(45), dp(42))
        )

        val label = TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }

        card.addView(
            label,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

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

    private fun addText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean,
        top: Int = 0
    ) {
        val text = TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(top), 0, dp(8))
        }

        content.addView(text)
    }

    private fun makeButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(20), dp(12), dp(20), dp(12))
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
                topMargin = dp(12)
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
