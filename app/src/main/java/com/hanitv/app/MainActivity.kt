package com.hanitv.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val categories = listOf(
        "القنوات الرياضية",
        "القنوات العربية",
        "القنوات الإخبارية",
        "قنوات الأطفال",
        "قنوات الترفيه",
        "الفيديوهات"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(120, 0, 0)
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 245))
        }

        val header = TextView(this).apply {
            text = "HANI TV"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(170, 0, 0))
            setPadding(16, 22, 16, 22)
        }
        root.addView(header)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 12, 10, 12)
        }

        categories.forEach { category ->
            val item = TextView(this).apply {
                text = category
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(140, 0, 0))
                setBackgroundColor(Color.WHITE)
                setPadding(12, 22, 12, 22)
            }

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10
            }

            list.addView(item, params)
        }

        scroll.addView(list)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }
}
