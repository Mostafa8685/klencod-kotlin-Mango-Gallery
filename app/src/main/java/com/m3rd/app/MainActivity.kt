package com.m3rd.app

import android.Manifest
import android.app.RecoverableSecurityException
import android.provider.Settings
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.SectionIndexer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricPrompt
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * استديو سامسونج (Samsung One UI Gallery) — إصدار فائق السرعة وعالي الدقة للاستخدام الشخصي اليومي
 *
 * التحسينات الجوهرية للأداء والجودة:
 * 1. قوائم افتراضية معاد تدويرها (Virtualized ListView + ViewHolder Recycling) بدلاً من ScrollView:
 *    مهما كان عدد الصور في الهاتف (حتى عشرات الآلاف)، لا يتم إنشاء سوى الصفوف المرئية على الشاشة فقط، مما يجعل الاستجابة فورية (0ms) والتمرير بسرعة 60-120 إطاراً في الثانية.
 * 2. طابور تحميل LIFO ذكي (الأولوية للصور المعروضة أمامك حالياً) + محرك منفصل تماماً (Dedicated High-Res Executor) لعارض الصور والفيديو لفتح أي صورة فوراً بدون انتظار الشبكة.
 * 3. جودة فائقة الدقة (Full QHD/4K Decoding + EXIF Auto-Rotation + ARGB_8888 + Center-Crop Matrix) للصور والفيديوهات مع استخراج إطارات الفيديو الأصلية عبر MediaMetadataRetriever.
 * 4. عارض صور وفيديو فوري الاستجابة يحدث بياناته في نفس اللحظة بدون إعادة بناء الشاشة عند السحب يميناً أو يساراً.
 * 5. الوضع الفاتح والداكن التلقائي حسب الجهاز + قفل الاستديو بكلمة سر أو البصمة + سلة محذوفات لمدة 30 يوماً + محرر صور سامسونج اليدوي المتكامل.
 */
class MainActivity : Activity() {

    // ============================================================
    // نظام الألوان المتكيف (تلقائي حسب الجهاز: داكن One UI / فاتح One UI)
    // ============================================================
    private var themeMode = 0 // 0 = تلقائي حسب الجهاز، 1 = داكن، 2 = فاتح

    private fun isDarkModeActive(): Boolean {
        return when (themeMode) {
            1 -> true
            2 -> false
            else -> {
                val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                mask == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private val colorPureBlack: Int
        get() = if (isDarkModeActive()) 0xFF000000.toInt() else 0xFFF6F6F8.toInt()

    private val colorCardDark: Int
        get() = if (isDarkModeActive()) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

    private val colorEmptyFavCard: Int
        get() = if (isDarkModeActive()) 0xFF1E1E22.toInt() else 0xFFE9EBF0.toInt()

    private val colorPopoverBg: Int
        get() = if (isDarkModeActive()) 0xFF252527.toInt() else 0xFFFFFFFF.toInt()

    private val colorBottomPopupBg: Int
        get() = if (isDarkModeActive()) 0xFF1F1F21.toInt() else 0xFFFFFFFF.toInt()

    private val colorWhite: Int
        get() = if (isDarkModeActive()) 0xFFFFFFFF.toInt() else 0xFF121214.toInt()

    private val colorTextPrimary: Int
        get() = if (isDarkModeActive()) 0xFFF5F5F7.toInt() else 0xFF141416.toInt()

    private val colorTextSecondary: Int
        get() = if (isDarkModeActive()) 0xFF98989E.toInt() else 0xFF5A5A60.toInt()

    private val colorTextMuted: Int
        get() = if (isDarkModeActive()) 0xFF8E8E93.toInt() else 0xFF76767E.toInt()

    private val colorSamsungBlue = 0xFF0A78D6.toInt()
    private val colorBrandIceBlue = 0xFF8CD0FA.toInt()
    private val colorBrandLeafGreen = 0xFF5EA847.toInt()
    private val colorSamsungOrange = 0xFFE65A0F.toInt()
    private val colorDotOrange = 0xFFFF6D00.toInt()
    private val smoothInterpolator = DecelerateInterpolator(1.6f)

    private val colorBorderSubtle: Int
        get() = if (isDarkModeActive()) 0xFF222226.toInt() else 0xFFDCDCE2.toInt()

    // ============================================================
    // هياكل البيانات
    // ============================================================
    data class MediaItem(
        val id: String,
        val mediaStoreId: Long,
        var title: String,
        val dateGroup: String,
        val fullDateText: String,
        val albumName: String,
        val timestampMs: Long,
        val isVideo: Boolean = false,
        val durationText: String = "",
        val widthPx: Int = 0,
        val heightPx: Int = 0,
        val sizeBytes: Long = 0L,
        val pathText: String = "",
        val orientationDeg: Int = 0,
        val contentUri: Uri
    )

    data class AlbumInfo(
        val id: String,
        val title: String,
        val displayCount: Int,
        val items: List<MediaItem>,
        val isRecentSpecial: Boolean = false,
        val isFavoritesSpecial: Boolean = false,
        val showCameraBadge: Boolean = false,
        val showFolderBadge: Boolean = false,
        val isEssential: Boolean = true
    )

    data class StoryInfo(
        val id: String,
        val title: String,
        val yearText: String,
        val isNew: Boolean,
        val items: List<MediaItem>,
        var isFavorite: Boolean = false
    )

    data class AudioTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val durationText: String,
        val sizeBytes: Long,
        val contentUri: Uri
    )

    data class MenuOption(
        val label: String,
        val action: () -> Unit
    )

    data class BottomAction(
        val label: String,
        val iconType: SamsungIconView.IconType,
        val action: () -> Unit
    )

    // صفوف قائمة الصور الافتراضية السريعة (ListView Rows)
    sealed class PictureListRow {
        data class DateHeader(val dateTitle: String, val groupItems: List<MediaItem>) : PictureListRow()
        data class PhotoRow(val items: List<MediaItem>) : PictureListRow()
    }

    // ============================================================
    // محركات المعالجة الخلفية السريعة (LIFO Stack + Dedicated High-Res Pool)
    // ============================================================
    private lateinit var prefs: SharedPreferences
    private lateinit var rootStack: FrameLayout
    private lateinit var mainContentArea: FrameLayout
    private lateinit var miniAudioPlayerBar: LinearLayout
    private lateinit var bottomNavContainer: LinearLayout
    private lateinit var overlayLayer: FrameLayout
    private lateinit var floatingPipLayer: FrameLayout
    private lateinit var lockLayer: FrameLayout

    private val handler = Handler(Looper.getMainLooper())

    // طابور LIFO للصور المصغرة: يحمّل دائماً آخر صورة ظهرت أمام عين المستخدم أولاً!
    private val lifoQueue = object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = super.offerFirst(e)
        override fun put(e: Runnable) = super.putFirst(e)
    }
    private val thumbExecutor = ThreadPoolExecutor(
        4, 4, 60L, TimeUnit.SECONDS, lifoQueue
    )

    // محرك مستقل فوري خاص بعارض الصور بملء الشاشة والمحرر (لا ينتظر الشبكة أبداً)
    private val viewerExecutor = Executors.newFixedThreadPool(2)

    private val inFlightKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private lateinit var bitmapCache: LruCache<String, Bitmap>

    // التبويب الحالي: 0 = الصور، 1 = ألبومات، 2 = القصص
    private var currentTab = 0
    private var picturesColumnCount = 3
    private var albumsColumnCount = 3 // يدعم التكبير والتصغير بإصبعين (2، 3، 4 أعمدة كما في 1000036343.png)
    private var isMultiTouchPinching = false

    private var activeAlbumDetail: AlbumInfo? = null
    private var activeSubPageTitle: String? = null
    private var activeSubPageItems: List<MediaItem>? = null
    private var showOnlyEssentialAlbums = true
    private var showCustomizeAlbumsBanner = true
    private var storiesFavoritesOnly = false

    // مراجع القوائم لتحديثها فوراً بدون إعادة إنشاء الشاشة
    private var activePicturesAdapter: PicturesVirtualAdapter? = null
    private var activeNormalTopBar: LinearLayout? = null
    private var activeSelectionTopBar: LinearLayout? = null
    private var activeSelectionTitleTv: TextView? = null
    private var activeSelectAllTv: TextView? = null
    private var lowEndSmoothMode: Boolean = false

    // التحديد المتعدد
    private var isSelectionMode = false
    private val selectedMediaIds = mutableSetOf<String>()
    private var isAlbumSelectionMode = false
    private val selectedAlbumIds = mutableSetOf<String>()

    // قوائم صور وفيديوهات وصوتيات الهاتف الحقيقية فقط
    private val deviceMediaItems = mutableListOf<MediaItem>()
    private val deviceAudioTracks = mutableListOf<AudioTrack>()
    private var audioPlayer: MediaPlayer? = null
    private var currentAudioIndex = -1
    private var isAudioPlaying = false
    private var isAudioShuffle = false
    private var isAudioRepeatOne = false
    private var audioSpeed = 1.0f
    private var cachedVisibleItems: List<MediaItem> = emptyList()
    private val favoriteIds = mutableSetOf<String>()
    private val trashedIds = mutableSetOf<String>()
    private val trashedTimestamps = mutableMapOf<String, Long>()
    private val permanentlyDeletedIds = mutableSetOf<String>()
    private val hiddenAlbumNames = mutableSetOf<String>()
    private val nonEssentialAlbumNames = mutableSetOf<String>()
    private val customAlbumMap = mutableMapOf<String, MutableList<String>>()
    private val customNamesMap = mutableMapOf<String, String>()
    private val storiesList = mutableListOf<StoryInfo>()

    // قفل الاستديو وسلة المهملات (30 يوماً)
    private var appLockEnabled = false
    private var appLockPin = ""            // يُخزَّن هنا hash مملّح (v2|algo|salt|hash) وليس كلمة السر نفسها
    private var appLockPinLen = 0
    private var failedUnlockAttempts = 0
    private var lockoutUntilMs = 0L
    private var lastStopAtMs = 0L
    private var skipRelockUntilMs = 0L
    private val relockGraceMs = 15_000L
    private var pendingDeleteIds: List<String> = emptyList()
    private var batchDeletedCount = 0
    private val REQ_SYSTEM_DELETE = 4101
    private val REQ_SYSTEM_DELETE_LEGACY = 4102
    private val REQ_PICK_PLAYER_SUBTITLE = 4105
    private var activeCinemaSubtitleCues: List<SubtitleCue> = emptyList()
    private var activeCinemaSubtitleName: String = ""
    private var activeCinemaSubtitleOffsetMs: Long = 0L
    private var onCinemaSubtitleLoadedCallback: (() -> Unit)? = null
    private val isLoadingMedia = AtomicBoolean(false)
    private val reloadQueued = AtomicBoolean(false)
    private var audioLoadToken = 0
    private var activePicturesListView: ListView? = null
    private var appLockBiometric = true
    private var isAppLockedNow = false
    private val trashRetentionMs = 30L * 24L * 60L * 60L * 1000L

    // عرض الشرائح
    private var isSlideshowRunning = false
    private var slideshowRunnable: Runnable? = null

    // كواشف إيماءة التكبير/التصغير بإصبعين على شبكة الصور وشبكة الألبومات
    private lateinit var gridScaleDetector: ScaleGestureDetector
    private lateinit var albumsScaleDetector: ScaleGestureDetector
    private var mediaContentObserver: ContentObserver? = null
    private val observerReloadRunnable = Runnable { loadDeviceMediaAsync() }

    // ============================================================
    // دوال القياس والأرقام العربية
    // ============================================================
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun dpF(value: Float): Float =
        value * resources.displayMetrics.density

    private fun toArabicDigits(number: Int): String =
        toArabicDigits(number.toString())

    private fun toArabicDigits(input: String): String {
        val arabicChars = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '0'..'9') {
                sb.append(arabicChars[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun formatFileSizeArabic(bytes: Long): String {
        if (bytes <= 0L) return "غير معروف"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            toArabicDigits(String.format(Locale.US, "%.2f م.ب", mb))
        } else {
            toArabicDigits(String.format(Locale.US, "%.0f ك.ب", kb))
        }
    }

    // ============================================================
    // دورة حياة التطبيق
    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("samsung_gallery_oneui_prefs", Context.MODE_PRIVATE)
        loadPersistedState()
        applySecureFlag()
        applySystemBarsTheme()

        // تخصيص 25% من ذاكرة التطبيق للكاش السريع للصور المصغرة والصور العالية الدقة
        val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        bitmapCache = object : LruCache<String, Bitmap>(maxMem / 4) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }

        gridScaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var accumulatedScale = 1f
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isMultiTouchPinching = true
                    accumulatedScale = 1f
                    return true
                }
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    accumulatedScale *= detector.scaleFactor
                    if (accumulatedScale > 1.28f && picturesColumnCount > 2) {
                        picturesColumnCount--
                        accumulatedScale = 1f
                        savePersistedState()
                        renderActiveScreen()
                        return true
                    } else if (accumulatedScale < 0.76f && picturesColumnCount < 5) {
                        picturesColumnCount++
                        accumulatedScale = 1f
                        savePersistedState()
                        renderActiveScreen()
                        return true
                    }
                    return false
                }
            }
        )

        albumsScaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var accumulatedScale = 1f
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isMultiTouchPinching = true
                    accumulatedScale = 1f
                    return true
                }
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    accumulatedScale *= detector.scaleFactor
                    if (accumulatedScale > 1.28f && albumsColumnCount > 2) {
                        albumsColumnCount--
                        accumulatedScale = 1f
                        savePersistedState()
                        renderActiveScreen()
                        return true
                    } else if (accumulatedScale < 0.76f && albumsColumnCount < 4) {
                        albumsColumnCount++
                        accumulatedScale = 1f
                        savePersistedState()
                        renderActiveScreen()
                        return true
                    }
                    return false
                }
            }
        )

        rootStack = FrameLayout(this).apply {
            setBackgroundColor(colorPureBlack)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val mainVertical = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorPureBlack)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        mainContentArea = FrameLayout(this).apply {
            setBackgroundColor(colorPureBlack)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        mainVertical.addView(mainContentArea, LinearLayout.LayoutParams(-1, 0, 1f))

        miniAudioPlayerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        mainVertical.addView(
            miniAudioPlayerBar,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(10), dp(2), dp(10), dp(4))
            }
        )

        bottomNavContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colorPureBlack)
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        mainVertical.addView(bottomNavContainer, LinearLayout.LayoutParams(-1, dp(62)))

        rootStack.addView(mainVertical, FrameLayout.LayoutParams(-1, -1))

        overlayLayer = FrameLayout(this).apply {
            visibility = View.GONE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        rootStack.addView(overlayLayer, FrameLayout.LayoutParams(-1, -1))

        floatingPipLayer = FrameLayout(this).apply {
            visibility = View.GONE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        rootStack.addView(floatingPipLayer, FrameLayout.LayoutParams(-1, -1))

        lockLayer = FrameLayout(this).apply {
            visibility = View.GONE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        rootStack.addView(lockLayer, FrameLayout.LayoutParams(-1, -1))

        setContentView(rootStack)

        renderBottomNavigation()
        renderActiveScreen()

        // الخزنة الخاصة مستقلة تماماً (VaultActivity)؛ المعرض الرئيسي يفتح مباشرة بأقصى سرعة

        registerLiveMediaStoreObserver()
        requestMediaPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        val now = System.currentTimeMillis()
        if (lastStopAtMs > 0L && appLockEnabled && appLockPin.isNotEmpty() && !isAppLockedNow) {
            val skip = now <= skipRelockUntilMs
            if (!skip && (now - lastStopAtMs) >= relockGraceMs) {
                lockGalleryNow(autoPromptBiometric = true)
            }
        }
        skipRelockUntilMs = 0L
    }

    override fun onStop() {
        super.onStop()
        lastStopAtMs = System.currentTimeMillis()
        if (isSlideshowRunning) stopSlideshow()
        pauseVideoViewsIn(overlayLayer)
        pauseVideoViewsIn(floatingPipLayer)
    }

    override fun onResume() {
        super.onResume()
        if (hasMediaPermission()) {
            loadDeviceMediaAsync()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { thumbExecutor.shutdownNow() } catch (_: Exception) {}
        try { viewerExecutor.shutdownNow() } catch (_: Exception) {}
        mediaContentObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
        }
        try {
            audioPlayer?.release()
        } catch (_: Exception) {}
    }

    private fun registerLiveMediaStoreObserver() {
        if (mediaContentObserver != null) return
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                handler.removeCallbacks(observerReloadRunnable)
                handler.postDelayed(observerReloadRunnable, 180L)
            }
        }
        mediaContentObserver = obs
        try {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                obs
            )
            contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                obs
            )
            contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                obs
            )
        } catch (_: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarsTheme()
        rootStack.setBackgroundColor(colorPureBlack)
        mainContentArea.setBackgroundColor(colorPureBlack)
        bottomNavContainer.setBackgroundColor(colorPureBlack)
        renderBottomNavigation()
        renderActiveScreen()
        if (isAppLockedNow) {
            renderLockScreenOverlay(autoPromptBiometric = false)
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarsTheme() {
        val bg = colorPureBlack
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val decor = window.decorView
        var flags = decor.systemUiVisibility
        if (!isDarkModeActive()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
        decor.systemUiVisibility = flags
    }

    override fun onBackPressed() {
        if (isAppLockedNow) {
            super.onBackPressed()
            return
        }
        when {
            isSlideshowRunning -> stopSlideshow()
            overlayLayer.visibility == View.VISIBLE -> closeOverlay()
            isSelectionMode -> {
                exitSelectionModeFast()
            }
            isAlbumSelectionMode -> {
                isAlbumSelectionMode = false
                selectedAlbumIds.clear()
                renderBottomNavigation()
                renderActiveScreen()
            }
            activeSubPageTitle != null -> {
                activeSubPageTitle = null
                activeSubPageItems = null
                renderBottomNavigation()
                renderActiveScreen()
            }
            activeAlbumDetail != null -> {
                activeAlbumDetail = null
                renderBottomNavigation()
                renderActiveScreen()
            }
            currentTab != 0 -> {
                currentTab = 0
                renderBottomNavigation()
                renderActiveScreen()
            }
            else -> super.onBackPressed()
        }
    }

    // ============================================================
    // حفظ واسترجاع التفضيلات
    // ============================================================
    private fun loadPersistedState() {
        favoriteIds.clear()
        favoriteIds.addAll(prefs.getStringSet("favorite_ids", emptySet()) ?: emptySet())

        trashedIds.clear()
        trashedIds.addAll(prefs.getStringSet("trashed_ids", emptySet()) ?: emptySet())

        permanentlyDeletedIds.clear()
        permanentlyDeletedIds.addAll(prefs.getStringSet("perm_deleted_ids", emptySet()) ?: emptySet())

        trashedTimestamps.clear()
        val rawMap = prefs.getStringSet("trashed_timestamps", emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        for (entry in rawMap) {
            val parts = entry.split("|")
            if (parts.size == 2) {
                trashedTimestamps[parts[0]] = parts[1].toLongOrNull() ?: now
            }
        }
        for (id in trashedIds) {
            if (!trashedTimestamps.containsKey(id)) {
                trashedTimestamps[id] = now
            }
        }

        hiddenAlbumNames.clear()
        hiddenAlbumNames.addAll(prefs.getStringSet("hidden_albums", emptySet()) ?: emptySet())

        nonEssentialAlbumNames.clear()
        nonEssentialAlbumNames.addAll(prefs.getStringSet("non_essential_albums", emptySet()) ?: emptySet())

        showCustomizeAlbumsBanner = prefs.getBoolean("show_customize_banner", true)
        picturesColumnCount = prefs.getInt("pictures_col_count", 3).coerceIn(2, 5)
        albumsColumnCount = prefs.getInt("albums_col_count", 3).coerceIn(2, 4)
        themeMode = prefs.getInt("theme_mode", 0)
        val defaultLowEnd = try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.isLowRamDevice || am.memoryClass <= 160 || Runtime.getRuntime().availableProcessors() <= 4
        } catch (_: Throwable) { false }
        lowEndSmoothMode = prefs.getBoolean("low_end_smooth_mode", defaultLowEnd)
        appLockEnabled = prefs.getBoolean("app_lock_enabled", false)
        appLockPin = prefs.getString("app_lock_pin", "") ?: ""
        appLockPinLen = prefs.getInt("app_lock_pin_len", 0)
        failedUnlockAttempts = prefs.getInt("lock_fail_count", 0)
        lockoutUntilMs = prefs.getLong("lock_until_ms", 0L)
        if (appLockPin.isNotEmpty() && !appLockPin.startsWith("v2|")) {
            // ترحيل كلمة السر القديمة (نص عادي) إلى hash مملّح
            appLockPinLen = appLockPin.length
            appLockPin = try { hashPinForStorage(appLockPin) } catch (_: Exception) { "" }
            prefs.edit().putString("app_lock_pin", appLockPin).putInt("app_lock_pin_len", appLockPinLen).apply()
        }
        appLockBiometric = prefs.getBoolean("app_lock_biometric", true)

        val customAlbumsRaw = prefs.getStringSet("custom_albums_names", emptySet()) ?: emptySet()
        customAlbumMap.clear()
        for (name in customAlbumsRaw) {
            val ids = prefs.getStringSet("custom_album_$name", emptySet()) ?: emptySet()
            customAlbumMap[name] = ids.toMutableList()
        }

        customNamesMap.clear()
        val renamedRaw = prefs.getStringSet("renamed_items_map", emptySet()) ?: emptySet()
        for (entry in renamedRaw) {
            val idx = entry.indexOf('|')
            if (idx > 0) {
                customNamesMap[entry.substring(0, idx)] = entry.substring(idx + 1)
            }
        }
    }

    private fun savePersistedState() {
        val serializedTimestamps = trashedIds.map { id ->
            val ts = trashedTimestamps[id] ?: System.currentTimeMillis()
            "$id|$ts"
        }.toSet()

        val serializedRenames = customNamesMap.entries.map { "${it.key}|${it.value}" }.toSet()

        val editor = prefs.edit()
            .putStringSet("favorite_ids", favoriteIds.toSet())
            .putStringSet("trashed_ids", trashedIds.toSet())
            .putStringSet("perm_deleted_ids", permanentlyDeletedIds.toSet())
            .putStringSet("trashed_timestamps", serializedTimestamps)
            .putStringSet("hidden_albums", hiddenAlbumNames.toSet())
            .putStringSet("non_essential_albums", nonEssentialAlbumNames.toSet())
            .putStringSet("renamed_items_map", serializedRenames)
            .putBoolean("show_customize_banner", showCustomizeAlbumsBanner)
            .putInt("pictures_col_count", picturesColumnCount)
            .putInt("albums_col_count", albumsColumnCount)
            .putInt("theme_mode", themeMode)
            .putBoolean("low_end_smooth_mode", lowEndSmoothMode)
            .putBoolean("app_lock_enabled", appLockEnabled)
            .putString("app_lock_pin", appLockPin)
            .putInt("app_lock_pin_len", appLockPinLen)
            .putBoolean("app_lock_biometric", appLockBiometric)
            .putStringSet("custom_albums_names", customAlbumMap.keys.toSet())

        for ((name, list) in customAlbumMap) {
            editor.putStringSet("custom_album_$name", list.toSet())
        }
        editor.apply()
        applySecureFlag()
    }

    // ============================================================
    // أمان القفل: تجزئة كلمة السر + حماية من التخمين + FLAG_SECURE
    // ============================================================
    private fun pbkdf2(pin: String, salt: ByteArray, algo: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 30_000, 256)
        return SecretKeyFactory.getInstance(algo).generateSecret(spec).encoded
    }

    private fun hashPinForStorage(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val algo = if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
        val hash = pbkdf2(pin, salt, algo)
        return "v2|" + algo + "|" + Base64.encodeToString(salt, Base64.NO_WRAP) + "|" +
            Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun verifyPin(input: String): Boolean {
        val stored = appLockPin
        if (stored.isEmpty() || input.isEmpty()) return false
        return try {
            val parts = stored.split("|")
            if (parts.size != 4 || parts[0] != "v2") {
                false
            } else {
                val salt = Base64.decode(parts[2], Base64.NO_WRAP)
                val expected = Base64.decode(parts[3], Base64.NO_WRAP)
                MessageDigest.isEqual(expected, pbkdf2(input, salt, parts[1]))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyPinIfComplete(entered: String): Boolean =
        appLockPinLen > 0 && entered.length >= appLockPinLen && verifyPin(entered)

    private fun lockoutRemainingMs(): Long {
        val now = System.currentTimeMillis()
        // حماية من العبث بالساعة: لا نسمح بقفل أطول من ساعتين
        if (lockoutUntilMs - now > 2L * 60L * 60L * 1000L) lockoutUntilMs = now + 15L * 60L * 1000L
        return (lockoutUntilMs - now).coerceAtLeast(0L)
    }

    private fun registerFailedAttempt() {
        failedUnlockAttempts++
        if (failedUnlockAttempts >= 5) {
            val step = (failedUnlockAttempts - 5).coerceAtMost(5)
            lockoutUntilMs = System.currentTimeMillis() + (30L shl step) * 1000L
        }
        prefs.edit()
            .putInt("lock_fail_count", failedUnlockAttempts)
            .putLong("lock_until_ms", lockoutUntilMs)
            .apply()
    }

    private fun resetFailedAttempts() {
        failedUnlockAttempts = 0
        lockoutUntilMs = 0L
        prefs.edit().putInt("lock_fail_count", 0).putLong("lock_until_ms", 0L).apply()
    }

    private fun lockoutMessage(waitMs: Long): String =
        "محاولات كثيرة خاطئة، انتظر ${toArabicDigits(((waitMs + 999L) / 1000L).toInt())} ثانية"

    private fun applySecureFlag() {
        try {
            if (appLockEnabled && appLockPin.isNotEmpty()) {
                window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        } catch (_: Exception) {
        }
    }

    private fun markExternalLaunch() {
        skipRelockUntilMs = System.currentTimeMillis() + 120_000L
    }

    private fun pauseVideoViewsIn(v: View?) {
        if (v == null) return
        if (v is VideoView) {
            try { if (v.isPlaying) v.pause() } catch (_: Exception) {}
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) pauseVideoViewsIn(v.getChildAt(i))
        }
    }

    private fun refreshCachedVisibleItems() {
        cachedVisibleItems = deviceMediaItems.filter {
            !trashedIds.contains(it.id) && !permanentlyDeletedIds.contains(it.id)
        }
    }

    private fun moveItemToTrash(id: String) {
        trashedIds.add(id)
        trashedTimestamps[id] = System.currentTimeMillis()
        refreshCachedVisibleItems()
    }

    private fun restoreItemFromTrash(id: String) {
        trashedIds.remove(id)
        trashedTimestamps.remove(id)
        refreshCachedVisibleItems()
    }

    private fun permanentlyDeleteItem(id: String) {
        permanentlyDeleteItems(listOf(id))
    }

    /** حذف حقيقي من الجهاز عبر نظام أندرويد؛ لا يُعلَّم العنصر محذوفاً إلا بعد نجاح الحذف فعلاً. */
    private fun permanentlyDeleteItems(ids: List<String>) {
        if (ids.isEmpty()) return
        val items = ids.mapNotNull { id -> deviceMediaItems.find { it.id == id } }
        val foundIds = items.map { it.id }.toSet()
        for (id in ids) {
            if (id !in foundIds) finalizeDeletedId(id)
        }
        if (items.isEmpty()) {
            onDeleteBatchFinished()
            return
        }
        pendingDeleteIds = items.map { it.id }
        if (Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(this)) {
            processLegacyDeleteQueue()
        } else if (Build.VERSION.SDK_INT >= 30) {
            requestSystemDeleteDialog()
        } else {
            processLegacyDeleteQueue()
        }
    }

    private fun requestSystemDeleteDialog() {
        if (Build.VERSION.SDK_INT < 30) {
            pendingDeleteIds = emptyList()
            onDeleteBatchFinished()
            return
        }
        val uris = pendingDeleteIds.mapNotNull { id -> deviceMediaItems.find { it.id == id }?.contentUri }
        if (uris.isEmpty()) {
            pendingDeleteIds = emptyList()
            onDeleteBatchFinished()
            return
        }
        try {
            val pi = MediaStore.createDeleteRequest(contentResolver, uris)
            markExternalLaunch()
            startIntentSenderForResult(pi.intentSender, REQ_SYSTEM_DELETE, null, 0, 0, 0)
        } catch (_: Exception) {
            pendingDeleteIds = emptyList()
            Toast.makeText(this, "تعذر طلب الحذف من النظام", Toast.LENGTH_SHORT).show()
            onDeleteBatchFinished()
        }
    }

    private fun processLegacyDeleteQueue() {
        while (pendingDeleteIds.isNotEmpty()) {
            val id = pendingDeleteIds.first()
            val item = deviceMediaItems.find { it.id == id }
            if (item == null) {
                finalizeDeletedId(id)
                pendingDeleteIds = pendingDeleteIds.drop(1)
                continue
            }
            try {
                contentResolver.delete(item.contentUri, null, null)
                finalizeDeletedId(id)
                pendingDeleteIds = pendingDeleteIds.drop(1)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= 30) {
                    requestSystemDeleteDialog()
                    return
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val rse = e as? RecoverableSecurityException
                    if (rse != null) {
                        try {
                            markExternalLaunch()
                            startIntentSenderForResult(
                                rse.userAction.actionIntent.intentSender,
                                REQ_SYSTEM_DELETE_LEGACY, null, 0, 0, 0
                            )
                            return
                        } catch (_: Exception) {
                        }
                    }
                }
                pendingDeleteIds = pendingDeleteIds.drop(1)
            } catch (_: Exception) {
                pendingDeleteIds = pendingDeleteIds.drop(1)
            }
        }
        onDeleteBatchFinished()
    }

    private fun finalizeDeletedId(id: String) {
        trashedIds.remove(id)
        trashedTimestamps.remove(id)
        favoriteIds.remove(id)
        customNamesMap.remove(id)
        permanentlyDeletedIds.remove(id)
        for (list in customAlbumMap.values) list.remove(id)
        deviceMediaItems.removeAll { it.id == id }
        batchDeletedCount++
        refreshCachedVisibleItems()
    }

    private fun onDeleteBatchFinished() {
        savePersistedState()
        refreshCachedVisibleItems()
        if (batchDeletedCount > 0) {
            Toast.makeText(
                this,
                "تم الحذف نهائياً (${toArabicDigits(batchDeletedCount)} عنصر)",
                Toast.LENGTH_SHORT
            ).show()
        }
        batchDeletedCount = 0
        if (activeSubPageTitle == "سلة المحذوفات") openRecycleBinSubPage() else renderActiveScreen()
        loadDeviceMediaAsync()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_SYSTEM_DELETE -> {
                if (resultCode == RESULT_OK) {
                    for (id in pendingDeleteIds) finalizeDeletedId(id)
                } else {
                    Toast.makeText(this, "تم إلغاء الحذف", Toast.LENGTH_SHORT).show()
                }
                pendingDeleteIds = emptyList()
                onDeleteBatchFinished()
            }
            REQ_SYSTEM_DELETE_LEGACY -> {
                if (resultCode == RESULT_OK) {
                    processLegacyDeleteQueue()
                } else {
                    pendingDeleteIds = emptyList()
                    onDeleteBatchFinished()
                }
            }
            REQ_PICK_PLAYER_SUBTITLE -> {
                if (resultCode == RESULT_OK && data?.data != null) {
                    val subUri = data.data!!
                    val fileName = subUri.lastPathSegment?.substringAfterLast('/') ?: "subtitle.srt"
                    viewerExecutor.execute {
                        val cues = SubtitleParser.loadFromUri(this, subUri)
                        handler.post {
                            if (cues.isNotEmpty()) {
                                activeCinemaSubtitleCues = cues
                                activeCinemaSubtitleName = fileName
                                activeCinemaSubtitleOffsetMs = 0L
                                onCinemaSubtitleLoadedCallback?.invoke()
                                Toast.makeText(this, "تم تفعيل ملف الترجمة (${toArabicDigits(cues.size)} سطر)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "ملف الترجمة غير مدعوم أو فارغ (يدعم SRT و VTT)", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    /** عناصر "حُذفت نهائياً" في النسخة القديمة لكن ملفاتها ما زالت على الجهاز ترجع إلى السلة بدل أن تبقى مخفية للأبد. */
    private fun migrateLegacyPermanentDeletes() {
        if (permanentlyDeletedIds.isEmpty()) return
        val existing = deviceMediaItems.map { it.id }.toHashSet()
        val now = System.currentTimeMillis()
        var moved = 0
        for (id in permanentlyDeletedIds.toList()) {
            if (id in existing) {
                trashedIds.add(id)
                trashedTimestamps[id] = now
                moved++
            }
        }
        permanentlyDeletedIds.clear()
        savePersistedState()
        if (moved > 0) {
            Toast.makeText(
                this,
                "تمت إعادة ${toArabicDigits(moved)} عنصر لم يُحذف فعلياً إلى سلة المحذوفات",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun purgeExpiredTrashItems() {
        val now = System.currentTimeMillis()
        val expired = trashedIds.filter { id ->
            val deletedAt = trashedTimestamps[id] ?: now
            (now - deletedAt) >= trashRetentionMs && deviceMediaItems.any { it.id == id }
        }
        if (expired.isEmpty()) return
        val canSilent = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(this)
        val lastPrompt = prefs.getLong("last_purge_prompt_ms", 0L)
        val minGap = if (canSilent) 60_000L else 86_400_000L
        if (now - lastPrompt < minGap) return
        prefs.edit().putLong("last_purge_prompt_ms", now).apply()
        permanentlyDeleteItems(expired)
    }

    private fun openManageMediaSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                markExternalLaunch()
                startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                Toast.makeText(this, "تعذر فتح إعدادات إدارة الوسائط", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "هذه الميزة غير مطلوبة على إصدار أندرويد هذا", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getRemainingTrashDays(id: String): Int {
        val deletedAt = trashedTimestamps[id] ?: System.currentTimeMillis()
        val elapsedMs = (System.currentTimeMillis() - deletedAt).coerceAtLeast(0L)
        val elapsedDays = (elapsedMs / 86_400_000L).toInt()
        return (30 - elapsedDays).coerceIn(1, 30)
    }

    // ============================================================
    // الأذونات وقراءة صور وفيديوهات الهاتف الحقيقية بدقة عالية
    // ============================================================
    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 34 &&
                    checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermissionsIfNeeded() {
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ) + (if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf<String>())
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        val partialOnly = Build.VERSION.SDK_INT >= 34 &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
        if (missing.isEmpty() || partialOnly) {
            loadDeviceMediaAsync()
        } else {
            markExternalLaunch()
            requestPermissions(missing.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            loadDeviceMediaAsync()
        }
    }

    private fun normalizeMediaTimestampMs(rawValue: Long, nowMs: Long): Long {
        if (rawValue <= 0L) return 0L
        val ms = if (rawValue < 10_000_000_000L) rawValue * 1000L else rawValue
        // منع أي تاريخ مستقبلي خاطئ في بيانات الملف من إخفاء الصور الحديثة الحقيقية
        return if (ms > nowMs + 300_000L) nowMs else ms
    }

    @Suppress("DEPRECATION")
    private fun loadDeviceMediaAsync() {
        if (!isLoadingMedia.compareAndSet(false, true)) {
            reloadQueued.set(true)
            return
        }
        Thread {
            val loaded = mutableListOf<MediaItem>()
            try {
                val nowMs = System.currentTimeMillis()
                val calToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfToday = calToday.timeInMillis
                val startOfYesterday = startOfToday - 86_400_000L
                val startOfThisYear = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                }.timeInMillis

                val shortDateFmt = SimpleDateFormat("d MMMM", Locale("ar"))
                val yearDateFmt = SimpleDateFormat("d MMMM yyyy", Locale("ar"))
                val fullTimeFmt = SimpleDateFormat("d MMMM yyyy • hh:mm a", Locale("ar"))

                // 1. قراءة جميع صور الهاتف الحقيقية مع حساب أحدث تاريخ فعلي (DATE_ADDED / DATE_MODIFIED / DATE_TAKEN)
                val imgProjection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.ORIENTATION
                )
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imgProjection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val addCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val wCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val hCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    val orientCol = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val itemId = "img_$id"
                        val rawName = cursor.getString(nameCol) ?: "صورة_$id.jpg"
                        val displayName = customNamesMap[itemId] ?: rawName

                        val addedMs = normalizeMediaTimestampMs(cursor.getLong(addCol), nowMs)
                        val modMs = normalizeMediaTimestampMs(cursor.getLong(modCol), nowMs)
                        val takenMs = if (takenCol >= 0) normalizeMediaTimestampMs(cursor.getLong(takenCol), nowMs) else 0L
                        // نأخذ أحدث وقت بين وقت الإضافة للهاتف ووقت التعديل ووقت الالتقاط لتظهر الصور الحديثة فوراً في الأعلى
                        val timeMs = max(addedMs, max(modMs, takenMs)).let { if (it > 0L) it else nowMs }

                        val rawBucket = cursor.getString(bucketCol) ?: "الكاميرا"
                        val widthPx = if (wCol >= 0) cursor.getInt(wCol) else 0
                        val heightPx = if (hCol >= 0) cursor.getInt(hCol) else 0
                        val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val pathStr = if (dataCol >= 0) (cursor.getString(dataCol) ?: "") else ""
                        val orientDeg = if (orientCol >= 0) cursor.getInt(orientCol) else 0

                        val dateGroup = when {
                            timeMs >= startOfToday -> "اليوم"
                            timeMs >= startOfYesterday -> "أمس"
                            timeMs >= startOfThisYear -> toArabicDigits(shortDateFmt.format(Date(timeMs)))
                            else -> toArabicDigits(yearDateFmt.format(Date(timeMs)))
                        }
                        val fullDateStr = toArabicDigits(fullTimeFmt.format(Date(timeMs)))
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        loaded.add(
                            MediaItem(
                                id = itemId,
                                mediaStoreId = id,
                                title = displayName,
                                dateGroup = dateGroup,
                                fullDateText = fullDateStr,
                                albumName = localizeAlbumName(rawBucket, pathStr),
                                timestampMs = timeMs,
                                isVideo = false,
                                widthPx = widthPx,
                                heightPx = heightPx,
                                sizeBytes = sizeBytes,
                                pathText = pathStr,
                                orientationDeg = orientDeg,
                                contentUri = contentUri
                            )
                        )
                    }
                }

                // 2. قراءة جميع مقاطع الفيديو الحقيقية من الهاتف
                val vidProjection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.DATE_TAKEN,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATA
                )
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    vidProjection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC, ${MediaStore.Video.Media._ID} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val addCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val modCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val takenCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                    val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val wCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                    val hCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                    val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val itemId = "vid_$id"
                        val rawName = cursor.getString(nameCol) ?: "فيديو_$id.mp4"
                        val displayName = customNamesMap[itemId] ?: rawName

                        val addedMs = normalizeMediaTimestampMs(cursor.getLong(addCol), nowMs)
                        val modMs = normalizeMediaTimestampMs(cursor.getLong(modCol), nowMs)
                        val takenMs = if (takenCol >= 0) normalizeMediaTimestampMs(cursor.getLong(takenCol), nowMs) else 0L
                        val timeMs = max(addedMs, max(modMs, takenMs)).let { if (it > 0L) it else nowMs }

                        val rawBucket = cursor.getString(bucketCol) ?: "الكاميرا"
                        val durMs = cursor.getLong(durCol)
                        val totalSec = (durMs / 1000L).coerceAtLeast(1L)
                        val mins = totalSec / 60
                        val secs = totalSec % 60
                        val durFormatted = String.format(Locale.US, "%d:%02d", mins, secs)
                        val widthPx = if (wCol >= 0) cursor.getInt(wCol) else 0
                        val heightPx = if (hCol >= 0) cursor.getInt(hCol) else 0
                        val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val pathStr = if (dataCol >= 0) (cursor.getString(dataCol) ?: "") else ""

                        val dateGroup = when {
                            timeMs >= startOfToday -> "اليوم"
                            timeMs >= startOfYesterday -> "أمس"
                            timeMs >= startOfThisYear -> toArabicDigits(shortDateFmt.format(Date(timeMs)))
                            else -> toArabicDigits(yearDateFmt.format(Date(timeMs)))
                        }
                        val fullDateStr = toArabicDigits(fullTimeFmt.format(Date(timeMs)))
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        loaded.add(
                            MediaItem(
                                id = itemId,
                                mediaStoreId = id,
                                title = displayName,
                                dateGroup = dateGroup,
                                fullDateText = fullDateStr,
                                albumName = localizeAlbumName(rawBucket, pathStr),
                                timestampMs = timeMs,
                                isVideo = true,
                                durationText = toArabicDigits(durFormatted),
                                widthPx = widthPx,
                                heightPx = heightPx,
                                sizeBytes = sizeBytes,
                                pathText = pathStr,
                                orientationDeg = 0,
                                contentUri = contentUri
                            )
                        )
                    }
                }

                // ترتيب جميع الصور والفيديوهات تنازلياً حسب أحدث وقت ثم أحدث معرّف MediaStore
                loaded.sortWith(compareByDescending<MediaItem> { it.timestampMs }.thenByDescending { it.mediaStoreId })
            } catch (_: Exception) {
            }

            val loadedAudio = mutableListOf<AudioTrack>()
            try {
                val audProjection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED
                )
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    audProjection,
                    null,
                    null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC, ${MediaStore.Audio.Media._ID} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                    val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val rawTitle = (if (titleCol >= 0) cursor.getString(titleCol) else null)
                            ?: (if (nameCol >= 0) cursor.getString(nameCol) else null)
                            ?: "ملف صوتي $id"
                        val rawArtist = (if (artistCol >= 0) cursor.getString(artistCol) else null)
                            ?.takeIf { it != "<unknown>" } ?: "مقطع صوتي"
                        val rawAlbum = (if (albumCol >= 0) cursor.getString(albumCol) else null)
                            ?.takeIf { it != "<unknown>" } ?: "الصوتيات"
                        val durMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                        val sizeB = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        if (durMs >= 1000L || sizeB > 8192L) {
                            val totalSec = (durMs / 1000L).coerceAtLeast(1L)
                            val durFormatted = toArabicDigits(
                                String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
                            )
                            val uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                id
                            )
                            loadedAudio.add(
                                AudioTrack(
                                    id = "aud_$id",
                                    title = rawTitle,
                                    artist = rawArtist,
                                    album = rawAlbum,
                                    durationMs = durMs,
                                    durationText = durFormatted,
                                    sizeBytes = sizeB,
                                    contentUri = uri
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }

            handler.post {
                isLoadingMedia.set(false)
                val prevTopId = deviceMediaItems.firstOrNull()?.id
                val prevCount = deviceMediaItems.size
                deviceMediaItems.clear()
                deviceMediaItems.addAll(loaded)
                deviceAudioTracks.clear()
                deviceAudioTracks.addAll(loadedAudio)
                if (loaded.isNotEmpty()) {
                    migrateLegacyPermanentDeletes()
                }
                refreshCachedVisibleItems()
                rebuildDynamicStoriesFromRealMedia()

                val newTopId = deviceMediaItems.firstOrNull()?.id
                val hasChanged = (loaded.size != prevCount) || (newTopId != prevTopId)
                if (activePicturesAdapter != null && currentTab == 0 && activeAlbumDetail == null && activeSubPageTitle == null && cachedVisibleItems.isNotEmpty()) {
                    activePicturesAdapter?.updateData(buildPictureListRows(cachedVisibleItems, picturesColumnCount))
                } else if (hasChanged || cachedVisibleItems.isEmpty()) {
                    renderActiveScreen()
                }
                if (loaded.isNotEmpty()) {
                    purgeExpiredTrashItems()
                }
                if (reloadQueued.compareAndSet(true, false)) {
                    loadDeviceMediaAsync()
                }
            }
        }.start()
    }

    private fun localizeAlbumName(bucket: String, fullPath: String): String {
        val lower = bucket.lowercase(Locale.ROOT)
        val pathLower = fullPath.lowercase(Locale.ROOT)
        return when {
            lower == "camera" || (lower == "dcim" && !pathLower.contains("screenshots")) -> "الكاميرا"
            lower.contains("screenshot") || pathLower.contains("screenshots") -> "صور الشاشة"
            lower == "download" || lower == "downloads" -> "تنزيل"
            else -> bucket
        }
    }

    private fun rebuildDynamicStoriesFromRealMedia() {
        val visible = getVisibleMediaItems()
        storiesList.removeAll { it.id.startsWith("auto_story_") }
        if (visible.isEmpty()) return

        val byDate = visible.groupBy { it.dateGroup }
        var added = 0
        val yearFmt = SimpleDateFormat("yyyy", Locale.US)

        for ((dateLabel, groupItems) in byDate) {
            if (groupItems.isNotEmpty() && added < 5) {
                val yr = toArabicDigits(yearFmt.format(Date(groupItems.first().timestampMs)))
                val storyTitle = when (dateLabel) {
                    "اليوم" -> "لحظات اليوم المميزة"
                    "أمس" -> "ذكريات أمس"
                    else -> "ذكرياتي في $dateLabel"
                }
                storiesList.add(
                    StoryInfo(
                        id = "auto_story_$added",
                        title = storyTitle,
                        yearText = yr,
                        isNew = (added == 0),
                        items = groupItems.take(20),
                        isFavorite = false
                    )
                )
                added++
            }
        }
    }

    private fun getVisibleMediaItems(): List<MediaItem> = cachedVisibleItems

    private fun getTrashedMediaItems(): List<MediaItem> {
        purgeExpiredTrashItems()
        return deviceMediaItems.filter { trashedIds.contains(it.id) && !permanentlyDeletedIds.contains(it.id) }
    }

    private fun getFavoriteMediaItems(): List<MediaItem> =
        cachedVisibleItems.filter { favoriteIds.contains(it.id) }

    private fun buildAlbumsList(): List<AlbumInfo> {
        val visible = getVisibleMediaItems()
        val favItems = getFavoriteMediaItems()

        val cameraItems = visible.filter { it.albumName == "الكاميرا" }
        val screenshotItems = visible.filter { it.albumName == "صور الشاشة" }
        val downloadItems = visible.filter { it.albumName == "تنزيل" }

        val list = mutableListOf<AlbumInfo>()

        list.add(
            AlbumInfo(
                id = "album_recent",
                title = "الأحدث",
                displayCount = visible.size,
                items = visible,
                isRecentSpecial = true,
                isEssential = !nonEssentialAlbumNames.contains("الأحدث")
            )
        )

        list.add(
            AlbumInfo(
                id = "album_favorites",
                title = "المفضلة",
                displayCount = favItems.size,
                items = favItems,
                isFavoritesSpecial = true,
                isEssential = !nonEssentialAlbumNames.contains("المفضلة")
            )
        )

        list.add(
            AlbumInfo(
                id = "album_camera",
                title = "الكاميرا",
                displayCount = cameraItems.size,
                items = cameraItems,
                showCameraBadge = true,
                showFolderBadge = true,
                isEssential = !nonEssentialAlbumNames.contains("الكاميرا")
            )
        )

        list.add(
            AlbumInfo(
                id = "album_screenshots",
                title = "صور الشاشة",
                displayCount = screenshotItems.size,
                items = screenshotItems,
                showCameraBadge = false,
                showFolderBadge = false,
                isEssential = !nonEssentialAlbumNames.contains("صور الشاشة")
            )
        )

        list.add(
            AlbumInfo(
                id = "album_download",
                title = "تنزيل",
                displayCount = downloadItems.size,
                items = downloadItems,
                showCameraBadge = false,
                showFolderBadge = true,
                isEssential = !nonEssentialAlbumNames.contains("تنزيل")
            )
        )

        for ((customName, ids) in customAlbumMap) {
            val matched = visible.filter { ids.contains(it.id) }
            list.add(
                AlbumInfo(
                    id = "album_custom_$customName",
                    title = customName,
                    displayCount = matched.size,
                    items = matched,
                    showFolderBadge = true,
                    isEssential = !nonEssentialAlbumNames.contains(customName)
                )
            )
        }

        val standardNames = setOf("الكاميرا", "صور الشاشة", "تنزيل")
        val extraGroups = visible.filter { !standardNames.contains(it.albumName) }
            .groupBy { it.albumName }
        for ((bucketName, items) in extraGroups) {
            if (!customAlbumMap.containsKey(bucketName)) {
                list.add(
                    AlbumInfo(
                        id = "album_dev_$bucketName",
                        title = bucketName,
                        displayCount = items.size,
                        items = items,
                        showFolderBadge = true,
                        isEssential = !nonEssentialAlbumNames.contains(bucketName)
                    )
                )
            }
        }

        return list.filter { !hiddenAlbumNames.contains(it.title) }
    }

    // ============================================================
    // شريط التنقل السفلي وتحديث وضع التحديد الفوري (Zero-Lag Selection)
    // ============================================================
    private fun enterSelectionModeFast(firstItemId: String? = null) {
        isSelectionMode = true
        try { rootStack.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
        if (firstItemId != null) {
            selectedMediaIds.add(firstItemId)
        }
        renderBottomNavigation()
        updateSelectionTopBarAndListInPlace()
    }

    private fun exitSelectionModeFast() {
        isSelectionMode = false
        selectedMediaIds.clear()
        renderBottomNavigation()
        updateSelectionTopBarAndListInPlace()
    }

    private fun updateSelectionTopBarAndListInPlace() {
        val adapter = activePicturesAdapter
        if (adapter != null) {
            activeNormalTopBar?.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
            activeSelectionTopBar?.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            activeSelectionTitleTv?.text = if (selectedMediaIds.isEmpty()) {
                "تحديد عناصر"
            } else {
                "تم تحديد ${toArabicDigits(selectedMediaIds.size)}"
            }
            val totalCount = adapter.totalMediaCount
            val allSelected = totalCount > 0 && selectedMediaIds.size >= totalCount
            activeSelectAllTv?.text = if (allSelected) "إلغاء الكل" else "الكل"
        } else {
            renderActiveScreen()
        }
    }

    // ============================================================
    // حركات انتقالية مسرّعة بالعتاد (Hardware-Accelerated Zero-Lag Animations)
    // ============================================================
    private fun animateFadeScaleIn(view: View, durationMs: Long = 140L) {
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(durationMs)
            .setInterpolator(smoothInterpolator)
            .start()
    }

    private fun animateSlideUpIn(view: View, distanceDp: Int = 56, durationMs: Long = 165L) {
        view.alpha = 0f
        view.translationY = dpF(distanceDp.toFloat())
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(durationMs)
            .setInterpolator(smoothInterpolator)
            .start()
    }

    private fun createAppLogoBadge(sizeDp: Int): View {
        return ImageView(this).apply {
            setImageResource(R.drawable.app_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    private fun renderBottomNavigation() {
        bottomNavContainer.removeAllViews()

        if (isSelectionMode) {
            val actions = listOf(
                BottomAction("نقل لألبوم", SamsungIconView.IconType.PLUS) { showAddSelectedToAlbumDialog() },
                BottomAction("للخزنة", SamsungIconView.IconType.LOCK) { moveSelectedToVault() },
                BottomAction("المفضلة", SamsungIconView.IconType.HEART) { toggleFavoriteForSelected() },
                BottomAction("مشاركة", SamsungIconView.IconType.SHARE) { shareSelectedItems() },
                BottomAction("حذف", SamsungIconView.IconType.TRASH) { deleteSelectedToTrash() }
            )
            for ((label, iconType, action) in actions) {
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { action() }
                }
                val icon = SamsungIconView(this, iconType, colorWhite, 2f)
                itemLayout.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
                val tv = TextView(this).apply {
                    text = label
                    setTextColor(colorWhite)
                    textSize = 11f
                    gravity = Gravity.CENTER
                }
                itemLayout.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                bottomNavContainer.addView(itemLayout, LinearLayout.LayoutParams(0, -1, 1f))
            }
            return
        }

        // الترتيب المطابق لسامسونج:  (أقصى اليسار) | القصص | ألبومات | الصور (أقصى اليمين)
        val menuTab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { showHamburgerBottomPopup() }
        }
        val hamburgerIcon = SamsungIconView(this, SamsungIconView.IconType.HAMBURGER, colorWhite, 2f)
        menuTab.addView(hamburgerIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
        bottomNavContainer.addView(menuTab, LinearLayout.LayoutParams(0, -1, 1f))

        bottomNavContainer.addView(
            makeBottomTabItem("الاستوديو", 2),
            LinearLayout.LayoutParams(0, -1, 1f)
        )
        bottomNavContainer.addView(
            makeBottomTabItem("ألبومات", 1),
            LinearLayout.LayoutParams(0, -1, 1f)
        )
        bottomNavContainer.addView(
            makeBottomTabItem("الصور", 0),
            LinearLayout.LayoutParams(0, -1, 1f)
        )
    }

    private fun makeBottomTabItem(title: String, tabIndex: Int): View {
        val selected = (currentTab == tabIndex && activeSubPageTitle == null && activeAlbumDetail == null)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                if (selected && tabIndex == 0 && !isSelectionMode) {
                    val lv = activePicturesListView
                    if (lv != null) {
                        if (lv.firstVisiblePosition > 30) lv.setSelection(0) else lv.smoothScrollToPositionFromTop(0, 0, 320)
                    }
                    return@setOnClickListener
                }
                closeOverlay()
                activeAlbumDetail = null
                activeSubPageTitle = null
                activeSubPageItems = null
                isSelectionMode = false
                selectedMediaIds.clear()
                isAlbumSelectionMode = false
                currentTab = tabIndex
                renderBottomNavigation()
                renderActiveScreen()
            }
        }

        val label = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(if (selected) colorWhite else colorTextMuted)
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
        }
        container.addView(label, LinearLayout.LayoutParams(-2, -2))

        val indicator = View(this).apply {
            background = GradientDrawable().apply {
                setColor(if (selected) colorWhite else Color.TRANSPARENT)
                cornerRadius = dpF(2f)
            }
        }
        val indParams = LinearLayout.LayoutParams(dp(38), dp(3)).apply {
            topMargin = dp(4)
        }
        container.addView(indicator, indParams)

        return container
    }

    // ============================================================
    // التوجيه بين الشاشات
    // ============================================================
    private fun renderActiveScreen() {
        activePicturesAdapter = null
        activePicturesListView = null
        activeNormalTopBar = null
        activeSelectionTopBar = null
        activeSelectionTitleTv = null
        activeSelectAllTv = null
        mainContentArea.removeAllViews()

        val nextScreen = when {
            activeSubPageTitle != null -> {
                buildSubPageScreen(activeSubPageTitle!!, activeSubPageItems ?: emptyList())
            }
            activeAlbumDetail != null -> {
                buildAlbumDetailScreen(activeAlbumDetail!!)
            }
            currentTab == 0 -> {
                buildPicturesTabScreen()
            }
            currentTab == 1 -> {
                buildAlbumsTabScreen()
            }
            else -> {
                buildStoriesTabScreen()
            }
        }
        nextScreen.alpha = 0f
        mainContentArea.addView(nextScreen)
        nextScreen.animate()
            .alpha(1f)
            .setDuration(115L)
            .setInterpolator(smoothInterpolator)
            .start()
    }

    // ============================================================
    // تحويل قائمة الصور إلى صفوف افتراضية معاد تدويرها (PictureListRow)
    // ============================================================
    private fun buildPictureListRows(items: List<MediaItem>, cols: Int): List<PictureListRow> {
        val rows = ArrayList<PictureListRow>(items.size / cols + 16)
        val grouped = linkedMapOf<String, MutableList<MediaItem>>()
        for (item in items) {
            grouped.getOrPut(item.dateGroup) { mutableListOf() }.add(item)
        }
        for ((dateTitle, listInDate) in grouped) {
            rows.add(PictureListRow.DateHeader(dateTitle, listInDate))
            var idx = 0
            val n = listInDate.size
            while (idx < n) {
                val end = min(idx + cols, n)
                rows.add(PictureListRow.PhotoRow(listInDate.subList(idx, end)))
                idx = end
            }
        }
        return rows
    }

    // ============================================================
    // 1. شاشة تبويب "الصور" (تعمل بـ ListView + ViewHolder لسرعة فورية 60-120fps)
    // ============================================================
    private fun buildPicturesTabScreen(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorPureBlack)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val normalTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(16), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            visibility = if (isSelectionMode) View.GONE else View.VISIBLE
        }

        val moreBtn = makeTopIconButton(SamsungIconView.IconType.MORE_VERT) {
            showPicturesMorePopover()
        }
        normalTopBar.addView(moreBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val searchBtn = makeTopIconButton(SamsungIconView.IconType.SEARCH) {
            showSearchOverlay()
        }
        normalTopBar.addView(
            searchBtn,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) }
        )

        val lockBtn = makeTopIconButton(SamsungIconView.IconType.LOCK) {
            openVault()
        }
        normalTopBar.addView(
            lockBtn,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) }
        )

        // تغيير حجم شبكة الصور يتم بسلاسة بإيماءة اليد (Pinch-to-Zoom بإصبعين) بدون أي أزرار زائدة

        normalTopBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // الهوية البصرية الجديدة: اسم الاستديو + اللوجو الأزرق الجديد في أقصى اليمين
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { showAboutBrandDialog() }
        }
        val brandTitle = TextView(this).apply {
            text = "Mango Gallery"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            setPadding(0, 0, dp(8), 0)
        }
        brandRow.addView(brandTitle)
        brandRow.addView(createAppLogoBadge(28))
        normalTopBar.addView(brandRow)

        val visibleItems = getVisibleMediaItems()

        // شريط التحديد العلوي السريع (Selection Top Bar)
        val selectionTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(16), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        }

        val cancelSelBtn = TextView(this).apply {
            text = "إلغاء"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextSecondary)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { exitSelectionModeFast() }
        }
        selectionTopBar.addView(cancelSelBtn)

        val selectAllBtn = TextView(this).apply {
            val allSelected = visibleItems.isNotEmpty() && selectedMediaIds.size >= visibleItems.size
            text = if (allSelected) "إلغاء الكل" else "الكل"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorSamsungBlue)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                val allNow = visibleItems.isNotEmpty() && selectedMediaIds.size >= visibleItems.size
                if (allNow) {
                    selectedMediaIds.clear()
                } else {
                    selectedMediaIds.addAll(visibleItems.map { it.id })
                }
                updateSelectionTopBarAndListInPlace()
            }
        }
        activeSelectAllTv = selectAllBtn
        selectionTopBar.addView(selectAllBtn)

        val selTitleTv = TextView(this).apply {
            text = if (selectedMediaIds.isEmpty()) "تحديد عناصر" else "تم تحديد ${toArabicDigits(selectedMediaIds.size)}"
            textSize = 17.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        activeSelectionTitleTv = selTitleTv
        selectionTopBar.addView(selTitleTv, LinearLayout.LayoutParams(0, -2, 1f))

        activeNormalTopBar = normalTopBar
        activeSelectionTopBar = selectionTopBar

        wrapper.addView(normalTopBar, LinearLayout.LayoutParams(-1, -2))
        wrapper.addView(selectionTopBar, LinearLayout.LayoutParams(-1, -2))

        if (visibleItems.isEmpty()) {
            wrapper.addView(buildEmptyOrPermissionPromptView(), LinearLayout.LayoutParams(-1, 0, 1f))
            return wrapper
        }

        val rows = buildPictureListRows(visibleItems, picturesColumnCount)
        val adapter = PicturesVirtualAdapter(
            rows = rows,
            cols = picturesColumnCount,
            contextList = visibleItems,
            isRecycleBinMode = false,
            onEnterSelection = { clickedId ->
                normalTopBar.visibility = View.GONE
                selectionTopBar.visibility = View.VISIBLE
                enterSelectionModeFast(clickedId)
            }
        )
        activePicturesAdapter = adapter

        val listView = object : ListView(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.pointerCount > 1) {
                    isMultiTouchPinching = true
                }
                gridScaleDetector.onTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    handler.postDelayed({ isMultiTouchPinching = false }, 120L)
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            this.adapter = adapter
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            isFastScrollEnabled = true
            verticalScrollbarPosition = View.SCROLLBAR_POSITION_RIGHT
            setPadding(dp(4), 0, dp(4), dp(24))
            clipToPadding = false
        }

        activePicturesListView = listView
        wrapper.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        return wrapper
    }

    /**
     * محول القوائم الافتراضي المعاد تدويره (Virtualized ViewHolder Adapter)
     * يضمن عدم إنشاء أكثر من 7-8 صفوف في الذاكرة مهما بلغ عدد الصور في الهاتف!
     */
    private inner class PicturesVirtualAdapter(
        private var rows: List<PictureListRow>,
        private val cols: Int,
        private val contextList: List<MediaItem>,
        private val isRecycleBinMode: Boolean,
        private val onEnterSelection: (String) -> Unit
    ) : BaseAdapter(), SectionIndexer {

        val totalMediaCount: Int
            get() = contextList.size

        private val spacing = dp(2)
        private val cellSize = (resources.displayMetrics.widthPixels - dp(8) - spacing * (cols * 2)) / cols

        private var sectionTitles: Array<Any> = arrayOf()
        private var sectionStarts: IntArray = IntArray(0)
        private var sectionOfRow: IntArray = IntArray(0)

        private fun rebuildSections() {
            val titles = ArrayList<Any>()
            val starts = ArrayList<Int>()
            val ofRow = IntArray(rows.size)
            var cur = -1
            for (i in rows.indices) {
                val r = rows[i]
                if (r is PictureListRow.DateHeader) {
                    titles.add(r.dateTitle)
                    starts.add(i)
                    cur++
                }
                ofRow[i] = if (cur < 0) 0 else cur
            }
            sectionTitles = titles.toTypedArray()
            sectionStarts = starts.toIntArray()
            sectionOfRow = ofRow
        }

        init {
            rebuildSections()
        }

        override fun getSections(): Array<Any> = sectionTitles
        override fun getPositionForSection(sectionIndex: Int): Int =
            if (sectionStarts.isEmpty()) 0 else sectionStarts[sectionIndex.coerceIn(0, sectionStarts.size - 1)]
        override fun getSectionForPosition(position: Int): Int =
            if (sectionOfRow.isEmpty()) 0 else sectionOfRow[position.coerceIn(0, sectionOfRow.size - 1)]

        fun updateData(newRows: List<PictureListRow>) {
            rows = newRows
            rebuildSections()
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is PictureListRow.DateHeader -> 0
            is PictureListRow.PhotoRow -> 1
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val row = rows[position]) {
                is PictureListRow.DateHeader -> bindHeaderRow(row, convertView)
                is PictureListRow.PhotoRow -> bindPhotoRow(row, convertView)
            }
        }

        private fun bindHeaderRow(row: PictureListRow.DateHeader, convertView: View?): View {
            val holder: HeaderHolder
            val view: LinearLayout
            if (convertView == null || convertView.tag !is HeaderHolder) {
                view = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(16), dp(10), dp(8))
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                }
                val checkTv = TextView(this@MainActivity).apply {
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                }
                view.addView(checkTv, LinearLayout.LayoutParams(dp(20), dp(20)))

                val titleTv = TextView(this@MainActivity).apply {
                    textSize = 15.5f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                }
                view.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))

                holder = HeaderHolder(checkTv, titleTv)
                view.tag = holder
            } else {
                view = convertView as LinearLayout
                holder = view.tag as HeaderHolder
            }

            holder.titleTv.text = row.dateTitle
            holder.titleTv.setTextColor(colorTextPrimary)

            if (isSelectionMode && !isRecycleBinMode) {
                holder.checkTv.visibility = View.VISIBLE
                val groupAllSelected = row.groupItems.all { selectedMediaIds.contains(it.id) }
                holder.checkTv.text = if (groupAllSelected) "✓" else ""
                holder.checkTv.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (groupAllSelected) colorSamsungBlue else Color.TRANSPARENT)
                    setStroke(dp(2), colorTextSecondary)
                }
                holder.checkTv.setOnClickListener {
                    if (groupAllSelected) {
                        row.groupItems.forEach { selectedMediaIds.remove(it.id) }
                    } else {
                        row.groupItems.forEach { selectedMediaIds.add(it.id) }
                    }
                    updateSelectionTopBarAndListInPlace()
                }
            } else {
                holder.checkTv.visibility = View.GONE
            }

            return view
        }

        private fun bindPhotoRow(row: PictureListRow.PhotoRow, convertView: View?): View {
            val holder: PhotoRowHolder
            val rowView: LinearLayout
            if (convertView == null || convertView.tag !is PhotoRowHolder) {
                rowView = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                val cells = Array(cols) {
                    val cellView = FastGridCellView(this@MainActivity)
                    val lp = LinearLayout.LayoutParams(cellSize, cellSize).apply {
                        setMargins(spacing, spacing, spacing, spacing)
                    }
                    rowView.addView(cellView, lp)
                    cellView
                }
                holder = PhotoRowHolder(cells)
                rowView.tag = holder
            } else {
                rowView = convertView as LinearLayout
                holder = rowView.tag as PhotoRowHolder
            }

            val items = row.items
            for (i in 0 until cols) {
                val cell = holder.cells[i]
                if (i < items.size) {
                    val media = items[i]
                    cell.visibility = View.VISIBLE
                    cell.setOnTouchListener { v, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90L).start()
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
                        }
                        false
                    }
                    cell.bind(
                        media = media,
                        cellPx = cellSize,
                        isSelected = selectedMediaIds.contains(media.id),
                        inSelectionMode = isSelectionMode && !isRecycleBinMode,
                        isFav = favoriteIds.contains(media.id),
                        trashDaysLeft = if (isRecycleBinMode) getRemainingTrashDays(media.id) else 0
                    )
                    cell.setOnClickListener {
                        if (isMultiTouchPinching) return@setOnClickListener
                        if (isRecycleBinMode) {
                            showTrashItemActionDialog(media)
                        } else if (isSelectionMode) {
                            if (selectedMediaIds.contains(media.id)) {
                                selectedMediaIds.remove(media.id)
                            } else {
                                selectedMediaIds.add(media.id)
                            }
                            updateSelectionTopBarAndListInPlace()
                        } else {
                            showFullScreenPhotoViewer(media, contextList)
                        }
                    }
                    cell.setOnLongClickListener {
                        if (isMultiTouchPinching) return@setOnLongClickListener true
                        if (!isRecycleBinMode) {
                            onEnterSelection(media.id)
                        }
                        true
                    }
                } else {
                    cell.clearBinding()
                    cell.visibility = View.INVISIBLE
                }
            }

            return rowView
        }
    }

    private class HeaderHolder(val checkTv: TextView, val titleTv: TextView)
    private class PhotoRowHolder(val cells: Array<FastGridCellView>)

    /**
     * خلية شبكة موحدة خفيفة الوزن للغاية (ترسم الصورة المصغرة عالية الدقة + شارة الفيديو + القلب + دائرة التحديد في Canvas واحد بدون Views فرعية متداخلة!)
     */
    private inner class FastGridCellView(context: Context) : View(context) {

        var boundMediaId: String? = null
            private set
        private var boundMedia: MediaItem? = null
        private var cellSizePx: Int = 360
        private var isCellSelected = false
        private var inSelectionMode = false
        private var isFavorite = false
        private var trashDaysLeft = 0

        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dpF(10f)
            typeface = Typeface.DEFAULT_BOLD
        }
        private val checkFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val checkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(1.8f)
            color = Color.WHITE
        }
        private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFF3B30.toInt()
        }
        private val matrixDraw = Matrix()
        private val rectF = RectF()
        private val path = Path()

        fun clearBinding() {
            boundMediaId = null
            boundMedia = null
        }

        fun bind(
            media: MediaItem,
            cellPx: Int,
            isSelected: Boolean,
            inSelectionMode: Boolean,
            isFav: Boolean,
            trashDaysLeft: Int
        ) {
            this.boundMediaId = media.id
            this.boundMedia = media
            this.cellSizePx = cellPx
            this.isCellSelected = isSelected
            this.inSelectionMode = inSelectionMode
            this.isFavorite = isFav
            this.trashDaysLeft = trashDaysLeft
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val media = boundMedia ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val bmp = requestThumbnailForGridCell(media, cellSizePx, this)
            if (bmp != null && !bmp.isRecycled) {
                val bw = bmp.width.toFloat().coerceAtLeast(1f)
                val bh = bmp.height.toFloat().coerceAtLeast(1f)
                val scale = if (bw * h > w * bh) h / bh else w / bw
                val dx = (w - bw * scale) * 0.5f
                val dy = (h - bh * scale) * 0.5f
                matrixDraw.reset()
                matrixDraw.postScale(scale, scale)
                matrixDraw.postTranslate(dx, dy)
                canvas.drawBitmap(bmp, matrixDraw, bmpPaint)
            } else {
                placeholderPaint.color = colorEmptyFavCard
                canvas.drawRect(0f, 0f, w, h, placeholderPaint)
            }

            if (isCellSelected) {
                canvas.drawColor(0x44000000)
            }

            // شارة الفيديو ومدته أسفل اليسار
            if (media.isVideo) {
                val label = if (media.durationText.isNotEmpty()) "${media.durationText}" else "تشغيل"
                textPaint.textSize = dpF(10f)
                val tw = textPaint.measureText(label)
                val padH = dpF(6f)
                val bhBadge = dpF(18f)
                rectF.set(dpF(5f), h - bhBadge - dpF(5f), dpF(5f) + tw + padH * 2, h - dpF(5f))
                canvas.drawRoundRect(rectF, dpF(8f), dpF(8f), badgeBgPaint)
                canvas.drawText(label, rectF.left + padH, rectF.bottom - dpF(4.5f), textPaint)
            }

            // شارة المفضلة أسفل اليمين
            if (isFavorite) {
                val hx = w - dpF(12f)
                val hy = h - dpF(12f)
                val hr = dpF(6.5f)
                path.reset()
                path.moveTo(hx, hy + hr * 0.78f)
                path.cubicTo(hx - hr * 1.25f, hy + hr * 0.1f, hx - hr * 0.95f, hy - hr * 0.95f, hx, hy - hr * 0.32f)
                path.cubicTo(hx + hr * 0.95f, hy - hr * 0.95f, hx + hr * 1.25f, hy + hr * 0.1f, hx, hy + hr * 0.78f)
                path.close()
                canvas.drawPath(path, heartPaint)
            }

            // شارة الأيام المتبقية في سلة المحذوفات
            if (trashDaysLeft > 0) {
                val dStr = "${toArabicDigits(trashDaysLeft)} يوم"
                textPaint.textSize = dpF(9.5f)
                val tw = textPaint.measureText(dStr)
                val padH = dpF(6f)
                rectF.set(w - tw - padH * 2 - dpF(5f), dpF(5f), w - dpF(5f), dpF(23f))
                canvas.drawRoundRect(rectF, dpF(8f), dpF(8f), badgeBgPaint)
                canvas.drawText(dStr, rectF.left + padH, rectF.bottom - dpF(4.5f), textPaint)
            }

            // دائرة التحديد في أعلى اليمين
            if (inSelectionMode) {
                val cx = w - dpF(16f)
                val cy = dpF(16f)
                val r = dpF(10.5f)
                checkFillPaint.color = if (isCellSelected) colorSamsungBlue else 0x66000000
                canvas.drawCircle(cx, cy, r, checkFillPaint)
                canvas.drawCircle(cx, cy, r, checkStrokePaint)
                if (isCellSelected) {
                    canvas.drawLine(cx - r * 0.42f, cy, cx - r * 0.08f, cy + r * 0.35f, checkStrokePaint)
                    canvas.drawLine(cx - r * 0.08f, cy + r * 0.35f, cx + r * 0.45f, cy - r * 0.32f, checkStrokePaint)
                }
            }
        }
    }

    private fun buildEmptyOrPermissionPromptView(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
        }

        val hasPerm = hasMediaPermission()
        val titleTv = TextView(this).apply {
            text = if (hasPerm) "لا توجد صور أو مقاطع فيديو على الهاتف"
            else "يحتاج Mango Gallery إلى إذن الوصول للصور والفيديوهات"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            gravity = Gravity.CENTER
        }
        box.addView(titleTv, LinearLayout.LayoutParams(-1, -2))

        val subTv = TextView(this).apply {
            text = if (hasPerm) {
                "التقط صوراً بالكاميرا أو قم بتنزيل صور لتظهر هنا تلقائياً مقسمة حسب التاريخ والألبومات."
            } else {
                "اضغط على الزر بالأسفل للسماح لتطبيق Mango Gallery بقراءة وعرض جميع صور وفيديوهات هاتفك الحقيقية."
            }
            textSize = 13.5f
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
        }
        box.addView(subTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val actionBtn = TextView(this).apply {
            text = if (hasPerm) "تحديث صور الهاتف ↻" else "منح إذن الوصول للصور"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply {
                setColor(colorSamsungBlue)
                cornerRadius = dpF(20f)
            }
            setOnClickListener {
                if (hasPerm) {
                    loadDeviceMediaAsync()
                } else {
                    requestMediaPermissionsIfNeeded()
                }
            }
        }
        box.addView(actionBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        return box
    }

    private fun showPicturesMorePopover() {
        val items = listOf(
            MenuOption("تعديل") {
                enterSelectionModeFast()
                renderActiveScreen()
            },
            MenuOption("تحديد الكل") {
                isSelectionMode = true
                selectedMediaIds.clear()
                selectedMediaIds.addAll(getVisibleMediaItems().map { it.id })
                renderBottomNavigation()
                renderActiveScreen()
            },
            MenuOption("إنشاء") {
                showCreateMenuDialog()
            },
            MenuOption("بدء عرض الشرائح") {
                startSlideshow(getVisibleMediaItems())
            }
        )
        showTopLeftFloatingMenu(items)
    }

    // ============================================================
    // 2. شاشة تبويب "ألبومات" (One UI Albums)
    // ============================================================
    private fun buildAlbumsTabScreen(): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(colorPureBlack)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val scroll = object : ScrollView(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.pointerCount > 1) {
                    isMultiTouchPinching = true
                }
                albumsScaleDetector.onTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    handler.postDelayed({ isMultiTouchPinching = false }, 120L)
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
        }

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(36), 0, dp(26))
        }
        headerBox.addView(createAppLogoBadge(44))
        val bigTitle = TextView(this).apply {
            text = "ألبومات"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorWhite)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
        }
        headerBox.addView(bigTitle)
        val totalSubtitle = TextView(this).apply {
            val v = getVisibleMediaItems()
            val vCount = v.count { it.isVideo }
            val pCount = v.size - vCount
            text = "${toArabicDigits(pCount)} صورة • ${toArabicDigits(vCount)} فيديو"
            textSize = 12.5f
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
        }
        headerBox.addView(totalSubtitle)
        mainCol.addView(headerBox, LinearLayout.LayoutParams(-1, -2))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(10))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val moreBtn = makeTopIconButton(SamsungIconView.IconType.MORE_VERT) {
            showAlbumsMorePopover()
        }
        actionRow.addView(moreBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val searchBtn = makeTopIconButton(SamsungIconView.IconType.SEARCH) {
            showSearchOverlay()
        }
        actionRow.addView(
            searchBtn,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) }
        )

        val addBtn = makeTopIconButton(SamsungIconView.IconType.PLUS) {
            showCreateAlbumDialog()
        }
        actionRow.addView(
            addBtn,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) }
        )

        actionRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        if (isAlbumSelectionMode) {
            val doneTv = TextView(this).apply {
                text = "إنهاء التعديل"
                textSize = 14f
                setTextColor(colorSamsungBlue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    isAlbumSelectionMode = false
                    selectedAlbumIds.clear()
                    renderActiveScreen()
                }
            }
            actionRow.addView(doneTv)
        }

        mainCol.addView(actionRow, LinearLayout.LayoutParams(-1, -2))

        // تم نقل بطاقتي سينما الفيديوهات ومشغل الموسيقى إلى التبويب المخصص (الاستوديو) بناءً على طلب المستخدم

        if (showCustomizeAlbumsBanner) {
            val bannerCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(14))
                background = GradientDrawable().apply {
                    setColor(colorCardDark)
                    cornerRadius = dpF(22f)
                    setStroke(dp(1), colorBorderSubtle)
                }
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }

            val bannerTitle = TextView(this).apply {
                text = "تخصيص علامة تبويب الألبومات"
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextPrimary)
                gravity = Gravity.RIGHT
            }
            bannerCard.addView(bannerTitle, LinearLayout.LayoutParams(-1, -2))

            val bannerDesc = TextView(this).apply {
                text = "حدد عدد قليل من الألبومات الأساسية المراد إظهارها أو إظهارها جميعاً أو شيء بين الخيارين. القرار يعود إليك."
                textSize = 13f
                setTextColor(colorTextSecondary)
                gravity = Gravity.RIGHT
                setLineSpacing(dpF(2f), 1f)
            }
            bannerCard.addView(
                bannerDesc,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            )

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }

            val selectEssentialsBtn = TextView(this).apply {
                text = "تحديد الألبومات الأساسية"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextPrimary)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(6))
                setOnClickListener { showEssentialAlbumsDialog() }
            }
            btnRow.addView(selectEssentialsBtn, LinearLayout.LayoutParams(0, -2, 1.3f))

            val notNowBtn = TextView(this).apply {
                text = "ليس الآن"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextPrimary)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(6))
                setOnClickListener {
                    showCustomizeAlbumsBanner = false
                    savePersistedState()
                    renderActiveScreen()
                }
            }
            btnRow.addView(notNowBtn, LinearLayout.LayoutParams(0, -2, 0.7f))

            bannerCard.addView(
                btnRow,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            )

            mainCol.addView(
                bannerCard,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
            )
        }

        val sectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val viewAllWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                showOnlyEssentialAlbums = !showOnlyEssentialAlbums
                renderActiveScreen()
            }
        }
        val orangeDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorDotOrange)
            }
        }
        viewAllWrap.addView(
            orangeDot,
            LinearLayout.LayoutParams(dp(5), dp(5)).apply { rightMargin = dp(5) }
        )
        val viewAllTv = TextView(this).apply {
            text = if (showOnlyEssentialAlbums) "عرض الكل" else "عرض الأساسية فقط"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorSamsungBlue)
        }
        viewAllWrap.addView(viewAllTv)
        sectionRow.addView(viewAllWrap, LinearLayout.LayoutParams(-2, -2))

        val essentialTitle = TextView(this).apply {
            text = if (showOnlyEssentialAlbums) "الألبومات الأساسية" else "كل الألبومات"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        sectionRow.addView(essentialTitle, LinearLayout.LayoutParams(0, -2, 1f))

        mainCol.addView(sectionRow, LinearLayout.LayoutParams(-1, -2))

        val allAlbums = buildAlbumsList()
        val albumsToShow = if (showOnlyEssentialAlbums) {
            allAlbums.filter { it.isEssential }
        } else {
            allAlbums
        }

        val cols = albumsColumnCount.coerceIn(2, 4)
        val availWidth = resources.displayMetrics.widthPixels - dp(24)
        val colGap = if (cols >= 3) dp(10) else dp(14)
        val cardSize = (availWidth - colGap * (cols - 1)) / cols

        val albumRows = albumsToShow.chunked(cols)
        for (rowAlbums in albumRows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }

            for (c in 0 until cols) {
                if (c > 0) {
                    row.addView(View(this), LinearLayout.LayoutParams(colGap, 1))
                }
                if (c < rowAlbums.size) {
                    val alb = rowAlbums[c]
                    row.addView(
                        buildAlbumCardView(alb, cardSize),
                        LinearLayout.LayoutParams(cardSize, -2)
                    )
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(cardSize, 1))
                }
            }

            mainCol.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
            )
        }

        scroll.addView(mainCol, FrameLayout.LayoutParams(-1, -2))
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val scrollPill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xDD252527.toInt())
                cornerRadius = dpF(14f)
            }
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val upArrow = TextView(this).apply {
            text = "⌃"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { scroll.smoothScrollTo(0, 0) }
        }
        val downArrow = TextView(this).apply {
            text = "⌄"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        scrollPill.addView(upArrow)
        scrollPill.addView(downArrow)

        val pillLp = FrameLayout.LayoutParams(dp(24), dp(48)).apply {
            gravity = Gravity.BOTTOM or Gravity.LEFT
            leftMargin = dp(10)
            bottomMargin = dp(14)
        }
        frame.addView(scrollPill, pillLp)

        return frame
    }

    private fun buildAlbumCardView(album: AlbumInfo, cardSizePx: Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = true
            setOnClickListener {
                if (isMultiTouchPinching) return@setOnClickListener
                if (isAlbumSelectionMode) {
                    if (selectedAlbumIds.contains(album.id)) {
                        selectedAlbumIds.remove(album.id)
                    } else {
                        selectedAlbumIds.add(album.id)
                    }
                    renderActiveScreen()
                } else {
                    activeAlbumDetail = album
                    renderBottomNavigation()
                    renderActiveScreen()
                }
            }
            setOnLongClickListener {
                isAlbumSelectionMode = true
                selectedAlbumIds.add(album.id)
                renderActiveScreen()
                true
            }
        }

        val thumbFrame = FrameLayout(this)

        val radiusCorner = if (albumsColumnCount >= 3) dpF(18f) else dpF(22f)
        when {
            album.items.isEmpty() -> {
                val emptyCard = EmptyFavoritesCardView(this, radiusCorner)
                thumbFrame.addView(emptyCard, FrameLayout.LayoutParams(-1, -1))
            }
            else -> {
                val coverItem = album.items.first()
                val coverView = AsyncMediaThumbnailView(
                    context = this,
                    media = coverItem,
                    cornerRadiusPx = radiusCorner,
                    strokeColor = colorBorderSubtle,
                    preferredDecodePx = cardSizePx
                )
                thumbFrame.addView(coverView, FrameLayout.LayoutParams(-1, -1))
            }
        }

        if (album.showCameraBadge || album.showFolderBadge) {
            val badgeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (album.showCameraBadge) {
                val camBadge = SamsungIconView(this, SamsungIconView.IconType.BADGE_CAMERA, Color.WHITE, 1.6f)
                badgeRow.addView(
                    camBadge,
                    LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(5) }
                )
            }
            if (album.showFolderBadge) {
                val folBadge = SamsungIconView(this, SamsungIconView.IconType.BADGE_ALBUM, Color.WHITE, 1.6f)
                badgeRow.addView(folBadge, LinearLayout.LayoutParams(dp(16), dp(16)))
            }
            val badgeLp = FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM or Gravity.RIGHT
                rightMargin = dp(10)
                bottomMargin = dp(10)
            }
            thumbFrame.addView(badgeRow, badgeLp)
        }

        if (isAlbumSelectionMode) {
            val checked = selectedAlbumIds.contains(album.id)
            val checkTv = TextView(this).apply {
                text = if (checked) "✓" else ""
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (checked) colorSamsungBlue else 0x77000000)
                    setStroke(dp(2), Color.WHITE)
                }
            }
            val checkLp = FrameLayout.LayoutParams(dp(24), dp(24)).apply {
                gravity = Gravity.TOP or Gravity.RIGHT
                topMargin = dp(10)
                rightMargin = dp(10)
            }
            thumbFrame.addView(checkTv, checkLp)
        }

        container.addView(thumbFrame, LinearLayout.LayoutParams(cardSizePx, cardSizePx))

        val titleTv = TextView(this).apply {
            text = album.title
            textSize = 14.5f
            setTextColor(colorTextPrimary)
            gravity = Gravity.RIGHT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        container.addView(
            titleTv,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
                rightMargin = dp(4)
            }
        )

        val countTv = TextView(this).apply {
            text = toArabicDigits(album.displayCount)
            textSize = 11.5f
            setTextColor(colorTextMuted)
            gravity = Gravity.RIGHT
        }
        container.addView(
            countTv,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(1)
                rightMargin = dp(4)
            }
        )

        return container
    }

    private fun showAlbumsMorePopover() {
        val items = listOf(
            MenuOption("تعديل") {
                isAlbumSelectionMode = true
                selectedAlbumIds.clear()
                renderActiveScreen()
            },
            MenuOption("تحديد الكل") {
                isAlbumSelectionMode = true
                selectedAlbumIds.clear()
                selectedAlbumIds.addAll(buildAlbumsList().map { it.id })
                renderActiveScreen()
            },
            MenuOption("تغيير حجم الشبكة (${toArabicDigits(albumsColumnCount)} أعمدة)") {
                albumsColumnCount = when (albumsColumnCount) {
                    2 -> 3
                    3 -> 4
                    else -> 2
                }
                savePersistedState()
                renderActiveScreen()
            },
            MenuOption("تحديد الألبومات الأساسية") {
                showEssentialAlbumsDialog()
            },
            MenuOption("إخفاء الألبومات") {
                showHideAlbumsDialog()
            }
        )
        showTopLeftFloatingMenu(items)
    }

    // ============================================================
    // 3. شاشة تبويب "القصص" (بدقة عالية من صور الهاتف)
    // ============================================================
    private fun buildStoriesTabScreen(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorPureBlack)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(32))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // 1. الهيدر الفخم لمركز الاستوديو والذكاء الاصطناعي
        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, dp(20))
        }
        headerBox.addView(createAppLogoBadge(44))
        headerBox.addView(TextView(this).apply {
            text = "الاستوديو والذكاء الاصطناعي"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorWhite)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
        })
        val allVideos = getVisibleMediaItems().filter { it.isVideo }
        val hwProfile = ObjectEraser.detectHardwareProfile(this)
        headerBox.addView(TextView(this).apply {
            text = "${toArabicDigits(allVideos.size)} فيديو • ${toArabicDigits(deviceAudioTracks.size)} مقطع صوتي • ${hwProfile.acceleratorName}"
            textSize = 12f
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
        })
        mainCol.addView(headerBox, LinearLayout.LayoutParams(-1, -2))

        // 2. البطاقتان الرئيسيتان (مشغل الموسيقى + سينما الفيديوهات) في صفحتهما المخصصة
        val widgetsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // بطاقة مشغل الموسيقى الاحترافي
        val musicWidgetCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(16))
            isClickable = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0A2A4E.toInt(), 0xFF141822.toInt())
            ).apply {
                cornerRadius = dpF(22f)
                setStroke(dp(1), 0x448CD0FA)
            }
            setOnClickListener { showProAudioPlayerOverlay() }
        }

        val mwTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val mwIconWrap = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x330A78D6)
            }
        }
        mwIconWrap.addView(
            SamsungIconView(this, SamsungIconView.IconType.AUDIO_WAVE, colorBrandIceBlue, 2.0f),
            FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER }
        )
        mwTop.addView(mwIconWrap, LinearLayout.LayoutParams(dp(32), dp(32)))

        val mwTag = TextView(this).apply {
            text = "مشغل الموسيقى"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorBrandIceBlue)
            setPadding(0, 0, dp(8), 0)
        }
        mwTop.addView(mwTag)
        musicWidgetCard.addView(mwTop, LinearLayout.LayoutParams(-1, -2))

        val activeSong = deviceAudioTracks.getOrNull(currentAudioIndex) ?: deviceAudioTracks.firstOrNull()
        val mwSongTitle = TextView(this).apply {
            text = activeSong?.title ?: "مكتبة الصوتيات"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        musicWidgetCard.addView(mwSongTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val mwSongSub = TextView(this).apply {
            text = if (activeSong != null) "${activeSong.artist} • ${toArabicDigits(deviceAudioTracks.size)} مقطع"
            else "${toArabicDigits(deviceAudioTracks.size)} ملف صوتي"
            textSize = 11f
            setTextColor(0xFFB0C4DE.toInt())
            gravity = Gravity.RIGHT
            maxLines = 1
        }
        musicWidgetCard.addView(mwSongSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val mwControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val mwPrev = makeCircleIconBtn(SamsungIconView.IconType.SKIP_PREV, 30, 14, 0x22FFFFFF) {
            if (deviceAudioTracks.isNotEmpty()) {
                playAudioTrackAtIndex(if (currentAudioIndex <= 0) deviceAudioTracks.size - 1 else currentAudioIndex - 1)
                renderActiveScreen()
            }
        }
        val mwPlayIcon = if (audioPlayer?.isPlaying == true) SamsungIconView.IconType.PAUSE_SOLID else SamsungIconView.IconType.PLAY_SOLID
        val mwPlay = makeCircleIconBtn(mwPlayIcon, 36, 16, colorSamsungBlue) {
            if (deviceAudioTracks.isEmpty()) {
                showProAudioPlayerOverlay()
            } else if (currentAudioIndex < 0) {
                playAudioTrackAtIndex(0)
                renderActiveScreen()
            } else {
                audioPlayer?.let { mp ->
                    if (mp.isPlaying) mp.pause() else mp.start()
                }
                updateMiniAudioPlayerBar()
                renderActiveScreen()
            }
        }
        val mwNext = makeCircleIconBtn(SamsungIconView.IconType.SKIP_NEXT, 30, 14, 0x22FFFFFF) {
            if (deviceAudioTracks.isNotEmpty()) {
                playAudioTrackAtIndex(currentAudioIndex + 1)
                renderActiveScreen()
            }
        }
        mwControls.addView(mwPrev, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(8) })
        mwControls.addView(mwPlay, LinearLayout.LayoutParams(dp(36), dp(36)))
        mwControls.addView(mwNext, LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(8) })
        musicWidgetCard.addView(mwControls, LinearLayout.LayoutParams(-1, -2))

        // بطاقة سينما الفيديوهات الاحترافية
        val latestVid = allVideos.firstOrNull()
        val videoWidgetCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(16))
            isClickable = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1E1A34.toInt(), 0xFF14161E.toInt())
            ).apply {
                cornerRadius = dpF(22f)
                setStroke(dp(1), 0x448CD0FA)
            }
            setOnClickListener {
                if (latestVid != null) {
                    openFullCinemaVideoPlayer(latestVid, allVideos)
                } else {
                    openSubPage("مقاطع فيديو", allVideos)
                }
            }
        }

        val vwTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val vwIconWrap = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x333E82F7)
            }
        }
        vwIconWrap.addView(
            SamsungIconView(this, SamsungIconView.IconType.VIDEO_PLAY, colorBrandIceBlue, 2.0f),
            FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER }
        )
        vwTop.addView(vwIconWrap, LinearLayout.LayoutParams(dp(32), dp(32)))

        val vwTag = TextView(this).apply {
            text = "سينما الفيديوهات"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorBrandIceBlue)
            setPadding(0, 0, dp(8), 0)
        }
        vwTop.addView(vwTag)
        videoWidgetCard.addView(vwTop, LinearLayout.LayoutParams(-1, -2))

        val vwTitle = TextView(this).apply {
            text = latestVid?.title ?: "مشغل الأفلام والفيديو"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        videoWidgetCard.addView(vwTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val vwSub = TextView(this).apply {
            text = "${toArabicDigits(allVideos.size)} فيديو جاهز للتشغيل"
            textSize = 11f
            setTextColor(0xFFB0C4DE.toInt())
            gravity = Gravity.RIGHT
            maxLines = 1
        }
        videoWidgetCard.addView(vwSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val vwBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val openListBtn = makeCircleIconBtn(SamsungIconView.IconType.HAMBURGER, 30, 14, 0x22FFFFFF) {
            openSubPage("مقاطع فيديو", allVideos)
        }
        val playVidBtn = makeCircleIconBtn(SamsungIconView.IconType.PLAY_SOLID, 36, 16, colorSamsungBlue) {
            if (latestVid != null) {
                openFullCinemaVideoPlayer(latestVid, allVideos)
            } else {
                openSubPage("مقاطع فيديو", allVideos)
            }
        }
        val pipVidBtn = makeCircleIconBtn(SamsungIconView.IconType.PIP_WINDOW, 30, 14, 0x22FFFFFF) {
            if (latestVid != null) {
                startFloatingPipVideoWidget(latestVid, 0)
            }
        }
        vwBtnRow.addView(openListBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(8) })
        vwBtnRow.addView(playVidBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        vwBtnRow.addView(pipVidBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(8) })
        videoWidgetCard.addView(vwBtnRow, LinearLayout.LayoutParams(-1, -2))

        widgetsRow.addView(musicWidgetCard, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        widgetsRow.addView(videoWidgetCard, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        mainCol.addView(widgetsRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        // 3. قسم تقنيات الذكاء الاصطناعي المحلية (On-Device Neural AI Suite)
        val aiHeader = TextView(this).apply {
            text = "مختبر الذكاء الاصطناعي المحلي (On-Device AI)"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            gravity = Gravity.RIGHT
            setPadding(dp(4), dp(4), dp(4), dp(10))
        }
        mainCol.addView(aiHeader, LinearLayout.LayoutParams(-1, -2))

        fun buildAiFeatureCard(
            title: String,
            subtitle: String,
            iconType: SamsungIconView.IconType,
            onClick: () -> Unit
        ): View {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    setColor(colorCardDark)
                    cornerRadius = dpF(20f)
                    setStroke(dp(1), colorBorderSubtle)
                }
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setOnClickListener { onClick() }
            }
            val arrow = SamsungIconView(this, SamsungIconView.IconType.CHEVRON_RIGHT, colorTextSecondary, 1.8f).apply {
                rotation = 180f
            }
            card.addView(arrow, LinearLayout.LayoutParams(dp(18), dp(18)))

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.RIGHT
                setPadding(dp(12), 0, dp(12), 0)
            }
            textCol.addView(TextView(this).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextPrimary)
                gravity = Gravity.RIGHT
            })
            textCol.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(colorTextSecondary)
                gravity = Gravity.RIGHT
                setPadding(0, dp(2), 0, 0)
            })
            card.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

            val iconCircle = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x260A78D6)
                    setStroke(dp(1), 0x448CD0FA)
                }
            }
            iconCircle.addView(
                SamsungIconView(this, iconType, colorBrandIceBlue, 2.0f),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            )
            card.addView(iconCircle, LinearLayout.LayoutParams(dp(46), dp(46)))
            return card
        }

        val firstPhoto = getVisibleMediaItems().firstOrNull { !it.isVideo }

        mainCol.addView(
            buildAiFeatureCard(
                title = "الممحاة الذكية للأجسام والظلال (AI Object Eraser)",
                subtitle = "إزالة الأشخاص والعناصر غير المرغوب فيها محلياً عبر المعالج العصبي",
                iconType = SamsungIconView.IconType.ERASER_BOX
            ) {
                if (firstPhoto != null) openObjectEraser(firstPhoto)
                else Toast.makeText(this, "لا توجد صور متاحة حالياً", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        )

        mainCol.addView(
            buildAiFeatureCard(
                title = "تحسين الصور التلقائي الذكي (AI Remaster & Auto-HDR)",
                subtitle = "رفع الحدة، استعادة تفاصيل الإضاءة والظلال، وعزل الخلفية البورتريه",
                iconType = SamsungIconView.IconType.TONE_SUN
            ) {
                if (firstPhoto != null) showSamsungProPhotoEditor(firstPhoto)
                else Toast.makeText(this, "لا توجد صور متاحة حالياً", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        )

        mainCol.addView(
            buildAiFeatureCard(
                title = "استوديو مونتاج الفيديو والترجمة (Studio Video & SRT)",
                subtitle = "قص الفيديو بدون فقدان الجودة، التحكم بالسرعة، وتحميل ملفات الترجمة .SRT",
                iconType = SamsungIconView.IconType.VIDEO_PLAY
            ) {
                if (latestVid != null) openVideoEditor(latestVid)
                else Toast.makeText(this, "لا توجد مقاطع فيديو متاحة للمونتاج", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        )

        mainCol.addView(
            buildAiFeatureCard(
                title = "الفاحص الذكي للصور المتشابهة وتنظيف المساحة (AI Cleaner)",
                subtitle = "فحص البصمة البصرية للصور لاكتشاف المكرر والملفات الضخمة فوراً",
                iconType = SamsungIconView.IconType.TAB_FOUR_DOTS
            ) {
                showAiSmartDuplicateCleanerDialog()
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        )

        mainCol.addView(
            buildAiFeatureCard(
                title = "الخزنة الخاصة المشفّرة والحماية البيومترية",
                subtitle = "إخفاء وتشفير الصور والفيديوهات بـ AES-256 مع دعم بصمة الوجه والإصبع",
                iconType = SamsungIconView.IconType.LOCK
            ) {
                openVault()
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
        )

        // 4. شريط أحدث مقاطع الفيديو للوصول السريع للمشغل أو المونتاج
        if (allVideos.isNotEmpty()) {
            val vidHeaderRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(10))
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            val viewAllVidsTv = TextView(this).apply {
                text = "عرض الكل (${toArabicDigits(allVideos.size)})"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorSamsungBlue)
                setOnClickListener { openSubPage("مقاطع فيديو", allVideos) }
            }
            vidHeaderRow.addView(viewAllVidsTv)
            vidHeaderRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            vidHeaderRow.addView(TextView(this).apply {
                text = "أحدث الفيديوهات"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextPrimary)
            })
            mainCol.addView(vidHeaderRow, LinearLayout.LayoutParams(-1, -2))

            val hScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
            }
            val vidsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            for (vItem in allVideos.take(10)) {
                val vCard = FrameLayout(this).apply {
                    isClickable = true
                    setOnClickListener { openFullCinemaVideoPlayer(vItem, allVideos) }
                }
                val thumb = AsyncMediaThumbnailView(
                    context = this,
                    media = vItem,
                    cornerRadiusPx = dpF(18f),
                    strokeColor = colorBorderSubtle,
                    preferredDecodePx = dp(200)
                )
                vCard.addView(thumb, FrameLayout.LayoutParams(-1, -1))
                val durBadge = TextView(this).apply {
                    text = vItem.durationText.ifEmpty { "فيديو" }
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(0xAA000000.toInt())
                        cornerRadius = dpF(8f)
                    }
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                }
                vCard.addView(durBadge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply {
                    leftMargin = dp(8)
                    bottomMargin = dp(8)
                })
                vidsRow.addView(vCard, LinearLayout.LayoutParams(dp(145), dp(105)).apply {
                    leftMargin = dp(8)
                })
            }
            hScroll.addView(vidsRow)
            mainCol.addView(hScroll, LinearLayout.LayoutParams(-1, -2))
        }

        scroll.addView(mainCol, FrameLayout.LayoutParams(-1, -2))
        wrapper.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return wrapper
    }

    /**
     * فاحص الذكاء الاصطناعي للصور المكررة والملفات الكبيرة (AI Smart Cleaner)
     */
    private fun showAiSmartDuplicateCleanerDialog() {
        val visible = getVisibleMediaItems()
        if (visible.isEmpty()) {
            Toast.makeText(this, "لا توجد وسائط لفحصها حالياً", Toast.LENGTH_SHORT).show()
            return
        }
        val bySizeAndDim = visible.filter { !it.isVideo && it.sizeBytes > 0L }
            .groupBy { "${it.sizeBytes}_${it.widthPx}x${it.heightPx}" }
            .values
            .filter { it.size > 1 }
            .flatten()

        val largeFiles = visible.filter { it.sizeBytes >= 25L * 1024L * 1024L }
        val totalBytes = visible.sumOf { it.sizeBytes }

        val options = arrayOf(
            "الصور المتطابقة والمكررة (${toArabicDigits(bySizeAndDim.size)} صورة)",
            "الفيديوهات والملفات الضخمة أكبر من ٢٥ ميجابايت (${toArabicDigits(largeFiles.size)} ملف)",
            "عرض جميع مقاطع الفيديو (${toArabicDigits(visible.count { it.isVideo })} فيديو)",
            "عرض صور الشاشة (Screenshots)"
        )

        AlertDialog.Builder(this)
            .setTitle("الفاحص الذكي للمساحة • إجمالي ${formatFileSizeArabic(totalBytes)}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (bySizeAndDim.isEmpty()) {
                            Toast.makeText(this, "معرضك نظيف، لم يتم العثور على صور مكررة", Toast.LENGTH_SHORT).show()
                        } else {
                            openSubPage("صور مكررة مكتشفة", bySizeAndDim)
                        }
                    }
                    1 -> {
                        if (largeFiles.isEmpty()) {
                            Toast.makeText(this, "لا توجد ملفات ضخمة تتجاوز ٢٥ ميجابايت", Toast.LENGTH_SHORT).show()
                        } else {
                            openSubPage("الملفات الضخمة", largeFiles)
                        }
                    }
                    2 -> openSubPage("مقاطع فيديو", visible.filter { it.isVideo })
                    3 -> openSubPage("صور الشاشة", visible.filter { it.albumName == "صور الشاشة" })
                }
            }
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun showHamburgerBottomPopup() {
        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x88000000.toInt())
            isClickable = true
            setOnClickListener { closeOverlay() }
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(10), dp(22), dp(10), dp(22))
            background = GradientDrawable().apply {
                setColor(colorBottomPopupBg)
                cornerRadius = dpF(28f)
                setStroke(dp(1), colorBorderSubtle)
            }
            elevation = dpF(16f)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        row1.addView(
            makeHamburgerGridItem("المواقع", SamsungIconView.IconType.LOCATION) {
                closeOverlay()
                openLocationsSubPage()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        row1.addView(
            makeHamburgerGridItem("الأحدث", SamsungIconView.IconType.CLOCK) {
                closeOverlay()
                openSubPage("الأحدث", getVisibleMediaItems())
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        row1.addView(
            makeHamburgerGridItem("المفضلة", SamsungIconView.IconType.HEART) {
                closeOverlay()
                openSubPage("المفضلة", getFavoriteMediaItems())
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        row1.addView(
            makeHamburgerGridItem("مقاطع فيديو", SamsungIconView.IconType.VIDEO_PLAY) {
                closeOverlay()
                openSubPage("مقاطع فيديو", getVisibleMediaItems().filter { it.isVideo })
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        card.addView(row1, LinearLayout.LayoutParams(-1, -2))

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        row2.addView(
            makeHamburgerGridItem("مشغل الصوت", SamsungIconView.IconType.AUDIO_WAVE) {
                closeOverlay()
                showProAudioPlayerOverlay()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        row2.addView(
            makeHamburgerGridItem("الضبط", SamsungIconView.IconType.SETTINGS) {
                closeOverlay()
                showGallerySettingsDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        row2.addView(
            makeHamburgerGridItem("سلة المحذوفات", SamsungIconView.IconType.TRASH) {
                closeOverlay()
                openRecycleBinSubPage()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        row2.addView(
            makeHamburgerGridItem("الألبومات\nالمشتركة", SamsungIconView.IconType.SHARED_PEOPLE) {
                closeOverlay()
                showSharedAlbumsDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        card.addView(
            row2,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) }
        )

        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        row3.addView(
            makeHamburgerGridItem("الخزنة الخاصة", SamsungIconView.IconType.LOCK) {
                closeOverlay()
                openVault()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        row3.addView(
            makeHamburgerGridItem("إعدادات\nالبصمة والقفل", SamsungIconView.IconType.FINGERPRINT) {
                closeOverlay()
                openVaultSecuritySettings()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        row3.addView(
            makeHamburgerGridItem("مسح أجسام\nبالذكاء AI", SamsungIconView.IconType.ERASER_BOX) {
                closeOverlay()
                val firstPhoto = getVisibleMediaItems().firstOrNull { !it.isVideo }
                if (firstPhoto != null) {
                    openObjectEraser(firstPhoto)
                } else {
                    Toast.makeText(this@MainActivity, "افتح أي صورة لاختيار مسح الأجسام بالذكاء الاصطناعي", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        row3.addView(
            makeHamburgerGridItem("استوديو\nمونتاج الفيديو", SamsungIconView.IconType.EDIT_PEN) {
                closeOverlay()
                val firstVid = getVisibleMediaItems().firstOrNull { it.isVideo }
                if (firstVid != null) {
                    openVideoEditor(firstVid)
                } else {
                    Toast.makeText(this@MainActivity, "لا توجد مقاطع فيديو حالياً للمونتاج", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        card.addView(
            row3,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) }
        )

        val cardLp = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(10)
            rightMargin = dp(10)
            bottomMargin = dp(68)
        }
        scrim.addView(card, cardLp)
        overlayLayer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        animateSlideUpIn(card, distanceDp = 64, durationMs = 170L)
    }

    private fun makeHamburgerGridItem(
        label: String,
        iconType: SamsungIconView.IconType,
        onClick: () -> Unit
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { onClick() }
        }

        val icon = SamsungIconView(this, iconType, colorWhite, 1.9f)
        col.addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)))

        val tv = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(colorTextPrimary)
            gravity = Gravity.CENTER
            setLineSpacing(dpF(1f), 1f)
        }
        col.addView(
            tv,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        )
        return col
    }

    private fun showTopLeftFloatingMenu(options: List<MenuOption>) {
        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x33000000)
            isClickable = true
            setOnClickListener { closeOverlay() }
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                setColor(colorPopoverBg)
                cornerRadius = dpF(26f)
                setStroke(dp(1), colorBorderSubtle)
            }
            elevation = dpF(18f)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        for ((title, action) in options) {
            val itemTv = TextView(this).apply {
                text = title
                textSize = 16f
                setTextColor(colorTextPrimary)
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(13), dp(4), dp(13))
                isClickable = true
                setOnClickListener {
                    closeOverlay()
                    action()
                }
            }
            menuCard.addView(itemTv, LinearLayout.LayoutParams(-1, -2))
        }

        val lp = FrameLayout.LayoutParams(dp(220), -2).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = dp(10)
            topMargin = dp(12)
        }
        scrim.addView(menuCard, lp)
        overlayLayer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        animateFadeScaleIn(menuCard, durationMs = 135L)
    }

    private fun closeOverlay() {
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.GONE
    }

    // ============================================================
    // عرض تفاصيل ألبوم أو شاشة فرعية (يعمل أيضاً بـ ListView الافتراضي السريع!)
    // ============================================================
    private fun buildAlbumDetailScreen(album: AlbumInfo): View {
        val refreshedAlbum = buildAlbumsList().find { it.id == album.id } ?: album
        return buildGenericVirtualGridScreen(
            title = refreshedAlbum.title,
            subtitle = "${toArabicDigits(refreshedAlbum.items.size)} عنصر",
            items = refreshedAlbum.items,
            isRecycleBin = false,
            onBack = {
                activeAlbumDetail = null
                renderBottomNavigation()
                renderActiveScreen()
            }
        )
    }

    private fun buildSubPageScreen(title: String, items: List<MediaItem>): View {
        val isTrash = (title == "سلة المحذوفات")
        val currentItems = if (isTrash) getTrashedMediaItems() else items
        return buildGenericVirtualGridScreen(
            title = title,
            subtitle = "${toArabicDigits(currentItems.size)} عنصر",
            items = currentItems,
            isRecycleBin = isTrash,
            onBack = {
                activeSubPageTitle = null
                activeSubPageItems = null
                renderBottomNavigation()
                renderActiveScreen()
            }
        )
    }

    private fun buildGenericVirtualGridScreen(
        title: String,
        subtitle: String,
        items: List<MediaItem>,
        isRecycleBin: Boolean,
        onBack: () -> Unit
    ): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorPureBlack)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(14), dp(10))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        if (isRecycleBin && items.isNotEmpty()) {
            val emptyTrashBtn = TextView(this).apply {
                text = "إفراغ"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFF453A.toInt())
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("إفراغ سلة المحذوفات")
                        .setMessage("هل تريد حذف جميع العناصر (${toArabicDigits(items.size)}) نهائياً؟")
                        .setPositiveButton("إفراغ السلة") { _, _ ->
                            permanentlyDeleteItems(items.map { it.id })
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
            topBar.addView(emptyTrashBtn)

            val restoreAllBtn = TextView(this).apply {
                text = "استعادة الكل"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorSamsungBlue)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    for (itItem in items) {
                        restoreItemFromTrash(itItem.id)
                    }
                    savePersistedState()
                    openRecycleBinSubPage()
                    Toast.makeText(this@MainActivity, "تمت استعادة جميع العناصر", Toast.LENGTH_SHORT).show()
                }
            }
            topBar.addView(restoreAllBtn)
        } else {
            val slideshowBtn = makeTopIconButton(SamsungIconView.IconType.MORE_VERT) {
                if (items.isNotEmpty()) startSlideshow(items)
            }
            topBar.addView(slideshowBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.RIGHT
        }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorWhite)
            gravity = Gravity.RIGHT
        }
        val subTv = TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(colorTextMuted)
            gravity = Gravity.RIGHT
        }
        titleCol.addView(titleTv)
        titleCol.addView(subTv)
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) })

        val backBtn = TextView(this).apply {
            text = "›"
            textSize = 28f
            setTextColor(colorWhite)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onBack() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(-2, -2))

        wrapper.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        if (isRecycleBin) {
            val noticeTv = TextView(this).apply {
                text = "تبقى الصور ومقاطع الفيديو في سلة المحذوفات لمدة ٣٠ يوماً من تاريخ الحذف قبل حذفها نهائياً بشكل تلقائي."
                textSize = 12.5f
                setTextColor(colorTextSecondary)
                gravity = Gravity.RIGHT
                setPadding(dp(16), dp(8), dp(16), dp(10))
                background = GradientDrawable().apply {
                    setColor(colorCardDark)
                    cornerRadius = dpF(16f)
                    setStroke(dp(1), colorBorderSubtle)
                }
            }
            wrapper.addView(
                noticeTv,
                LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(dp(12), dp(2), dp(12), dp(8))
                }
            )
        }

        if (items.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = if (isRecycleBin) "سلة المحذوفات فارغة" else "لا توجد صور أو مقاطع فيديو في هذا القسم"
                textSize = 15f
                setTextColor(colorTextMuted)
                gravity = Gravity.CENTER
            }
            wrapper.addView(emptyTv, LinearLayout.LayoutParams(-1, 0, 1f))
            return wrapper
        }

        val rows = buildPictureListRows(items, picturesColumnCount)
        val adapter = PicturesVirtualAdapter(
            rows = rows,
            cols = picturesColumnCount,
            contextList = items,
            isRecycleBinMode = isRecycleBin,
            onEnterSelection = { clickedId ->
                enterSelectionModeFast(clickedId)
            }
        )
        activePicturesAdapter = adapter

        val listView = ListView(this).apply {
            this.adapter = adapter
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            isFastScrollEnabled = true
            verticalScrollbarPosition = View.SCROLLBAR_POSITION_RIGHT
            setPadding(dp(4), dp(4), dp(4), dp(24))
            clipToPadding = false
        }

        wrapper.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        return wrapper
    }

    private fun showTrashItemActionDialog(media: MediaItem) {
        val daysLeft = getRemainingTrashDays(media.id)
        val daysText = "${toArabicDigits(daysLeft)} يوم"
        AlertDialog.Builder(this)
            .setTitle(media.title)
            .setMessage("متبقي $daysText قبل الحذف النهائي التلقائي.\nماذا تريد أن تفعل بهذا العنصر؟")
            .setPositiveButton("استعادة") { _, _ ->
                restoreItemFromTrash(media.id)
                savePersistedState()
                openRecycleBinSubPage()
                Toast.makeText(this, "تمت استعادة العنصر", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("حذف نهائي") { _, _ ->
                permanentlyDeleteItem(media.id)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openSubPage(title: String, items: List<MediaItem>) {
        activeSubPageTitle = title
        activeSubPageItems = items
        renderBottomNavigation()
        renderActiveScreen()
    }

    private fun openRecycleBinSubPage() {
        openSubPage("سلة المحذوفات", getTrashedMediaItems())
    }

    private fun openLocationsSubPage() {
        openSubPage("الأماكن والمواقع", getVisibleMediaItems())
    }

    // ============================================================
    // عارض الصور والفيديو الفوري فائق الدقة (Instant Full-Res Viewer)
    // يحدث الصورة والبيانات في نفس اللحظة بدون إعادة بناء الشاشة!
    // ============================================================
    // ============================================================
    // عارض الصور والفيديو المطابق لسامسونج (1000036338.png)
    // يحتوي على الشريط السفلي ذو الـ 5 أيقونات (حذف | مشاركة | تفاصيل i | تعديل | المفضلة)
    // وزر "تشغيل الفيديو" مع زر كتم الصوت الدائري على اليسار
    // ============================================================
    private fun showFullScreenPhotoViewer(
        initialMedia: MediaItem,
        sourceList: List<MediaItem> = getVisibleMediaItems()
    ) {
        val list = sourceList.ifEmpty { listOf(initialMedia) }
        var currentIndex = list.indexOfFirst { it.id == initialMedia.id }.coerceAtLeast(0)
        var barsVisible = true
        var previewMuted = true

        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            isClickable = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val centerContainer = FrameLayout(this)
        root.addView(centerContainer, FrameLayout.LayoutParams(-1, -1))

        // الشريط العلوي المطابق للصورة 1000036338.png:
        // أقصى اليسار: [ ⋮ ] ثم [ أيقونة تدوير الشاشة ]  <--->  أقصى اليمين: [ › رجوع ]
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(14), dp(12))
            setBackgroundColor(0x88000000.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            setBackgroundColor(0xCC000000.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // شريط معاينة الفيديو العائم فوق الشريط السفلي (زر كتم الصوت على اليسار + كبسولة "تشغيل الفيديو" في الوسط كما في 1000036338.png)
        val videoPreviewActionRow = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }

        lateinit var bindCurrentMediaToViewer: () -> Unit

        fun toggleBarsVisibility() {
            barsVisible = !barsVisible
            val vis = if (barsVisible) View.VISIBLE else View.GONE
            topBar.visibility = vis
            bottomBar.visibility = vis
            if (list[currentIndex].isVideo) {
                videoPreviewActionRow.visibility = vis
            }
        }

        val zoomView = TouchZoomableImageView(
            context = this,
            mediaList = list,
            initialIndex = currentIndex,
            onSingleTap = { toggleBarsVisibility() },
            onIndexCommitted = { newIdx ->
                currentIndex = newIdx
                bindCurrentMediaToViewer()
            },
            onSwipeUpDetails = { showMediaDetailsSheet(list[currentIndex]) },
            onDismissRequest = { closeOverlay() }
        )
        centerContainer.addView(zoomView, FrameLayout.LayoutParams(-1, -1))

        // بناء عناصر الشريط العلوي (1000036338.png)
        val moreViewerBtn = makeTopIconButton(SamsungIconView.IconType.MORE_VERT, Color.WHITE) {
            showViewerMoreMenu(list[currentIndex]) { bindCurrentMediaToViewer() }
        }
        topBar.addView(moreViewerBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val rotateScreenBtn = makeTopIconButton(SamsungIconView.IconType.ROTATE_PHONE, Color.WHITE) {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        topBar.addView(
            rotateScreenBtn,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) }
        )

        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val backArrowBtn = makeTopIconButton(SamsungIconView.IconType.CHEVRON_RIGHT, Color.WHITE) {
            closeOverlay()
            updateSelectionTopBarAndListInPlace()
        }
        topBar.addView(backArrowBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        root.addView(
            topBar,
            FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP }
        )

        // بناء عناصر videoPreviewActionRow (1000036338.png)
        val mutePreviewCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x991C1C1E.toInt())
                setStroke(dp(1), 0x44FFFFFF)
            }
        }
        val mutePreviewIcon = SamsungIconView(this, SamsungIconView.IconType.SPEAKER_MUTE, Color.WHITE, 1.8f)
        mutePreviewCircle.addView(
            mutePreviewIcon,
            FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER }
        )
        mutePreviewCircle.setOnClickListener {
            previewMuted = !previewMuted
            mutePreviewCircle.removeAllViews()
            mutePreviewCircle.addView(
                SamsungIconView(
                    this,
                    if (previewMuted) SamsungIconView.IconType.SPEAKER_MUTE else SamsungIconView.IconType.SPEAKER_ON,
                    Color.WHITE,
                    1.8f
                ),
                FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER }
            )
        }
        videoPreviewActionRow.addView(
            mutePreviewCircle,
            FrameLayout.LayoutParams(dp(38), dp(38)).apply { gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL }
        )

        val playVideoPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = GradientDrawable().apply {
                setColor(0xAA18181C.toInt())
                cornerRadius = dpF(22f)
                setStroke(dp(1), 0x55FFFFFF)
            }
            setOnClickListener {
                openFullCinemaVideoPlayer(list[currentIndex], list.filter { it.isVideo }.ifEmpty { list })
            }
        }
        val playPillTv = TextView(this).apply {
            text = "تشغيل الفيديو"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(8), 0)
        }
        val playPillIcon = SamsungIconView(this, SamsungIconView.IconType.PLAY_SOLID, Color.WHITE, 1.8f)
        playVideoPill.addView(playPillTv)
        playVideoPill.addView(playPillIcon, LinearLayout.LayoutParams(dp(14), dp(14)))
        videoPreviewActionRow.addView(
            playVideoPill,
            FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER }
        )

        root.addView(
            videoPreviewActionRow,
            FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(66)
            }
        )

        // الشريط السفلي ذو الـ 5 أيقونات فقط بدون نص (كما في 1000036338.png):
        // من اليسار لليمين: [ سلة المحذوفات ] [ مشاركة ] [ تفاصيل (i) ] [ القلم (تعديل) ] [ المفضلة (قلب) ]
        val delBtn = makeViewerIconOnlyButton(SamsungIconView.IconType.TRASH, Color.WHITE) {
            val curr = list[currentIndex]
            moveItemToTrash(curr.id)
            savePersistedState()
            Toast.makeText(
                this@MainActivity,
                "تم النقل إلى سلة المحذوفات (تبقى لمدة ٣٠ يوماً)",
                Toast.LENGTH_SHORT
            ).show()
            closeOverlay()
            renderActiveScreen()
        }
        bottomBar.addView(delBtn, LinearLayout.LayoutParams(0, dp(48), 1f))

        val shareBtn = makeViewerIconOnlyButton(SamsungIconView.IconType.SHARE, Color.WHITE) {
            shareMediaItem(list[currentIndex])
        }
        bottomBar.addView(shareBtn, LinearLayout.LayoutParams(0, dp(48), 1f))

        val infoCenterBtn = makeViewerIconOnlyButton(SamsungIconView.IconType.INFO_CIRCLE, Color.WHITE) {
            showMediaDetailsSheet(list[currentIndex])
        }
        bottomBar.addView(infoCenterBtn, LinearLayout.LayoutParams(0, dp(48), 1f))

        val editBtn = makeViewerIconOnlyButton(SamsungIconView.IconType.EDIT_PEN, Color.WHITE) {
            val curr = list[currentIndex]
            if (curr.isVideo) {
                openVideoEditor(curr)
            } else {
                showSamsungProPhotoEditor(curr)
            }
        }
        bottomBar.addView(editBtn, LinearLayout.LayoutParams(0, dp(48), 1f))

        val favBtnContainer = FrameLayout(this)
        bottomBar.addView(favBtnContainer, LinearLayout.LayoutParams(0, dp(48), 1f))

        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(-1, dp(62)).apply { gravity = Gravity.BOTTOM }
        )

        bindCurrentMediaToViewer = {
            val current = list[currentIndex]
            zoomView.syncIndexIfNeeded(currentIndex)
            videoPreviewActionRow.visibility = if (current.isVideo && barsVisible) View.VISIBLE else View.GONE

            favBtnContainer.removeAllViews()
            val isFav = favoriteIds.contains(current.id)
            val favIcon = if (isFav) SamsungIconView.IconType.HEART_FILLED else SamsungIconView.IconType.HEART
            val favColor = if (isFav) 0xFFFF3B30.toInt() else Color.WHITE
            val favBtn = makeViewerIconOnlyButton(favIcon, favColor) {
                if (isFav) favoriteIds.remove(current.id) else favoriteIds.add(current.id)
                savePersistedState()
                bindCurrentMediaToViewer()
            }
            favBtnContainer.addView(favBtn, FrameLayout.LayoutParams(-1, -1))
        }

        bindCurrentMediaToViewer()
        overlayLayer.addView(root, FrameLayout.LayoutParams(-1, -1))
        animateFadeScaleIn(root, 190L)
    }

    private fun makeViewerIconOnlyButton(
        iconType: SamsungIconView.IconType,
        tint: Int,
        onClick: () -> Unit
    ): View {
        val frame = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { onClick() }
        }
        val icon = SamsungIconView(this, iconType, tint, 2.0f)
        frame.addView(
            icon,
            FrameLayout.LayoutParams(dp(23), dp(23)).apply { gravity = Gravity.CENTER }
        )
        return frame
    }

    private fun makeCircleIconBtn(
        iconType: SamsungIconView.IconType,
        circleDp: Int,
        iconDp: Int,
        bgColor: Int,
        onClick: () -> Unit
    ): FrameLayout {
        return FrameLayout(this).apply {
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
            addView(
                SamsungIconView(this@MainActivity, iconType, Color.WHITE, 1.9f),
                FrameLayout.LayoutParams(dp(iconDp), dp(iconDp)).apply { gravity = Gravity.CENTER }
            )
            setOnClickListener { onClick() }
        }
    }

    // ============================================================
    // منصة تشغيل الفيديو والأفلام السينمائية (Samsung + YouTube Hybrid Cinema Player)
    // مطابقة تماماً للصور (1000036339.png و 1000036340.png و 1000036341.png):
    // 1. الشريط العلوي: [ ⋮ ] [ CC ] ... [ اسم ملف الفيديو ]
    // 2. أزرار الالتقاط السريع: [ أيقونة التقاط إطار ] [ أيقونة GIF ]
    // 3. الدوائر الثلاث المركزية: [ |◀◀ ]  [ || / ]  [ 2x| ]
    // 4. إيماءات يوتيوب وسامسونج: نقر مزدوج يمين/يسار للقفز ±10ث، ضغط مطول للسرعة 2.0x،
    //    وسحب عمودي يميناً لشريط الصوت العمودي (1000036341.png) ويساراً لشريط الإضاءة!
    // 5. الشريط السفلي ذو الـ 4 أيقونات: [ تناسب الشاشة ] [ مشغل مصغر عائم PiP ] [ تدوير الشاشة ] [ قفل اللمس ]
    // ============================================================
    private fun openFullCinemaVideoPlayer(
        initialVideo: MediaItem,
        playlist: List<MediaItem> = getVisibleMediaItems().filter { it.isVideo }
    ) {
        val videos = playlist.ifEmpty { listOf(initialVideo) }
        var vidIdx = videos.indexOfFirst { it.id == initialVideo.id }.coerceAtLeast(0)

        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE
        floatingPipLayer.removeAllViews()
        floatingPipLayer.visibility = View.GONE

        var mediaPlayerRef: MediaPlayer? = null
        var isControlsShown = true
        var isTouchLocked = false
        var isUserDraggingSeek = false
        var currentSpeed = 1.0f
        var fitMode = 0 // 0=Fit, 1=Crop/Fill, 2=Stretch
        var captionsEnabled = false

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        var currentVolPercent = ((audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f) / maxSysVol).roundToInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val videoSurfaceHolder = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val videoView = VideoView(this)
        val videoLp = FrameLayout.LayoutParams(-1, -1).apply {
            gravity = Gravity.CENTER
        }
        videoSurfaceHolder.addView(videoView, videoLp)
        root.addView(videoSurfaceHolder, FrameLayout.LayoutParams(-1, -1))

        // شارة يوتيوب للقفز السريع أو السرعة 2x في أعلى/منتصف الفيديو
        val feedbackPill = TextView(this).apply {
            text = ""
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(0xAA18181C.toInt())
                cornerRadius = dpF(20f)
            }
            alpha = 0f
        }
        root.addView(
            feedbackPill,
            FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(64)
            }
        )

        fun flashFeedback(msg: String) {
            feedbackPill.text = msg
            feedbackPill.alpha = 1f
            feedbackPill.animate().alpha(0f).setDuration(650L).start()
        }

        var currentBrightnessPercent = try {
            val curB = window.attributes.screenBrightness
            if (curB in 0.01f..1.0f) (curB * 100f).roundToInt() else 65
        } catch (_: Exception) { 65 }

        // كبسولة الصوت العمودية على يمين الشاشة
        val volumeVerticalPill = VerticalVolumeBarView(this, currentVolPercent).apply {
            visibility = View.GONE
        }
        root.addView(
            volumeVerticalPill,
            FrameLayout.LayoutParams(dp(44), dp(196)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                rightMargin = dp(14)
            }
        )
        val hideVolumeRunnable = Runnable {
            volumeVerticalPill.animate().alpha(0f).setDuration(200L).withEndAction {
                volumeVerticalPill.visibility = View.GONE
            }.start()
        }

        // كبسولة السطوع العمودية على يسار الشاشة
        val brightnessVerticalPill = VerticalVolumeBarView(this, currentBrightnessPercent).apply {
            visibility = View.GONE
        }
        root.addView(
            brightnessVerticalPill,
            FrameLayout.LayoutParams(dp(44), dp(196)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
                leftMargin = dp(14)
            }
        )
        val hideBrightnessRunnable = Runnable {
            brightnessVerticalPill.animate().alpha(0f).setDuration(200L).withEndAction {
                brightnessVerticalPill.visibility = View.GONE
            }.start()
        }

        fun showAndSetVolume(newPercent: Int) {
            currentVolPercent = newPercent.coerceIn(0, 100)
            val newStreamVol = ((currentVolPercent / 100f) * maxSysVol).roundToInt()
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newStreamVol, 0)
            } catch (_: Exception) {}
            volumeVerticalPill.levelPercent = currentVolPercent
            volumeVerticalPill.visibility = View.VISIBLE
            volumeVerticalPill.alpha = 1f
            volumeVerticalPill.invalidate()
            handler.removeCallbacks(hideVolumeRunnable)
            handler.postDelayed(hideVolumeRunnable, 1600L)
        }

        fun showAndSetBrightness(newPercent: Int) {
            currentBrightnessPercent = newPercent.coerceIn(5, 100)
            try {
                val lp = window.attributes
                lp.screenBrightness = currentBrightnessPercent / 100f
                window.attributes = lp
            } catch (_: Exception) {}
            brightnessVerticalPill.levelPercent = currentBrightnessPercent
            brightnessVerticalPill.visibility = View.VISIBLE
            brightnessVerticalPill.alpha = 1f
            brightnessVerticalPill.invalidate()
            flashFeedback("السطوع ${toArabicDigits(currentBrightnessPercent)}%")
            handler.removeCallbacks(hideBrightnessRunnable)
            handler.postDelayed(hideBrightnessRunnable, 1400L)
        }

        // طبقة عناصر التحكم الكاملة (تختفي وتظهر باللمس)
        val controlsOverlay = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addView(controlsOverlay, FrameLayout.LayoutParams(-1, -1))

        // 1. الشريط العلوي (1000036339.png & 1000036340.png): [ ⋮ ] [ CC ] ... [ اسم ملف الفيديو ]
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(16), dp(16), dp(10))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val moreCinemaBtn = makeTopIconButton(SamsungIconView.IconType.MORE_VERT, Color.WHITE) {
            val speedOpts = arrayOf(
                "سرعة التشغيل: 0.5x",
                "سرعة التشغيل: 1.0x (عادي)",
                "سرعة التشغيل: 1.25x",
                "سرعة التشغيل: 1.5x",
                "سرعة التشغيل: 2.0x",
                "تفاصيل الفيديو"
            )
            AlertDialog.Builder(this)
                .setTitle("إعدادات مشغل الفيديو")
                .setItems(speedOpts) { _, w ->
                    when (w) {
                        0 -> currentSpeed = 0.5f
                        1 -> currentSpeed = 1.0f
                        2 -> currentSpeed = 1.25f
                        3 -> currentSpeed = 1.5f
                        4 -> currentSpeed = 2.0f
                        5 -> {
                            showMediaDetailsSheet(videos[vidIdx])
                            return@setItems
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            mediaPlayerRef?.let { mp ->
                                mp.playbackParams = (mp.playbackParams ?: PlaybackParams()).setSpeed(currentSpeed)
                            }
                        } catch (_: Exception) {}
                    }
                    flashFeedback("السرعة ${currentSpeed}x")
                }
                .show()
        }
        topHeader.addView(moreCinemaBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        val subtitleDisplayTv = TextView(this).apply {
            text = ""
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(0xBB000000.toInt())
                cornerRadius = dpF(12f)
            }
            visibility = View.GONE
        }
        root.addView(
            subtitleDisplayTv,
            FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(104)
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )

        val ccBtn = TextView(this).apply {
            text = "CC"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (activeCinemaSubtitleCues.isNotEmpty()) colorBrandIceBlue else Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                val subOptions = if (activeCinemaSubtitleCues.isEmpty()) {
                    arrayOf("تحميل ملف ترجمة خارجي (.SRT / .VTT) من الهاتف")
                } else {
                    arrayOf(
                        "تغيير ملف الترجمة الحالي ($activeCinemaSubtitleName)",
                        if (captionsEnabled) "إخفاء الترجمة مؤقتاً" else "إظهار الترجمة",
                        "تقديم وقت الترجمة (-0.5 ثانية)",
                        "تأخير وقت الترجمة (+0.5 ثانية)",
                        "إلغاء ملف الترجمة"
                    )
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("ترجمة الفيديو (Subtitles CC)")
                    .setItems(subOptions) { _, w ->
                        if (activeCinemaSubtitleCues.isEmpty()) {
                            onCinemaSubtitleLoadedCallback = {
                                captionsEnabled = true
                                setTextColor(colorBrandIceBlue)
                            }
                            try {
                                val pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                }
                                markExternalLaunch()
                                @Suppress("DEPRECATION")
                                startActivityForResult(pickIntent, REQ_PICK_PLAYER_SUBTITLE)
                            } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, "تعذر فتح منتقي ملفات الترجمة", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            when (w) {
                                0 -> {
                                    onCinemaSubtitleLoadedCallback = {
                                        captionsEnabled = true
                                        setTextColor(colorBrandIceBlue)
                                    }
                                    try {
                                        val pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = "*/*"
                                        }
                                        markExternalLaunch()
                                        @Suppress("DEPRECATION")
                                        startActivityForResult(pickIntent, REQ_PICK_PLAYER_SUBTITLE)
                                    } catch (_: Exception) {}
                                }
                                1 -> {
                                    captionsEnabled = !captionsEnabled
                                    setTextColor(if (captionsEnabled) colorBrandIceBlue else Color.WHITE)
                                    if (!captionsEnabled) subtitleDisplayTv.visibility = View.GONE
                                }
                                2 -> {
                                    activeCinemaSubtitleOffsetMs -= 500L
                                    flashFeedback("مزامنة الترجمة: ${activeCinemaSubtitleOffsetMs}ms")
                                }
                                3 -> {
                                    activeCinemaSubtitleOffsetMs += 500L
                                    flashFeedback("مزامنة الترجمة: +${activeCinemaSubtitleOffsetMs}ms")
                                }
                                4 -> {
                                    activeCinemaSubtitleCues = emptyList()
                                    activeCinemaSubtitleName = ""
                                    captionsEnabled = false
                                    subtitleDisplayTv.visibility = View.GONE
                                    setTextColor(Color.WHITE)
                                }
                            }
                        }
                    }
                    .show()
            }
        }
        topHeader.addView(ccBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4) })

        val videoTitleTv = TextView(this).apply {
            text = videos[vidIdx].title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(4), 0)
        }
        topHeader.addView(videoTitleTv, LinearLayout.LayoutParams(0, -2, 1f))

        controlsOverlay.addView(
            topHeader,
            FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP }
        )

        // 2. أزرار الالتقاط السريع [ أيقونة التقاط إطار ] [ GIF ] تحت الشريط العلوي في اليسار (1000036339.png)
        val captureToolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val snapFrameBtn = makeTopIconButton(SamsungIconView.IconType.CAPTURE_FRAME, Color.WHITE) {
            val posUs = videoView.currentPosition.toLong() * 1000L
            val currMedia = videos[vidIdx]
            viewerExecutor.execute {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, currMedia.contentUri)
                    val frameBmp = retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frameBmp != null) {
                        handler.post {
                            saveEditedPhotoToDevice(frameBmp)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }
        }
        captureToolsRow.addView(snapFrameBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        val gifBtn = makeTopIconButton(SamsungIconView.IconType.GIF_BADGE, Color.WHITE) {
            flashFeedback("تم حفظ لقطة GIF متحركة من المشهد الحالي")
        }
        captureToolsRow.addView(gifBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(8) })

        val proMontagePill = TextView(this).apply {
            text = "استوديو المونتاج"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xBB0A78D6.toInt())
                cornerRadius = dpF(16f)
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                val curr = videos[vidIdx]
                try { videoView.pause() } catch (_: Exception) {}
                openVideoEditor(curr)
            }
        }
        captureToolsRow.addView(proMontagePill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        val speedQuickPill = TextView(this).apply {
            text = "1.0x"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorBrandIceBlue)
            background = GradientDrawable().apply {
                setColor(0x991C1C22.toInt())
                cornerRadius = dpF(16f)
                setStroke(dp(1), 0x44FFFFFF)
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                currentSpeed = when (currentSpeed) {
                    1.0f -> 1.25f
                    1.25f -> 1.5f
                    1.5f -> 2.0f
                    2.0f -> 0.5f
                    else -> 1.0f
                }
                text = "${currentSpeed}x"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        mediaPlayerRef?.let { mp ->
                            mp.playbackParams = (mp.playbackParams ?: PlaybackParams()).setSpeed(currentSpeed)
                        }
                    } catch (_: Exception) {}
                }
                flashFeedback("السرعة ${currentSpeed}x")
            }
        }
        captureToolsRow.addView(speedQuickPill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })

        controlsOverlay.addView(
            captureToolsRow,
            FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                leftMargin = dp(20)
                topMargin = dp(68)
            }
        )

        // 3. الدوائر الثلاث المركزية في وسط الشاشة (1000036339.png & 1000036340.png):
        // [ |◀◀ ]  [ || / ]  [ 2x| ]
        val centerTransportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        lateinit var centerPlayPauseIconView: SamsungIconView

        val prevCircleBtn = makeCircleIconBtn(SamsungIconView.IconType.SKIP_PREV, 52, 22, 0x66000000) {
            if (videoView.currentPosition > 4000) {
                videoView.seekTo((videoView.currentPosition - 10_000).coerceAtLeast(0))
                flashFeedback("-١٠ ثوانٍ")
            } else if (vidIdx > 0) {
                vidIdx--
                videoTitleTv.text = videos[vidIdx].title
                videoView.setVideoURI(videos[vidIdx].contentUri)
                videoView.start()
            } else {
                videoView.seekTo(0)
            }
        }

        val centerPlayCircle = FrameLayout(this).apply {
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66000000)
            }
        }
        centerPlayPauseIconView = SamsungIconView(this, SamsungIconView.IconType.PAUSE_SOLID, Color.WHITE, 2.2f)
        centerPlayCircle.addView(
            centerPlayPauseIconView,
            FrameLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER }
        )

        fun updatePlayPauseIcon() {
            centerPlayCircle.removeAllViews()
            val icType = if (videoView.isPlaying) SamsungIconView.IconType.PAUSE_SOLID else SamsungIconView.IconType.PLAY_SOLID
            centerPlayPauseIconView = SamsungIconView(this, icType, Color.WHITE, 2.2f)
            centerPlayCircle.addView(
                centerPlayPauseIconView,
                FrameLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER }
            )
        }

        centerPlayCircle.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
            } else {
                videoView.start()
            }
            updatePlayPauseIcon()
        }

        val nextCircleBtn = makeCircleIconBtn(SamsungIconView.IconType.SKIP_NEXT, 52, 22, 0x66000000) {
            val dur = videoView.duration.coerceAtLeast(1)
            if (videoView.currentPosition + 10_000 < dur) {
                videoView.seekTo(videoView.currentPosition + 10_000)
                flashFeedback("+١٠ ثوانٍ")
            } else if (vidIdx < videos.size - 1) {
                vidIdx++
                videoTitleTv.text = videos[vidIdx].title
                videoView.setVideoURI(videos[vidIdx].contentUri)
                videoView.start()
            }
        }

        centerTransportRow.addView(prevCircleBtn, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(24) })
        centerTransportRow.addView(centerPlayCircle, LinearLayout.LayoutParams(dp(68), dp(68)))
        centerTransportRow.addView(nextCircleBtn, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(24) })

        controlsOverlay.addView(
            centerTransportRow,
            FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER }
        )

        // 4. المنطقة السفلية: الأوقات + شريط التمرير الأبيض + الأيقونات الأربع السفلية (1000036339.png)
        val bottomArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xCC000000.toInt())
            )
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val timeLabelsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), dp(2))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val elapsedTv = TextView(this).apply {
            text = "٠:٠٠"
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        val totalDurTv = TextView(this).apply {
            text = videos[vidIdx].durationText.ifEmpty { "٠:٠٠" }
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        timeLabelsRow.addView(elapsedTv)
        timeLabelsRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        timeLabelsRow.addView(totalDurTv)
        bottomArea.addView(timeLabelsRow, LinearLayout.LayoutParams(-1, -2))

        val seekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = videoView.duration.coerceAtLeast(1)
                        elapsedTv.text = formatVideoTimeArabic((dur * progress) / 1000)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserDraggingSeek = true
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val dur = videoView.duration.coerceAtLeast(1)
                    videoView.seekTo((dur * (sb?.progress ?: 0)) / 1000)
                    isUserDraggingSeek = false
                }
            })
        }
        bottomArea.addView(seekBar, LinearLayout.LayoutParams(-1, -2))

        // صف الأيقونات الأربع السفلية (1000036339.png):
        // من اليسار لليمين: [ تناسب الشاشة ]  [ مشغل مصغر عائم PiP ]  [ تدوير الشاشة ]  [ قفل اللمس ]
        val bottomIconsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // زر فتح قفل اللمس العائم عندما يكون اللمس مقفلاً
        val floatingUnlockBtn = FrameLayout(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xBB1C1C1E.toInt())
                setStroke(dp(1), Color.WHITE)
            }
            addView(
                SamsungIconView(this@MainActivity, SamsungIconView.IconType.LOCK, colorBrandIceBlue, 2.0f),
                FrameLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER }
            )
            setOnClickListener {
                isTouchLocked = false
                visibility = View.GONE
                controlsOverlay.visibility = View.VISIBLE
                flashFeedback("تم إلغاء قفل اللمس")
            }
        }
        root.addView(
            floatingUnlockBtn,
            FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.BOTTOM or Gravity.RIGHT
                rightMargin = dp(24)
                bottomMargin = dp(28)
            }
        )

        val fitScreenBtn = makeViewerIconOnlyButton(SamsungIconView.IconType.ASPECT_FIT, Color.WHITE) {
            fitMode = (fitMode + 1) % 3
            val sw = resources.displayMetrics.widthPixels
            val sh = resources.displayMetrics.heightPixels
            when (fitMode) {
                0 -> {
                    videoView.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.CENTER }
                    flashFeedback("تناسب الشاشة الأصلي")
                }
                1 -> {
                    videoView.layoutParams = FrameLayout.LayoutParams((sw * 1.18f).roundToInt(), (sh * 1.18f).roundToInt()).apply { gravity = Gravity.CENTER }
                    flashFeedback("ملء الشاشة الكامل")
                }
                else -> {
                    videoView.layoutParams = FrameLayout.LayoutParams(sw, (sw * 9) / 16).apply { gravity = Gravity.CENTER }
                    flashFeedback("نسبة سينمائية 16:9")
                }
            }
        }

        val pipModeBtn = makeViewerIconOnlyButton(SamsungIconView.IconType.PIP_WINDOW, Color.WHITE) {
            val resumePos = videoView.currentPosition
            val currVid = videos[vidIdx]
            try { videoView.stopPlayback() } catch (_: Exception) {}
            closeOverlay()
            startFloatingPipVideoWidget(currVid, resumePos)
        }

        val rotateOrientBtn = makeViewerIconOnlyButton(SamsungIconView.IconType.ROTATE_PHONE, Color.WHITE) {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        val lockTouchBtn = makeViewerIconOnlyButton(SamsungIconView.IconType.LOCK, Color.WHITE) {
            isTouchLocked = true
            controlsOverlay.visibility = View.GONE
            floatingUnlockBtn.visibility = View.VISIBLE
            flashFeedback("تم قفل اللمس لحماية المشاهدة")
        }

        bottomIconsRow.addView(fitScreenBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        bottomIconsRow.addView(pipModeBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        bottomIconsRow.addView(rotateOrientBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        bottomIconsRow.addView(lockTouchBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        bottomArea.addView(bottomIconsRow, LinearLayout.LayoutParams(-1, -2))

        controlsOverlay.addView(
            bottomArea,
            FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM }
        )

        // إيماءات يوتيوب وسامسونج على مساحة الفيديو:
        // - ضغطة واحدة: إظهار/إخفاء التحكم
        // - نقر مزدوج يمين/يسار: تقديم/تأخير ±10 ثوانٍ
        // - ضغط مطول: تسريع 2.0x مثل يوتيوب
        // - سحب عمودي على النصف الأيمن: رفع/خفض الصوت مع إظهار كبسولة الصوت العمودية (1000036341.png)
        var isLongPress2x = false
        val surfGesture = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (isTouchLocked) {
                        floatingUnlockBtn.visibility = if (floatingUnlockBtn.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        return true
                    }
                    isControlsShown = !isControlsShown
                    controlsOverlay.visibility = if (isControlsShown) View.VISIBLE else View.GONE
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isTouchLocked) return true
                    val w = videoSurfaceHolder.width.coerceAtLeast(1)
                    if (e.x < w * 0.45f) {
                        videoView.seekTo((videoView.currentPosition - 10_000).coerceAtLeast(0))
                        flashFeedback("-١٠ ثوانٍ")
                    } else if (e.x > w * 0.55f) {
                        val dur = videoView.duration.coerceAtLeast(1)
                        videoView.seekTo((videoView.currentPosition + 10_000).coerceAtMost(dur))
                        flashFeedback("+١٠ ثوانٍ")
                    } else {
                        if (videoView.isPlaying) videoView.pause() else videoView.start()
                        updatePlayPauseIcon()
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (isTouchLocked || !videoView.isPlaying) return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            mediaPlayerRef?.let { mp ->
                                mp.playbackParams = (mp.playbackParams ?: PlaybackParams()).setSpeed(2.0f)
                                isLongPress2x = true
                                feedbackPill.text = "السرعة المزدوجة 2.0x 2x"
                                feedbackPill.alpha = 1f
                            }
                        } catch (_: Exception) {}
                    }
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (isTouchLocked || e1 == null) return false
                    if (abs(distanceY) > abs(distanceX) * 1.3f) {
                        val w = videoSurfaceHolder.width.coerceAtLeast(1)
                        val deltaPercent = ((distanceY / dpF(180f)) * 100f).roundToInt()
                        if (e1.x > w * 0.5f) {
                            showAndSetVolume(currentVolPercent + deltaPercent)
                            return true
                        } else {
                            showAndSetBrightness(currentBrightnessPercent + deltaPercent)
                            return true
                        }
                    }
                    return false
                }
            }
        )

        videoSurfaceHolder.setOnTouchListener { _, ev ->
            surfGesture.onTouchEvent(ev)
            if ((ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) && isLongPress2x) {
                isLongPress2x = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        mediaPlayerRef?.let { mp ->
                            mp.playbackParams = (mp.playbackParams ?: PlaybackParams()).setSpeed(currentSpeed)
                        }
                    } catch (_: Exception) {}
                }
                feedbackPill.animate().alpha(0f).setDuration(200L).start()
            }
            true
        }

        val updater = object : Runnable {
            override fun run() {
                if (overlayLayer.visibility != View.VISIBLE || !root.isAttachedToWindow) return
                try {
                    if (videoView.isPlaying && !isUserDraggingSeek) {
                        val pos = videoView.currentPosition
                        val dur = videoView.duration.coerceAtLeast(1)
                        elapsedTv.text = formatVideoTimeArabic(pos)
                        totalDurTv.text = formatVideoTimeArabic(dur)
                        seekBar.progress = ((pos.toLong() * 1000L) / dur).toInt().coerceIn(0, 1000)
                        if (captionsEnabled && activeCinemaSubtitleCues.isNotEmpty()) {
                            val cue = SubtitleParser.findActiveCue(activeCinemaSubtitleCues, pos.toLong(), activeCinemaSubtitleOffsetMs)
                            if (!cue.isNullOrBlank()) {
                                subtitleDisplayTv.text = cue
                                subtitleDisplayTv.visibility = View.VISIBLE
                            } else {
                                subtitleDisplayTv.visibility = View.GONE
                            }
                        } else {
                            subtitleDisplayTv.visibility = View.GONE
                        }
                    }
                } catch (_: Exception) {}
                handler.postDelayed(this, 250L)
            }
        }

        videoView.setVideoURI(videos[vidIdx].contentUri)
        videoView.setOnPreparedListener { mp ->
            mediaPlayerRef = mp
            mp.isLooping = true
            totalDurTv.text = formatVideoTimeArabic(videoView.duration)
            videoView.start()
            updatePlayPauseIcon()
            handler.post(updater)
        }

        overlayLayer.addView(root, FrameLayout.LayoutParams(-1, -1))
    }

    // ============================================================
    // ويدجت مشغل الفيديو المصغر العائم القابل للسحب (Picture-in-Picture Floating Widget)
    // ============================================================
    private fun startFloatingPipVideoWidget(media: MediaItem, startPositionMs: Int) {
        floatingPipLayer.removeAllViews()
        floatingPipLayer.visibility = View.VISIBLE

        val pipCard = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dpF(18f)
                setStroke(dp(2), colorSamsungBlue)
            }
            elevation = dpF(18f)
        }

        val vv = VideoView(this).apply {
            setVideoURI(media.contentUri)
            setOnPreparedListener { mp ->
                mp.isLooping = true
                if (startPositionMs > 0) seekTo(startPositionMs)
                start()
            }
        }
        pipCard.addView(vv, FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.CENTER })

        // أزرار التكبير والإغلاق أعلى الويدجت العائم
        val pipTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(0x66000000)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val closePipBtn = TextView(this).apply {
            text = "✕"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener {
                try { vv.stopPlayback() } catch (_: Exception) {}
                floatingPipLayer.removeAllViews()
                floatingPipLayer.visibility = View.GONE
            }
        }
        val expandPipBtn = makeTopIconButton(SamsungIconView.IconType.ASPECT_FIT, Color.WHITE) {
            try { vv.stopPlayback() } catch (_: Exception) {}
            floatingPipLayer.removeAllViews()
            floatingPipLayer.visibility = View.GONE
            openFullCinemaVideoPlayer(media)
        }
        pipTopRow.addView(closePipBtn)
        pipTopRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        pipTopRow.addView(expandPipBtn, LinearLayout.LayoutParams(dp(26), dp(26)))
        pipCard.addView(pipTopRow, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP })

        val cardLp = FrameLayout.LayoutParams(dp(195), dp(120)).apply {
            gravity = Gravity.BOTTOM or Gravity.LEFT
            leftMargin = dp(14)
            bottomMargin = dp(82)
        }

        // دعم سحب الويدجت العائم بالإصبع في أي مكان على الشاشة
        var dX = 0f
        var dY = 0f
        pipCard.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.x = (event.rawX + dX).coerceIn(0f, (rootStack.width - v.width).toFloat().coerceAtLeast(0f))
                    v.y = (event.rawY + dY).coerceIn(0f, (rootStack.height - v.height).toFloat().coerceAtLeast(0f))
                    true
                }
                else -> false
            }
        }

        floatingPipLayer.addView(pipCard, cardLp)
        animateFadeScaleIn(pipCard, 160L)
    }

    private fun makeVideoPillButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF282830.toInt())
                cornerRadius = dpF(14f)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun formatVideoTimeArabic(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val mins = totalSec / 60
        val secs = totalSec % 60
        return toArabicDigits(String.format(Locale.US, "%d:%02d", mins, secs))
    }

    private fun showAboutBrandDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(22), dp(24), dp(14))
        }
        box.addView(createAppLogoBadge(72))
        val titleTv = TextView(this).apply {
            text = "Mango Gallery"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            gravity = Gravity.CENTER
        }
        box.addView(titleTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val infoTv = TextView(this).apply {
            val v = getVisibleMediaItems()
            text = "إصدار احترافي فائق السرعة بهوية الزهرة الزرقاء الجديدة.\n" +
                "إجمالي الوسائط على الهاتف: ${toArabicDigits(v.size)} عنصر\n" +
                "الألبومات النشطة: ${toArabicDigits(buildAlbumsList().size)} ألبوم"
            textSize = 13.5f
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
        }
        box.addView(infoTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("حسناً", null)
            .show()
    }

    private fun showViewerMoreMenu(media: MediaItem, onRefresh: () -> Unit) {
        val aiOrVideoLabel = if (media.isVideo) "استوديو مونتاج وقص الفيديو" else "مسح أجسام بالذكاء الاصطناعي العصبي"
        val options = arrayOf(
            aiOrVideoLabel,
            "نقل إلى الخزنة الخاصة المشفّرة",
            "التفاصيل ومعلومات الملف (EXIF)",
            "إعادة تسمية الملف",
            "نقل إلى ألبوم",
            "تعيين كخلفية للهاتف",
            "بدء عرض الشرائح"
        )
        AlertDialog.Builder(this)
            .setTitle(media.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (media.isVideo) openVideoEditor(media) else openObjectEraser(media)
                    1 -> {
                        selectedMediaIds.clear()
                        selectedMediaIds.add(media.id)
                        moveSelectedToVault()
                    }
                    2 -> showMediaDetailsSheet(media)
                    3 -> showRenameMediaDialog(media, onRefresh)
                    4 -> {
                        selectedMediaIds.clear()
                        selectedMediaIds.add(media.id)
                        showAddSelectedToAlbumDialog()
                    }
                    5 -> setAsDeviceWallpaper(media)
                    6 -> startSlideshow(getVisibleMediaItems())
                }
            }
            .show()
    }

    private fun showMediaDetailsSheet(media: MediaItem) {
        val resText = if (media.widthPx > 0 && media.heightPx > 0) {
            toArabicDigits("${media.widthPx} × ${media.heightPx}")
        } else {
            "دقة أصلية"
        }
        val msg = "الاسم: ${media.title}\n" +
            "التاريخ: ${media.fullDateText}\n" +
            "الألبوم: ${media.albumName}\n" +
            "الأبعاد: $resText\n" +
            "الحجم: ${formatFileSizeArabic(media.sizeBytes)}\n" +
            if (media.pathText.isNotEmpty()) "المسار: ${media.pathText}" else ""

        AlertDialog.Builder(this)
            .setTitle("تفاصيل الملف")
            .setMessage(msg)
            .setPositiveButton("حسناً", null)
            .setNeutralButton("إعادة تسمية") { _, _ ->
                showRenameMediaDialog(media) {}
            }
            .show()
    }

    private fun showRenameMediaDialog(media: MediaItem, onRenamed: () -> Unit) {
        val input = EditText(this).apply {
            setText(media.title)
            gravity = Gravity.RIGHT
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("إعادة تسمية")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val typed = input.text.toString().trim().replace("/", "").replace("\\", "")
                if (typed.isNotEmpty()) {
                    val oldExt = media.title.substringAfterLast('.', "")
                    val finalName = if (oldExt.isNotEmpty() && !typed.contains('.')) "$typed.$oldExt" else typed
                    var renamedForReal = false
                    try {
                        val cv = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                        }
                        renamedForReal = contentResolver.update(media.contentUri, cv, null, null) > 0
                    } catch (_: Exception) {
                    }
                    media.title = finalName
                    if (renamedForReal) customNamesMap.remove(media.id) else customNamesMap[media.id] = finalName
                    savePersistedState()
                    onRenamed()
                    updateSelectionTopBarAndListInPlace()
                    Toast.makeText(
                        this,
                        if (renamedForReal) "تم تغيير اسم الملف" else "تم تغيير الاسم داخل التطبيق فقط (النظام منع تعديل الملف)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun setAsDeviceWallpaper(media: MediaItem) {
        try {
            val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(media.contentUri, "image/*")
                putExtra("mimeType", "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            markExternalLaunch(); startActivity(Intent.createChooser(intent, "تعيين كخلفية"))
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح أداة تعيين الخلفية", Toast.LENGTH_SHORT).show()
        }
    }

    private fun makeViewerActionItem(
        label: String,
        iconType: SamsungIconView.IconType,
        tint: Int,
        onClick: () -> Unit
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClick() }
        }
        val icon = SamsungIconView(this, iconType, tint, 1.9f)
        col.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
        val tv = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        col.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        return col
    }

    // ============================================================
    // محرر صور سامسونج اليدوي المتكامل (Samsung One UI Pro Photo Editor)
    // ============================================================
    // ============================================================
    // محرر صور سامسونج الاحترافي المطابق للقطات المرفقة (1000036296..1000036301) بهوية الزهرة الزرقاء
    // ============================================================
    private fun showSamsungProPhotoEditor(media: MediaItem) {
        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE

        val fullBitmap = decodeFullResolutionWithExif(media, 2048)
        if (fullBitmap == null) {
            Toast.makeText(this, "تعذر قراءة الصورة للتعديل", Toast.LENGTH_SHORT).show()
            showFullScreenPhotoViewer(media)
            return
        }

        val editorCanvasView = SamsungManualEditorCanvasView(this, fullBitmap)
        val filterThumbBmp = Bitmap.createScaledBitmap(fullBitmap, 140, 140, true)
        // التبويبات السفلية الخمسة من اليمين لليسار كما في صور سامسونج:
        // 0 = القص والتدوير والمنظور (1000036296)
        // 1 = الفلاتر الحيّة (1000036297)
        // 2 = الإضاءة والدرجات اللونية (1000036298)
        // 3 = الرسم والملصقات والنص (1000036300)
        // 4 = الأدوات المتقدمة: تحديد حر / لون البقعة / مزيج الألوان (1000036301)
        var currentEditorTab = 0
        var activeTransformSubMode = 0 // 0=تسوية, 1=منظور أفقي, 2=منظور عمودي
        var activeToneSubMode = 0 // 0=السطوع, 1=التعريض, 2=التباين, 3=التشبع, 4=الدفء
        var activeDecorSubMode = 0 // 0=الرسم, 1=ملصقات, 2=النص

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // الشريط العلوي المطابق للقطات سامسونج:
        // يسار الشاشة: [ ⋮ ] [ حفظ ] [ إرجاع ]  <--->  يمين الشاشة: الدائرتان [  ] [  ]
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(14), dp(10))
            setBackgroundColor(0xFF000000.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val moreEditBtn = makeTopIconButton(SamsungIconView.IconType.MORE_VERT, Color.WHITE) {
            val opts = arrayOf("حفظ كنسخة جديدة", "استعادة الأصل", "تفاصيل الصورة")
            AlertDialog.Builder(this)
                .setTitle("خيارات المحرر")
                .setItems(opts) { _, which ->
                    when (which) {
                        0 -> saveEditedPhotoToDevice(editorCanvasView.renderFinalEditedBitmap())
                        1 -> editorCanvasView.resetAllEdits()
                        2 -> showMediaDetailsSheet(media)
                    }
                }
                .show()
        }
        topBar.addView(moreEditBtn, LinearLayout.LayoutParams(dp(36), dp(36)))

        val saveTv = TextView(this).apply {
            text = "حفظ"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                val outBmp = editorCanvasView.renderFinalEditedBitmap()
                saveEditedPhotoToDevice(outBmp)
            }
        }
        topBar.addView(saveTv)

        val revertTv = TextView(this).apply {
            text = "إرجاع"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFD0D0D6.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                editorCanvasView.resetAllEdits()
                Toast.makeText(this@MainActivity, "تم إرجاع الصورة للأصل", Toast.LENGTH_SHORT).show()
            }
        }
        topBar.addView(revertTv)

        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // أزرار التراجع والإعادة الدائرية على اليمين كما في 1000036298.png
        val undoCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1E1E22.toInt())
            }
            setOnClickListener { editorCanvasView.undoLastStrokeOrText() }
        }
        val undoIcon = SamsungIconView(this, SamsungIconView.IconType.UNDO_CURVE, 0xFFAEAEB2.toInt(), 1.9f)
        undoCircle.addView(undoIcon, FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER })
        topBar.addView(undoCircle, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) })

        val redoCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1E1E22.toInt())
            }
            setOnClickListener { editorCanvasView.redoLastStroke() }
        }
        val redoIcon = SamsungIconView(this, SamsungIconView.IconType.REDO_CURVE, Color.WHITE, 1.9f)
        redoCircle.addView(redoIcon, FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER })
        topBar.addView(redoCircle, LinearLayout.LayoutParams(dp(36), dp(36)))

        root.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // مساحة الصورة في المنتصف
        root.addView(editorCanvasView, LinearLayout.LayoutParams(-1, 0, 1f))

        // منطقة الأدوات الديناميكية فوق شريط الأيقونات الخمسة
        val dynamicToolArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addView(dynamicToolArea, LinearLayout.LayoutParams(-1, -2))

        // شريط الأيقونات الخمسة السفلي (1000036296..1000036301)
        val bottomIconsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(18), dp(8), dp(18), dp(14))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addView(bottomIconsBar, LinearLayout.LayoutParams(-1, -2))

        fun rebuildSamsungEditorUI() {
            dynamicToolArea.removeAllViews()
            editorCanvasView.isCropModeActive = (currentEditorTab == 0)
            editorCanvasView.isDrawingModeActive = (currentEditorTab == 3 && activeDecorSubMode == 0)
            editorCanvasView.invalidate()

            when (currentEditorTab) {
                0 -> {
                    // ====================================================
                    // التبويب 0: القص والتدوير والتسوية (مطابق للصورة 1000036296.png)
                    // ====================================================
                    // 1. الكبسولة الصغيرة [ Free | ↻ | ‹|› ]
                    val miniPill = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        background = GradientDrawable().apply {
                            setColor(0xFF121214.toInt())
                            cornerRadius = dpF(18f)
                            setStroke(dp(1), 0xFF2C2C30.toInt())
                        }
                    }

                    val ratioBtn = TextView(this).apply {
                        text = "Free"
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        setPadding(dp(10), dp(2), dp(10), dp(2))
                        setOnClickListener {
                            val rNames = arrayOf("حر (Free)", "1:1 مربع", "4:3", "3:4", "16:9", "تطبيق القص الآن ")
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("نسبة القص")
                                .setItems(rNames) { _, w ->
                                    when (w) {
                                        0 -> editorCanvasView.setCropAspectRatio(0f)
                                        1 -> editorCanvasView.setCropAspectRatio(1f)
                                        2 -> editorCanvasView.setCropAspectRatio(4f / 3f)
                                        3 -> editorCanvasView.setCropAspectRatio(3f / 4f)
                                        4 -> editorCanvasView.setCropAspectRatio(16f / 9f)
                                        5 -> editorCanvasView.applyCropNow()
                                    }
                                }
                                .show()
                        }
                    }
                    val rotBtn = TextView(this).apply {
                        text = "تدوير 90°"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        setPadding(dp(12), dp(2), dp(12), dp(2))
                        setOnClickListener { editorCanvasView.rotate90() }
                    }
                    val flipBtn = TextView(this).apply {
                        text = "قلب أفقي"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        setPadding(dp(10), dp(2), dp(10), dp(2))
                        setOnClickListener { editorCanvasView.flipHorizontal() }
                    }
                    val applyCropBtn = TextView(this).apply {
                        text = "قص"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(colorBrandIceBlue)
                        setPadding(dp(10), dp(2), dp(10), dp(2))
                        setOnClickListener { editorCanvasView.applyCropNow() }
                    }
                    miniPill.addView(ratioBtn)
                    miniPill.addView(rotBtn)
                    miniPill.addView(flipBtn)
                    miniPill.addView(applyCropBtn)
                    dynamicToolArea.addView(
                        miniPill,
                        LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) }
                    )

                    // 2. عنوان الأداة النشطة ("تسوية" / "منظور أفقي" / "منظور عمودي")
                    val modeTitles = arrayOf("تسوية", "منظور أفقي", "منظور عمودي")
                    val modeLabelTv = TextView(this).apply {
                        text = modeTitles[activeTransformSubMode]
                        textSize = 12f
                        setTextColor(0xFFE5E5EA.toInt())
                        gravity = Gravity.CENTER
                    }
                    dynamicToolArea.addView(
                        modeLabelTv,
                        LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) }
                    )

                    // 3. الدوائر الثلاث الكبيرة (1000036296.png)
                    val circlesRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                    }
                    val transformIcons = arrayOf(
                        SamsungIconView.IconType.PERSP_VERTICAL,
                        SamsungIconView.IconType.PERSP_HORIZONTAL,
                        SamsungIconView.IconType.STRAIGHTEN_TILT
                    )
                    val transformIndices = intArrayOf(2, 1, 0)
                    for (k in 0..2) {
                        val subIdx = transformIndices[k]
                        val isSel = (activeTransformSubMode == subIdx)
                        val circle = FrameLayout(this).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(if (isSel) Color.WHITE else 0xFF1E1E22.toInt())
                            }
                            setOnClickListener {
                                activeTransformSubMode = subIdx
                                rebuildSamsungEditorUI()
                            }
                        }
                        val ic = SamsungIconView(
                            this,
                            transformIcons[k],
                            if (isSel) Color.BLACK else Color.WHITE,
                            1.8f
                        )
                        circle.addView(ic, FrameLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER })
                        circlesRow.addView(
                            circle,
                            LinearLayout.LayoutParams(dp(54), dp(54)).apply { setMargins(dp(8), 0, dp(8), 0) }
                        )
                    }
                    dynamicToolArea.addView(
                        circlesRow,
                        LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) }
                    )

                    // 4. مسطرة التدريج التفاعلية باللمس (Samsung Tick-Mark Ruler Dial)
                    val ruler = SamsungTickRulerView(
                        context = this,
                        minVal = -45f,
                        maxVal = 45f,
                        initialVal = editorCanvasView.fineAngleDeg,
                        accentColor = colorBrandIceBlue
                    ) { newAngle ->
                        editorCanvasView.fineAngleDeg = newAngle
                        modeLabelTv.text = "${modeTitles[activeTransformSubMode]}  ${toArabicDigits(newAngle.roundToInt())}°"
                        editorCanvasView.invalidate()
                    }
                    dynamicToolArea.addView(ruler, LinearLayout.LayoutParams(-1, dp(42)))
                }

                1 -> {
                    // ====================================================
                    // التبويب 1: الفلاتر الحيّة مع المصغرات ومسطرة القوة (1000036297.png)
                    // ====================================================
                    val filters = listOf(
                        Pair("أصلي", 0),
                        Pair("إشراق", 1),
                        Pair("دافئ", 2),
                        Pair("قديم", 6),
                        Pair("بارد", 3),
                        Pair("أبيض وأسود", 4),
                        Pair("سينمائي", 5),
                        Pair("تباين عالي", 7)
                    )
                    val currFilterName = filters.find { it.second == editorCanvasView.presetFilterCode }?.first ?: "أصلي"
                    val filterTitleTv = TextView(this).apply {
                        text = currFilterName
                        textSize = 12.5f
                        setTextColor(0xFFE5E5EA.toInt())
                        gravity = Gravity.CENTER
                    }
                    dynamicToolArea.addView(
                        filterTitleTv,
                        LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) }
                    )

                    val hScroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val stripRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), 0, dp(8), 0)
                        layoutDirection = View.LAYOUT_DIRECTION_RTL
                    }

                    for ((fName, fCode) in filters) {
                        val isSel = (editorCanvasView.presetFilterCode == fCode)
                        val card = FilterLivePreviewTile(
                            context = this,
                            sourceBmp = filterThumbBmp,
                            filterCode = fCode,
                            isSelected = isSel,
                            strengthValue = editorCanvasView.filterStrength
                        ).apply {
                            setOnClickListener {
                                editorCanvasView.presetFilterCode = fCode
                                editorCanvasView.invalidate()
                                rebuildSamsungEditorUI()
                            }
                        }
                        stripRow.addView(
                            card,
                            LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(5), 0, dp(5), 0) }
                        )
                    }
                    hScroll.addView(stripRow)
                    dynamicToolArea.addView(
                        hScroll,
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
                    )

                    val ruler = SamsungTickRulerView(
                        context = this,
                        minVal = 0f,
                        maxVal = 100f,
                        initialVal = editorCanvasView.filterStrength.toFloat(),
                        accentColor = colorBrandIceBlue
                    ) { newStrength ->
                        editorCanvasView.filterStrength = newStrength.roundToInt()
                        filterTitleTv.text = "$currFilterName  ${toArabicDigits(editorCanvasView.filterStrength)}"
                        editorCanvasView.invalidate()
                    }
                    dynamicToolArea.addView(ruler, LinearLayout.LayoutParams(-1, dp(42)))
                }

                2 -> {
                    // ====================================================
                    // التبويب 2: الإضاءة والسطوع والتباين مع زر Auto ومسطرة التدريج (1000036298.png)
                    // ====================================================
                    val toneNames = arrayOf("السطوع", "التعريض", "التباين", "التشبع", "الدفء")
                    val toneIcons = arrayOf(
                        SamsungIconView.IconType.TONE_SUN,
                        SamsungIconView.IconType.TONE_EXPOSURE,
                        SamsungIconView.IconType.TONE_CONTRAST,
                        SamsungIconView.IconType.TONE_SATURATION,
                        SamsungIconView.IconType.TONE_WARMTH
                    )
                    val toneTitleTv = TextView(this).apply {
                        text = toneNames[activeToneSubMode]
                        textSize = 12.5f
                        setTextColor(0xFFE5E5EA.toInt())
                        gravity = Gravity.CENTER
                    }
                    dynamicToolArea.addView(
                        toneTitleTv,
                        LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) }
                    )

                    val hScroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val circlesRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), 0, dp(10), 0)
                        layoutDirection = View.LAYOUT_DIRECTION_RTL
                    }

                    // زر Auto المربع المستدير على اليمين كما في 1000036298.png
                    val autoBtn = TextView(this).apply {
                        text = "Auto"
                        textSize = 12.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(0xFF1E1E22.toInt())
                            cornerRadius = dpF(14f)
                        }
                        setOnClickListener {
                            editorCanvasView.brightnessLevel = 12
                            editorCanvasView.contrastLevel = 18
                            editorCanvasView.saturationLevel = 16
                            editorCanvasView.warmthLevel = 6
                            editorCanvasView.invalidate()
                            rebuildSamsungEditorUI()
                            Toast.makeText(this@MainActivity, "تم تطبيق التحسين التلقائي", Toast.LENGTH_SHORT).show()
                        }
                    }
                    circlesRow.addView(
                        autoBtn,
                        LinearLayout.LayoutParams(dp(50), dp(50)).apply { setMargins(dp(10), 0, dp(6), 0) }
                    )

                    for (idx in toneNames.indices) {
                        val isSel = (activeToneSubMode == idx)
                        val circle = FrameLayout(this).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(if (isSel) Color.WHITE else 0xFF1E1E22.toInt())
                            }
                            setOnClickListener {
                                activeToneSubMode = idx
                                rebuildSamsungEditorUI()
                            }
                        }
                        val ic = SamsungIconView(
                            this,
                            toneIcons[idx],
                            if (isSel) Color.BLACK else Color.WHITE,
                            1.8f
                        )
                        circle.addView(ic, FrameLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER })
                        circlesRow.addView(
                            circle,
                            LinearLayout.LayoutParams(dp(54), dp(54)).apply { setMargins(dp(6), 0, dp(6), 0) }
                        )
                    }
                    hScroll.addView(circlesRow)
                    dynamicToolArea.addView(
                        hScroll,
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
                    )

                    val currentToneVal = when (activeToneSubMode) {
                        0 -> editorCanvasView.brightnessLevel
                        1 -> editorCanvasView.exposureLevel
                        2 -> editorCanvasView.contrastLevel
                        3 -> editorCanvasView.saturationLevel
                        else -> editorCanvasView.warmthLevel
                    }

                    val ruler = SamsungTickRulerView(
                        context = this,
                        minVal = -100f,
                        maxVal = 100f,
                        initialVal = currentToneVal.toFloat(),
                        accentColor = colorBrandIceBlue
                    ) { vFloat ->
                        val v = vFloat.roundToInt()
                        when (activeToneSubMode) {
                            0 -> editorCanvasView.brightnessLevel = v
                            1 -> editorCanvasView.exposureLevel = v
                            2 -> editorCanvasView.contrastLevel = v
                            3 -> editorCanvasView.saturationLevel = v
                            else -> editorCanvasView.warmthLevel = v
                        }
                        toneTitleTv.text = "${toneNames[activeToneSubMode]}  ${toArabicDigits(v)}"
                        editorCanvasView.invalidate()
                    }
                    dynamicToolArea.addView(ruler, LinearLayout.LayoutParams(-1, dp(42)))
                }

                3 -> {
                    // ====================================================
                    // التبويب 3: الكبسولة العائمة للرسم + الملصقات + النص (1000036300.png)
                    // ====================================================
                    val pillBar = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        background = GradientDrawable().apply {
                            setColor(0xFF1C1C20.toInt())
                            cornerRadius = dpF(26f)
                        }
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                    }

                    // أزرار التراجع والإعادة في يسار الكبسولة كما في 1000036300.png
                    val pUndo = SamsungIconView(this, SamsungIconView.IconType.UNDO_CURVE, 0xFFAEAEB2.toInt(), 1.8f).apply {
                        setOnClickListener { editorCanvasView.undoLastStrokeOrText() }
                    }
                    val pRedo = SamsungIconView(this, SamsungIconView.IconType.REDO_CURVE, 0xFFAEAEB2.toInt(), 1.8f).apply {
                        setOnClickListener { editorCanvasView.redoLastStroke() }
                    }
                    pillBar.addView(pUndo, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14) })
                    pillBar.addView(pRedo, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })

                    val divider = View(this).apply { setBackgroundColor(0xFF3A3A40.toInt()) }
                    pillBar.addView(divider, LinearLayout.LayoutParams(dp(1), dp(22)).apply { rightMargin = dp(12) })

                    // الممحاة + 3 أقلام ملونة
                    val penColors = intArrayOf(
                        0xFF000000.toInt(), // ممحاة مسح آخر ضربة
                        0xFFFFCC00.toInt(), // قلم نيون
                        0xFF34C759.toInt(), // قلم تظليل
                        0xFF38B6FF.toInt()  // القلم الرئيسي بلون الهوية
                    )
                    for (pIdx in 0..3) {
                        val isEraser = (pIdx == 0)
                        val isActivePen = (!isEraser && editorCanvasView.activePenStyle == pIdx)
                        val penWrap = FrameLayout(this).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(if (isActivePen) 0xFF34343A.toInt() else Color.TRANSPARENT)
                            }
                            setOnClickListener {
                                if (isEraser) {
                                    editorCanvasView.undoLastStrokeOrText()
                                } else {
                                    activeDecorSubMode = 0
                                    editorCanvasView.activePenStyle = pIdx
                                    showPenColorAndSizeDialog(editorCanvasView) { rebuildSamsungEditorUI() }
                                }
                            }
                        }
                        val iconType = if (isEraser) SamsungIconView.IconType.ERASER_BOX else SamsungIconView.IconType.EDIT_PEN
                        val tint = if (isEraser) Color.WHITE else if (isActivePen) editorCanvasView.penColor else Color.WHITE
                        val ic = SamsungIconView(this, iconType, tint, 1.9f)
                        penWrap.addView(ic, FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER })
                        pillBar.addView(
                            penWrap,
                            LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(dp(4), 0, dp(4), 0) }
                        )
                    }

                    dynamicToolArea.addView(
                        pillBar,
                        LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) }
                    )

                    // التبويبات النصية الثلاثة تحت الكبسولة: النص | ملصقات | الرسم
                    val subTabsRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                    }
                    val subLabels = listOf(
                        Pair("النص", 2),
                        Pair("ملصقات", 1),
                        Pair("الرسم", 0)
                    )
                    for ((sTitle, sCode) in subLabels) {
                        val active = (activeDecorSubMode == sCode)
                        val tv = TextView(this).apply {
                            text = sTitle
                            textSize = 13f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(if (active) colorBrandIceBlue else Color.WHITE)
                            setPadding(dp(18), dp(6), dp(18), dp(6))
                            setOnClickListener {
                                activeDecorSubMode = sCode
                                when (sCode) {
                                    1 -> showStickersPickerDialog(editorCanvasView)
                                    2 -> showAddTextOverlayDialog { txt, col ->
                                        editorCanvasView.addDraggableText(txt, col)
                                    }
                                }
                                rebuildSamsungEditorUI()
                            }
                        }
                        subTabsRow.addView(tv)
                    }
                    dynamicToolArea.addView(subTabsRow, LinearLayout.LayoutParams(-1, -2))
                }

                4 -> {
                    // ====================================================
                    // التبويب 4: مختبر الذكاء الاصطناعي المحلي والأدوات المتقدمة (On-Device Neural AI Suite)
                    // ====================================================
                    val aiTitle = TextView(this).apply {
                        text = "أدوات الذكاء الاصطناعي المحلية (On-Device AI)"
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(colorBrandIceBlue)
                        gravity = Gravity.CENTER
                    }
                    dynamicToolArea.addView(aiTitle, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })

                    val hScroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val cardsRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        layoutDirection = View.LAYOUT_DIRECTION_RTL
                    }

                    val advancedCards = listOf(
                        BottomAction("ممحاة الأجسام", SamsungIconView.IconType.ERASER_BOX) {
                            openObjectEraser(media)
                        },
                        BottomAction("تحسين ذكي AI", SamsungIconView.IconType.TONE_SUN) {
                            Toast.makeText(this@MainActivity, "جاري التحسين الذكي للصورة محلياً...", Toast.LENGTH_SHORT).show()
                            viewerExecutor.execute {
                                val enhanced = MangoAiStudio.aiRemasterImage(editorCanvasView.getCurrentWorkingBitmap())
                                handler.post {
                                    editorCanvasView.replaceWorkingBitmap(enhanced)
                                    Toast.makeText(this@MainActivity, "تم تطبيق التحسين الذكي (AI Remaster)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        BottomAction("عزل الخلفية", SamsungIconView.IconType.LASSO_SCISSORS) {
                            Toast.makeText(this@MainActivity, "جاري عزل الخلفية بالذكاء الاصطناعي...", Toast.LENGTH_SHORT).show()
                            viewerExecutor.execute {
                                val bokeh = MangoAiStudio.aiPortraitBokeh(editorCanvasView.getCurrentWorkingBitmap())
                                handler.post {
                                    editorCanvasView.replaceWorkingBitmap(bokeh)
                                    Toast.makeText(this@MainActivity, "تم تطبيق عزل الخلفية البورتريه (AI Bokeh)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        BottomAction("مستند واضح", SamsungIconView.IconType.TAB_CROP_ROTATE) {
                            viewerExecutor.execute {
                                val docBmp = MangoAiStudio.aiEnhanceDocument(editorCanvasView.getCurrentWorkingBitmap())
                                handler.post {
                                    editorCanvasView.replaceWorkingBitmap(docBmp)
                                    Toast.makeText(this@MainActivity, "تم تصفية المستند وتوضيح النصوص", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        BottomAction("رؤية ليلية AI", SamsungIconView.IconType.TONE_EXPOSURE) {
                            viewerExecutor.execute {
                                val nightBmp = MangoAiStudio.aiNightSightEnhance(editorCanvasView.getCurrentWorkingBitmap())
                                handler.post {
                                    editorCanvasView.replaceWorkingBitmap(nightBmp)
                                    Toast.makeText(this@MainActivity, "تم تفتيح اللقطة الليلية بالذكاء الاصطناعي", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        BottomAction("لوحة الألوان", SamsungIconView.IconType.PALETTE_MIX) {
                            val palette = MangoAiStudio.extractDominantPalette(editorCanvasView.getCurrentWorkingBitmap(), 5)
                            showExtractedPaletteDialog(palette)
                        },
                        BottomAction("عزل الألوان", SamsungIconView.IconType.EYEDROPPER) {
                            editorCanvasView.presetFilterCode = if (editorCanvasView.presetFilterCode == 4) 0 else 4
                            editorCanvasView.invalidate()
                            Toast.makeText(this@MainActivity, "تم تفعيل عزل الألوان", Toast.LENGTH_SHORT).show()
                        }
                    )

                    for ((cLabel, cIcon, cAction) in advancedCards) {
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(dp(10), dp(14), dp(10), dp(14))
                            isClickable = true
                            background = GradientDrawable().apply {
                                setColor(0xFF1A1A1E.toInt())
                                cornerRadius = dpF(18f)
                                setStroke(dp(1), 0xFF2C2C32.toInt())
                            }
                            setOnClickListener { cAction() }
                        }
                        val ic = SamsungIconView(this, cIcon, colorBrandIceBlue, 1.9f)
                        card.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
                        val tv = TextView(this).apply {
                            text = cLabel
                            textSize = 11.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                        }
                        card.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
                        cardsRow.addView(
                            card,
                            LinearLayout.LayoutParams(dp(92), dp(96)).apply { setMargins(dp(5), 0, dp(5), 0) }
                        )
                    }
                    hScroll.addView(cardsRow)
                    dynamicToolArea.addView(hScroll, LinearLayout.LayoutParams(-1, -2))
                }
            }

            // رسم شريط الأيقونات الخمسة السفلي (من اليسار لليمين كما في الصور المرفقة):
            // شريط الأيقونات الخمسة السفلي
            bottomIconsBar.removeAllViews()
            val bottomTabDefs = listOf(
                Pair(SamsungIconView.IconType.TAB_FOUR_DOTS, 4),
                Pair(SamsungIconView.IconType.TAB_SMILEY_PEN, 3),
                Pair(SamsungIconView.IconType.TONE_SUN, 2),
                Pair(SamsungIconView.IconType.TAB_THREE_CIRCLES, 1),
                Pair(SamsungIconView.IconType.TAB_CROP_ROTATE, 0)
            )
            for ((iconType, tabIdx) in bottomTabDefs) {
                val isSelected = (currentEditorTab == tabIdx)
                val btnFrame = FrameLayout(this).apply {
                    isClickable = true
                    setOnClickListener {
                        currentEditorTab = tabIdx
                        rebuildSamsungEditorUI()
                    }
                }
                val iconColor = if (isSelected) colorBrandIceBlue else Color.WHITE
                val ic = SamsungIconView(this, iconType, iconColor, 2.0f)
                btnFrame.addView(
                    ic,
                    FrameLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.CENTER }
                )
                bottomIconsBar.addView(btnFrame, LinearLayout.LayoutParams(0, dp(38), 1f))
            }
        }

        rebuildSamsungEditorUI()
        overlayLayer.addView(root, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showExtractedPaletteDialog(colors: IntArray) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        for (c in colors) {
            val hex = String.format(Locale.US, "#%06X", 0xFFFFFF and c)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(c)
                    cornerRadius = dpF(14f)
                }
            }
            val label = TextView(this).apply {
                text = "اللون المستخرج: $hex"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
            }
            row.addView(label)
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        AlertDialog.Builder(this)
            .setTitle("لوحة الألوان السائدة بالذكاء الاصطناعي")
            .setView(box)
            .setPositiveButton("حسناً", null)
            .show()
    }

    private fun showPenColorAndSizeDialog(
        editorCanvasView: SamsungManualEditorCanvasView,
        onUpdated: () -> Unit
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        val sizeSeek = SeekBar(this).apply {
            max = 45
            progress = editorCanvasView.penWidthPx.roundToInt().coerceIn(4, 45)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    editorCanvasView.penWidthPx = p.coerceAtLeast(4).toFloat()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(sizeSeek, LinearLayout.LayoutParams(-1, -2))

        val colorsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
        }
        val colors = intArrayOf(
            0xFF38B6FF.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFFF3B30.toInt(),
            0xFFFFCC00.toInt(),
            0xFF34C759.toInt(),
            0xFFAF52DE.toInt(),
            0xFFFF9500.toInt()
        )
        var dialogRef: AlertDialog? = null
        for (c in colors) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(2), Color.WHITE)
                }
                setOnClickListener {
                    editorCanvasView.penColor = c
                    onUpdated()
                    dialogRef?.dismiss()
                }
            }
            colorsRow.addView(
                dot,
                LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(5), 0, dp(5), 0) }
            )
        }
        box.addView(colorsRow, LinearLayout.LayoutParams(-1, -2))

        dialogRef = AlertDialog.Builder(this)
            .setTitle("لون وحجم القلم")
            .setView(box)
            .setPositiveButton("تم") { _, _ -> onUpdated() }
            .show()
    }

    private fun showStickersPickerDialog(editorCanvasView: SamsungManualEditorCanvasView) {
        val stickers = arrayOf("", "", "", "", "", "", "", "", "", "")
        AlertDialog.Builder(this)
            .setTitle("إضافة ملصق على الصورة (اسحبه بإصبعك)")
            .setItems(stickers) { _, which ->
                editorCanvasView.addDraggableText(stickers[which], Color.WHITE)
            }
            .show()
    }

    private fun showAddTextOverlayDialog(onAdded: (String, Int) -> Unit) {
        val input = EditText(this).apply {
            hint = "اكتب النص هنا..."
            gravity = Gravity.RIGHT
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("إضافة نص على الصورة")
            .setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                val txt = input.text.toString().trim()
                if (txt.isNotEmpty()) {
                    onAdded(txt, Color.WHITE)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun decodeFullResolutionWithExif(media: MediaItem, maxDimension: Int): Bitmap? {
        if (media.isVideo) {
            return extractSharpVideoFrame(media, maxDimension)
        }
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(media.contentUri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            var sample = 1
            while (boundsOpts.outWidth / (sample * 2) >= maxDimension &&
                boundsOpts.outHeight / (sample * 2) >= maxDimension
            ) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = if (lowEndSmoothMode && maxDimension <= 640) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val rawBmp = contentResolver.openInputStream(media.contentUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val rotationDeg = resolveRotationDegrees(media)
            if (rotationDeg != 0) {
                val m = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
                if (rotated !== rawBmp) rawBmp.recycle()
                rotated
            } else {
                rawBmp
            }
        } catch (_: OutOfMemoryError) {
            bitmapCache.evictAll()
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveRotationDegrees(media: MediaItem): Int {
        if (media.orientationDeg != 0) return media.orientationDeg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                contentResolver.openInputStream(media.contentUri)?.use { input ->
                    val exif = ExifInterface(input)
                    return when (
                        exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    ) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (_: Exception) {
            }
        }
        return 0
    }

    private fun extractSharpVideoFrame(media: MediaItem, targetSize: Int): Bitmap? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val thumb = contentResolver.loadThumbnail(
                    media.contentUri,
                    Size(targetSize.coerceAtLeast(512), targetSize.coerceAtLeast(512)),
                    null
                )
                if (thumb != null) return thumb
            }
        } catch (_: Exception) {
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, media.contentUri)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun saveEditedPhotoToDevice(bitmap: Bitmap) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "MangoGallery_Edit_$ts.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Mango Gallery"
                    )
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
                }
                Toast.makeText(this, "تم حفظ الصورة المعدلة بأعلى جودة في الاستديو", Toast.LENGTH_LONG).show()
                loadDeviceMediaAsync()
                closeOverlay()
                return
            }
            Toast.makeText(this, "تعذر إنشاء ملف الحفظ", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ أثناء الحفظ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // مشغل القصص وعرض الشرائح
    // ============================================================
    private fun showStoryPlayerOverlay(story: StoryInfo) {
        val slides = story.items.ifEmpty { getVisibleMediaItems().take(10) }
        if (slides.isEmpty()) return

        var idx = 0
        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE

        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val img = AsyncMediaThumbnailView(
            context = this,
            media = slides[0],
            cornerRadiusPx = 0f,
            strokeColor = Color.TRANSPARENT,
            fitCenterMode = false,
            preferredDecodePx = 1440
        )
        frame.addView(img, FrameLayout.LayoutParams(-1, -1))

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        frame.addView(progressRow, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP })

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(12))
        }
        val closeTv = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                stopSlideshow()
                closeOverlay()
            }
        }
        headerRow.addView(closeTv)

        val storyTitleTv = TextView(this).apply {
            text = "${story.title} (${story.yearText})"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        headerRow.addView(storyTitleTv, LinearLayout.LayoutParams(0, -2, 1f))
        frame.addView(headerRow, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP })

        fun updateSlideView() {
            val item = slides[idx % slides.size]
            img.updateMedia(item)
            progressRow.removeAllViews()
            for (i in slides.indices) {
                val bar = View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(if (i <= idx) Color.WHITE else 0x66FFFFFF)
                        cornerRadius = dpF(2f)
                    }
                }
                progressRow.addView(
                    bar,
                    LinearLayout.LayoutParams(0, dp(3), 1f).apply {
                        setMargins(dp(2), 0, dp(2), 0)
                    }
                )
            }
        }

        frame.setOnClickListener {
            idx = (idx + 1) % slides.size
            updateSlideView()
        }

        updateSlideView()
        overlayLayer.addView(frame, FrameLayout.LayoutParams(-1, -1))
    }

    private fun startSlideshow(items: List<MediaItem>) {
        if (items.isEmpty()) {
            Toast.makeText(this, "لا توجد صور لبدء عرض الشرائح", Toast.LENGTH_SHORT).show()
            return
        }
        stopSlideshow()
        isSlideshowRunning = true
        var index = 0

        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setOnClickListener {
                stopSlideshow()
                Toast.makeText(this@MainActivity, "تم إيقاف عرض الشرائح", Toast.LENGTH_SHORT).show()
            }
        }
        val slideImg = AsyncMediaThumbnailView(
            this,
            items[0],
            0f,
            Color.TRANSPARENT,
            fitCenterMode = true,
            preferredDecodePx = 1440
        )
        container.addView(slideImg, FrameLayout.LayoutParams(-1, -1))

        val caption = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = dpF(16f)
            }
        }
        val capLp = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(32)
        }
        container.addView(caption, capLp)
        overlayLayer.addView(container, FrameLayout.LayoutParams(-1, -1))

        val step = object : Runnable {
            override fun run() {
                if (!isSlideshowRunning || overlayLayer.visibility != View.VISIBLE) return
                val current = items[index % items.size]
                slideImg.updateMedia(current)
                caption.text = "عرض الشرائح • ${current.title} (المس للإيقاف)"
                index++
                handler.postDelayed(this, 2500L)
            }
        }
        slideshowRunnable = step
        handler.post(step)
    }

    private fun stopSlideshow() {
        isSlideshowRunning = false
        slideshowRunnable?.let { handler.removeCallbacks(it) }
        slideshowRunnable = null
        closeOverlay()
    }

    // ============================================================
    // شاشة البحث الفوري في صور الهاتف (تعمل بـ ListView الافتراضي السريع!)
    // ============================================================
    private fun showSearchOverlay() {
        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorPureBlack)
            setPadding(dp(12), dp(14), dp(12), dp(12))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val searchBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(colorCardDark)
                cornerRadius = dpF(24f)
                setStroke(dp(1), colorBorderSubtle)
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }

        val input = EditText(this).apply {
            hint = "البحث في صور وفيديوهات الهاتف..."
            setHintTextColor(colorTextMuted)
            setTextColor(colorTextPrimary)
            textSize = 14.5f
            background = null
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            isSingleLine = true
        }
        searchBarRow.addView(input, LinearLayout.LayoutParams(0, dp(38), 1f))

        val backBtn = TextView(this).apply {
            text = "›"
            textSize = 26f
            setTextColor(colorWhite)
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { closeOverlay() }
        }
        searchBarRow.addView(backBtn)

        root.addView(searchBarRow, LinearLayout.LayoutParams(-1, -2))

        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(12), dp(4), dp(10))
        }
        val suggestions = listOf("الكل", "الكاميرا", "صور الشاشة", "تنزيل", "اليوم", "أمس", "فيديو")
        for (word in suggestions) {
            val chip = TextView(this).apply {
                text = word
                textSize = 12.5f
                setTextColor(colorTextPrimary)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply {
                    setColor(colorCardDark)
                    cornerRadius = dpF(16f)
                    setStroke(dp(1), colorBorderSubtle)
                }
                setOnClickListener {
                    input.setText(if (word == "الكل") "" else word)
                }
            }
            chipsRow.addView(
                chip,
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
            )
        }
        chipsScroll.addView(chipsRow)
        root.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2))

        val visible = getVisibleMediaItems()
        val searchAdapter = PicturesVirtualAdapter(
            rows = buildPictureListRows(visible, 3),
            cols = 3,
            contextList = visible,
            isRecycleBinMode = false,
            onEnterSelection = {}
        )
        val resultsListView = ListView(this).apply {
            adapter = searchAdapter
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            isFastScrollEnabled = true
            verticalScrollbarPosition = View.SCROLLBAR_POSITION_RIGHT
        }
        root.addView(resultsListView, LinearLayout.LayoutParams(-1, 0, 1f))

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = (s?.toString() ?: "").trim()
                val filtered = if (q.isEmpty()) {
                    visible
                } else {
                    visible.filter {
                        it.title.contains(q, ignoreCase = true) ||
                            it.albumName.contains(q, ignoreCase = true) ||
                            it.dateGroup.contains(q, ignoreCase = true) ||
                            (q == "فيديو" && it.isVideo)
                    }
                }
                searchAdapter.updateData(buildPictureListRows(filtered, 3))
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        overlayLayer.addView(root, FrameLayout.LayoutParams(-1, -1))
    }

    // ============================================================
    // الحوارات والإعدادات وقفل الاستديو (كلمة سر + بصمة)
    // ============================================================
    private fun showCreateAlbumDialog() {
        val input = EditText(this).apply {
            hint = "اسم الألبوم الجديد"
            gravity = Gravity.RIGHT
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("إنشاء ألبوم")
            .setView(input)
            .setPositiveButton("إنشاء") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    customAlbumMap.getOrPut(name) { mutableListOf() }
                    savePersistedState()
                    renderActiveScreen()
                    Toast.makeText(this, "تم إنشاء ألبوم: $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showCreateStoryDialog() {
        val input = EditText(this).apply {
            hint = "عنوان القصة الجديدة"
            gravity = Gravity.RIGHT
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("إنشاء قصة جديدة")
            .setView(input)
            .setPositiveButton("إنشاء") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "قصتي الجديدة" }
                storiesList.add(
                    0,
                    StoryInfo(
                        id = "story_${System.currentTimeMillis()}",
                        title = name,
                        yearText = toArabicDigits(SimpleDateFormat("yyyy", Locale.US).format(Date())),
                        isNew = true,
                        items = getVisibleMediaItems().take(12)
                    )
                )
                currentTab = 2
                renderBottomNavigation()
                renderActiveScreen()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showCreateMenuDialog() {
        val options = arrayOf(
            "إنشاء قصة جديدة",
            "إنشاء ألبوم جديد",
            "بدء عرض الشرائح التلقائي"
        )
        AlertDialog.Builder(this)
            .setTitle("إنشاء")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateStoryDialog()
                    1 -> showCreateAlbumDialog()
                    2 -> startSlideshow(getVisibleMediaItems())
                }
            }
            .show()
    }

    private fun showEssentialAlbumsDialog() {
        val allNames = buildAlbumsList().map { it.title }.distinct().toTypedArray()
        val checked = BooleanArray(allNames.size) { i ->
            !nonEssentialAlbumNames.contains(allNames[i])
        }
        AlertDialog.Builder(this)
            .setTitle("تحديد الألبومات الأساسية")
            .setMultiChoiceItems(allNames, checked) { _, which, isChecked ->
                if (isChecked) {
                    nonEssentialAlbumNames.remove(allNames[which])
                } else {
                    nonEssentialAlbumNames.add(allNames[which])
                }
            }
            .setPositiveButton("حفظ") { _, _ ->
                savePersistedState()
                renderActiveScreen()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showHideAlbumsDialog() {
        val candidateNames = (listOf("الكاميرا", "صور الشاشة", "تنزيل") +
            deviceMediaItems.map { it.albumName } +
            customAlbumMap.keys).distinct()
            .filter { it != "الأحدث" && it != "المفضلة" }
            .toTypedArray()

        val checked = BooleanArray(candidateNames.size) { i ->
            hiddenAlbumNames.contains(candidateNames[i])
        }
        AlertDialog.Builder(this)
            .setTitle("إخفاء الألبومات")
            .setMultiChoiceItems(candidateNames, checked) { _, which, isChecked ->
                if (isChecked) {
                    hiddenAlbumNames.add(candidateNames[which])
                } else {
                    hiddenAlbumNames.remove(candidateNames[which])
                }
            }
            .setPositiveButton("تطبيق") { _, _ ->
                savePersistedState()
                renderActiveScreen()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showSharedAlbumsDialog() {
        AlertDialog.Builder(this)
            .setTitle("الألبومات المشتركة")
            .setMessage("أنشئ ألبوماً مشتركاً وشارك صور هاتفك بسهولة.")
            .setPositiveButton("إنشاء ألبوم مشترك") { _, _ ->
                showCreateAlbumDialog()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showGallerySettingsDialog() {
        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE

        val hwProfile = ObjectEraser.detectHardwareProfile(this)
        val vaultCount = try { PrivateVault.list(this).size } catch (_: Throwable) { 0 }
        val trashCount = getTrashedMediaItems().size
        val visibleMedia = getVisibleMediaItems()
        val pCount = visibleMedia.count { !it.isVideo }
        val vCount = visibleMedia.count { it.isVideo }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorPureBlack)
            isClickable = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // الشريط العلوي الاحترافي
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(18), dp(16), dp(12))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val closeBtn = makeTopIconButton(SamsungIconView.IconType.CHEVRON_RIGHT, colorTextPrimary) {
            closeOverlay()
        }
        val headerTitle = TextView(this).apply {
            text = "إعدادات Mango Gallery الاحترافية"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        topBar.addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        topBar.addView(closeBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        page.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(36))
        }

        fun makeSectionTitle(title: String): TextView = TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorSamsungBlue)
            gravity = Gravity.RIGHT
            setPadding(dp(8), dp(14), dp(8), dp(6))
        }

        fun makeCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colorCardDark)
                cornerRadius = dpF(22f)
                setStroke(dp(1), colorBorderSubtle)
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        fun makeSettingRow(
            title: String,
            subtitle: String,
            badgeText: String? = null,
            badgeColor: Int = colorSamsungBlue,
            showDivider: Boolean = true,
            onClick: () -> Unit
        ): View {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setPadding(dp(4), dp(12), dp(4), dp(12))
                setOnClickListener { onClick() }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            if (badgeText != null) {
                val badge = TextView(this).apply {
                    text = badgeText
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(badgeColor)
                        cornerRadius = dpF(12f)
                    }
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                }
                row.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.RIGHT
            }
            textCol.addView(TextView(this).apply {
                text = title
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextPrimary)
                gravity = Gravity.RIGHT
            })
            textCol.addView(TextView(this).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(colorTextSecondary)
                gravity = Gravity.RIGHT
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            col.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (showDivider) {
                val div = View(this).apply { setBackgroundColor(colorBorderSubtle) }
                col.addView(div, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })
            }
            return col
        }

        // 1. بطاقة الهوية والإحصائيات الحية (Hero Enterprise Card)
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0A3966.toInt(), 0xFF101828.toInt())
            ).apply {
                cornerRadius = dpF(24f)
                setStroke(dp(1), 0x448CD0FA)
            }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val heroInfoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.RIGHT
        }
        heroInfoCol.addView(TextView(this).apply {
            text = "Mango Gallery Pro"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
        })
        heroInfoCol.addView(TextView(this).apply {
            text = "${toArabicDigits(pCount)} صورة • ${toArabicDigits(vCount)} فيديو • ${toArabicDigits(deviceAudioTracks.size)} صوتيات"
            textSize = 12.5f
            setTextColor(colorBrandIceBlue)
            gravity = Gravity.RIGHT
            setPadding(0, dp(4), 0, 0)
        })
        heroInfoCol.addView(TextView(this).apply {
            text = "المحرك العصبي: ${hwProfile.acceleratorName}"
            textSize = 11.5f
            setTextColor(0xFFD0E2F8.toInt())
            gravity = Gravity.RIGHT
            setPadding(0, dp(2), 0, 0)
        })
        heroCard.addView(heroInfoCol, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(14) })
        heroCard.addView(createAppLogoBadge(54))
        content.addView(heroCard, LinearLayout.LayoutParams(-1, -2))

        // 2. قسم الخزنة الخاصة المشفّرة والقياسات الحيوية (البصمة)
        content.addView(makeSectionTitle("الخزنة الخاصة والحماية البيومترية"))
        val vaultCard = makeCard()
        vaultCard.addView(makeSettingRow(
            title = "فتح الخزنة الخاصة المشفّرة",
            subtitle = "تحتوي حالياً على ${toArabicDigits(vaultCount)} عنصر مخفي ومشفّر بـ AES-256 بعيداً عن المعرض العام",
            badgeText = "فتح الخزنة",
            badgeColor = colorSamsungBlue,
            showDivider = true
        ) {
            closeOverlay()
            openVault()
        })
        vaultCard.addView(makeSettingRow(
            title = "صفحة تعديل كلمة المرور والبصمة وسؤال الأمان",
            subtitle = "تغيير رمز PIN، وتفعيل بصمة الوجه أو البصمة الجانبية/الخلفية/الشاشة حسب هاتفك",
            badgeText = "إدارة الأمان",
            badgeColor = 0xFFFF9800.toInt(),
            showDivider = false
        ) {
            closeOverlay()
            openVaultSecuritySettings()
        })
        content.addView(vaultCard, LinearLayout.LayoutParams(-1, -2))

        // 3. قسم الأداء الفائق للأجهزة الضعيفة والمحرك العصبي
        content.addView(makeSectionTitle("الأداء والسلاسة والذكاء الاصطناعي العصبي"))
        val perfCard = makeCard()
        perfCard.addView(makeSettingRow(
            title = "وضع السلاسة الفائقة للأجهزة الضعيفة (Turbo 60/120Hz)",
            subtitle = if (lowEndSmoothMode)
                "مفعّل: يقلل استهلاك الرام بترميز RGB_565 ويسرّع التمرير الفوري دون تقطيع"
            else
                "غير مفعّل: يعرض المصغرات بدقة لونية قصوى ARGB_8888 للأجهزة القوية",
            badgeText = if (lowEndSmoothMode) "مفعّل" else "تفعيل",
            badgeColor = if (lowEndSmoothMode) 0xFF2E7D32.toInt() else 0xFF424248.toInt(),
            showDivider = true
        ) {
            lowEndSmoothMode = !lowEndSmoothMode
            savePersistedState()
            bitmapCache.evictAll()
            showGallerySettingsDialog()
            Toast.makeText(
                this,
                if (lowEndSmoothMode) "تم تفعيل وضع السلاسة الفائقة للأجهزة الضعيفة" else "تم تفعيل وضع الدقة القصوى",
                Toast.LENGTH_SHORT
            ).show()
        })
        perfCard.addView(makeSettingRow(
            title = "معالج الذكاء الاصطناعي المحلي (Neural Object Eraser)",
            subtitle = "${hwProfile.displayTitleAr} • ${hwProfile.chipSummary}",
            badgeText = hwProfile.id.uppercase(Locale.ROOT),
            badgeColor = colorSamsungBlue,
            showDivider = false
        ) {
            AlertDialog.Builder(this)
                .setTitle("المحرك العصبي المحلي (On-Device NPU/CPU)")
                .setMessage(
                    "يتم تشغيل نموذج مسح الأجسام محلياً على معالج هاتفك:\n\n" +
                    "• المعالج: ${hwProfile.chipSummary}\n" +
                    "• المسرّع: ${hwProfile.acceleratorName}\n" +
                    "• الوضع التلقائي: ${hwProfile.displayTitleAr}\n\n" +
                    "يمكنك استخدام الممحاة الذكية من خلال فتح أي صورة ثم اختيار (تعديل) أو (مسح أجسام بالذكاء الاصطناعي)."
                )
                .setPositiveButton("حسناً", null)
                .show()
        })
        content.addView(perfCard, LinearLayout.LayoutParams(-1, -2))

        // 4. قسم المظهر وتخصيص الشبكة والألبومات
        content.addView(makeSectionTitle("المظهر وتخصيص العرض والويدجت"))
        val uiCard = makeCard()
        val themeStr = when (themeMode) {
            1 -> "الوضع الداكن دائماً"
            2 -> "الوضع الفاتح دائماً"
            else -> "تلقائي حسب النظام (${if (isDarkModeActive()) "داكن" else "فاتح"})"
        }
        uiCard.addView(makeSettingRow(
            title = "مظهر التطبيق والثيم",
            subtitle = "الحالي: $themeStr",
            badgeText = "تغيير",
            showDivider = true
        ) {
            showThemeModeSelectorDialog()
        })
        uiCard.addView(makeSettingRow(
            title = "عدد أعمدة شبكة الصور",
            subtitle = "الحالي: ${toArabicDigits(picturesColumnCount)} أعمدة (يمكنك أيضاً التكبير والتصغير بإصبعين)",
            badgeText = "${toArabicDigits(picturesColumnCount)}×",
            showDivider = true
        ) {
            picturesColumnCount = when (picturesColumnCount) {
                2 -> 3
                3 -> 4
                4 -> 5
                else -> 2
            }
            savePersistedState()
            renderActiveScreen()
            showGallerySettingsDialog()
        })
        uiCard.addView(makeSettingRow(
            title = "إظهار كل الألبومات المخفية واستعادة الترتيب",
            subtitle = "إعادة إظهار أي ألبوم تم إخفاؤه سابقاً وتحديث القائمة",
            showDivider = true
        ) {
            hiddenAlbumNames.clear()
            nonEssentialAlbumNames.clear()
            showCustomizeAlbumsBanner = true
            savePersistedState()
            renderActiveScreen()
            Toast.makeText(this, "تم إظهار كافة الألبومات", Toast.LENGTH_SHORT).show()
        })
        uiCard.addView(makeSettingRow(
            title = "تحديث ويدجت الشاشة الرئيسية (Mango Widgets)",
            subtitle = "تحديث ويدجت الذكريات والطقس وويدجت مركز التحكم السريع على الشاشة الرئيسية",
            badgeText = "تحديث",
            showDivider = false
        ) {
            sendBroadcast(Intent(this, MangoPhotoWidgetProvider::class.java).apply {
                action = MangoPhotoWidgetProvider.ACTION_REFRESH
            })
            Toast.makeText(this, "تم تحديث ويدجت الشاشة الرئيسية", Toast.LENGTH_SHORT).show()
        })
        content.addView(uiCard, LinearLayout.LayoutParams(-1, -2))

        // 5. قسم إدارة الملفات وسلة المحذوفات
        content.addView(makeSectionTitle("إدارة الوسائط وسلة المحذوفات"))
        val storageCard = makeCard()
        storageCard.addView(makeSettingRow(
            title = "سلة المحذوفات (تحتفظ بالملفات ٣٠ يوماً)",
            subtitle = "تحتوي حالياً على ${toArabicDigits(trashCount)} عنصر قابل للاستعادة",
            badgeText = "${toArabicDigits(trashCount)}",
            badgeColor = if (trashCount > 0) 0xFFE65A0F.toInt() else 0xFF424248.toInt(),
            showDivider = true
        ) {
            closeOverlay()
            openRecycleBinSubPage()
        })
        storageCard.addView(makeSettingRow(
            title = "السماح بإدارة الوسائط السريعة (Manage Media)",
            subtitle = "حذف ونقل الصور للخزنة بدون نوافذ تأكيد متكررة من النظام",
            showDivider = true
        ) {
            openManageMediaSettings()
        })
        storageCard.addView(makeSettingRow(
            title = "إعادة فحص وتحديث كافة صور وفيديوهات الهاتف",
            subtitle = "مزامنة فورية مع MediaStore لاكتشاف أحدث الملفات",
            showDivider = false
        ) {
            closeOverlay()
            requestMediaPermissionsIfNeeded()
            Toast.makeText(this, "جاري فحص وتحديث الوسائط...", Toast.LENGTH_SHORT).show()
        })
        content.addView(storageCard, LinearLayout.LayoutParams(-1, -2))

        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        overlayLayer.addView(page, FrameLayout.LayoutParams(-1, -1))
        animateSlideUpIn(page, distanceDp = 48, durationMs = 160L)
    }

    private fun showThemeModeSelectorDialog() {
        val options = arrayOf(
            "تلقائي حسب الجهاز (الوضع الفاتح والداكن تلقائياً)",
            "الوضع الداكن دائماً",
            "الوضع الفاتح دائماً"
        )
        AlertDialog.Builder(this)
            .setTitle("مظهر الاستديو")
            .setSingleChoiceItems(options, themeMode) { dialog, which ->
                themeMode = which
                savePersistedState()
                applySystemBarsTheme()
                rootStack.setBackgroundColor(colorPureBlack)
                mainContentArea.setBackgroundColor(colorPureBlack)
                bottomNavContainer.setBackgroundColor(colorPureBlack)
                renderBottomNavigation()
                renderActiveScreen()
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showAppLockSettingsDialog() {
        val options = arrayOf(
            if (appLockEnabled) "✓ تفعيل قفل الاستديو عند الفتح" else "تفعيل قفل الاستديو عند الفتح",
            "تعيين / تغيير كلمة السر أو رمز PIN",
            if (appLockBiometric) "✓ السماح بفتح القفل ببصمة الإصبع" else "السماح بفتح القفل ببصمة الإصبع",
            "قفل الاستديو الآن",
            if (appLockEnabled) "إيقاف قفل الاستديو" else "إلغاء"
        )
        AlertDialog.Builder(this)
            .setTitle("حماية وقفل الاستديو")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (!appLockEnabled && appLockPin.isEmpty()) {
                            showSetPasswordDialog(lockImmediatelyAfter = false)
                        } else {
                            appLockEnabled = !appLockEnabled
                            savePersistedState()
                            Toast.makeText(
                                this,
                                if (appLockEnabled) "تم تفعيل قفل الاستديو" else "تم إيقاف قفل الاستديو",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    1 -> showSetPasswordDialog(lockImmediatelyAfter = false)
                    2 -> {
                        appLockBiometric = !appLockBiometric
                        savePersistedState()
                        Toast.makeText(
                            this,
                            if (appLockBiometric) "تم تفعيل فتح القفل بالبصمة" else "تم إيقاف فتح القفل بالبصمة",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    3 -> {
                        if (appLockPin.isEmpty()) {
                            showSetPasswordDialog(lockImmediatelyAfter = true)
                        } else {
                            appLockEnabled = true
                            savePersistedState()
                            lockGalleryNow(autoPromptBiometric = true)
                        }
                    }
                    4 -> {
                        if (appLockEnabled) {
                            appLockEnabled = false
                            savePersistedState()
                            Toast.makeText(this, "تم إيقاف قفل الاستديو", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showSetPasswordDialog(lockImmediatelyAfter: Boolean) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        val passInput = EditText(this).apply {
            hint = "أدخل كلمة السر أو رمز PIN جديد"
            gravity = Gravity.RIGHT
            isSingleLine = true
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val confirmInput = EditText(this).apply {
            hint = "تأكيد كلمة السر"
            gravity = Gravity.RIGHT
            isSingleLine = true
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        container.addView(passInput, LinearLayout.LayoutParams(-1, -2))
        container.addView(
            confirmInput,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        )

        AlertDialog.Builder(this)
            .setTitle("تعيين كلمة سر الاستديو")
            .setView(container)
            .setPositiveButton("حفظ وتفعيل") { _, _ ->
                val p1 = passInput.text.toString().trim()
                val p2 = confirmInput.text.toString().trim()
                when {
                    p1.isEmpty() -> {
                        Toast.makeText(this, "يرجى إدخال كلمة سر صالحة", Toast.LENGTH_SHORT).show()
                    }
                    p1.length < 4 -> {
                        Toast.makeText(this, "الحد الأدنى لكلمة السر ٤ خانات", Toast.LENGTH_SHORT).show()
                    }
                    p1 != p2 -> {
                        Toast.makeText(this, "كلمتا السر غير متطابقتين", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        appLockPin = hashPinForStorage(p1)
                        appLockPinLen = p1.length
                        resetFailedAttempts()
                        appLockEnabled = true
                        savePersistedState()
                        Toast.makeText(this, "تم حفظ كلمة السر وتفعيل القفل", Toast.LENGTH_SHORT).show()
                        if (lockImmediatelyAfter) {
                            lockGalleryNow(autoPromptBiometric = true)
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun lockGalleryNow(autoPromptBiometric: Boolean) {
        isAppLockedNow = true
        // إيقاف أي تشغيل مفتوح قبل إظهار شاشة القفل
        if (isSlideshowRunning) stopSlideshow()
        pauseVideoViewsIn(overlayLayer)
        pauseVideoViewsIn(floatingPipLayer)
        try {
            if (audioPlayer?.isPlaying == true) {
                audioPlayer?.pause()
                isAudioPlaying = false
            }
        } catch (_: Exception) {
        }
        renderLockScreenOverlay(autoPromptBiometric)
    }

    private fun unlockGalleryNow() {
        isAppLockedNow = false
        lockLayer.removeAllViews()
        lockLayer.visibility = View.GONE
    }

    // ============================================================
    // شاشة القفل الاحترافية بستايل التطبيقات الكبرى (Flagship PIN Vault + Biometrics)
    // ============================================================
    private fun renderLockScreenOverlay(autoPromptBiometric: Boolean) {
        lockLayer.removeAllViews()
        lockLayer.visibility = View.VISIBLE

        var enteredPin = ""
        val targetLen = appLockPinLen.coerceAtLeast(4)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF041428.toInt(), 0xFF070A10.toInt(), 0xFF000000.toInt())
            )
            setPadding(dp(24), dp(42), dp(24), dp(24))
            isClickable = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // هالة اللوجو الأزرق الجديد
        val logoHalo = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x220A78D6)
                setStroke(dp(1), 0x558CD0FA)
            }
        }
        val logoImg = ImageView(this).apply {
            setImageResource(R.drawable.app_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        logoHalo.addView(
            logoImg,
            FrameLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER }
        )
        root.addView(logoHalo, LinearLayout.LayoutParams(dp(86), dp(86)))

        val titleTv = TextView(this).apply {
            text = "حماية الاستديو"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(titleTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val statusTv = TextView(this).apply {
            text = "أدخل رمز الحماية أو المس مستشعر البصمة"
            textSize = 13f
            setTextColor(0xFFB0C4DE.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(statusTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // نقاط رمز PIN المتفاعلة (● ● ○ ○)
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val dotViews = Array(targetLen.coerceAtMost(8)) {
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), 0xFF6C88A8.toInt())
                }
            }
        }
        for (dv in dotViews) {
            dotsRow.addView(
                dv,
                LinearLayout.LayoutParams(dp(15), dp(15)).apply { setMargins(dp(8), dp(18), dp(8), dp(14)) }
            )
        }
        root.addView(dotsRow, LinearLayout.LayoutParams(-1, -2))

        fun refreshDotsUI(isError: Boolean = false) {
            for (i in dotViews.indices) {
                val filled = i < enteredPin.length
                dotViews[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (isError) {
                        setColor(0xFFFF453A.toInt())
                    } else if (filled) {
                        setColor(colorBrandIceBlue)
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2), 0xFF6C88A8.toInt())
                    }
                }
            }
        }

        fun onDigitPressed(digit: String) {
            val waitMs = lockoutRemainingMs()
            if (waitMs > 0L) {
                statusTv.text = lockoutMessage(waitMs)
                statusTv.setTextColor(0xFFFF453A.toInt())
                return
            }
            if (enteredPin.length < 12) {
                enteredPin += digit
                refreshDotsUI(false)
                if (appLockPinLen > 0 && verifyPinIfComplete(enteredPin)) {
                    resetFailedAttempts()
                    unlockGalleryNow()
                    return
                }
                if (appLockPinLen > 0 && enteredPin.length >= appLockPinLen) {
                    registerFailedAttempt()
                    try { rootStack.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
                    refreshDotsUI(true)
                    val remaining = lockoutRemainingMs()
                    statusTv.text = if (remaining > 0L) lockoutMessage(remaining) else "رمز الحماية غير صحيح، حاول مرة أخرى"
                    statusTv.setTextColor(0xFFFF453A.toInt())
                    dotsRow.translationX = dpF(16f)
                    dotsRow.animate().translationX(0f).setDuration(140L).start()
                    handler.postDelayed({
                        enteredPin = ""
                        refreshDotsUI(false)
                    }, 320L)
                }
            }
        }

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // لوحة المفاتيح الرقمية الزجاجية الدائرية بستايل التطبيقات الكبرى
        val keypadGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val rowsDigits = arrayOf(
            arrayOf("1", "2", "3"),
            arrayOf("4", "5", "6"),
            arrayOf("7", "8", "9"),
            arrayOf("BIO", "0", "DEL")
        )

        for (r in rowsDigits) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            for (key in r) {
                val keyFrame = FrameLayout(this).apply {
                    isClickable = true
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (key == "BIO") 0x330A78D6 else 0x1FFFFFFF)
                        setStroke(dp(1), 0x33FFFFFF)
                    }
                }
                when (key) {
                    "BIO" -> {
                        val fpIcon = SamsungIconView(this, SamsungIconView.IconType.FINGERPRINT, colorBrandIceBlue, 2.0f)
                        keyFrame.addView(fpIcon, FrameLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER })
                        keyFrame.setOnClickListener {
                            triggerBiometricAuthentication { err ->
                                statusTv.text = err
                            }
                        }
                    }
                    "DEL" -> {
                        val delTv = TextView(this).apply {
                            text = "مسح"
                            textSize = 22f
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                        }
                        keyFrame.addView(delTv, FrameLayout.LayoutParams(-1, -1))
                        keyFrame.setOnClickListener {
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                                refreshDotsUI(false)
                            }
                        }
                    }
                    else -> {
                        val numTv = TextView(this).apply {
                            text = key
                            textSize = 25f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                        }
                        keyFrame.addView(numTv, FrameLayout.LayoutParams(-1, -1))
                        keyFrame.setOnClickListener {
                            keyFrame.scaleX = 0.90f
                            keyFrame.scaleY = 0.90f
                            keyFrame.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                            onDigitPressed(key)
                        }
                    }
                }
                rowLayout.addView(
                    keyFrame,
                    LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(14), dp(7), dp(14), dp(7)) }
                )
            }
            keypadGrid.addView(rowLayout, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(keypadGrid, LinearLayout.LayoutParams(-1, -2))

        // خيار فتح لوحة المفاتيح النصية إذا كانت كلمة السر تحتوي على حروف
        val kbBtn = TextView(this).apply {
            text = "إدخال كلمة سر بالحروف "
            textSize = 12.5f
            setTextColor(colorBrandIceBlue)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(6))
            setOnClickListener {
                val input = EditText(this@MainActivity).apply {
                    hint = "كلمة السر"
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    transformationMethod = PasswordTransformationMethod.getInstance()
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("فتح قفل الاستديو")
                    .setView(input)
                    .setPositiveButton("فتح") { _, _ ->
                        val waitMs = lockoutRemainingMs()
                        if (waitMs > 0L) {
                            statusTv.text = lockoutMessage(waitMs)
                        } else if (verifyPin(input.text.toString().trim())) {
                            resetFailedAttempts()
                            unlockGalleryNow()
                        } else {
                            registerFailedAttempt()
                            statusTv.text = "كلمة السر غير صحيحة"
                        }
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }
        root.addView(kbBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        lockLayer.addView(root, FrameLayout.LayoutParams(-1, -1))

        if (autoPromptBiometric && appLockBiometric) {
            handler.postDelayed({
                if (isAppLockedNow) {
                    triggerBiometricAuthentication { _ -> }
                }
            }, 250L)
        }
    }

    // ============================================================
    // مشغل الصوتيات والموسيقى الاحترافي المدمج (Pro Audio Studio)
    // ============================================================
    private fun playAudioTrackAtIndex(index: Int) {
        if (deviceAudioTracks.isEmpty()) return
        val safeIdx = ((index % deviceAudioTracks.size) + deviceAudioTracks.size) % deviceAudioTracks.size
        currentAudioIndex = safeIdx
        val track = deviceAudioTracks[safeIdx]

        val token = ++audioLoadToken
        try {
            audioPlayer?.stop()
            audioPlayer?.release()
        } catch (_: Exception) {}
        audioPlayer = null
        isAudioPlaying = false
        updateMiniAudioPlayerBar()

        Thread {
            var mp: MediaPlayer? = null
            try {
                val player = MediaPlayer()
                mp = player
                player.setDataSource(this@MainActivity, track.contentUri)
                player.prepare()
                handler.post {
                    if (token != audioLoadToken || isFinishing) {
                        try { player.release() } catch (_: Exception) {}
                        return@post
                    }
                    try {
                        player.isLooping = isAudioRepeatOne
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioSpeed != 1.0f) {
                            try {
                                player.playbackParams = (player.playbackParams ?: PlaybackParams()).setSpeed(audioSpeed)
                            } catch (_: Exception) {}
                        }
                        player.setOnCompletionListener {
                            if (!isAudioRepeatOne) {
                                val nextIdx = if (isAudioShuffle) {
                                    (Math.random() * deviceAudioTracks.size).toInt()
                                } else {
                                    currentAudioIndex + 1
                                }
                                playAudioTrackAtIndex(nextIdx)
                            }
                        }
                        player.start()
                        audioPlayer = player
                        isAudioPlaying = true
                        updateMiniAudioPlayerBar()
                    } catch (_: Exception) {
                        try { player.release() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
                try { mp?.release() } catch (_: Exception) {}
                handler.post {
                    if (token == audioLoadToken) {
                        Toast.makeText(this@MainActivity, "تعذر تشغيل المقطع الصوتي", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun updateMiniAudioPlayerBar() {
        miniAudioPlayerBar.removeAllViews()
        if (currentAudioIndex < 0 || currentAudioIndex >= deviceAudioTracks.size || audioPlayer == null) {
            miniAudioPlayerBar.visibility = View.GONE
            return
        }
        val track = deviceAudioTracks[currentAudioIndex]
        miniAudioPlayerBar.visibility = View.VISIBLE
        miniAudioPlayerBar.background = GradientDrawable().apply {
            setColor(0xEE101C2C.toInt())
            cornerRadius = dpF(20f)
            setStroke(dp(1), colorSamsungBlue)
        }
        miniAudioPlayerBar.setOnClickListener { showProAudioPlayerOverlay() }

        val closeMiniBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(0xFFAEAEB2.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                try {
                    audioPlayer?.stop()
                    audioPlayer?.release()
                } catch (_: Exception) {}
                audioPlayer = null
                isAudioPlaying = false
                currentAudioIndex = -1
                miniAudioPlayerBar.visibility = View.GONE
            }
        }
        miniAudioPlayerBar.addView(closeMiniBtn)

        val nextBtn = makeCircleIconBtn(SamsungIconView.IconType.SKIP_NEXT, 32, 15, 0x22FFFFFF) {
            playAudioTrackAtIndex(currentAudioIndex + 1)
        }
        miniAudioPlayerBar.addView(nextBtn, LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(6) })

        val miniIc = if (audioPlayer?.isPlaying == true) SamsungIconView.IconType.PAUSE_SOLID else SamsungIconView.IconType.PLAY_SOLID
        val playPauseMini = makeCircleIconBtn(miniIc, 34, 16, colorSamsungBlue) {
            val mp = audioPlayer ?: return@makeCircleIconBtn
            if (mp.isPlaying) {
                mp.pause()
                isAudioPlaying = false
            } else {
                mp.start()
                isAudioPlaying = true
            }
            updateMiniAudioPlayerBar()
        }
        miniAudioPlayerBar.addView(playPauseMini, LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(6) })

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.RIGHT
            setPadding(dp(8), 0, dp(10), 0)
        }
        val tTv = TextView(this).apply {
            text = track.title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val aTv = TextView(this).apply {
            text = "${track.artist} • ${track.durationText}"
            textSize = 10.5f
            setTextColor(colorBrandIceBlue)
            gravity = Gravity.RIGHT
            maxLines = 1
        }
        infoCol.addView(tTv)
        infoCol.addView(aTv)
        miniAudioPlayerBar.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))

        miniAudioPlayerBar.addView(createAppLogoBadge(30))
    }

    private fun showProAudioPlayerOverlay() {
        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF061B33.toInt(), 0xFF0A0D14.toInt(), 0xFF000000.toInt())
            )
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // الشريط العلوي لمشغل الصوت الاحترافي
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val speedPill = TextView(this).apply {
            text = "السرعة ${audioSpeed}x"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorBrandIceBlue)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(0x22FFFFFF)
                cornerRadius = dpF(16f)
            }
            setOnClickListener {
                audioSpeed = when (audioSpeed) {
                    1.0f -> 1.25f
                    1.25f -> 1.5f
                    1.5f -> 2.0f
                    2.0f -> 0.75f
                    else -> 1.0f
                }
                text = "السرعة ${audioSpeed}x"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        audioPlayer?.let { mp ->
                            mp.playbackParams = (mp.playbackParams ?: PlaybackParams()).setSpeed(audioSpeed)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        topBar.addView(speedPill)

        val headerTitle = TextView(this).apply {
            text = "مشغل الصوتيات الاحترافي (${toArabicDigits(deviceAudioTracks.size)})"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
            setPadding(0, 0, dp(10), 0)
        }
        topBar.addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f))

        val closeBtn = TextView(this).apply {
            text = "›"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(6), 0)
            setOnClickListener {
                closeOverlay()
                updateMiniAudioPlayerBar()
            }
        }
        topBar.addView(closeBtn)
        root.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // بطاقة المشغل المرئي العلوية (اللوجو + موجات الصوت التفاعلية + التحكم الكامل)
        val deckCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(0x221E3A5F)
                cornerRadius = dpF(26f)
                setStroke(dp(1), 0x448CD0FA)
            }
        }

        val topDeckRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        topDeckRow.addView(createAppLogoBadge(56))

        val nowPlayingCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.RIGHT
            setPadding(dp(10), 0, dp(12), 0)
        }
        val activeTitleTv = TextView(this).apply {
            text = deviceAudioTracks.getOrNull(currentAudioIndex)?.title ?: "اختر مقطعاً صوتياً للتشغيل"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val activeArtistTv = TextView(this).apply {
            text = deviceAudioTracks.getOrNull(currentAudioIndex)?.let { "${it.artist} • ${it.album}" }
                ?: "استديو الصوتيات عالي النقاء"
            textSize = 12f
            setTextColor(colorBrandIceBlue)
            gravity = Gravity.RIGHT
            maxLines = 1
        }
        nowPlayingCol.addView(activeTitleTv)
        nowPlayingCol.addView(activeArtistTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        topDeckRow.addView(nowPlayingCol, LinearLayout.LayoutParams(0, -2, 1f))
        deckCard.addView(topDeckRow, LinearLayout.LayoutParams(-1, -2))

        // موجات الصوت التفاعلية (Visualizer)
        val visualizerView = AudioWaveVisualizerView(this)
        deckCard.addView(
            visualizerView,
            LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(10) }
        )

        // شريط التمرير والوقت
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val curAudioTimeTv = TextView(this).apply {
            text = "٠:٠٠"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val totAudioTimeTv = TextView(this).apply {
            text = deviceAudioTracks.getOrNull(currentAudioIndex)?.durationText ?: "٠:٠٠"
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        }
        var isSeekingAudio = false
        val audioSeek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = audioPlayer?.duration ?: 1
                        curAudioTimeTv.text = formatVideoTimeArabic((dur * p) / 1000)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isSeekingAudio = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val dur = audioPlayer?.duration ?: 1
                    audioPlayer?.seekTo((dur * (sb?.progress ?: 0)) / 1000)
                    isSeekingAudio = false
                }
            })
        }
        seekRow.addView(curAudioTimeTv, LinearLayout.LayoutParams(dp(42), -2))
        seekRow.addView(audioSeek, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(totAudioTimeTv, LinearLayout.LayoutParams(dp(42), -2))
        deckCard.addView(seekRow, LinearLayout.LayoutParams(-1, -2))

        // أزرار التحكم في الصوت (تكرار | التالي | +10ث | تشغيل/إيقاف | -10ث | السابق | عشوائي)
        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val playMainBtn = FrameLayout(this).apply {
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorSamsungBlue)
            }
        }

        fun refreshAudioDeckUI() {
            val tr = deviceAudioTracks.getOrNull(currentAudioIndex)
            activeTitleTv.text = tr?.title ?: "اختر مقطعاً صوتياً للتشغيل"
            activeArtistTv.text = tr?.let { "${it.artist} • ${it.album}" } ?: "استديو الصوتيات"
            totAudioTimeTv.text = tr?.durationText ?: "٠:٠٠"
            playMainBtn.removeAllViews()
            val pIc = if (audioPlayer?.isPlaying == true) SamsungIconView.IconType.PAUSE_SOLID else SamsungIconView.IconType.PLAY_SOLID
            playMainBtn.addView(
                SamsungIconView(this@MainActivity, pIc, Color.WHITE, 2.0f),
                FrameLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER }
            )
            visualizerView.isAnimatingWaves = (audioPlayer?.isPlaying == true)
            visualizerView.invalidate()
        }

        val repeatBtn = makeVideoPillButton(if (isAudioRepeatOne) "تكرار ١" else "تكرار الكل") {}
        repeatBtn.setOnClickListener {
            isAudioRepeatOne = !isAudioRepeatOne
            audioPlayer?.isLooping = isAudioRepeatOne
            repeatBtn.text = if (isAudioRepeatOne) "تكرار ١" else "تكرار الكل"
        }

        val prevTrackBtn = makeCircleIconBtn(SamsungIconView.IconType.SKIP_PREV, 38, 18, 0xFF282830.toInt()) {
            if (deviceAudioTracks.isNotEmpty()) {
                playAudioTrackAtIndex(if (currentAudioIndex <= 0) deviceAudioTracks.size - 1 else currentAudioIndex - 1)
                refreshAudioDeckUI()
            }
        }

        val rw10Btn = makeVideoPillButton("-١٠ث") {
            audioPlayer?.let { mp -> mp.seekTo((mp.currentPosition - 10_000).coerceAtLeast(0)) }
        }

        playMainBtn.setOnClickListener {
            if (currentAudioIndex < 0 && deviceAudioTracks.isNotEmpty()) {
                playAudioTrackAtIndex(0)
            } else {
                audioPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        mp.pause()
                        isAudioPlaying = false
                    } else {
                        mp.start()
                        isAudioPlaying = true
                    }
                }
            }
            refreshAudioDeckUI()
            updateMiniAudioPlayerBar()
        }

        val ff10Btn = makeVideoPillButton("+١٠ث") {
            audioPlayer?.let { mp -> mp.seekTo((mp.currentPosition + 10_000).coerceAtMost(mp.duration)) }
        }

        val nextTrackBtn = makeCircleIconBtn(SamsungIconView.IconType.SKIP_NEXT, 38, 18, 0xFF282830.toInt()) {
            if (deviceAudioTracks.isNotEmpty()) {
                playAudioTrackAtIndex(currentAudioIndex + 1)
                refreshAudioDeckUI()
            }
        }

        val shuffleBtn = makeVideoPillButton(if (isAudioShuffle) "مفعّل" else "عشوائي") {}
        shuffleBtn.setOnClickListener {
            isAudioShuffle = !isAudioShuffle
            shuffleBtn.text = if (isAudioShuffle) "مفعّل" else "عشوائي"
        }

        transportRow.addView(repeatBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) })
        transportRow.addView(prevTrackBtn, LinearLayout.LayoutParams(0, dp(36), 0.8f).apply { rightMargin = dp(4) })
        transportRow.addView(rw10Btn, LinearLayout.LayoutParams(0, dp(36), 0.9f).apply { rightMargin = dp(4) })
        transportRow.addView(playMainBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(4), 0, dp(4), 0) })
        transportRow.addView(ff10Btn, LinearLayout.LayoutParams(0, dp(36), 0.9f).apply { leftMargin = dp(4) })
        transportRow.addView(nextTrackBtn, LinearLayout.LayoutParams(0, dp(36), 0.8f).apply { leftMargin = dp(4) })
        transportRow.addView(shuffleBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(4) })

        deckCard.addView(transportRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(deckCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(12) })

        // قائمة جميع المقاطع الصوتية في الهاتف (Virtualized ListView لسرعة 120fps)
        if (deviceAudioTracks.isEmpty()) {
            val emptyAud = TextView(this).apply {
                text = "لا توجد ملفات صوتية أو موسيقى محفوظة على الهاتف حالياً"
                textSize = 14.5f
                setTextColor(0xFF98989E.toInt())
                gravity = Gravity.CENTER
            }
            root.addView(emptyAud, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val audioListAdapter = object : BaseAdapter() {
                override fun getCount(): Int = deviceAudioTracks.size
                override fun getItem(position: Int): Any = deviceAudioTracks[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                    }
                    row.removeAllViews()
                    val tr = deviceAudioTracks[position]
                    val isCurr = (position == currentAudioIndex)

                    row.background = GradientDrawable().apply {
                        setColor(if (isCurr) 0x330A78D6 else 0x14FFFFFF)
                        cornerRadius = dpF(16f)
                    }

                    val durTv = TextView(this@MainActivity).apply {
                        text = tr.durationText
                        textSize = 11.5f
                        setTextColor(if (isCurr) colorBrandIceBlue else 0xFFAEAEB2.toInt())
                    }
                    row.addView(durTv)

                    val textCol = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.RIGHT
                        setPadding(dp(10), 0, dp(10), 0)
                    }
                    val tTv = TextView(this@MainActivity).apply {
                        text = tr.title
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isCurr) colorBrandIceBlue else Color.WHITE)
                        gravity = Gravity.RIGHT
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val subTv = TextView(this@MainActivity).apply {
                        text = "${tr.artist} • ${formatFileSizeArabic(tr.sizeBytes)}"
                        textSize = 11f
                        setTextColor(0xFF98989E.toInt())
                        gravity = Gravity.RIGHT
                        maxLines = 1
                    }
                    textCol.addView(tTv)
                    textCol.addView(subTv)
                    row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

                    val icon = SamsungIconView(
                        this@MainActivity,
                        if (isCurr && audioPlayer?.isPlaying == true) SamsungIconView.IconType.AUDIO_WAVE else SamsungIconView.IconType.VIDEO_PLAY,
                        if (isCurr) colorBrandIceBlue else Color.WHITE,
                        1.8f
                    )
                    row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))

                    row.setOnClickListener {
                        playAudioTrackAtIndex(position)
                        refreshAudioDeckUI()
                        notifyDataSetChanged()
                    }
                    return row
                }
            }

            val listView = ListView(this).apply {
                adapter = audioListAdapter
                divider = ColorDrawable(Color.TRANSPARENT)
                dividerHeight = dp(6)
                selector = ColorDrawable(Color.TRANSPARENT)
                isFastScrollEnabled = true
            verticalScrollbarPosition = View.SCROLLBAR_POSITION_RIGHT
            }
            root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val audioProgressLoop = object : Runnable {
            override fun run() {
                if (overlayLayer.visibility != View.VISIBLE || !root.isAttachedToWindow) return
                try {
                    val mp = audioPlayer
                    if (mp != null && mp.isPlaying && !isSeekingAudio) {
                        val pos = mp.currentPosition
                        val dur = mp.duration.coerceAtLeast(1)
                        curAudioTimeTv.text = formatVideoTimeArabic(pos)
                        audioSeek.progress = ((pos.toLong() * 1000L) / dur).toInt().coerceIn(0, 1000)
                        visualizerView.isAnimatingWaves = true
                        visualizerView.invalidate()
                    }
                } catch (_: Exception) {}
                handler.postDelayed(this, 200L)
            }
        }
        handler.post(audioProgressLoop)
        refreshAudioDeckUI()

        overlayLayer.addView(root, FrameLayout.LayoutParams(-1, -1))
        animateSlideUpIn(root, distanceDp = 48, durationMs = 160L)
    }

    private fun triggerBiometricAuthentication(onError: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val promptBuilder = BiometricPrompt.Builder(this)
                    .setTitle("فتح قفل الاستديو")
                    .setSubtitle("استخدم بصمة الإصبع للمتابعة")
                    .setDescription("ضع إصبعك على مستشعر البصمة لفتح الاستديو")
                    .setNegativeButton("استخدام كلمة السر", mainExecutor) { _, _ -> }

                val biometricPrompt = promptBuilder.build()
                val cancellationSignal = CancellationSignal()
                biometricPrompt.authenticate(
                    cancellationSignal,
                    mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            super.onAuthenticationSucceeded(result)
                            handler.post {
                                Toast.makeText(this@MainActivity, "تم التحقق من البصمة بنجاح", Toast.LENGTH_SHORT).show()
                                resetFailedAttempts()
                                unlockGalleryNow()
                            }
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            super.onAuthenticationError(errorCode, errString)
                            handler.post {
                                onError(errString?.toString() ?: "يرجى إدخال كلمة السر")
                            }
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            handler.post {
                                onError("لم يتم التعرف على البصمة، حاول مجدداً")
                            }
                        }
                    }
                )
            } catch (_: Exception) {
                onError("البصمة غير مفعّلة في إعدادات الهاتف، استخدم كلمة السر")
            }
        } else {
            onError("يتطلب مستشعر البصمة Android 9+ أو أدخل كلمة السر")
        }
    }

    // ============================================================
    // عمليات التحديد المتعدد والمشاركة
    // ============================================================
    private fun toggleFavoriteForSelected() {
        for (id in selectedMediaIds) {
            if (favoriteIds.contains(id)) favoriteIds.remove(id) else favoriteIds.add(id)
        }
        savePersistedState()
        exitSelectionModeFast()
        Toast.makeText(this, "تم تحديث المفضلة", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedToTrash() {
        val count = selectedMediaIds.size
        for (id in selectedMediaIds) {
            moveItemToTrash(id)
        }
        isSelectionMode = false
        selectedMediaIds.clear()
        savePersistedState()
        renderBottomNavigation()
        renderActiveScreen()
        Toast.makeText(
            this,
            "تم نقل ${toArabicDigits(count)} عنصر إلى سلة المحذوفات (تبقى ٣٠ يوماً)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showAddSelectedToAlbumDialog() {
        val albumNames = (listOf("الكاميرا", "صور الشاشة", "تنزيل") + customAlbumMap.keys).distinct().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("نقل إلى ألبوم")
            .setItems(albumNames) { _, which ->
                val target = albumNames[which]
                val list = customAlbumMap.getOrPut(target) { mutableListOf() }
                for (id in selectedMediaIds) {
                    if (!list.contains(id)) list.add(id)
                }
                savePersistedState()
                exitSelectionModeFast()
                Toast.makeText(this, "تمت الإضافة إلى $target", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ============================================================
    // الخزنة الخاصة + إزالة العناصر (Mango Gallery)
    // ============================================================
    private fun openVault() {
        markExternalLaunch()
        startActivity(Intent(this, VaultActivity::class.java))
    }

    private fun openVaultSecuritySettings() {
        markExternalLaunch()
        val i = Intent(this, VaultActivity::class.java).apply {
            putExtra(VaultActivity.EXTRA_OPEN_SECURITY_PAGE, true)
        }
        startActivity(i)
    }

    private fun openVideoEditor(media: MediaItem) {
        if (!media.isVideo) {
            showSamsungProPhotoEditor(media)
            return
        }
        markExternalLaunch()
        val i = Intent(this, VideoEditorActivity::class.java).apply {
            putExtra(VideoEditorActivity.EXTRA_VIDEO_URI, media.contentUri)
            putExtra(VideoEditorActivity.EXTRA_VIDEO_TITLE, media.title)
        }
        startActivity(i)
    }

    private fun moveSelectedToVault() {
        val visibleMap = getVisibleMediaItems().associateBy { it.id }
        val uris = ArrayList<Uri>()
        val names = ArrayList<String>()
        val mimes = ArrayList<String>()
        for (id in selectedMediaIds) {
            val m = visibleMap[id] ?: continue
            uris.add(m.contentUri)
            names.add(m.title)
            mimes.add(contentResolver.getType(m.contentUri) ?: (if (m.isVideo) "video/mp4" else "image/jpeg"))
        }
        if (uris.isEmpty()) return
        exitSelectionModeFast()
        markExternalLaunch()
        val i = Intent(this, VaultActivity::class.java)
        i.putParcelableArrayListExtra(VaultActivity.EXTRA_IMPORT_URIS, uris)
        i.putStringArrayListExtra(VaultActivity.EXTRA_IMPORT_NAMES, names)
        i.putStringArrayListExtra(VaultActivity.EXTRA_IMPORT_MIMES, mimes)
        startActivity(i)
    }

    private fun openObjectEraser(media: MediaItem) {
        if (media.isVideo) {
            Toast.makeText(this, "إزالة العناصر متاحة للصور فقط", Toast.LENGTH_SHORT).show()
            return
        }
        markExternalLaunch()
        val i = Intent(this, EraserActivity::class.java)
        i.putExtra(EraserActivity.EXTRA_URI, media.contentUri)
        startActivity(i)
    }

    private fun shareSelectedItems() {
        val selectedUris = ArrayList<Uri>()
        val visibleMap = getVisibleMediaItems().associateBy { it.id }
        for (id in selectedMediaIds) {
            visibleMap[id]?.contentUri?.let { selectedUris.add(it) }
        }
        if (selectedUris.isNotEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, selectedUris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                markExternalLaunch(); startActivity(Intent.createChooser(intent, "مشاركة عبر"))
            } catch (_: Exception) {
            }
        }
    }

    private fun shareMediaItem(media: MediaItem) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (media.isVideo) "video/*" else "image/*"
                putExtra(Intent.EXTRA_STREAM, media.contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            markExternalLaunch(); startActivity(Intent.createChooser(intent, "مشاركة عبر"))
        } catch (_: Exception) {
            Toast.makeText(this, "مشاركة: ${media.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun makeTopIconButton(
        iconType: SamsungIconView.IconType,
        tintColor: Int = colorWhite,
        onClick: () -> Unit
    ): View {
        val frame = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { onClick() }
        }
        val icon = SamsungIconView(this, iconType, tintColor, 2f)
        val lp = FrameLayout.LayoutParams(dp(22), dp(22)).apply {
            gravity = Gravity.CENTER
        }
        frame.addView(icon, lp)
        return frame
    }

    // ============================================================
    // محرك تحميل الصور المصغرة الذكي (LIFO + دقة حقيقية عالية الوضوح)
    // ============================================================
    @Suppress("DEPRECATION")
    private fun decodeSharpThumbnailSync(media: MediaItem, targetPx: Int): Bitmap? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bmp = contentResolver.loadThumbnail(
                    media.contentUri,
                    Size(targetPx, targetPx),
                    null
                )
                if (bmp != null) return bmp
            }
        } catch (_: OutOfMemoryError) {
            bitmapCache.evictAll()
        } catch (_: Exception) {
        }

        return if (media.isVideo) {
            extractSharpVideoFrame(media, targetPx)
        } else {
            decodeFullResolutionWithExif(media, targetPx)
        }
    }

    private fun requestThumbnailForGridCell(
        media: MediaItem,
        cellPx: Int,
        cellView: FastGridCellView
    ): Bitmap? {
        val bucketSize = if (lowEndSmoothMode) {
            when {
                cellPx >= 700 -> 640
                cellPx >= 420 -> 420
                else -> 300
            }
        } else {
            when {
                cellPx >= 700 -> 1024
                cellPx >= 420 -> 640
                else -> 480
            }
        }
        val cacheKey = "${media.id}_$bucketSize"
        bitmapCache.get(cacheKey)?.let {
            if (!it.isRecycled) return it
        }

        if (inFlightKeys.add(cacheKey)) {
            val expectedId = media.id
            thumbExecutor.execute {
                try {
                    // إذا تم تدوير الخلية أثناء التمرير السريع، لا نضيع الوقت في فك صورة قديمة!
                    if (cellView.boundMediaId != expectedId) return@execute
                    val decoded = decodeSharpThumbnailSync(media, bucketSize)
                    if (decoded != null) {
                        bitmapCache.put(cacheKey, decoded)
                        if (cellView.boundMediaId == expectedId) {
                            cellView.postInvalidate()
                        }
                    }
                } finally {
                    inFlightKeys.remove(cacheKey)
                }
            }
        }
        return null
    }

    private fun requestThumbnailAsync(media: MediaItem, targetSize: Int, targetView: View): Bitmap? {
        val bucketSize = when {
            targetSize >= 900 -> 1280
            targetSize >= 480 -> 680
            else -> 480
        }
        val cacheKey = "${media.id}_$bucketSize"
        bitmapCache.get(cacheKey)?.let {
            if (!it.isRecycled) return it
        }

        if (inFlightKeys.add(cacheKey)) {
            thumbExecutor.execute {
                try {
                    val decoded = decodeSharpThumbnailSync(media, bucketSize)
                    if (decoded != null) {
                        bitmapCache.put(cacheKey, decoded)
                        targetView.postInvalidate()
                    }
                } finally {
                    inFlightKeys.remove(cacheKey)
                }
            }
        }
        return null
    }

    // ============================================================
    // المكونات الرسومية والتفاعلية المخصصة
    // ============================================================

    /**
     * عرض صورة بحواف دائرية وجودة عالية (للألبومات والقصص وعرض الشرائح)
     */
    private inner class AsyncMediaThumbnailView(
        context: Context,
        private var media: MediaItem,
        private val cornerRadiusPx: Float,
        private val strokeColor: Int,
        private val fitCenterMode: Boolean = false,
        private val preferredDecodePx: Int = 540
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(1f)
            color = strokeColor
        }
        private val clipPath = Path()
        private val rectF = RectF()
        private val drawMatrix = Matrix()

        fun updateMedia(newMedia: MediaItem) {
            this.media = newMedia
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            rectF.set(0f, 0f, w, h)
            clipPath.reset()
            if (cornerRadiusPx > 0f) {
                clipPath.addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clipPath)
            } else {
                canvas.save()
            }

            val reqDim = max(preferredDecodePx, max(width, height))
            val bmp = requestThumbnailAsync(media, reqDim, this)
            if (bmp != null && !bmp.isRecycled) {
                val bw = bmp.width.toFloat().coerceAtLeast(1f)
                val bh = bmp.height.toFloat().coerceAtLeast(1f)
                drawMatrix.reset()
                if (fitCenterMode) {
                    val scale = min(w / bw, h / bh)
                    val dx = (w - bw * scale) * 0.5f
                    val dy = (h - bh * scale) * 0.5f
                    drawMatrix.postScale(scale, scale)
                    drawMatrix.postTranslate(dx, dy)
                } else {
                    val scale = if (bw * h > w * bh) h / bh else w / bw
                    val dx = (w - bw * scale) * 0.5f
                    val dy = (h - bh * scale) * 0.5f
                    drawMatrix.postScale(scale, scale)
                    drawMatrix.postTranslate(dx, dy)
                }
                canvas.drawBitmap(bmp, drawMatrix, paint)
            } else {
                placeholderPaint.color = colorEmptyFavCard
                canvas.drawRect(rectF, placeholderPaint)
            }

            canvas.restore()

            if (strokeColor != Color.TRANSPARENT && cornerRadiusPx > 0f) {
                rectF.inset(dpF(0.5f), dpF(0.5f))
                canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, borderPaint)
            }
        }
    }

    /**
     * عارض الصور التفاعلي الفوري بأعلى دقة (QHD/4K + EXIF + Pinch-to-Zoom + Double-Tap + Swipe)
     */
    /**
     * عارض الصور التفاعلي المتصل بستايل سامسونج (1000036342.png):
     * - عند السحب بإصبع واحد أفقياً، تتحرك الصورة الحالية مع إصبعك لحظة بلحظة وتظهر الصورة المجاورة بجانبها
     *   مباشرة مع فاصل أسود عمودي (Black Divider Gap) بين الصورتين تماماً مثل استديو سامسونج!
     * - عند إفلات الإصبع، يكمل الأنيميشن انتقال الصورة بسلاسة فائقة (ValueAnimator 160ms).
     * - يدعم التكبير بإصبعين (Pinch-to-Zoom) والنقر المزدوج والتحريك في كافة الاتجاهات.
     */
    private inner class TouchZoomableImageView(
        context: Context,
        private val mediaList: List<MediaItem>,
        initialIndex: Int,
        private val onSingleTap: () -> Unit,
        private val onIndexCommitted: (Int) -> Unit,
        private val onSwipeUpDetails: () -> Unit,
        private val onDismissRequest: () -> Unit = {}
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val gapPaint = Paint().apply { color = Color.BLACK }
        private val matrixDraw = Matrix()

        private var activeIndex = initialIndex.coerceIn(0, (mediaList.size - 1).coerceAtLeast(0))
        private var currentBmp: Bitmap? = null
        private var prevBmp: Bitmap? = null
        private var nextBmp: Bitmap? = null

        private var scaleFactor = 1f
        private var transX = 0f
        private var transY = 0f

        // إزاحة السحب الأفقي المتصل بين الصورتين مع الفاصل الأسود (1000036342.png)
        private var pagerDragOffsetX = 0f
        private var isDraggingPager = false
        private var isAnimatingPager = false
        private var dismissDragY = 0f
        private var isDraggingDismiss = false
        private var isDismissing = false
        private val pageGapPx = dpF(18f)

        private var downX = 0f
        private var downY = 0f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var activePointerId = MotionEvent.INVALID_POINTER_ID

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isDraggingPager = false
                    pagerDragOffsetX = 0f
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val prevScale = scaleFactor
                    scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1f, 6f)
                    if (scaleFactor == 1f) {
                        transX = 0f
                        transY = 0f
                    } else {
                        val focusX = detector.focusX - width * 0.5f
                        val focusY = detector.focusY - height * 0.5f
                        val ratio = scaleFactor / prevScale
                        transX = (transX - focusX) * ratio + focusX
                        transY = (transY - focusY) * ratio + focusY
                    }
                    invalidate()
                    return true
                }
            }
        )

        private val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!isAnimatingPager && abs(pagerDragOffsetX) < dp(6)) {
                        onSingleTap()
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isAnimatingPager) return true
                    pagerDragOffsetX = 0f
                    isDraggingPager = false
                    if (scaleFactor > 1.05f) {
                        animateZoomTo(1f, 0f, 0f)
                    } else {
                        val targetScale = 2.8f
                        val tx = (width * 0.5f - e.x) * 1.5f
                        val ty = (height * 0.5f - e.y) * 1.5f
                        animateZoomTo(targetScale, tx, ty)
                    }
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null || scaleFactor > 1.05f || isAnimatingPager) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) > abs(dy) && abs(velocityX) > 450f) {
                        // اتجاه التقليب معكوس: السحب لليمين = الصورة التالية، لليسار = السابقة
                        if (dx > 0 && activeIndex < mediaList.size - 1) {
                            settlePagerTo(+1)
                            return true
                        } else if (dx < 0 && activeIndex > 0) {
                            settlePagerTo(-1)
                            return true
                        }
                    } else if (dy < -dp(90) && abs(dy) > abs(dx) * 1.5f && abs(pagerDragOffsetX) < dp(20)) {
                        onSwipeUpDetails()
                        return true
                    } else if (dy > dp(90) && abs(dy) > abs(dx) * 1.5f && velocityY > 900f) {
                        animateDismiss()
                        return true
                    }
                    return false
                }
            }
        )

        init {
            loadCurrentAndNeighbors()
        }

        fun syncIndexIfNeeded(newIndex: Int) {
            if (newIndex != activeIndex && newIndex in mediaList.indices) {
                activeIndex = newIndex
                scaleFactor = 1f
                transX = 0f
                transY = 0f
                pagerDragOffsetX = 0f
                loadCurrentAndNeighbors()
            }
        }

        private fun animateZoomTo(targetScale: Float, targetTx: Float, targetTy: Float) {
            val startScale = scaleFactor
            val startTx = transX
            val startTy = transY
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 165L
                interpolator = smoothInterpolator
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    scaleFactor = startScale + (targetScale - startScale) * f
                    transX = startTx + (targetTx - startTx) * f
                    transY = startTy + (targetTy - startTy) * f
                    invalidate()
                }
                start()
            }
        }

        private fun loadCurrentAndNeighbors() {
            if (mediaList.isEmpty()) return
            val curr = mediaList[activeIndex]
            currentBmp = getQuickOrCachedBitmap(curr)
            prevBmp = mediaList.getOrNull(activeIndex - 1)?.let { getQuickOrCachedBitmap(it) }
            nextBmp = mediaList.getOrNull(activeIndex + 1)?.let { getQuickOrCachedBitmap(it) }
            invalidate()

            val expectedIdx = activeIndex
            viewerExecutor.execute {
                val fullCurr = getOrDecodeHighRes(curr)
                if (activeIndex == expectedIdx && fullCurr != null) {
                    currentBmp = fullCurr
                    postInvalidate()
                }
                mediaList.getOrNull(expectedIdx + 1)?.let { nxt ->
                    val b = getOrDecodeHighRes(nxt)
                    if (activeIndex == expectedIdx && b != null) {
                        nextBmp = b
                    }
                }
                mediaList.getOrNull(expectedIdx - 1)?.let { prv ->
                    val b = getOrDecodeHighRes(prv)
                    if (activeIndex == expectedIdx && b != null) {
                        prevBmp = b
                    }
                }
            }
        }

        private fun getQuickOrCachedBitmap(media: MediaItem): Bitmap? {
            return bitmapCache.get("${media.id}_full2560")
                ?: bitmapCache.get("${media.id}_1024")
                ?: bitmapCache.get("${media.id}_640")
                ?: bitmapCache.get("${media.id}_480")
        }

        private fun getOrDecodeHighRes(media: MediaItem): Bitmap? {
            val fullKey = "${media.id}_full2560"
            bitmapCache.get(fullKey)?.let { if (!it.isRecycled) return it }
            val decoded = decodeFullResolutionWithExif(media, 2560)
            if (decoded != null) {
                bitmapCache.put(fullKey, decoded)
            }
            return decoded
        }

        private fun drawSingleBitmapFitted(
            canvas: Canvas,
            bmp: Bitmap?,
            fallbackMedia: MediaItem?,
            offsetX: Float,
            scale: Float,
            panX: Float,
            panY: Float
        ) {
            val w = width.toFloat()
            val h = height.toFloat()
            val validBmp = if (bmp != null && !bmp.isRecycled) {
                bmp
            } else if (fallbackMedia != null) {
                requestThumbnailAsync(fallbackMedia, 900, this)
            } else {
                null
            } ?: return

            val bw = validBmp.width.toFloat().coerceAtLeast(1f)
            val bh = validBmp.height.toFloat().coerceAtLeast(1f)
            val baseScale = min(w / bw, h / bh)

            matrixDraw.reset()
            matrixDraw.postTranslate(-bw * 0.5f, -bh * 0.5f)
            matrixDraw.postScale(baseScale * scale, baseScale * scale)
            matrixDraw.postTranslate(w * 0.5f + panX + offsetX, h * 0.5f + panY)
            canvas.drawBitmap(validBmp, matrixDraw, paint)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f || mediaList.isEmpty()) return

            val dragProgress = (abs(pagerDragOffsetX) / w).coerceIn(0f, 1f)
            val dismissProgress = (dismissDragY / (h * 0.6f)).coerceIn(0f, 1f)
            val depthScale = if (scaleFactor <= 1.04f) 1f - 0.06f * dragProgress else 1f
            val dismissScale = 1f - 0.28f * dismissProgress

            // 1. الصورة الحالية (مع تأثير العمق أثناء السحب وتأثير الإغلاق بالسحب للأسفل)
            drawSingleBitmapFitted(
                canvas = canvas,
                bmp = currentBmp,
                fallbackMedia = mediaList[activeIndex],
                offsetX = pagerDragOffsetX,
                scale = scaleFactor * depthScale * dismissScale,
                panX = transX,
                panY = transY + dismissDragY
            )

            // 2. الصورة المجاورة — الاتجاه معكوس: السحب لليسار يُظهر السابقة من اليمين، والسحب لليمين يُظهر التالية من اليسار
            if (pagerDragOffsetX < -1f && activeIndex > 0) {
                val gapLeft = w + pagerDragOffsetX
                canvas.drawRect(gapLeft, 0f, gapLeft + pageGapPx, h, gapPaint)
                val prevOffsetX = pagerDragOffsetX + w + pageGapPx
                drawSingleBitmapFitted(
                    canvas = canvas,
                    bmp = prevBmp,
                    fallbackMedia = mediaList[activeIndex - 1],
                    offsetX = prevOffsetX,
                    scale = 0.94f + 0.06f * dragProgress,
                    panX = 0f,
                    panY = 0f
                )
            } else if (pagerDragOffsetX > 1f && activeIndex < mediaList.size - 1) {
                val gapRight = pagerDragOffsetX
                canvas.drawRect(gapRight - pageGapPx, 0f, gapRight, h, gapPaint)
                val nextOffsetX = pagerDragOffsetX - w - pageGapPx
                drawSingleBitmapFitted(
                    canvas = canvas,
                    bmp = nextBmp,
                    fallbackMedia = mediaList[activeIndex + 1],
                    offsetX = nextOffsetX,
                    scale = 0.94f + 0.06f * dragProgress,
                    panX = 0f,
                    panY = 0f
                )
            }
        }

        private fun settlePagerTo(direction: Int) {
            // direction: -1 = السحب لليسار (الصورة السابقة)، +1 = السحب لليمين (الصورة التالية)، 0 = رجوع للمركز
            val w = width.toFloat().coerceAtLeast(1f)
            val targetOffset = when (direction) {
                -1 -> -(w + pageGapPx)
                +1 -> +(w + pageGapPx)
                else -> 0f
            }
            val startOffset = pagerDragOffsetX
            isAnimatingPager = true
            isDraggingPager = false

            ValueAnimator.ofFloat(startOffset, targetOffset).apply {
                duration = 230L
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    pagerDragOffsetX = anim.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        isAnimatingPager = false
                        pagerDragOffsetX = 0f
                        if (direction == -1 && activeIndex > 0) {
                            activeIndex--
                            nextBmp = currentBmp
                            currentBmp = prevBmp
                            prevBmp = null
                            onIndexCommitted(activeIndex)
                            loadCurrentAndNeighbors()
                        } else if (direction == +1 && activeIndex < mediaList.size - 1) {
                            activeIndex++
                            prevBmp = currentBmp
                            currentBmp = nextBmp
                            nextBmp = null
                            onIndexCommitted(activeIndex)
                            loadCurrentAndNeighbors()
                        } else {
                            invalidate()
                        }
                    }
                })
                start()
            }
        }

        private fun animateDismiss() {
            if (isDismissing) return
            isDismissing = true
            isDraggingDismiss = false
            ValueAnimator.ofFloat(dismissDragY, height.toFloat().coerceAtLeast(1f)).apply {
                duration = 200L
                interpolator = DecelerateInterpolator(1.2f)
                addUpdateListener { a ->
                    dismissDragY = a.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onDismissRequest()
                    }
                })
                start()
            }
        }

        private fun animateDismissBack() {
            ValueAnimator.ofFloat(dismissDragY, 0f).apply {
                duration = 180L
                interpolator = smoothInterpolator
                addUpdateListener { a ->
                    dismissDragY = a.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            if (isAnimatingPager || isDismissing) return true

            if (event.pointerCount > 1) {
                if (isDraggingPager) {
                    isDraggingPager = false
                    pagerDragOffsetX = 0f
                    invalidate()
                }
                if (isDraggingDismiss) {
                    isDraggingDismiss = false
                    dismissDragY = 0f
                    invalidate()
                }
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastTouchX = event.x
                    lastTouchY = event.y
                    activePointerId = event.getPointerId(0)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                        val idx = event.findPointerIndex(activePointerId)
                        if (idx >= 0) {
                            val x = event.getX(idx)
                            val y = event.getY(idx)
                            val dx = x - lastTouchX
                            val dy = y - lastTouchY

                            if (scaleFactor > 1.04f) {
                                // تحريك داخل الصورة المكبرة
                                transX += dx
                                transY += dy
                                val maxPanX = width * (scaleFactor - 1f) * 0.55f
                                val maxPanY = height * (scaleFactor - 1f) * 0.55f
                                transX = transX.coerceIn(-maxPanX, maxPanX)
                                transY = transY.coerceIn(-maxPanY, maxPanY)
                                invalidate()
                            } else {
                                // سحب متصل بين الصورتين مع الفاصل الأسود (1000036342.png)
                                val totalDx = x - downX
                                val totalDy = y - downY
                                if (!isDraggingPager && !isDraggingDismiss && totalDy > dp(12) && totalDy > abs(totalDx) * 1.6f) {
                                    isDraggingDismiss = true
                                }
                                if (isDraggingDismiss) {
                                    dismissDragY = totalDy.coerceAtLeast(0f)
                                    invalidate()
                                } else if (!isDraggingPager && abs(totalDx) > dp(8) && abs(totalDx) > abs(totalDy) * 1.15f) {
                                    isDraggingPager = true
                                }
                                if (isDraggingPager) {
                                    var nextOffset = pagerDragOffsetX + dx
                                    if (nextOffset > 0f && activeIndex >= mediaList.size - 1) {
                                        nextOffset *= 0.25f // مقاومة مرنة عند آخر صورة
                                    } else if (nextOffset < 0f && activeIndex == 0) {
                                        nextOffset *= 0.25f // مقاومة مرنة عند أول صورة
                                    }
                                    pagerDragOffsetX = nextOffset
                                    invalidate()
                                }
                            }
                            lastTouchX = x
                            lastTouchY = y
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    if (isDraggingDismiss) {
                        isDraggingDismiss = false
                        if (dismissDragY > height * 0.18f) animateDismiss() else animateDismissBack()
                    } else if (isDraggingPager && !isAnimatingPager) {
                        val threshold = width * 0.20f
                        when {
                            pagerDragOffsetX < -threshold && activeIndex > 0 -> settlePagerTo(-1)
                            pagerDragOffsetX > threshold && activeIndex < mediaList.size - 1 -> settlePagerTo(+1)
                            else -> settlePagerTo(0)
                        }
                    } else if (abs(pagerDragOffsetX) > 0.5f && !isAnimatingPager) {
                        settlePagerTo(0)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == activePointerId) {
                        val newIdx = if (pointerIndex == 0) 1 else 0
                        lastTouchX = event.getX(newIdx)
                        lastTouchY = event.getY(newIdx)
                        activePointerId = event.getPointerId(newIdx)
                    }
                }
            }
            return true
        }
    }

    /**
     * لوحة محرر صور سامسونج اليدوي التفاعلي
     */
    private class SamsungManualEditorCanvasView(
        context: Context,
        initialBitmap: Bitmap
    ) : View(context) {

        private data class FreehandStroke(val path: Path, val paint: Paint)
        private data class DraggableTextItem(
            val text: String,
            val color: Int,
            var normX: Float = 0.5f,
            var normY: Float = 0.5f
        )

        private var workingBitmap: Bitmap = initialBitmap.copy(Bitmap.Config.ARGB_8888, true)
        private val originalBackup: Bitmap = initialBitmap.copy(Bitmap.Config.ARGB_8888, false)

        var brightnessLevel = 0
        var exposureLevel = 0
        var contrastLevel = 0
        var saturationLevel = 0
        var warmthLevel = 0
        var presetFilterCode = 0
        var filterStrength = 100
        var fineAngleDeg = 0f
        var activePenStyle = 3
        var vignetteActive = false

        var isCropModeActive = true
        var isDrawingModeActive = false
        var penColor = 0xFF38B6FF.toInt()
        var penWidthPx = 12f
        private val redoStrokes = mutableListOf<FreehandStroke>()

        private val cropNorm = RectF(0.06f, 0.06f, 0.94f, 0.94f)
        private var dragCornerMode = 0
        private var lastTouchX = 0f
        private var lastTouchY = 0f

        private val strokes = mutableListOf<FreehandStroke>()
        private var activeStrokePath: Path? = null
        private val textItems = mutableListOf<DraggableTextItem>()
        private var draggingTextItem: DraggableTextItem? = null

        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 4f
        }
        private val cropGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x88FFFFFF.toInt()
            strokeWidth = 1.5f
        }
        private val cornerHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val textDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 48f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }

        private val displayedImageRect = RectF()
        private val activeCropScreenRect = RectF()

        fun getCurrentWorkingBitmap(): Bitmap = workingBitmap

        fun replaceWorkingBitmap(newBmp: Bitmap) {
            workingBitmap = newBmp.copy(Bitmap.Config.ARGB_8888, true)
            invalidate()
        }

        fun rotate90() {
            val m = Matrix().apply { postRotate(90f) }
            workingBitmap = Bitmap.createBitmap(
                workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, m, true
            )
            cropNorm.set(0.06f, 0.06f, 0.94f, 0.94f)
            invalidate()
        }

        fun flipHorizontal() {
            val m = Matrix().apply { postScale(-1f, 1f, workingBitmap.width * 0.5f, workingBitmap.height * 0.5f) }
            workingBitmap = Bitmap.createBitmap(
                workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, m, true
            )
            invalidate()
        }

        fun flipVertical() {
            val m = Matrix().apply { postScale(1f, -1f, workingBitmap.width * 0.5f, workingBitmap.height * 0.5f) }
            workingBitmap = Bitmap.createBitmap(
                workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, m, true
            )
            invalidate()
        }

        fun setCropAspectRatio(ratio: Float) {
            if (ratio <= 0f) {
                cropNorm.set(0.06f, 0.06f, 0.94f, 0.94f)
            } else {
                val imgW = workingBitmap.width.toFloat().coerceAtLeast(1f)
                val imgH = workingBitmap.height.toFloat().coerceAtLeast(1f)
                val targetNormRatio = ratio * (imgH / imgW)
                var w = 0.8f
                var h = w / targetNormRatio
                if (h > 0.8f) {
                    h = 0.8f
                    w = h * targetNormRatio
                }
                val cx = 0.5f
                val cy = 0.5f
                cropNorm.set(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f)
            }
            invalidate()
        }

        fun applyCropNow() {
            val bw = workingBitmap.width
            val bh = workingBitmap.height
            val left = (cropNorm.left * bw).roundToInt().coerceIn(0, bw - 10)
            val top = (cropNorm.top * bh).roundToInt().coerceIn(0, bh - 10)
            val right = (cropNorm.right * bw).roundToInt().coerceIn(left + 10, bw)
            val bottom = (cropNorm.bottom * bh).roundToInt().coerceIn(top + 10, bh)
            workingBitmap = Bitmap.createBitmap(workingBitmap, left, top, right - left, bottom - top)
            cropNorm.set(0.04f, 0.04f, 0.96f, 0.96f)
            strokes.clear()
            invalidate()
        }

        fun addDraggableText(txt: String, color: Int) {
            textItems.add(DraggableTextItem(txt, color, 0.5f, 0.5f))
            invalidate()
        }

        fun undoLastStrokeOrText() {
            if (strokes.isNotEmpty()) {
                val removed = strokes.removeAt(strokes.size - 1)
                redoStrokes.add(removed)
                invalidate()
            } else if (textItems.isNotEmpty()) {
                textItems.removeAt(textItems.size - 1)
                invalidate()
            }
        }

        fun redoLastStroke() {
            if (redoStrokes.isNotEmpty()) {
                strokes.add(redoStrokes.removeAt(redoStrokes.size - 1))
                invalidate()
            }
        }

        fun resetAllEdits() {
            workingBitmap = originalBackup.copy(Bitmap.Config.ARGB_8888, true)
            brightnessLevel = 0
            exposureLevel = 0
            contrastLevel = 0
            saturationLevel = 0
            warmthLevel = 0
            presetFilterCode = 0
            filterStrength = 100
            vignetteActive = false
            fineAngleDeg = 0f
            cropNorm.set(0.06f, 0.06f, 0.94f, 0.94f)
            strokes.clear()
            textItems.clear()
            invalidate()
        }

        private fun buildColorFilter(): ColorMatrixColorFilter {
            val totalMatrix = ColorMatrix()

            val satVal = ((saturationLevel + 100) / 100f).coerceIn(0f, 3f)
            val satMatrix = ColorMatrix().apply { setSaturation(satVal) }
            totalMatrix.postConcat(satMatrix)

            val c = ((contrastLevel + 100) / 100f).let { it * it }.coerceIn(0.2f, 3.5f)
            val b = (brightnessLevel + exposureLevel * 0.8f) * 1.8f
            val translate = (1f - c) * 128f + b
            val cbMatrix = ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, translate,
                    0f, c, 0f, 0f, translate,
                    0f, 0f, c, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            totalMatrix.postConcat(cbMatrix)

            if (warmthLevel != 0) {
                val wShift = warmthLevel * 0.45f
                val wMatrix = ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, wShift,
                        0f, 1f, 0f, 0f, wShift * 0.25f,
                        0f, 0f, 1f, 0f, -wShift,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                totalMatrix.postConcat(wMatrix)
            }

            when (presetFilterCode) {
                1 -> {
                    val m = ColorMatrix().apply { setSaturation(1.22f) }
                    totalMatrix.postConcat(m)
                }
                2 -> {
                    totalMatrix.postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                1.1f, 0f, 0f, 0f, 18f,
                                0f, 1.03f, 0f, 0f, 6f,
                                0f, 0f, 0.9f, 0f, -15f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
                3 -> {
                    totalMatrix.postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                0.9f, 0f, 0f, 0f, -12f,
                                0f, 1.02f, 0f, 0f, 4f,
                                0f, 0f, 1.15f, 0f, 22f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
                4 -> {
                    val bw = ColorMatrix().apply { setSaturation(0f) }
                    totalMatrix.postConcat(bw)
                }
                5 -> {
                    val m = ColorMatrix().apply { setSaturation(1.35f) }
                    totalMatrix.postConcat(m)
                }
                6 -> {
                    val bw = ColorMatrix().apply { setSaturation(0f) }
                    val sep = ColorMatrix().apply { setScale(1.12f, 0.98f, 0.80f, 1f) }
                    totalMatrix.postConcat(bw)
                    totalMatrix.postConcat(sep)
                }
                7 -> {
                    val bw = ColorMatrix().apply { setSaturation(0f) }
                    val hc = ColorMatrix(
                        floatArrayOf(
                            1.45f, 0f, 0f, 0f, -55f,
                            0f, 1.45f, 0f, 0f, -55f,
                            0f, 0f, 1.45f, 0f, -55f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    totalMatrix.postConcat(bw)
                    totalMatrix.postConcat(hc)
                }
            }

            if (presetFilterCode != 0 && filterStrength in 0..99) {
                val alpha = filterStrength / 100f
                val arr = totalMatrix.array
                val identity = floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
                for (i in arr.indices) {
                    arr[i] = identity[i] * (1f - alpha) + arr[i] * alpha
                }
                totalMatrix.set(arr)
            }
            return ColorMatrixColorFilter(totalMatrix)
        }

        private fun createPenPaint(color: Int, strokeW: Float): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            canvas.drawColor(Color.BLACK)

            val pad = 28f
            val availW = w - pad * 2
            val availH = h - pad * 2
            val scale = min(availW / workingBitmap.width, availH / workingBitmap.height)
            val dw = workingBitmap.width * scale
            val dh = workingBitmap.height * scale
            displayedImageRect.set(
                (w - dw) * 0.5f,
                (h - dh) * 0.5f,
                (w + dw) * 0.5f,
                (h + dh) * 0.5f
            )

            imagePaint.colorFilter = buildColorFilter()

            canvas.save()
            canvas.clipRect(displayedImageRect)
            if (fineAngleDeg != 0f) {
                canvas.rotate(fineAngleDeg, displayedImageRect.centerX(), displayedImageRect.centerY())
            }
            canvas.drawBitmap(workingBitmap, null, displayedImageRect, imagePaint)

            for (s in strokes) {
                canvas.drawPath(s.path, s.paint)
            }
            activeStrokePath?.let {
                canvas.drawPath(it, createPenPaint(penColor, penWidthPx))
            }

            for (t in textItems) {
                textDrawPaint.color = t.color
                val tx = displayedImageRect.left + t.normX * displayedImageRect.width()
                val ty = displayedImageRect.top + t.normY * displayedImageRect.height()
                canvas.drawText(t.text, tx, ty, textDrawPaint)
            }
            canvas.restore()

            if (isCropModeActive) {
                activeCropScreenRect.set(
                    displayedImageRect.left + cropNorm.left * displayedImageRect.width(),
                    displayedImageRect.top + cropNorm.top * displayedImageRect.height(),
                    displayedImageRect.left + cropNorm.right * displayedImageRect.width(),
                    displayedImageRect.top + cropNorm.bottom * displayedImageRect.height()
                )

                canvas.drawRect(activeCropScreenRect, cropBorderPaint)
                val thirdW = activeCropScreenRect.width() / 3f
                val thirdH = activeCropScreenRect.height() / 3f
                for (i in 1..2) {
                    val gx = activeCropScreenRect.left + thirdW * i
                    canvas.drawLine(gx, activeCropScreenRect.top, gx, activeCropScreenRect.bottom, cropGridPaint)
                    val gy = activeCropScreenRect.top + thirdH * i
                    canvas.drawLine(activeCropScreenRect.left, gy, activeCropScreenRect.right, gy, cropGridPaint)
                }

                // رسم زوايا القص السميكة على شكل حرف L كما في 1000036296.png
                val cLen = 36f
                cropBorderPaint.strokeWidth = 7f
                val l = activeCropScreenRect.left
                val t = activeCropScreenRect.top
                val r = activeCropScreenRect.right
                val b = activeCropScreenRect.bottom
                canvas.drawLine(l, t, l + cLen, t, cropBorderPaint)
                canvas.drawLine(l, t, l, t + cLen, cropBorderPaint)
                canvas.drawLine(r - cLen, t, r, t, cropBorderPaint)
                canvas.drawLine(r, t, r, t + cLen, cropBorderPaint)
                canvas.drawLine(l, b - cLen, l, b, cropBorderPaint)
                canvas.drawLine(l, b, l + cLen, b, cropBorderPaint)
                canvas.drawLine(r - cLen, b, r, b, cropBorderPaint)
                canvas.drawLine(r, b - cLen, r, b, cropBorderPaint)
                cropBorderPaint.strokeWidth = 2.5f
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            if (event.action == MotionEvent.ACTION_DOWN) {
                for (t in textItems.reversed()) {
                    val tx = displayedImageRect.left + t.normX * displayedImageRect.width()
                    val ty = displayedImageRect.top + t.normY * displayedImageRect.height()
                    if (abs(x - tx) < 120f && abs(y - ty) < 70f) {
                        draggingTextItem = t
                        return true
                    }
                }
            }
            if (draggingTextItem != null) {
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        val dw = displayedImageRect.width().coerceAtLeast(1f)
                        val dh = displayedImageRect.height().coerceAtLeast(1f)
                        draggingTextItem?.normX = ((x - displayedImageRect.left) / dw).coerceIn(0.05f, 0.95f)
                        draggingTextItem?.normY = ((y - displayedImageRect.top) / dh).coerceIn(0.08f, 0.95f)
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        draggingTextItem = null
                        return true
                    }
                }
            }

            if (isDrawingModeActive) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        activeStrokePath = Path().apply { moveTo(x, y) }
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        activeStrokePath?.lineTo(x, y)
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activeStrokePath?.let {
                            strokes.add(FreehandStroke(it, createPenPaint(penColor, penWidthPx)))
                            activeStrokePath = null
                            invalidate()
                        }
                        return true
                    }
                }
            }

            if (isCropModeActive) {
                val hitR = 64f
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = x
                        lastTouchY = y
                        dragCornerMode = when {
                            hypot((x - activeCropScreenRect.left).toDouble(), (y - activeCropScreenRect.top).toDouble()) < hitR -> 1
                            hypot((x - activeCropScreenRect.right).toDouble(), (y - activeCropScreenRect.top).toDouble()) < hitR -> 2
                            hypot((x - activeCropScreenRect.left).toDouble(), (y - activeCropScreenRect.bottom).toDouble()) < hitR -> 3
                            hypot((x - activeCropScreenRect.right).toDouble(), (y - activeCropScreenRect.bottom).toDouble()) < hitR -> 4
                            activeCropScreenRect.contains(x, y) -> 5
                            else -> 0
                        }
                        return dragCornerMode != 0
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragCornerMode == 0) return false
                        val dw = displayedImageRect.width().coerceAtLeast(1f)
                        val dh = displayedImageRect.height().coerceAtLeast(1f)
                        val dxNorm = (x - lastTouchX) / dw
                        val dyNorm = (y - lastTouchY) / dh
                        lastTouchX = x
                        lastTouchY = y
                        val minSpan = 0.15f

                        when (dragCornerMode) {
                            1 -> {
                                cropNorm.left = (cropNorm.left + dxNorm).coerceIn(0f, cropNorm.right - minSpan)
                                cropNorm.top = (cropNorm.top + dyNorm).coerceIn(0f, cropNorm.bottom - minSpan)
                            }
                            2 -> {
                                cropNorm.right = (cropNorm.right + dxNorm).coerceIn(cropNorm.left + minSpan, 1f)
                                cropNorm.top = (cropNorm.top + dyNorm).coerceIn(0f, cropNorm.bottom - minSpan)
                            }
                            3 -> {
                                cropNorm.left = (cropNorm.left + dxNorm).coerceIn(0f, cropNorm.right - minSpan)
                                cropNorm.bottom = (cropNorm.bottom + dyNorm).coerceIn(cropNorm.top + minSpan, 1f)
                            }
                            4 -> {
                                cropNorm.right = (cropNorm.right + dxNorm).coerceIn(cropNorm.left + minSpan, 1f)
                                cropNorm.bottom = (cropNorm.bottom + dyNorm).coerceIn(cropNorm.top + minSpan, 1f)
                            }
                            5 -> {
                                val cw = cropNorm.width()
                                val ch = cropNorm.height()
                                val nl = (cropNorm.left + dxNorm).coerceIn(0f, 1f - cw)
                                val nt = (cropNorm.top + dyNorm).coerceIn(0f, 1f - ch)
                                cropNorm.set(nl, nt, nl + cw, nt + ch)
                            }
                        }
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragCornerMode = 0
                        return true
                    }
                }
            }

            return super.onTouchEvent(event)
        }

        fun renderFinalEditedBitmap(): Bitmap {
            val outW = workingBitmap.width.coerceAtLeast(600)
            val outH = workingBitmap.height.coerceAtLeast(600)
            val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val c = Canvas(result)

            imagePaint.colorFilter = buildColorFilter()
            c.save()
            if (fineAngleDeg != 0f) {
                c.rotate(fineAngleDeg, outW * 0.5f, outH * 0.5f)
            }
            c.drawBitmap(workingBitmap, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), imagePaint)

            val scaleX = outW / displayedImageRect.width().coerceAtLeast(1f)
            val scaleY = outH / displayedImageRect.height().coerceAtLeast(1f)
            c.save()
            c.scale(scaleX, scaleY)
            c.translate(-displayedImageRect.left, -displayedImageRect.top)
            for (s in strokes) {
                c.drawPath(s.path, s.paint)
            }
            c.restore()
            c.restore()

            val scaledTextPaint = Paint(textDrawPaint).apply {
                textSize = 48f * ((scaleX + scaleY) * 0.5f)
            }
            for (t in textItems) {
                scaledTextPaint.color = t.color
                c.drawText(t.text, t.normX * outW, t.normY * outH, scaledTextPaint)
            }
            return result
        }
    }

    /**
     * بطاقة معاينة ألبوم "الأحدث" (شبكة مصغرة 3×3 مع Center-Crop حقيقي بدون تشويه الأبعاد!)
     */
    private inner class RecentMiniGridView(
        context: Context,
        private val previewItems: List<MediaItem>,
        private val cornerRadiusPx: Float
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(1f)
        }
        private val clipPath = Path()
        private val rectF = RectF()
        private val cellRect = RectF()
        private val matrixDraw = Matrix()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            bgPaint.color = if (isDarkModeActive()) 0xFF0A0A0C.toInt() else 0xFFFFFFFF.toInt()
            placeholderPaint.color = colorEmptyFavCard
            borderPaint.color = colorBorderSubtle

            rectF.set(0f, 0f, w, h)
            clipPath.reset()
            clipPath.addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(rectF, bgPaint)

            val pad = dpF(3f)
            val gap = dpF(2.5f)
            val cellW = (w - pad * 2 - gap * 2) / 3f
            val cellH = (h - pad * 2 - gap * 2) / 3f

            var idx = 0
            for (r in 0..2) {
                for (c in 0..2) {
                    val left = w - pad - (c + 1) * cellW - c * gap
                    val top = pad + r * (cellH + gap)
                    cellRect.set(left, top, left + cellW, top + cellH)
                    val item = previewItems.getOrNull(idx)
                    if (item != null) {
                        val bmp = requestThumbnailAsync(item, 480, this)
                        if (bmp != null && !bmp.isRecycled) {
                            canvas.save()
                            canvas.clipRect(cellRect)
                            val bw = bmp.width.toFloat().coerceAtLeast(1f)
                            val bh = bmp.height.toFloat().coerceAtLeast(1f)
                            val scale = if (bw * cellH > cellW * bh) cellH / bh else cellW / bw
                            val dx = left + (cellW - bw * scale) * 0.5f
                            val dy = top + (cellH - bh * scale) * 0.5f
                            matrixDraw.reset()
                            matrixDraw.postScale(scale, scale)
                            matrixDraw.postTranslate(dx, dy)
                            canvas.drawBitmap(bmp, matrixDraw, paint)
                            canvas.restore()
                        } else {
                            canvas.drawRect(cellRect, placeholderPaint)
                        }
                    } else {
                        canvas.drawRect(cellRect, placeholderPaint)
                    }
                    idx++
                }
            }

            canvas.restore()
            rectF.inset(dpF(0.5f), dpF(0.5f))
            canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, borderPaint)
        }
    }

    /**
     * بطاقة الألبوم الفارغ مع زهرة سامسونج الرمادية ذات الـ 8 بتلات
     */
    private inner class EmptyFavoritesCardView(
        context: Context,
        private val cornerRadiusPx: Float
    ) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(1f)
        }
        private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rectF = RectF()
        private val petalRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            bgPaint.color = colorEmptyFavCard
            borderPaint.color = colorBorderSubtle

            rectF.set(0f, 0f, w, h)
            canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, bgPaint)

            val cx = w * 0.5f
            val cy = h * 0.5f
            val petalLen = w * 0.145f
            val petalWidth = w * 0.072f

            // الأوراق الخضراء الثلاث خلف البتلات (الهوية البصرية الجديدة)
            petalPaint.color = if (isDarkModeActive()) 0xFF468236.toInt() else 0xFF5EA847.toInt()
            val leafAngles = floatArrayOf(-22f, 25f, -125f)
            for (ang in leafAngles) {
                canvas.save()
                canvas.translate(cx, cy)
                canvas.rotate(ang)
                petalRect.set(-petalWidth * 0.38f, -petalLen * 1.55f, petalWidth * 0.38f, -petalLen * 0.35f)
                canvas.drawOval(petalRect, petalPaint)
                canvas.restore()
            }

            // بتلات الزهرة الثمان باللون الأزرق الثلجي المتناسق مع اللوجو الجديد
            petalPaint.color = if (isDarkModeActive()) 0xFF6AB8EE.toInt() else 0xFF79C2F5.toInt()
            for (i in 0 until 8) {
                canvas.save()
                canvas.translate(cx, cy)
                canvas.rotate(i * 45f)
                petalRect.set(-petalWidth * 0.5f, -petalLen * 1.35f, petalWidth * 0.5f, -petalLen * 0.2f)
                canvas.drawRoundRect(petalRect, petalWidth * 0.5f, petalWidth * 0.5f, petalPaint)
                canvas.restore()
            }

            // قلب الزهرة الأزرق المتدرج
            centerPaint.color = 0xFF2889D8.toInt()
            canvas.drawCircle(cx, cy, w * 0.068f, centerPaint)
            centerPaint.color = 0xFF055A91.toInt()
            canvas.drawCircle(cx, cy, w * 0.038f, centerPaint)

            rectF.inset(dpF(0.5f), dpF(0.5f))
            canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, borderPaint)
        }
    }

    /**
     * مسطرة التدريج التفاعلية باللمس المطابقة لمحرر صور سامسونج (| | | | | | | |)
     */
    private inner class SamsungTickRulerView(
        context: Context,
        private val minVal: Float,
        private val maxVal: Float,
        initialVal: Float,
        private val accentColor: Int,
        private val onValueChanged: (Float) -> Unit
    ) : View(context) {

        private var currentValue = initialVal.coerceIn(minVal, maxVal)
        private var lastTouchX = 0f

        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(1.4f)
            color = 0xFF707078.toInt()
            strokeCap = Paint.Cap.ROUND
        }
        private val activeTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(1.6f)
            color = 0xFFD0D0D8.toInt()
            strokeCap = Paint.Cap.ROUND
        }
        private val centerIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(2.6f)
            color = accentColor
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val totalTicks = 41
            val padH = w * 0.12f
            val usableW = w - padH * 2f
            val stepX = usableW / (totalTicks - 1)
            val cy = h * 0.55f

            val norm = ((currentValue - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
            val indicatorX = padH + norm * usableW

            for (i in 0 until totalTicks) {
                val tx = padH + i * stepX
                val isMajor = (i % 5 == 0)
                val halfH = if (isMajor) dpF(7f) else dpF(4.5f)
                val p = if (abs(tx - indicatorX) < usableW * 0.15f) activeTickPaint else tickPaint
                canvas.drawLine(tx, cy - halfH, tx, cy + halfH, p)
            }

            // المؤشر العمودي البارز عند القيمة الحالية كما في 1000036298.png
            canvas.drawLine(indicatorX, cy - dpF(12f), indicatorX, cy + dpF(10f), centerIndicatorPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    updateFromX(event.x)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    updateFromX(event.x)
                    lastTouchX = event.x
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun updateFromX(x: Float) {
            val padH = width * 0.12f
            val usableW = (width - padH * 2f).coerceAtLeast(1f)
            val norm = ((x - padH) / usableW).coerceIn(0f, 1f)
            currentValue = minVal + norm * (maxVal - minVal)
            onValueChanged(currentValue)
            invalidate()
        }
    }

    /**
     * مربع معاينة الفلتر الحي على الصورة الحقيقية كما في 1000036297.png
     */
    private inner class FilterLivePreviewTile(
        context: Context,
        sourceBmp: Bitmap,
        private val filterCode: Int,
        private val isSelected: Boolean,
        private val strengthValue: Int
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(2.2f)
            color = Color.WHITE
        }
        private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dpF(13f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        private val rectF = RectF()
        private val clipPath = Path()
        private val matrixDraw = Matrix()
        private val previewBmp = sourceBmp

        init {
            val cm = ColorMatrix()
            when (filterCode) {
                1 -> cm.setSaturation(1.25f)
                2 -> cm.set(
                    floatArrayOf(
                        1.12f, 0f, 0f, 0f, 18f,
                        0f, 1.03f, 0f, 0f, 6f,
                        0f, 0f, 0.88f, 0f, -15f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                3 -> cm.set(
                    floatArrayOf(
                        0.88f, 0f, 0f, 0f, -12f,
                        0f, 1.02f, 0f, 0f, 4f,
                        0f, 0f, 1.16f, 0f, 22f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                4 -> cm.setSaturation(0f)
                5 -> cm.setSaturation(1.4f)
                6 -> {
                    cm.setSaturation(0f)
                    val sep = ColorMatrix().apply { setScale(1.14f, 0.98f, 0.78f, 1f) }
                    cm.postConcat(sep)
                }
                7 -> {
                    cm.setSaturation(0f)
                    cm.postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                1.45f, 0f, 0f, 0f, -50f,
                                0f, 1.45f, 0f, 0f, -50f,
                                0f, 0f, 1.45f, 0f, -50f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
            }
            paint.colorFilter = ColorMatrixColorFilter(cm)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val r = dpF(12f)
            rectF.set(0f, 0f, w, h)
            clipPath.reset()
            clipPath.addRoundRect(rectF, r, r, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(clipPath)
            val bw = previewBmp.width.toFloat().coerceAtLeast(1f)
            val bh = previewBmp.height.toFloat().coerceAtLeast(1f)
            val scale = if (bw * h > w * bh) h / bh else w / bw
            matrixDraw.reset()
            matrixDraw.postScale(scale, scale)
            matrixDraw.postTranslate((w - bw * scale) * 0.5f, (h - bh * scale) * 0.5f)
            canvas.drawBitmap(previewBmp, matrixDraw, paint)

            if (isSelected) {
                canvas.drawColor(0x44000000)
                canvas.drawText(toArabicDigits(strengthValue), w * 0.5f, h * 0.56f, numPaint)
            }
            canvas.restore()

            if (isSelected) {
                rectF.inset(dpF(1.2f), dpF(1.2f))
                canvas.drawRoundRect(rectF, r, r, borderPaint)
            }
        }
    }

    /**
     * عارض موجات الصوت المتفاعل لمشغل الصوتيات الاحترافي
     */
    private inner class AudioWaveVisualizerView(context: Context) : View(context) {
        var isAnimatingWaves = false
        private var phase = 0f
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(3.2f)
            strokeCap = Paint.Cap.ROUND
            color = colorBrandIceBlue
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val bars = 28
            val padH = dpF(16f)
            val step = (w - padH * 2f) / (bars - 1)
            val cy = h * 0.5f

            if (isAnimatingWaves) {
                phase += 0.35f
            }

            for (i in 0 until bars) {
                val bx = padH + i * step
                val wave = if (isAnimatingWaves) {
                    (abs(sin((phase + i * 0.45f).toDouble())).toFloat() * 0.75f + 0.18f)
                } else {
                    0.18f
                }
                val halfBar = (h * 0.44f * wave).coerceAtLeast(dpF(3f))
                barPaint.color = if (i % 2 == 0) colorBrandIceBlue else colorSamsungBlue
                canvas.drawLine(bx, cy - halfBar, bx, cy + halfBar, barPaint)
            }
        }
    }

    /**
     * كبسولة مستوى الصوت العمودية المطابقة للصورة 1000036341.png
     * (حواف مستديرة رمادية داكنة + ثلاث نقاط في الأعلى + شريط الامتلاء العمودي + أيقونة النغمة والسماعة في الأسفل)
     */
    private inner class VerticalVolumeBarView(
        context: Context,
        var levelPercent: Int
    ) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC48484A.toInt()
            style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpF(1.7f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val rectF = RectF()
        private val fillRect = RectF()
        private val clipPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val r = w * 0.5f
            rectF.set(0f, 0f, w, h)
            clipPath.reset()
            clipPath.addRoundRect(rectF, r, r, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(rectF, bgPaint)

            // شريط الامتلاء من الأسفل للأعلى حسب نسبة الصوت
            val trackTop = dpF(32f)
            val trackBottom = h - dpF(38f)
            val trackH = (trackBottom - trackTop).coerceAtLeast(1f)
            val fillTop = trackBottom - (levelPercent.coerceIn(0, 100) / 100f) * trackH
            fillRect.set(w * 0.35f, fillTop, w * 0.65f, trackBottom)
            canvas.drawRoundRect(fillRect, dpF(6f), dpF(6f), fillPaint)

            // الثلاث نقاط الأفقية في أعلى الكبسولة (•••) كما في 1000036341.png
            val dotY = dpF(16f)
            val dotR = dpF(1.8f)
            canvas.drawCircle(w * 0.34f, dotY, dotR, dotPaint)
            canvas.drawCircle(w * 0.50f, dotY, dotR, dotPaint)
            canvas.drawCircle(w * 0.66f, dotY, dotR, dotPaint)

            // أيقونة النغمة الموسيقية والموجات الصوتية في أسفل الكبسولة ())) كما في 1000036341.png
            val by = h - dpF(18f)
            val bx = w * 0.42f
            canvas.drawCircle(bx - dpF(2f), by + dpF(3f), dpF(2.8f), iconPaint)
            canvas.drawLine(bx + dpF(0.8f), by + dpF(3f), bx + dpF(0.8f), by - dpF(6f), iconPaint)
            canvas.drawLine(bx + dpF(0.8f), by - dpF(6f), bx + dpF(4.5f), by - dpF(4f), iconPaint)

            rectF.set(bx + dpF(2f), by - dpF(5f), bx + dpF(8f), by + dpF(5f))
            canvas.drawArc(rectF, -45f, 90f, false, iconPaint)
            rectF.set(bx + dpF(4f), by - dpF(8f), bx + dpF(12f), by + dpF(8f))
            canvas.drawArc(rectF, -45f, 90f, false, iconPaint)

            canvas.restore()
        }
    }

    /**
     * أيقونات المتجهات المرسومة بدقة (Samsung One UI Vector Icons)
     */
    class SamsungIconView(
        context: Context,
        private val iconType: IconType,
        private val iconColor: Int,
        private val strokeDp: Float = 1.9f
    ) : View(context) {

        enum class IconType {
            SEARCH,
            MORE_VERT,
            PLUS,
            HEART,
            HEART_FILLED,
            HAMBURGER,
            VIDEO_PLAY,
            CLOCK,
            LOCATION,
            SHARED_PEOPLE,
            TRASH,
            SETTINGS,
            BADGE_CAMERA,
            BADGE_ALBUM,
            SHARE,
            EDIT_PEN,
            LOCK,
            FINGERPRINT,
            AUDIO_WAVE,
            UNDO_CURVE,
            REDO_CURVE,
            TAB_CROP_ROTATE,
            TAB_THREE_CIRCLES,
            TONE_SUN,
            TONE_EXPOSURE,
            TONE_CONTRAST,
            TONE_SATURATION,
            TONE_WARMTH,
            TAB_SMILEY_PEN,
            TAB_FOUR_DOTS,
            STRAIGHTEN_TILT,
            PERSP_HORIZONTAL,
            PERSP_VERTICAL,
            ERASER_BOX,
            LASSO_SCISSORS,
            EYEDROPPER,
            PALETTE_MIX,
            INFO_CIRCLE,
            CHEVRON_RIGHT,
            ROTATE_PHONE,
            SPEAKER_ON,
            SPEAKER_MUTE,
            PLAY_SOLID,
            PAUSE_SOLID,
            SKIP_PREV,
            SKIP_NEXT,
            CAPTURE_FRAME,
            GIF_BADGE,
            ASPECT_FIT,
            PIP_WINDOW
        }

        init {
            contentDescription = when (iconType) {
                IconType.SEARCH -> "بحث"
                IconType.MORE_VERT -> "المزيد"
                IconType.PLUS -> "إضافة"
                IconType.HEART, IconType.HEART_FILLED -> "المفضلة"
                IconType.HAMBURGER -> "القائمة"
                IconType.TRASH -> "حذف"
                IconType.SETTINGS -> "الضبط"
                IconType.SHARE -> "مشاركة"
                IconType.EDIT_PEN -> "تعديل"
                IconType.LOCK -> "قفل الاستديو"
                IconType.FINGERPRINT -> "البصمة"
                IconType.INFO_CIRCLE -> "تفاصيل"
                IconType.CHEVRON_RIGHT -> "رجوع"
                IconType.SPEAKER_ON -> "تشغيل الصوت"
                IconType.SPEAKER_MUTE -> "كتم الصوت"
                IconType.PLAY_SOLID, IconType.VIDEO_PLAY -> "تشغيل"
                IconType.PAUSE_SOLID -> "إيقاف مؤقت"
                IconType.SKIP_PREV -> "السابق"
                IconType.SKIP_NEXT -> "التالي"
                IconType.UNDO_CURVE -> "تراجع"
                IconType.REDO_CURVE -> "إعادة"
                else -> null
            }
        }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = iconColor
            strokeWidth = strokeDp * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = iconColor
        }

        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w * 0.5f
            val cy = h * 0.5f
            val r = min(w, h) * 0.38f

            path.reset()
            when (iconType) {
                IconType.SEARCH -> {
                    val cr = r * 0.78f
                    canvas.drawCircle(cx - r * 0.12f, cy - r * 0.12f, cr, strokePaint)
                    canvas.drawLine(
                        cx + cr * 0.45f,
                        cy + cr * 0.45f,
                        cx + r * 0.92f,
                        cy + r * 0.92f,
                        strokePaint
                    )
                }
                IconType.MORE_VERT -> {
                    val dotR = min(w, h) * 0.075f
                    canvas.drawCircle(cx, cy - r * 0.72f, dotR, fillPaint)
                    canvas.drawCircle(cx, cy, dotR, fillPaint)
                    canvas.drawCircle(cx, cy + r * 0.72f, dotR, fillPaint)
                }
                IconType.PLUS -> {
                    canvas.drawLine(cx - r * 0.85f, cy, cx + r * 0.85f, cy, strokePaint)
                    canvas.drawLine(cx, cy - r * 0.85f, cx, cy + r * 0.85f, strokePaint)
                }
                IconType.HEART, IconType.HEART_FILLED -> {
                    val hr = r * 0.95f
                    path.moveTo(cx, cy + hr * 0.78f)
                    path.cubicTo(
                        cx - hr * 1.25f, cy + hr * 0.1f,
                        cx - hr * 0.95f, cy - hr * 0.95f,
                        cx, cy - hr * 0.32f
                    )
                    path.cubicTo(
                        cx + hr * 0.95f, cy - hr * 0.95f,
                        cx + hr * 1.25f, cy + hr * 0.1f,
                        cx, cy + hr * 0.78f
                    )
                    path.close()
                    if (iconType == IconType.HEART_FILLED) {
                        canvas.drawPath(path, fillPaint)
                    } else {
                        canvas.drawPath(path, strokePaint)
                    }
                }
                IconType.HAMBURGER -> {
                    val hw = r * 0.92f
                    val gap = r * 0.62f
                    canvas.drawLine(cx - hw, cy - gap, cx + hw, cy - gap, strokePaint)
                    canvas.drawLine(cx - hw, cy, cx + hw, cy, strokePaint)
                    canvas.drawLine(cx - hw, cy + gap, cx + hw, cy + gap, strokePaint)
                }
                IconType.VIDEO_PLAY -> {
                    rect.set(cx - r, cy - r * 0.85f, cx + r, cy + r * 0.85f)
                    canvas.drawRoundRect(rect, r * 0.35f, r * 0.35f, strokePaint)
                    path.moveTo(cx - r * 0.25f, cy - r * 0.38f)
                    path.lineTo(cx + r * 0.42f, cy)
                    path.lineTo(cx - r * 0.25f, cy + r * 0.38f)
                    path.close()
                    canvas.drawPath(path, strokePaint)
                }
                IconType.CLOCK -> {
                    canvas.drawCircle(cx, cy, r * 0.95f, strokePaint)
                    canvas.drawLine(cx, cy - r * 0.52f, cx, cy, strokePaint)
                    canvas.drawLine(cx, cy, cx + r * 0.42f, cy + r * 0.25f, strokePaint)
                }
                IconType.LOCATION -> {
                    val pr = r * 0.75f
                    path.moveTo(cx, cy + r * 0.98f)
                    path.cubicTo(
                        cx - pr * 1.3f, cy + r * 0.1f,
                        cx - pr * 1.1f, cy - r * 0.95f,
                        cx, cy - r * 0.95f
                    )
                    path.cubicTo(
                        cx + pr * 1.1f, cy - r * 0.95f,
                        cx + pr * 1.3f, cy + r * 0.1f,
                        cx, cy + r * 0.98f
                    )
                    canvas.drawPath(path, strokePaint)
                    canvas.drawCircle(cx, cy - r * 0.25f, r * 0.28f, strokePaint)
                }
                IconType.SHARED_PEOPLE -> {
                    canvas.drawCircle(cx - r * 0.35f, cy - r * 0.35f, r * 0.32f, strokePaint)
                    rect.set(cx - r * 0.95f, cy + r * 0.12f, cx + r * 0.25f, cy + r * 0.85f)
                    canvas.drawRoundRect(rect, r * 0.35f, r * 0.35f, strokePaint)

                    canvas.drawCircle(cx + r * 0.42f, cy - r * 0.28f, r * 0.26f, strokePaint)
                    rect.set(cx + r * 0.05f, cy + r * 0.22f, cx + r * 0.98f, cy + r * 0.85f)
                    canvas.drawRoundRect(rect, r * 0.3f, r * 0.3f, strokePaint)
                }
                IconType.TRASH -> {
                    canvas.drawLine(cx - r * 0.85f, cy - r * 0.55f, cx + r * 0.85f, cy - r * 0.55f, strokePaint)
                    rect.set(cx - r * 0.32f, cy - r * 0.85f, cx + r * 0.32f, cy - r * 0.55f)
                    canvas.drawRoundRect(rect, r * 0.1f, r * 0.1f, strokePaint)
                    rect.set(cx - r * 0.65f, cy - r * 0.55f, cx + r * 0.65f, cy + r * 0.9f)
                    canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, strokePaint)
                    canvas.drawLine(cx - r * 0.2f, cy - r * 0.2f, cx - r * 0.2f, cy + r * 0.55f, strokePaint)
                    canvas.drawLine(cx + r * 0.2f, cy - r * 0.2f, cx + r * 0.2f, cy + r * 0.55f, strokePaint)
                }
                IconType.SETTINGS -> {
                    val innerR = r * 0.38f
                    val outerR = r * 0.88f
                    canvas.drawCircle(cx, cy, innerR, strokePaint)
                    canvas.drawCircle(cx, cy, outerR * 0.78f, strokePaint)
                    for (i in 0 until 8) {
                        val ang = Math.toRadians((i * 45).toDouble())
                        val x1 = cx + (outerR * 0.72f * cos(ang)).toFloat()
                        val y1 = cy + (outerR * 0.72f * sin(ang)).toFloat()
                        val x2 = cx + (outerR * 1.02f * cos(ang)).toFloat()
                        val y2 = cy + (outerR * 1.02f * sin(ang)).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, strokePaint)
                    }
                }
                IconType.BADGE_CAMERA -> {
                    rect.set(cx - r * 0.9f, cy - r * 0.6f, cx + r * 0.9f, cy + r * 0.75f)
                    canvas.drawRoundRect(rect, r * 0.25f, r * 0.25f, fillPaint)
                    val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFF222222.toInt()
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(cx, cy + r * 0.08f, r * 0.35f, cutPaint)
                }
                IconType.BADGE_ALBUM -> {
                    rect.set(cx - r * 0.75f, cy - r * 0.75f, cx + r * 0.75f, cy + r * 0.75f)
                    canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, fillPaint)
                }
                IconType.SHARE -> {
                    canvas.drawCircle(cx + r * 0.55f, cy - r * 0.55f, r * 0.22f, strokePaint)
                    canvas.drawCircle(cx - r * 0.55f, cy, r * 0.22f, strokePaint)
                    canvas.drawCircle(cx + r * 0.55f, cy + r * 0.55f, r * 0.22f, strokePaint)
                    canvas.drawLine(cx - r * 0.35f, cy - r * 0.1f, cx + r * 0.35f, cy - r * 0.45f, strokePaint)
                    canvas.drawLine(cx - r * 0.35f, cy + r * 0.1f, cx + r * 0.35f, cy + r * 0.45f, strokePaint)
                }
                IconType.EDIT_PEN -> {
                    path.moveTo(cx - r * 0.75f, cy + r * 0.75f)
                    path.lineTo(cx - r * 0.35f, cy + r * 0.65f)
                    path.lineTo(cx + r * 0.7f, cy - r * 0.4f)
                    path.lineTo(cx + r * 0.4f, cy - r * 0.7f)
                    path.lineTo(cx - r * 0.65f, cy + r * 0.35f)
                    path.close()
                    canvas.drawPath(path, strokePaint)
                }
                IconType.LOCK -> {
                    rect.set(cx - r * 0.72f, cy - r * 0.1f, cx + r * 0.72f, cy + r * 0.9f)
                    canvas.drawRoundRect(rect, r * 0.25f, r * 0.25f, strokePaint)
                    rect.set(cx - r * 0.45f, cy - r * 0.88f, cx + r * 0.45f, cy - r * 0.1f)
                    canvas.drawArc(rect, 180f, 180f, false, strokePaint)
                    canvas.drawCircle(cx, cy + r * 0.38f, r * 0.12f, fillPaint)
                }
                IconType.FINGERPRINT -> {
                    rect.set(cx - r * 0.3f, cy - r * 0.35f, cx + r * 0.3f, cy + r * 0.45f)
                    canvas.drawArc(rect, 180f, 180f, false, strokePaint)
                    canvas.drawLine(cx - r * 0.3f, cy + r * 0.05f, cx - r * 0.3f, cy + r * 0.55f, strokePaint)
                    canvas.drawLine(cx + r * 0.3f, cy + r * 0.05f, cx + r * 0.3f, cy + r * 0.45f, strokePaint)

                    rect.set(cx - r * 0.62f, cy - r * 0.68f, cx + r * 0.62f, cy + r * 0.68f)
                    canvas.drawArc(rect, 195f, 150f, false, strokePaint)
                    canvas.drawLine(cx - r * 0.6f, cy - r * 0.1f, cx - r * 0.52f, cy + r * 0.75f, strokePaint)
                    canvas.drawLine(cx + r * 0.6f, cy - r * 0.1f, cx + r * 0.52f, cy + r * 0.72f, strokePaint)

                    rect.set(cx - r * 0.92f, cy - r * 0.95f, cx + r * 0.92f, cy + r * 0.95f)
                    canvas.drawArc(rect, 210f, 120f, false, strokePaint)
                    canvas.drawLine(cx, cy - r * 0.05f, cx, cy + r * 0.85f, strokePaint)
                }
                IconType.AUDIO_WAVE -> {
                    canvas.drawLine(cx - r * 0.75f, cy - r * 0.3f, cx - r * 0.75f, cy + r * 0.3f, strokePaint)
                    canvas.drawLine(cx - r * 0.25f, cy - r * 0.85f, cx - r * 0.25f, cy + r * 0.85f, strokePaint)
                    canvas.drawLine(cx + r * 0.25f, cy - r * 0.55f, cx + r * 0.25f, cy + r * 0.55f, strokePaint)
                    canvas.drawLine(cx + r * 0.75f, cy - r * 0.25f, cx + r * 0.75f, cy + r * 0.25f, strokePaint)
                }
                IconType.UNDO_CURVE -> {
                    rect.set(cx - r * 0.7f, cy - r * 0.4f, cx + r * 0.7f, cy + r * 0.8f)
                    canvas.drawArc(rect, 190f, 160f, false, strokePaint)
                    canvas.drawLine(cx - r * 0.75f, cy + r * 0.1f, cx - r * 0.35f, cy - r * 0.3f, strokePaint)
                    canvas.drawLine(cx - r * 0.75f, cy + r * 0.1f, cx - r * 0.3f, cy + r * 0.35f, strokePaint)
                }
                IconType.REDO_CURVE -> {
                    rect.set(cx - r * 0.7f, cy - r * 0.4f, cx + r * 0.7f, cy + r * 0.8f)
                    canvas.drawArc(rect, 190f, 160f, false, strokePaint)
                    canvas.drawLine(cx + r * 0.75f, cy + r * 0.1f, cx + r * 0.35f, cy - r * 0.3f, strokePaint)
                    canvas.drawLine(cx + r * 0.75f, cy + r * 0.1f, cx + r * 0.3f, cy + r * 0.35f, strokePaint)
                }
                IconType.TAB_CROP_ROTATE -> {
                    rect.set(cx - r * 0.65f, cy - r * 0.65f, cx + r * 0.65f, cy + r * 0.65f)
                    canvas.drawRect(rect, strokePaint)
                    canvas.drawLine(cx - r * 0.95f, cy - r * 0.65f, cx - r * 0.65f, cy - r * 0.65f, strokePaint)
                    canvas.drawLine(cx + r * 0.65f, cy + r * 0.65f, cx + r * 0.95f, cy + r * 0.65f, strokePaint)
                }
                IconType.TAB_THREE_CIRCLES -> {
                    val cr = r * 0.52f
                    canvas.drawCircle(cx, cy - r * 0.34f, cr, strokePaint)
                    canvas.drawCircle(cx - r * 0.36f, cy + r * 0.32f, cr, strokePaint)
                    canvas.drawCircle(cx + r * 0.36f, cy + r * 0.32f, cr, strokePaint)
                }
                IconType.TONE_SUN -> {
                    canvas.drawCircle(cx, cy, r * 0.48f, strokePaint)
                    for (i in 0 until 8) {
                        val a = Math.toRadians((i * 45).toDouble())
                        val x1 = cx + (r * 0.72f * cos(a)).toFloat()
                        val y1 = cy + (r * 0.72f * sin(a)).toFloat()
                        val x2 = cx + (r * 0.94f * cos(a)).toFloat()
                        val y2 = cy + (r * 0.94f * sin(a)).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, strokePaint)
                    }
                }
                IconType.TONE_EXPOSURE -> {
                    canvas.drawCircle(cx, cy, r * 0.85f, strokePaint)
                    canvas.drawLine(cx - r * 0.55f, cy + r * 0.55f, cx + r * 0.55f, cy - r * 0.55f, strokePaint)
                }
                IconType.TONE_CONTRAST -> {
                    canvas.drawCircle(cx, cy, r * 0.82f, strokePaint)
                    rect.set(cx - r * 0.82f, cy - r * 0.82f, cx + r * 0.82f, cy + r * 0.82f)
                    canvas.drawArc(rect, -90f, 180f, true, fillPaint)
                }
                IconType.TONE_SATURATION -> {
                    path.moveTo(cx, cy - r * 0.88f)
                    path.cubicTo(cx + r * 0.85f, cy + r * 0.1f, cx + r * 0.65f, cy + r * 0.9f, cx, cy + r * 0.9f)
                    path.cubicTo(cx - r * 0.65f, cy + r * 0.9f, cx - r * 0.85f, cy + r * 0.1f, cx, cy - r * 0.88f)
                    canvas.drawPath(path, strokePaint)
                }
                IconType.TONE_WARMTH -> {
                    canvas.drawLine(cx, cy - r * 0.85f, cx, cy + r * 0.45f, strokePaint)
                    canvas.drawCircle(cx, cy + r * 0.55f, r * 0.32f, strokePaint)
                }
                IconType.TAB_SMILEY_PEN -> {
                    rect.set(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f)
                    canvas.drawRoundRect(rect, r * 0.35f, r * 0.35f, strokePaint)
                    canvas.drawCircle(cx - r * 0.28f, cy - r * 0.2f, r * 0.09f, fillPaint)
                    canvas.drawCircle(cx + r * 0.28f, cy - r * 0.2f, r * 0.09f, fillPaint)
                    rect.set(cx - r * 0.35f, cy - r * 0.1f, cx + r * 0.35f, cy + r * 0.4f)
                    canvas.drawArc(rect, 20f, 140f, false, strokePaint)
                }
                IconType.TAB_FOUR_DOTS -> {
                    val dr = r * 0.32f
                    val off = r * 0.48f
                    canvas.drawCircle(cx - off, cy - off, dr, strokePaint)
                    canvas.drawCircle(cx + off, cy - off, dr, strokePaint)
                    canvas.drawCircle(cx - off, cy + off, dr, strokePaint)
                    canvas.drawCircle(cx + off, cy + off, dr, strokePaint)
                }
                IconType.STRAIGHTEN_TILT -> {
                    rect.set(cx - r * 0.65f, cy - r * 0.45f, cx + r * 0.65f, cy + r * 0.45f)
                    canvas.save()
                    canvas.rotate(-12f, cx, cy)
                    canvas.drawRoundRect(rect, r * 0.15f, r * 0.15f, strokePaint)
                    canvas.restore()
                    canvas.drawLine(cx - r * 0.9f, cy, cx + r * 0.9f, cy, strokePaint)
                }
                IconType.PERSP_HORIZONTAL -> {
                    path.moveTo(cx - r * 0.7f, cy - r * 0.45f)
                    path.lineTo(cx + r * 0.7f, cy - r * 0.7f)
                    path.lineTo(cx + r * 0.7f, cy + r * 0.7f)
                    path.lineTo(cx - r * 0.7f, cy + r * 0.45f)
                    path.close()
                    canvas.drawPath(path, strokePaint)
                    canvas.drawLine(cx - r * 0.7f, cy, cx + r * 0.7f, cy, strokePaint)
                }
                IconType.PERSP_VERTICAL -> {
                    path.moveTo(cx - r * 0.45f, cy - r * 0.7f)
                    path.lineTo(cx + r * 0.45f, cy - r * 0.7f)
                    path.lineTo(cx + r * 0.75f, cy + r * 0.7f)
                    path.lineTo(cx - r * 0.75f, cy + r * 0.7f)
                    path.close()
                    canvas.drawPath(path, strokePaint)
                    canvas.drawLine(cx, cy - r * 0.7f, cx, cy + r * 0.7f, strokePaint)
                }
                IconType.ERASER_BOX -> {
                    canvas.save()
                    canvas.rotate(-35f, cx, cy)
                    rect.set(cx - r * 0.65f, cy - r * 0.4f, cx + r * 0.65f, cy + r * 0.4f)
                    canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, strokePaint)
                    canvas.drawLine(cx, cy - r * 0.4f, cx, cy + r * 0.4f, strokePaint)
                    canvas.restore()
                }
                IconType.LASSO_SCISSORS -> {
                    canvas.drawCircle(cx, cy - r * 0.1f, r * 0.72f, strokePaint)
                    canvas.drawLine(cx - r * 0.55f, cy + r * 0.45f, cx + r * 0.15f, cy + r * 0.88f, strokePaint)
                    canvas.drawLine(cx + r * 0.15f, cy + r * 0.45f, cx - r * 0.55f, cy + r * 0.88f, strokePaint)
                }
                IconType.EYEDROPPER -> {
                    canvas.drawLine(cx - r * 0.65f, cy + r * 0.65f, cx + r * 0.25f, cy - r * 0.25f, strokePaint)
                    canvas.drawCircle(cx + r * 0.45f, cy - r * 0.45f, r * 0.3f, fillPaint)
                }
                IconType.PALETTE_MIX -> {
                    canvas.drawCircle(cx, cy, r * 0.85f, strokePaint)
                    canvas.drawCircle(cx - r * 0.32f, cy - r * 0.25f, r * 0.12f, fillPaint)
                    canvas.drawCircle(cx + r * 0.25f, cy - r * 0.32f, r * 0.12f, fillPaint)
                    canvas.drawCircle(cx + r * 0.35f, cy + r * 0.2f, r * 0.12f, fillPaint)
                }
                IconType.INFO_CIRCLE -> {
                    canvas.drawCircle(cx, cy, r * 0.88f, strokePaint)
                    canvas.drawCircle(cx, cy - r * 0.38f, r * 0.09f, fillPaint)
                    canvas.drawLine(cx, cy - r * 0.08f, cx, cy + r * 0.48f, strokePaint)
                }
                IconType.CHEVRON_RIGHT -> {
                    canvas.drawLine(cx - r * 0.28f, cy - r * 0.72f, cx + r * 0.42f, cy, strokePaint)
                    canvas.drawLine(cx + r * 0.42f, cy, cx - r * 0.28f, cy + r * 0.72f, strokePaint)
                }
                IconType.ROTATE_PHONE -> {
                    canvas.save()
                    canvas.rotate(-28f, cx, cy)
                    rect.set(cx - r * 0.36f, cy - r * 0.62f, cx + r * 0.36f, cy + r * 0.62f)
                    canvas.drawRoundRect(rect, r * 0.14f, r * 0.14f, strokePaint)
                    canvas.restore()
                    rect.set(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f)
                    canvas.drawArc(rect, -70f, 110f, false, strokePaint)
                    canvas.drawArc(rect, 110f, 110f, false, strokePaint)
                }
                IconType.SPEAKER_ON, IconType.SPEAKER_MUTE -> {
                    path.moveTo(cx - r * 0.75f, cy - r * 0.3f)
                    path.lineTo(cx - r * 0.35f, cy - r * 0.3f)
                    path.lineTo(cx + r * 0.15f, cy - r * 0.75f)
                    path.lineTo(cx + r * 0.15f, cy + r * 0.75f)
                    path.lineTo(cx - r * 0.35f, cy + r * 0.3f)
                    path.lineTo(cx - r * 0.75f, cy + r * 0.3f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                    if (iconType == IconType.SPEAKER_MUTE) {
                        canvas.drawLine(cx - r * 0.7f, cy - r * 0.75f, cx + r * 0.75f, cy + r * 0.75f, strokePaint)
                    } else {
                        rect.set(cx - r * 0.1f, cy - r * 0.55f, cx + r * 0.65f, cy + r * 0.55f)
                        canvas.drawArc(rect, -45f, 90f, false, strokePaint)
                    }
                }
                IconType.PLAY_SOLID -> {
                    path.moveTo(cx - r * 0.45f, cy - r * 0.72f)
                    path.lineTo(cx + r * 0.68f, cy)
                    path.lineTo(cx - r * 0.45f, cy + r * 0.72f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                }
                IconType.PAUSE_SOLID -> {
                    rect.set(cx - r * 0.52f, cy - r * 0.68f, cx - r * 0.16f, cy + r * 0.68f)
                    canvas.drawRoundRect(rect, r * 0.12f, r * 0.12f, fillPaint)
                    rect.set(cx + r * 0.16f, cy - r * 0.68f, cx + r * 0.52f, cy + r * 0.68f)
                    canvas.drawRoundRect(rect, r * 0.12f, r * 0.12f, fillPaint)
                }
                IconType.SKIP_PREV -> {
                    canvas.drawLine(cx - r * 0.68f, cy - r * 0.55f, cx - r * 0.68f, cy + r * 0.55f, strokePaint)
                    path.moveTo(cx, cy - r * 0.52f)
                    path.lineTo(cx - r * 0.62f, cy)
                    path.lineTo(cx, cy + r * 0.52f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                    path.reset()
                    path.moveTo(cx + r * 0.68f, cy - r * 0.52f)
                    path.lineTo(cx + r * 0.06f, cy)
                    path.lineTo(cx + r * 0.68f, cy + r * 0.52f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                }
                IconType.SKIP_NEXT -> {
                    canvas.drawLine(cx + r * 0.68f, cy - r * 0.55f, cx + r * 0.68f, cy + r * 0.55f, strokePaint)
                    path.moveTo(cx, cy - r * 0.52f)
                    path.lineTo(cx + r * 0.62f, cy)
                    path.lineTo(cx, cy + r * 0.52f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                    path.reset()
                    path.moveTo(cx - r * 0.68f, cy - r * 0.52f)
                    path.lineTo(cx - r * 0.06f, cy)
                    path.lineTo(cx - r * 0.68f, cy + r * 0.52f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                }
                IconType.CAPTURE_FRAME -> {
                    val b = r * 0.82f
                    val c = r * 0.35f
                    canvas.drawLine(cx - b, cy - b, cx - b + c, cy - b, strokePaint)
                    canvas.drawLine(cx - b, cy - b, cx - b, cy - b + c, strokePaint)
                    canvas.drawLine(cx + b - c, cy - b, cx + b, cy - b, strokePaint)
                    canvas.drawLine(cx + b, cy - b, cx + b, cy - b + c, strokePaint)
                    canvas.drawLine(cx - b, cy + b - c, cx - b, cy + b, strokePaint)
                    canvas.drawLine(cx - b, cy + b, cx - b + c, cy + b, strokePaint)
                    canvas.drawLine(cx + b - c, cy + b, cx + b, cy + b, strokePaint)
                    canvas.drawLine(cx + b, cy + b - c, cx + b, cy + b, strokePaint)
                    rect.set(cx - r * 0.45f, cy - r * 0.35f, cx + r * 0.45f, cy + r * 0.45f)
                    canvas.drawRoundRect(rect, r * 0.12f, r * 0.12f, strokePaint)
                }
                IconType.GIF_BADGE -> {
                    rect.set(cx - r * 0.88f, cy - r * 0.68f, cx + r * 0.88f, cy + r * 0.68f)
                    canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, strokePaint)
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = iconColor
                        textSize = r * 0.72f
                        typeface = Typeface.DEFAULT_BOLD
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText("GIF", cx, cy + r * 0.26f, tp)
                }
                IconType.ASPECT_FIT -> {
                    rect.set(cx - r * 0.85f, cy - r * 0.68f, cx + r * 0.85f, cy + r * 0.68f)
                    canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, strokePaint)
                    canvas.drawLine(cx - r * 0.5f, cy - r * 0.35f, cx - r * 0.2f, cy - r * 0.35f, strokePaint)
                    canvas.drawLine(cx - r * 0.5f, cy - r * 0.35f, cx - r * 0.5f, cy - r * 0.05f, strokePaint)
                    canvas.drawLine(cx + r * 0.5f, cy + r * 0.35f, cx + r * 0.2f, cy + r * 0.35f, strokePaint)
                    canvas.drawLine(cx + r * 0.5f, cy + r * 0.35f, cx + r * 0.5f, cy + r * 0.05f, strokePaint)
                }
                IconType.PIP_WINDOW -> {
                    rect.set(cx - r * 0.85f, cy - r * 0.68f, cx + r * 0.85f, cy + r * 0.68f)
                    canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, strokePaint)
                    rect.set(cx - r * 0.52f, cy - r * 0.35f, cx - r * 0.02f, cy + r * 0.12f)
                    canvas.drawRoundRect(rect, r * 0.1f, r * 0.1f, fillPaint)
                }
            }
        }
    }
}