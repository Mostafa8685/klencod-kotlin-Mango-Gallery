package com.m3rd.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * استوديو مونتاج الفيديو الاحترافي (Samsung One UI Studio Video Editor).
 * - شريط زمني سينمائي موحد للقص باللمس (`VideoTimelineTrimView`) مع إطارات مصغرة حقيقية، ومقبضي سحب للبداية والنهاية، ومؤشر تشغيل حي (Playhead).
 * - استيراد وتحميل ملفات الترجمة الخارجية (`.srt` / `.vtt`) وعرضها متزامنة مع الفيديو مع التحكم في حجم ولون ومزامنة الترجمة.
 * - تصدير فوري فائق السرعة بدون فقدان الجودة عبر `MediaExtractor` و `MediaMuxer`.
 * - التحكم في السرعة (`0.5x` إلى `2.0x`)، وكتم/تشغيل الصوت، والفلاتر السينمائية، ونسب الأبعاد، واستخراج الإطارات عالية الدقة.
 * - تصميم خالٍ تماماً من الإيموجيات ويعتمد كلياً على أيقونات المتجهات وهوية Mango Gallery.
 */
class VideoEditorActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO_URI = "video_editor_uri"
        const val EXTRA_VIDEO_TITLE = "video_editor_title"
        private const val REQ_PICK_SUBTITLE = 5201
    }

    private val colorPureBlack = 0xFF000000.toInt()
    private val colorCardDark = 0xFF1C1C1E.toInt()
    private val colorCardElevated = 0xFF26262A.toInt()
    private val colorBorderSubtle = 0xFF2E2E34.toInt()
    private val colorSamsungBlue = 0xFF0A78D6.toInt()
    private val colorBrandIceBlue = 0xFF8CD0FA.toInt()
    private val textPrimary = 0xFFF5F5F7.toInt()
    private val textSecondary = 0xFF98989E.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)

    private var videoUri: Uri? = null
    private var videoTitle: String = "فيديو"
    private var durationMs: Long = 0L
    private var trimStartMs: Long = 0L
    private var trimEndMs: Long = 0L
    private var muteAudio: Boolean = false
    private var playbackSpeed: Float = 1.0f
    private var aspectMode: Int = 0 // 0=أصلي, 1=9:16, 2=16:9, 3=1:1
    private var filterMode: Int = 0 // 0=أصلي, 1=سينمائي, 2=دافئ, 3=بارد, 4=أبيض وأسود
    private var activeTab: Int = 0  // 0=القص, 1=السرعة والصوت, 2=الفلاتر والأبعاد, 3=الترجمة (CC)
    private var exporting: Boolean = false

    // بيانات ملف الترجمة الخارجي (SRT / VTT)
    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var subtitleFileName: String = ""
    private var subtitleOffsetMs: Long = 0L
    private var subtitleTextSizeSp: Float = 16.5f
    private var subtitleColorMode: Int = 0 // 0=أبيض، 1=أصفر سينمائي، 2=أزرق جليدي

    private lateinit var videoHolder: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var filterTintOverlay: View
    private lateinit var subtitleOverlayTv: TextView
    private lateinit var playPauseIconContainer: FrameLayout
    private lateinit var currentTimeTv: TextView
    private lateinit var totalCutDurationTv: TextView
    private lateinit var timelineTrimView: VideoTimelineTrimView
    private lateinit var toolPanelContainer: LinearLayout
    private lateinit var exportOverlay: FrameLayout
    private lateinit var exportStatusTv: TextView
    private var mediaPlayerRef: MediaPlayer? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    private fun roundBg(color: Int, radiusDp: Float, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (strokeColor != 0) setStroke(dp(1), strokeColor)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = colorPureBlack
        window.navigationBarColor = colorPureBlack

        val uri = intent?.getParcelableExtra<Uri>(EXTRA_VIDEO_URI)
        if (uri == null) {
            finish()
            return
        }
        videoUri = uri
        videoTitle = intent?.getStringExtra(EXTRA_VIDEO_TITLE) ?: "فيديو"

        val root = FrameLayout(this).apply { setBackgroundColor(colorPureBlack) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // 1. الشريط العلوي الاحترافي المطابق لمحرر سامسونج:
        // يسار: [ حفظ ] [ إرجاع ]  <--->  يمين: [ اسم الفيديو ] [ رجوع › ]
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(32), dp(14), dp(10))
            setBackgroundColor(colorPureBlack)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val saveBtn = TextView(this).apply {
            text = "حفظ الفيديو"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundBg(colorSamsungBlue, 18f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { if (!exporting) exportEditedVideo() }
        }
        topBar.addView(saveBtn)

        val revertBtn = TextView(this).apply {
            text = "إرجاع"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textSecondary)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { resetAllVideoEdits() }
        }
        topBar.addView(revertBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4) })

        val titleTv = TextView(this).apply {
            text = videoTitle
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            maxLines = 1
            setPadding(dp(10), 0, dp(10), 0)
        }
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))

        val backBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorCardDark)
                setStroke(dp(1), colorBorderSubtle)
            }
            addView(
                MainActivity.SamsungIconView(this@VideoEditorActivity, MainActivity.SamsungIconView.IconType.CHEVRON_RIGHT, Color.WHITE, 2.0f),
                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            )
            setOnClickListener { if (!exporting) finish() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(38), dp(38)))
        page.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // 2. منطقة شاشة عرض الفيديو والترجمة المتزامنة
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

        // طبقة عرض الترجمة المتزامنة (Subtitle Cue Overlay)
        subtitleOverlayTv = TextView(this).apply {
            text = ""
            textSize = subtitleTextSizeSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = roundBg(0xBB000000.toInt(), 12f)
            visibility = View.GONE
        }
        videoHolder.addView(
            subtitleOverlayTv,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(20)
                leftMargin = dp(20)
                rightMargin = dp(20)
            }
        )

        page.addView(videoHolder, LinearLayout.LayoutParams(-1, 0, 1f))

        // 3. شريط التحكم بالتشغيل والوقت والتقاط الإطار (Transport Bar)
        val transportBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(colorPureBlack)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        playPauseIconContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorSamsungBlue)
            }
            setOnClickListener { togglePlayback() }
        }
        updatePlayPauseButtonIcon(true)
        transportBar.addView(playPauseIconContainer, LinearLayout.LayoutParams(dp(38), dp(38)))

        currentTimeTv = TextView(this).apply {
            text = "0:00.0"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, dp(8), 0)
        }
        transportBar.addView(currentTimeTv)

        transportBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        totalCutDurationTv = TextView(this).apply {
            text = "المدة المحددة: 0:00.0"
            textSize = 12f
            setTextColor(colorBrandIceBlue)
            setPadding(dp(8), 0, dp(12), 0)
        }
        transportBar.addView(totalCutDurationTv)

        val snapFrameBtn = FrameLayout(this).apply {
            background = roundBg(colorCardDark, 14f, colorBorderSubtle)
            addView(
                MainActivity.SamsungIconView(this@VideoEditorActivity, MainActivity.SamsungIconView.IconType.CAPTURE_FRAME, Color.WHITE, 1.9f),
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            )
            setOnClickListener { captureCurrentFrameToGallery() }
        }
        transportBar.addView(snapFrameBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        page.addView(transportBar, LinearLayout.LayoutParams(-1, -2))

        // 4. منطقة الخط الزمني التفاعلي الموحد والأدوات السفلية (Studio Deck)
        val studioDeck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colorCardDark)
                cornerRadii = floatArrayOf(
                    dpF(24f), dpF(24f),
                    dpF(24f), dpF(24f),
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1), colorBorderSubtle)
            }
            setPadding(dp(14), dp(14), dp(14), dp(16))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // الشريط الزمني التفاعلي الموحد (VideoTimelineTrimView)
        timelineTrimView = VideoTimelineTrimView(this) { newStartRatio, newEndRatio, seekRatio, isDraggingHandle ->
            if (durationMs > 0L) {
                trimStartMs = (newStartRatio * durationMs).toLong().coerceIn(0L, max(0L, durationMs - 500L))
                trimEndMs = (newEndRatio * durationMs).toLong().coerceIn(min(durationMs, trimStartMs + 500L), durationMs)
                val targetSeekMs = (seekRatio * durationMs).toLong().coerceIn(trimStartMs, trimEndMs)
                try {
                    videoView.seekTo(targetSeekMs.toInt())
                } catch (_: Throwable) {}
                currentTimeTv.text = formatMs(targetSeekMs)
                updateTrimSummary()
            }
        }
        studioDeck.addView(timelineTrimView, LinearLayout.LayoutParams(-1, dp(68)).apply { bottomMargin = dp(12) })

        // لوحة خيارات التبويب النشط
        toolPanelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(10))
        }
        studioDeck.addView(toolPanelContainer, LinearLayout.LayoutParams(-1, -2))

        // شريط التبويبات السفلية الأربعة بأيقونات المتجهات ونصوص نظيفة بدون إيموجي
        val bottomTabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), 0)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        data class VideoEditorTabDef(val title: String, val icon: MainActivity.SamsungIconView.IconType, val index: Int)
        val tabDefs = listOf(
            VideoEditorTabDef("الترجمة CC", MainActivity.SamsungIconView.IconType.INFO_CIRCLE, 3),
            VideoEditorTabDef("الفلاتر والأبعاد", MainActivity.SamsungIconView.IconType.TAB_THREE_CIRCLES, 2),
            VideoEditorTabDef("السرعة والصوت", MainActivity.SamsungIconView.IconType.SPEAKER_ON, 1),
            VideoEditorTabDef("القص والتشذيب", MainActivity.SamsungIconView.IconType.TAB_CROP_ROTATE, 0)
        )

        fun rebuildBottomTabsAndPanel() {
            bottomTabsRow.removeAllViews()
            for (tDef in tabDefs) {
                val selected = (activeTab == tDef.index)
                val tabCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    background = if (selected) roundBg(0x280A78D6, 16f, colorSamsungBlue) else null
                    setOnClickListener {
                        activeTab = tDef.index
                        rebuildBottomTabsAndPanel()
                    }
                }
                val icColor = if (selected) colorBrandIceBlue else Color.WHITE
                tabCol.addView(
                    MainActivity.SamsungIconView(this, tDef.icon, icColor, 1.9f),
                    LinearLayout.LayoutParams(dp(20), dp(20))
                )
                tabCol.addView(TextView(this).apply {
                    text = tDef.title
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(icColor)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                bottomTabsRow.addView(tabCol, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    leftMargin = dp(3)
                    rightMargin = dp(3)
                })
            }
            rebuildActiveToolPanel()
        }

        studioDeck.addView(bottomTabsRow, LinearLayout.LayoutParams(-1, -2))
        page.addView(studioDeck, LinearLayout.LayoutParams(-1, -2))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        // طبقة التصدير
        exportOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val expCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundBg(colorCardDark, 22f, colorBorderSubtle)
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
        rebuildBottomTabsAndPanel()
        setupVideoPlayback(uri)
        loadFilmstripFrames(uri)
    }

    private fun updatePlayPauseButtonIcon(playing: Boolean) {
        playPauseIconContainer.removeAllViews()
        val ic = if (playing) MainActivity.SamsungIconView.IconType.PAUSE_SOLID else MainActivity.SamsungIconView.IconType.PLAY_SOLID
        playPauseIconContainer.addView(
            MainActivity.SamsungIconView(this, ic, Color.WHITE, 2.0f),
            FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        )
    }

    private fun resetAllVideoEdits() {
        trimStartMs = 0L
        trimEndMs = durationMs
        muteAudio = false
        playbackSpeed = 1.0f
        aspectMode = 0
        filterMode = 0
        timelineTrimView.setRange(0f, 1f, 0f)
        applySpeedAndAudio()
        applyFilterAndAspectPreview()
        updateTrimSummary()
        rebuildActiveToolPanel()
        Toast.makeText(this, "تمت استعادة إعدادات الفيديو الأصلية", Toast.LENGTH_SHORT).show()
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
            updatePlayPauseButtonIcon(true)
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
                    currentTimeTv.text = formatMs(pos)
                    if (durationMs > 0L) {
                        timelineTrimView.updatePlayhead((pos.toFloat() / durationMs).coerceIn(0f, 1f))
                    }
                    // تحديث سطر الترجمة المتزامن
                    if (subtitleCues.isNotEmpty()) {
                        val cueText = SubtitleParser.findActiveCue(subtitleCues, pos, subtitleOffsetMs)
                        if (!cueText.isNullOrBlank()) {
                            subtitleOverlayTv.text = cueText
                            subtitleOverlayTv.visibility = View.VISIBLE
                        } else {
                            subtitleOverlayTv.visibility = View.GONE
                        }
                    } else {
                        subtitleOverlayTv.visibility = View.GONE
                    }
                }
            } catch (_: Throwable) {}
            handler.postDelayed(this, 100L)
        }
    }

    private fun togglePlayback() {
        try {
            if (videoView.isPlaying) {
                videoView.pause()
                updatePlayPauseButtonIcon(false)
            } else {
                if (videoView.currentPosition < trimStartMs || videoView.currentPosition >= trimEndMs) {
                    videoView.seekTo(trimStartMs.toInt())
                }
                videoView.start()
                updatePlayPauseButtonIcon(true)
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
        val audioTag = if (muteAudio) "صوت مكتوم" else "صوت أصلي"
        totalCutDurationTv.text = "النطاق: ${formatMs(trimStartMs)} - ${formatMs(trimEndMs)} (${formatMs(cutDur)}) • ${playbackSpeed}x • $audioTag"
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60L
        val s = totalSec % 60L
        val tenths = (ms % 1000L) / 100L
        return String.format(Locale.US, "%d:%02d.%d", m, s, tenths)
    }

    private fun rebuildActiveToolPanel() {
        toolPanelContainer.removeAllViews()
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        when (activeTab) {
            0 -> {
                // أدوات القص السريع والدقيق
                row.addView(chipBtn("كامل الفيديو", trimStartMs == 0L && trimEndMs == durationMs) {
                    trimStartMs = 0L
                    trimEndMs = durationMs
                    timelineTrimView.setRange(0f, 1f, 0f)
                    videoView.seekTo(0)
                    updateTrimSummary()
                    rebuildActiveToolPanel()
                })
                row.addView(chipBtn("قص لـ ١٥ ثانية (Reels)") {
                    if (durationMs > 0L) {
                        trimStartMs = 0L
                        trimEndMs = min(durationMs, 15_000L)
                        timelineTrimView.setRange(0f, trimEndMs.toFloat() / durationMs, 0f)
                        videoView.seekTo(0)
                        updateTrimSummary()
                    }
                })
                row.addView(chipBtn("قص لـ ٣٠ ثانية") {
                    if (durationMs > 0L) {
                        trimStartMs = 0L
                        trimEndMs = min(durationMs, 30_000L)
                        timelineTrimView.setRange(0f, trimEndMs.toFloat() / durationMs, 0f)
                        videoView.seekTo(0)
                        updateTrimSummary()
                    }
                })
                row.addView(chipBtn("تعيين البداية عند المؤشر") {
                    if (durationMs > 0L) {
                        val pos = videoView.currentPosition.toLong()
                        if (pos < trimEndMs - 500L) {
                            trimStartMs = pos
                            timelineTrimView.setRange(trimStartMs.toFloat() / durationMs, trimEndMs.toFloat() / durationMs, pos.toFloat() / durationMs)
                            updateTrimSummary()
                        }
                    }
                })
                row.addView(chipBtn("تعيين النهاية عند المؤشر") {
                    if (durationMs > 0L) {
                        val pos = videoView.currentPosition.toLong()
                        if (pos > trimStartMs + 500L) {
                            trimEndMs = pos
                            timelineTrimView.setRange(trimStartMs.toFloat() / durationMs, trimEndMs.toFloat() / durationMs, pos.toFloat() / durationMs)
                            updateTrimSummary()
                        }
                    }
                })
            }
            1 -> {
                // السرعة والصوت
                row.addView(chipBtn(if (muteAudio) "الصوت: مكتوم" else "الصوت: مفعّل", muteAudio) {
                    muteAudio = !muteAudio
                    applySpeedAndAudio()
                    updateTrimSummary()
                    rebuildActiveToolPanel()
                })
                val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                for (sp in speeds) {
                    row.addView(chipBtn("سرعة ${sp}x", playbackSpeed == sp) {
                        playbackSpeed = sp
                        applySpeedAndAudio()
                        updateTrimSummary()
                        rebuildActiveToolPanel()
                    })
                }
            }
            2 -> {
                // الفلاتر والأبعاد
                val aspectNames = arrayOf("الأبعاد: أصلي", "الأبعاد: 9:16 طولي", "الأبعاد: 16:9 سينمائي", "الأبعاد: 1:1 مربع")
                row.addView(chipBtn(aspectNames[aspectMode], aspectMode != 0) {
                    aspectMode = (aspectMode + 1) % 4
                    applyFilterAndAspectPreview()
                    rebuildActiveToolPanel()
                })
                val filterNames = arrayOf("فلتر: أصلي", "سينمائي أزرق", "ذهبي دافئ", "جليدي بارد", "تباين داكن")
                for (i in filterNames.indices) {
                    row.addView(chipBtn(filterNames[i], filterMode == i && i != 0) {
                        filterMode = i
                        applyFilterAndAspectPreview()
                        rebuildActiveToolPanel()
                    })
                }
            }
            3 -> {
                // استيراد وتخصيص ملف الترجمة (SRT / VTT)
                val loadLabel = if (subtitleCues.isEmpty()) "إضافة ملف ترجمة (.SRT / .VTT)" else "ملف الترجمة: $subtitleFileName (${subtitleCues.size} سطر)"
                row.addView(chipBtn(loadLabel, true) {
                    pickSubtitleFileFromDevice()
                })
                if (subtitleCues.isNotEmpty()) {
                    row.addView(chipBtn("حجم الخط: ${subtitleTextSizeSp.toInt()}") {
                        subtitleTextSizeSp = when (subtitleTextSizeSp) {
                            14f -> 16.5f
                            16.5f -> 20f
                            20f -> 24f
                            else -> 14f
                        }
                        subtitleOverlayTv.textSize = subtitleTextSizeSp
                        rebuildActiveToolPanel()
                    })
                    val colLabel = when (subtitleColorMode) {
                        1 -> "لون الترجمة: أصفر سينمائي"
                        2 -> "لون الترجمة: أزرق جليدي"
                        else -> "لون الترجمة: أبيض ناصع"
                    }
                    row.addView(chipBtn(colLabel) {
                        subtitleColorMode = (subtitleColorMode + 1) % 3
                        subtitleOverlayTv.setTextColor(
                            when (subtitleColorMode) {
                                1 -> 0xFFFFD60A.toInt()
                                2 -> colorBrandIceBlue
                                else -> Color.WHITE
                            }
                        )
                        rebuildActiveToolPanel()
                    })
                    row.addView(chipBtn("تقديم الترجمة -0.5s") {
                        subtitleOffsetMs -= 500L
                        Toast.makeText(this, "مزامنة الترجمة: ${subtitleOffsetMs}ms", Toast.LENGTH_SHORT).show()
                    })
                    row.addView(chipBtn("تأخير الترجمة +0.5s") {
                        subtitleOffsetMs += 500L
                        Toast.makeText(this, "مزامنة الترجمة: +${subtitleOffsetMs}ms", Toast.LENGTH_SHORT).show()
                    })
                    row.addView(chipBtn("إزالة الترجمة") {
                        subtitleCues = emptyList()
                        subtitleFileName = ""
                        subtitleOverlayTv.visibility = View.GONE
                        rebuildActiveToolPanel()
                    })
                }
            }
        }

        scroll.addView(row)
        toolPanelContainer.addView(scroll, LinearLayout.LayoutParams(-1, -2))
    }

    private fun pickSubtitleFileFromDevice() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_SUBTITLE)
        } catch (_: Throwable) {
            Toast.makeText(this, "تعذر فتح منتقي ملفات الترجمة", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_SUBTITLE && resultCode == RESULT_OK && data?.data != null) {
            val subUri = data.data!!
            var name = "subtitle.srt"
            try {
                contentResolver.query(subUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
            } catch (_: Throwable) {}
            io.execute {
                val parsed = SubtitleParser.loadFromUri(this, subUri)
                handler.post {
                    if (parsed.isNotEmpty()) {
                        subtitleCues = parsed
                        subtitleFileName = name
                        subtitleOffsetMs = 0L
                        rebuildActiveToolPanel()
                        Toast.makeText(this, "تم تحميل ملف الترجمة بنجاح (${parsed.size} جملة)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "لم يتم العثور على توقيتات ترجمة صالحة في الملف (يدعم SRT و VTT)", Toast.LENGTH_LONG).show()
                    }
                }
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
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (active) Color.WHITE else textPrimary)
            background = if (active) roundBg(colorSamsungBlue, 16f) else roundBg(colorPureBlack, 16f, colorBorderSubtle)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
            setOnClickListener { onClick() }
        }

    private fun loadFilmstripFrames(uri: Uri) {
        io.execute {
            val retriever = MediaMetadataRetriever()
            val frames = ArrayList<Bitmap>()
            try {
                retriever.setDataSource(this, uri)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val totalMs = durStr?.toLongOrNull() ?: 5000L
                val count = 9
                for (i in 0 until count) {
                    if (isFinishing) break
                    val timeUs = ((totalMs * i) / count) * 1000L
                    val rawBmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (rawBmp != null) {
                        val thumb = Bitmap.createScaledBitmap(rawBmp, dp(52), dp(56), true)
                        if (thumb !== rawBmp) rawBmp.recycle()
                        frames.add(thumb)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
            handler.post {
                if (!isFinishing && frames.isNotEmpty()) {
                    timelineTrimView.setFrames(frames)
                }
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
                    if (saved) "تم حفظ لقطة الإطار بدقة عالية في Mango Gallery" else "تعذر استخراج اللقطة",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

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

                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "MangoGallery_Video_$ts.mp4")
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
                    Toast.makeText(this, "تم تصدير وحفظ الفيديو المعدل في Mango Gallery بنجاح", Toast.LENGTH_LONG).show()
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

/**
 * مكوّن الشريط الزمني التفاعلي الموحد لمحرر الفيديو (Samsung Studio Interactive Video Timeline):
 * - يعرض إطارات الفيديو المصغرة داخل مسار مستدير الحواف.
 * - يظلل المناطق المقصوصة خارج نطاق (البداية .. النهاية) بطبقة داكنة شفافة.
 * - يرسم إطاراً أزرق مضيئاً بمقبضي سحب سميكين على اليمين واليسار (Left/Right Trim Handles).
 * - يرسم مؤشر التشغيل الأبيض العمودي (Playhead) مع إمكانية التمرير المباشر باللمس.
 */
class VideoTimelineTrimView(
    context: Context,
    private val onTimelineChanged: (startRatio: Float, endRatio: Float, seekRatio: Float, isDraggingHandle: Boolean) -> Unit
) : View(context) {

    private var frames: List<Bitmap> = emptyList()
    private var startRatio = 0f
    private var endRatio = 1f
    private var playheadRatio = 0f
    private var dragMode = 0 // 0=none, 1=left handle, 2=right handle, 3=playhead scrub

    private val density = resources.displayMetrics.density
    private fun dpF(v: Float): Float = v * density

    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101218.toInt()
        style = Paint.Style.FILL
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB8000000.toInt()
        style = Paint.Style.FILL
    }
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0A78D6.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(3f)
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0A78D6.toInt()
        style = Paint.Style.FILL
    }
    private val handleGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(2.8f)
        strokeCap = Paint.Cap.ROUND
    }
    private val playheadKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8CD0FA.toInt()
        style = Paint.Style.FILL
    }

    private val trackRect = RectF()
    private val tempRect = RectF()
    private val clipPath = Path()

    fun setFrames(list: List<Bitmap>) {
        frames = list
        invalidate()
    }

    fun setRange(start: Float, end: Float, playhead: Float) {
        startRatio = start.coerceIn(0f, 0.95f)
        endRatio = end.coerceIn(startRatio + 0.05f, 1f)
        playheadRatio = playhead.coerceIn(startRatio, endRatio)
        invalidate()
    }

    fun updatePlayhead(ratio: Float) {
        if (dragMode == 0) {
            playheadRatio = ratio.coerceIn(startRatio, endRatio)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val handleW = dpF(14f)
        val padTop = dpF(6f)
        val padBottom = dpF(6f)
        val trackLeft = handleW
        val trackRight = w - handleW
        val trackW = (trackRight - trackLeft).coerceAtLeast(1f)

        trackRect.set(trackLeft, padTop, trackRight, h - padBottom)
        clipPath.reset()
        clipPath.addRoundRect(trackRect, dpF(10f), dpF(10f), Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(trackRect, trackBgPaint)

        // رسم الإطارات المصغرة على طول المسار
        if (frames.isNotEmpty()) {
            val cellW = trackW / frames.size
            for (i in frames.indices) {
                val bmp = frames[i]
                if (!bmp.isRecycled) {
                    val cl = trackLeft + i * cellW
                    tempRect.set(cl, trackRect.top, cl + cellW + 1f, trackRect.bottom)
                    canvas.drawBitmap(bmp, null, tempRect, bmpPaint)
                }
            }
        }

        val selLeft = trackLeft + startRatio * trackW
        val selRight = trackLeft + endRatio * trackW

        // تعتيم المناطق المقصوصة على اليسار واليمين
        if (selLeft > trackLeft) {
            tempRect.set(trackLeft, trackRect.top, selLeft, trackRect.bottom)
            canvas.drawRect(tempRect, dimScrimPaint)
        }
        if (selRight < trackRight) {
            tempRect.set(selRight, trackRect.top, trackRight, trackRect.bottom)
            canvas.drawRect(tempRect, dimScrimPaint)
        }
        canvas.restore()

        val selLeft2 = trackLeft + startRatio * trackW
        val selRight2 = trackLeft + endRatio * trackW

        // الإطار المحيط بمنطقة القص النشطة
        tempRect.set(selLeft2, trackRect.top, selRight2, trackRect.bottom)
        canvas.drawRoundRect(tempRect, dpF(8f), dpF(8f), activeBorderPaint)

        // مقبض السحب الأيسر
        tempRect.set(selLeft2 - handleW, trackRect.top, selLeft2 + dpF(2f), trackRect.bottom)
        canvas.drawRoundRect(tempRect, dpF(8f), dpF(8f), handleFillPaint)
        val gripCy = trackRect.centerY()
        val leftGripX = tempRect.centerX()
        canvas.drawLine(leftGripX, gripCy - dpF(8f), leftGripX, gripCy + dpF(8f), handleGripPaint)

        // مقبض السحب الأيمن
        tempRect.set(selRight2 - dpF(2f), trackRect.top, selRight2 + handleW, trackRect.bottom)
        canvas.drawRoundRect(tempRect, dpF(8f), dpF(8f), handleFillPaint)
        val rightGripX = tempRect.centerX()
        canvas.drawLine(rightGripX, gripCy - dpF(8f), rightGripX, gripCy + dpF(8f), handleGripPaint)

        // مؤشر التشغيل الحي (Playhead)
        val px = trackLeft + playheadRatio.coerceIn(startRatio, endRatio) * trackW
        canvas.drawLine(px, dpF(2f), px, h - dpF(2f), playheadPaint)
        canvas.drawCircle(px, dpF(5f), dpF(4.5f), playheadKnobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handleW = dpF(14f)
        val trackLeft = handleW
        val trackRight = width.toFloat() - handleW
        val trackW = (trackRight - trackLeft).coerceAtLeast(1f)
        val x = event.x

        val selLeft = trackLeft + startRatio * trackW
        val selRight = trackLeft + endRatio * trackW
        val hitSlop = dpF(26f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = when {
                    abs(x - selLeft) <= hitSlop -> 1
                    abs(x - selRight) <= hitSlop -> 2
                    else -> 3
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                handleTouchMove(x, trackLeft, trackW)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleTouchMove(x, trackLeft, trackW)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = 0
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouchMove(x: Float, trackLeft: Float, trackW: Float) {
        val norm = ((x - trackLeft) / trackW).coerceIn(0f, 1f)
        val minGap = 0.05f
        when (dragMode) {
            1 -> {
                startRatio = norm.coerceIn(0f, endRatio - minGap)
                playheadRatio = startRatio
                onTimelineChanged(startRatio, endRatio, playheadRatio, true)
            }
            2 -> {
                endRatio = norm.coerceIn(startRatio + minGap, 1f)
                playheadRatio = endRatio
                onTimelineChanged(startRatio, endRatio, playheadRatio, true)
            }
            3 -> {
                playheadRatio = norm.coerceIn(startRatio, endRatio)
                onTimelineChanged(startRatio, endRatio, playheadRatio, false)
            }
        }
        invalidate()
    }
}
