package com.hanitv.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.Button
import android.widget.Toast
import android.text.InputType
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var urlInput: EditText

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
            setPadding(16, 18, 16, 18)
        }
        root.addView(header)

        val urlLabel = TextView(this).apply {
            text = "أدخل رابط الفيديو الذي تملك حق تشغيله"
            textSize = 16f
            setTextColor(Color.rgb(120, 0, 0))
            setPadding(12, 10, 12, 6)
        }
        root.addView(urlLabel)

        urlInput = EditText(this).apply {
            hint = "https://example.com/video.m3u8"
            textSize = 14f
            
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(
            urlInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(10, 0, 10, 8)
            }
        )

        val playButton = Button(this).apply {
            text = "تشغيل الفيديو"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(170, 0, 0))
            setOnClickListener {
                playVideo(urlInput.text.toString().trim())
            }
        }

        root.addView(
            playButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(10, 0, 10, 8)
            }
        )

        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
        }

        root.addView(
            playerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(230)
            ).apply {
                setMargins(0, 0, 0, 8)
            }
        )

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 12)
        }

        categories.forEach { category ->
            val item = TextView(this).apply {
                text = category
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(140, 0, 0))
                setBackgroundColor(Color.WHITE)
                setPadding(12, 18, 12, 18)
            }

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
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

    private fun playVideo(url: String) {
        if (!url.startsWith("https://") &&
            !url.startsWith("http://")) {
            Toast.makeText(
                this,
                "أدخل رابط فيديو صحيحًا يبدأ بـ http أو https",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            if (player == null) {
                player = ExoPlayer.Builder(this).build()
                playerView.player = player
            }

            val mediaItemBuilder = MediaItem.Builder().setUri(url)

            if (url.substringBefore("?")
                    .lowercase()
                    .endsWith(".m3u8")) {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }

            player?.apply {
                setMediaItem(mediaItemBuilder.build())
                prepare()
                playWhenReady = true
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "تعذر تشغيل الرابط: ${e.message ?: "خطأ غير معروف"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
