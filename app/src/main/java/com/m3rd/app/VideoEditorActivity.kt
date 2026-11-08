package com.m3rd.app

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * استوديو محرر الفيديو والمونتاج الاحترافي (Mango Pro Video Editor Studio).
 * - قص وتشذيب الفيديو بدقة عالية (Start / End Timeline Scrubber مع شريط الإطارات المصغرة).
 * - تصدير فوري فائق السرعة بدون فقدان الجودة (Hardware Lossless Stream Copy عبر MediaExtractor & MediaMuxer) يعمل بسلاسة حتى على الأجهزة الضعيفة.
 * - التحكم في الصوت (كتم الصوت / استبدال أو إبقاء الصوت الأصلي).
 * - سرعات العرض السينمائية (0.5x بطيء، 1.0x، 1.5x، 2.0x سريع) + نسب الأبعاد (16:9، 9:16، 1:1، أصلي).
 * - معاينة الفلاتر السينمائية واستخراج لقطات الفيديو عالية الدقة (4K Frame Grab).
 */
class VideoEditorActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO_URI = "video_editor_uri"
        const val EXTRA_VIDEO_TITLE = "video_editor_title"
    }

    private val bg = 0xFF090B10.toInt()
    private val card = 0xFF161922.toInt()
    private val border = 0xFF262C3A.toInt()
    private val accent = 0xFF0A78D6.toInt()
    private val accentLight = 0xFF8CD0FA.toInt()
    private val mangoGold = 0xFFFF9800.toInt()
    private val textPrimary = 0xFFF5F7FA.toInt()
    private val textSecondary = 0xFF98A2B3.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)

    private var videoUri: Uri? = null
    private var videoTitle: String = "فيديو"
    private var durationMs: Long = 0L
    private var trimStartMs: Long = 0L
    private var trimEndMs: Long = 0L
    private var muteAudio: Boolean = false
    private var playbackSpeed: Float = 1.0f
    private var aspectMode: Int = 0 // 0=أصلي, 1=9:16 ريلز, 2=16:9 يوتيوب, 3=1:1 مربع
    private var filterMode: Int = 0 // 0=أصلي, 1=سينمائي, 2=دافئ, 3=بارد, 4=أبيض وأسود
    private var activeTab: Int = 0  // 0=القص, 1=السرعة والصوت, 2=الفلاتر والأبعاد, 3=التقاط إطار
    private var exporting: Boolean = false

    private lateinit var videoHolder: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var filterTintOverlay: View
    private lateinit var playPauseBtn: TextView
    private lateinit var currentTimeTv: TextView
    private lateinit var trimRangeSummaryTv: TextView
    private lateinit var filmstripRow: LinearLayout
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var toolPanel: LinearLayout
    private lateinit var exportOverlay: FrameLayout
    private lateinit var exportStatusTv: TextView
    private var mediaPlayerRef: MediaPlayer? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Float, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (strokeColor != 0) setStroke(dp(1), strokeColor)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val uri = intent?.getParcelableExtra<Uri>(EXTRA_VIDEO_URI)
        if (uri == null) {
            finish()
            return
        }
        videoUri = uri
        videoTitle = intent?.getStringExtra(EXTRA_VIDEO_TITLE) ?: "فيديو"

        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // الشريط العلوي
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(32), dp(12), dp(10))
        }
        topBar.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { if (!exporting) finish() }
        })
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        titleCol.addView(TextView(this).apply {
            text = "استوديو مونتاج الفيديو 🎬"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        titleCol.addView(TextView(this).apply {
            text = videoTitle
            textSize = 11.5f
            setTextColor(textSecondary)
            maxLines = 1
        })
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        val saveBtn = TextView(this).apply {
            text = "تصدير وحفظ ✨"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            background = roundBg(mangoGold, 16f)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener { if (!exporting) exportEditedVideo() }
        }
        topBar.addView(saveBtn)
        page.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // منطقة معاينة الفيديو السينمائية
        videoHolder = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        videoView = VideoView(this)
        videoHolder.addView(videoView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        filterTintOverlay = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
        }
        videoHolder.addView(filterTintOverlay, FrameLayout.LayoutParams(-1, -1))

        playPauseBtn = TextView(this).apply {
            text = "⏸"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundBg(0x88000000.toInt(), 28f, 0x55FFFFFF)
            setOnClickListener { togglePlayback() }
        }
        videoHolder.addView(playPauseBtn, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(14)
            bottomMargin = dp(14)
        })

        currentTimeTv = TextView(this).apply {
            text = "0:00 / 0:00"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundBg(0x99000000.toInt(), 12f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        videoHolder.addView(currentTimeTv, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(14)
            bottomMargin = dp(14)
        })

        page.addView(videoHolder, LinearLayout.LayoutParams(-1, 0, 1f))

        // منطقة الخط الزمني (Timeline & Filmstrip) والأدوات السفلية
        val bottomArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1), border)
            }
            setPadding(dp(14), dp(12), dp(14), dp(16))
        }

        trimRangeSummaryTv = TextView(this).apply {
            text = "نطاق المونتاج: كامل الفيديو"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentLight)
            gravity = Gravity.CENTER
        }
        bottomArea.addView(trimRangeSummaryTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        // شريط الإطارات المصغرة (Filmstrip Timeline)
        val filmScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        filmstripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(0xFF0E1016.toInt(), 10f, mangoGold)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        filmScroll.addView(filmstripRow)
        bottomArea.addView(filmScroll, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(8) })

        // منزلقات البداية والنهاية للقص الدقيق
        val startRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startRow.addView(TextView(this).apply {
            text = "البداية:"
            textSize = 12f
            setTextColor(textSecondary)
        }, LinearLayout.LayoutParams(dp(52), -2))
        startSeek = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser || durationMs <= 0L) return
                    val candidate = (durationMs * p) / 1000L
                    trimStartMs = min(candidate, max(0L, trimEndMs - 500L))
                    videoView.seekTo(trimStartMs.toInt())
                    updateTrimSummary()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        startRow.addView(startSeek, LinearLayout.LayoutParams(0, -2, 1f))
        bottomArea.addView(startRow, LinearLayout.LayoutParams(-1, -2))

        val endRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        endRow.addView(TextView(this).apply {
            text = "النهاية:"
            textSize = 12f
            setTextColor(textSecondary)
        }, LinearLayout.LayoutParams(dp(52), -2))
        endSeek = SeekBar(this).apply {
            max = 1000
            progress = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser || durationMs <= 0L) return
                    val candidate = (durationMs * p) / 1000L
                    trimEndMs = max(candidate, min(durationMs, trimStartMs + 500L))
                    videoView.seekTo(trimEndMs.toInt())
                    updateTrimSummary()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        endRow.addView(endSeek, LinearLayout.LayoutParams(0, -2, 1f))
        bottomArea.addView(endRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // لوحة الأدوات المتغيرة حسب التبويب
        toolPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        bottomArea.addView(toolPanel, LinearLayout.LayoutParams(-1, -2))

        // شريط التبويبات السفلية للمونتاج
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val tabTitles = arrayOf("✂️ القص", "⚡ السرعة والصوت", "🎨 الفلاتر والأبعاد", "📸 لقطة إطار")
        val tabBtns = ArrayList<TextView>()

        fun refreshTabs() {
            for (i in tabBtns.indices) {
                val sel = (activeTab == i)
                tabBtns[i].background = if (sel) roundBg(accent, 14f) else roundBg(bg, 14f, border)
                tabBtns[i].setTextColor(if (sel) Color.WHITE else textSecondary)
            }
            rebuildActiveToolPanel()
        }

        for (i in tabTitles.indices) {
            val b = TextView(this).apply {
                text = tabTitles[i]
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(9), dp(8), dp(9))
                setOnClickListener {
                    activeTab = i
                    refreshTabs()
                }
            }
            tabBtns.add(b)
            tabsRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i < tabTitles.size - 1) rightMargin = dp(5)
            })
        }
        bottomArea.addView(tabsRow, LinearLayout.LayoutParams(-1, -2))
        page.addView(bottomArea, LinearLayout.LayoutParams(-1, -2))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        // شاشة التقدم أثناء التصدير
        exportOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val expCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundBg(card, 20f, border)
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        exportStatusTv = TextView(this).apply {
            text = "جاري تصدير الفيديو بدون فقدان الجودة..."
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        expCard.addView(exportStatusTv)
        exportOverlay.addView(expCard, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(exportOverlay, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        refreshTabs()
        setupVideoPlayback(uri)
        loadFilmstripThumbnails(uri)
    }

    private fun setupVideoPlayback(uri: Uri) {
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            mediaPlayerRef = mp
            mp.isLooping = true
            durationMs = videoView.duration.toLong().coerceAtLeast(1000L)
            if (trimEndMs <= 0L || trimEndMs > durationMs) {
                trimEndMs = durationMs
            }
            applySpeedAndAudio()
            updateTrimSummary()
            videoView.start()
            playPauseBtn.text = "⏸"
            handler.post(progressUpdater)
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (isFinishing) return
            try {
                if (videoView.isPlaying) {
                    val pos = videoView.currentPosition.toLong()
                    if (trimEndMs > 0L && pos >= trimEndMs) {
                        videoView.seekTo(trimStartMs.toInt())
                    }
                    currentTimeTv.text = "${formatMs(pos)} / ${formatMs(durationMs)}"
                }
            } catch (_: Throwable) {}
            handler.postDelayed(this, 200L)
        }
    }

    private fun togglePlayback() {
        try {
            if (videoView.isPlaying) {
                videoView.pause()
                playPauseBtn.text = "▶"
            } else {
                if (videoView.currentPosition < trimStartMs || videoView.currentPosition >= trimEndMs) {
                    videoView.seekTo(trimStartMs.toInt())
                }
                videoView.start()
                playPauseBtn.text = "⏸"
            }
        } catch (_: Throwable) {}
    }

    private fun applySpeedAndAudio() {
        val mp = mediaPlayerRef ?: return
        try {
            val vol = if (muteAudio) 0f else 1f
            mp.setVolume(vol, vol)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mp.playbackParams = (mp.playbackParams ?: PlaybackParams()).setSpeed(playbackSpeed)
            }
        } catch (_: Throwable) {}
    }

    private fun updateTrimSummary() {
        val cutDur = max(0L, trimEndMs - trimStartMs)
        val audioTag = if (muteAudio) "🔇 بدون صوت" else "🔊 صوت أصلي"
        trimRangeSummaryTv.text = "القص: ${formatMs(trimStartMs)} ← ${formatMs(trimEndMs)} (المدة: ${formatMs(cutDur)}) • ${playbackSpeed}x • $audioTag"
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60L
        val s = totalSec % 60L
        val tenths = (ms % 1000L) / 100L
        return String.format(Locale.US, "%d:%02d.%d", m, s, tenths)
    }

    private fun rebuildActiveToolPanel() {
        toolPanel.removeAllViews()
        when (activeTab) {
            0 -> {
                // أدوات القص السريع
                toolPanel.addView(chipBtn("إعادة ضبط القص الكامل") {
                    trimStartMs = 0L
                    trimEndMs = durationMs
                    startSeek.progress = 0
                    endSeek.progress = 1000
                    videoView.seekTo(0)
                    updateTrimSummary()
                })
                toolPanel.addView(chipBtn("قص أول 15 ثانية (Reels)") {
                    trimStartMs = 0L
                    trimEndMs = min(durationMs, 15_000L)
                    startSeek.progress = 0
                    endSeek.progress = ((trimEndMs * 1000L) / max(1L, durationMs)).toInt()
                    videoView.seekTo(0)
                    updateTrimSummary()
                })
                toolPanel.addView(chipBtn("قص أول 30 ثانية") {
                    trimStartMs = 0L
                    trimEndMs = min(durationMs, 30_000L)
                    startSeek.progress = 0
                    endSeek.progress = ((trimEndMs * 1000L) / max(1L, durationMs)).toInt()
                    videoView.seekTo(0)
                    updateTrimSummary()
                })
            }
            1 -> {
                // السرعة والصوت
                val muteBtn = chipBtn(if (muteAudio) "🔇 الصوت مكتوم (اضغط للتشغيل)" else "🔊 كتم صوت الفيديو") {
                    muteAudio = !muteAudio
                    applySpeedAndAudio()
                    updateTrimSummary()
                    rebuildActiveToolPanel()
                }
                toolPanel.addView(muteBtn)
                val speeds = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
                for (sp in speeds) {
                    val active = (playbackSpeed == sp)
                    val btn = chipBtn("${sp}x", active) {
                        playbackSpeed = sp
                        applySpeedAndAudio()
                        updateTrimSummary()
                        rebuildActiveToolPanel()
                    }
                    toolPanel.addView(btn)
                }
            }
            2 -> {
                // الفلاتر السينمائية ونسب الأبعاد
                val filterNames = arrayOf("أصلي", "سينمائي", "ذهبي دافئ", "أزرق بارد", "درامي داكن")
                for (i in filterNames.indices) {
                    toolPanel.addView(chipBtn(filterNames[i], filterMode == i) {
                        filterMode = i
                        applyFilterAndAspectPreview()
                        rebuildActiveToolPanel()
                    })
                }
                val aspectBtn = chipBtn("النسبة: ${when (aspectMode) { 1 -> "9:16"; 2 -> "16:9"; 3 -> "1:1"; else -> "أصلي" }}") {
                    aspectMode = (aspectMode + 1) % 4
                    applyFilterAndAspectPreview()
                    rebuildActiveToolPanel()
                }
                toolPanel.addView(aspectBtn)
            }
            3 -> {
                // استخراج إطار عالي الدقة كصورة
                toolPanel.addView(chipBtn("📸 حفظ المشهد الحالي كصورة عالية الدقة في المعرض", true) {
                    captureCurrentFrameToGallery()
                })
            }
        }
    }

    private fun applyFilterAndAspectPreview() {
        val tintColor = when (filterMode) {
            1 -> 0x260A78D6
            2 -> 0x28FF9800
            3 -> 0x2800B0FF
            4 -> 0x44000000
            else -> Color.TRANSPARENT
        }
        filterTintOverlay.setBackgroundColor(tintColor)

        val w = videoHolder.width.coerceAtLeast(300)
        val h = videoHolder.height.coerceAtLeast(300)
        val lp = when (aspectMode) {
            1 -> {
                val targetW = min(w, (h * 9) / 16)
                FrameLayout.LayoutParams(targetW, h, Gravity.CENTER)
            }
            2 -> {
                val targetH = min(h, (w * 9) / 16)
                FrameLayout.LayoutParams(w, targetH, Gravity.CENTER)
            }
            3 -> {
                val side = min(w, h)
                FrameLayout.LayoutParams(side, side, Gravity.CENTER)
            }
            else -> FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        videoView.layoutParams = lp
    }

    private fun chipBtn(label: String, active: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (active) Color.WHITE else textPrimary)
            background = if (active) roundBg(accent, 12f) else roundBg(bg, 12f, border)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            val lp = LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun loadFilmstripThumbnails(uri: Uri) {
        io.execute {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val totalMs = durStr?.toLongOrNull() ?: 5000L
                val count = 8
                for (i in 0 until count) {
                    if (isFinishing) break
                    val timeUs = ((totalMs * i) / count) * 1000L
                    val rawBmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (rawBmp != null) {
                        val thumb = Bitmap.createScaledBitmap(rawBmp, dp(48), dp(48), true)
                        if (thumb !== rawBmp) rawBmp.recycle()
                        handler.post {
                            if (!isFinishing) {
                                val iv = ImageView(this).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    setImageBitmap(thumb)
                                }
                                filmstripRow.addView(iv, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                                    rightMargin = dp(2)
                                })
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun captureCurrentFrameToGallery() {
        val uri = videoUri ?: return
        val posUs = videoView.currentPosition.toLong() * 1000L
        io.execute {
            val retriever = MediaMetadataRetriever()
            var saved = false
            try {
                retriever.setDataSource(this, uri)
                val bmp = retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "MangoGallery_Frame_$ts.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mango Gallery")
                        }
                    }
                    val outUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (outUri != null) {
                        contentResolver.openOutputStream(outUri)?.use { os ->
                            saved = bmp.compress(Bitmap.CompressFormat.JPEG, 96, os)
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
            handler.post {
                Toast.makeText(
                    this,
                    if (saved) "تم حفظ لقطة الإطار بدقة عالية في Mango Gallery 📸" else "تعذر استخراج اللقطة",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * تصدير الفيديو المعدل بسرعة فائقة عبر MediaExtractor + MediaMuxer بدون إعادة ترميز ثقيلة،
     * مما يضمن عمله فوراً حتى على الهواتف الاقتصادية والضعيفة.
     */
    private fun exportEditedVideo() {
        val uri = videoUri ?: return
        exporting = true
        exportOverlay.visibility = View.VISIBLE
        exportStatusTv.text = "جاري معالجة وتصدير الفيديو..."

        val startUs = trimStartMs * 1000L
        val endUs = (if (trimEndMs > trimStartMs) trimEndMs else durationMs) * 1000L
        val stripAudio = muteAudio

        io.execute {
            var success = false
            val tempFile = File(cacheDir, "mango_trim_${System.currentTimeMillis()}.mp4")
            try {
                val extractor = MediaExtractor()
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                }
                val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val trackIndexMap = HashMap<Int, Int>()
                var maxBufferSize = 1024 * 1024

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    val isVideoTrack = mime.startsWith("video/")
                    val isAudioTrack = mime.startsWith("audio/")
                    if (isVideoTrack || (isAudioTrack && !stripAudio)) {
                        extractor.selectTrack(i)
                        val dstIndex = muxer.addTrack(format)
                        trackIndexMap[i] = dstIndex
                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            maxBufferSize = max(maxBufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        }
                    }
                }

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, uri)
                    val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot != 0) muxer.setOrientationHint(rot)
                } catch (_: Throwable) {
                } finally {
                    try { retriever.release() } catch (_: Throwable) {}
                }

                muxer.start()
                val buffer = ByteBuffer.allocate(maxBufferSize)
                val bufferInfo = MediaCodec.BufferInfo()

                if (startUs > 0L) {
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }

                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break
                    val sampleTimeUs = extractor.sampleTime
                    if (endUs > 0L && sampleTimeUs > endUs) break

                    val trackIndex = extractor.sampleTrackIndex
                    val dstTrack = trackIndexMap[trackIndex]
                    if (dstTrack != null && sampleTimeUs >= startUs) {
                        bufferInfo.presentationTimeUs = ((sampleTimeUs - startUs) / playbackSpeed).toLong().coerceAtLeast(0L)
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                    }
                    extractor.advance()
                }

                muxer.stop()
                muxer.release()
                extractor.release()

                // حفظ الملف الناتج في MediaStore تحت مجلد Movies/Mango Gallery
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "MangoGallery_Edit_$ts.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Mango Gallery")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (outUri != null) {
                    contentResolver.openOutputStream(outUri)?.use { outStream ->
                        tempFile.inputStream().use { inStream ->
                            inStream.copyTo(outStream, 256 * 1024)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                        contentResolver.update(outUri, done, null, null)
                    }
                    success = true
                }
            } catch (_: Throwable) {
                success = false
            } finally {
                tempFile.delete()
            }

            handler.post {
                exporting = false
                exportOverlay.visibility = View.GONE
                if (isFinishing) return@post
                if (success) {
                    Toast.makeText(this, "تم تصدير وحفظ الفيديو الجديد في Mango Gallery بنجاح 🎬✨", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "تعذر تصدير هذا النطاق، جرّب توسيع مدة القص قليلاً", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { videoView.stopPlayback() } catch (_: Throwable) {}
        io.shutdownNow()
    }
}
