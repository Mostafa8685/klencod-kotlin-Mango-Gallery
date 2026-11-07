package com.m3rd.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
    private var picturesQuickFilter = 0 // 0=الكل, 1=الصور فقط, 2=الفيديوهات, 3=المفضلة

    private var activeAlbumDetail: AlbumInfo? = null
    private var activeSubPageTitle: String? = null
    private var activeSubPageItems: List<MediaItem>? = null
    private var showOnlyEssentialAlbums = true
    private var showCustomizeAlbumsBanner = true
    private var storiesFavoritesOnly = false

    // مراجع القوائم لتحديثها فوراً بدون إعادة إنشاء الشاشة
    private var activePicturesAdapter: PicturesVirtualAdapter? = null
    private var activeSelectionTitleTv: TextView? = null
    private var activeSelectAllTv: TextView? = null

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
    private var appLockPin = ""
    private var appLockBiometric = true
    private var isAppLockedNow = false
    private val trashRetentionMs = 30L * 24L * 60L * 60L * 1000L

    // عرض الشرائح
    private var isSlideshowRunning = false
    private var slideshowRunnable: Runnable? = null

    // كاشف إيماءة التكبير/التصغير بإصبعين على شبكة الصور
    private lateinit var gridScaleDetector: ScaleGestureDetector

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
        purgeExpiredTrashItems()
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
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    accumulatedScale *= detector.scaleFactor
                    if (accumulatedScale > 1.32f && picturesColumnCount > 2) {
                        picturesColumnCount--
                        accumulatedScale = 1f
                        savePersistedState()
                        renderActiveScreen()
                        return true
                    } else if (accumulatedScale < 0.74f && picturesColumnCount < 5) {
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

        lockLayer = FrameLayout(this).apply {
            visibility = View.GONE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        rootStack.addView(lockLayer, FrameLayout.LayoutParams(-1, -1))

        setContentView(rootStack)

        renderBottomNavigation()
        renderActiveScreen()

        if (appLockEnabled && appLockPin.isNotEmpty()) {
            lockGalleryNow(autoPromptBiometric = true)
        }

        requestMediaPermissionsIfNeeded()
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
        themeMode = prefs.getInt("theme_mode", 0)
        appLockEnabled = prefs.getBoolean("app_lock_enabled", false)
        appLockPin = prefs.getString("app_lock_pin", "") ?: ""
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
            .putInt("theme_mode", themeMode)
            .putBoolean("app_lock_enabled", appLockEnabled)
            .putString("app_lock_pin", appLockPin)
            .putBoolean("app_lock_biometric", appLockBiometric)
            .putStringSet("custom_albums_names", customAlbumMap.keys.toSet())

        for ((name, list) in customAlbumMap) {
            editor.putStringSet("custom_album_$name", list.toSet())
        }
        editor.apply()
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
        trashedIds.remove(id)
        trashedTimestamps.remove(id)
        permanentlyDeletedIds.add(id)
        refreshCachedVisibleItems()
        val item = deviceMediaItems.find { it.id == id }
        if (item != null) {
            try {
                contentResolver.delete(item.contentUri, null, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun purgeExpiredTrashItems() {
        val now = System.currentTimeMillis()
        val expired = trashedIds.filter { id ->
            val deletedAt = trashedTimestamps[id] ?: now
            (now - deletedAt) >= trashRetentionMs
        }
        if (expired.isNotEmpty()) {
            for (id in expired) {
                permanentlyDeleteItem(id)
            }
            savePersistedState()
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
                checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
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
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            loadDeviceMediaAsync()
        } else {
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

    @Suppress("DEPRECATION")
    private fun loadDeviceMediaAsync() {
        Thread {
            val loaded = mutableListOf<MediaItem>()
            try {
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

                // 1. قراءة الصور الحقيقية مع زاوية التوجيه (ORIENTATION)
                val imgProjection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DATE_ADDED,
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
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val addCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
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
                        val rawSec = cursor.getLong(modCol).let { if (it > 0L) it else cursor.getLong(addCol) }
                        val timeMs = if (rawSec > 100_000_000_000L) rawSec else rawSec * 1000L
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
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.DATE_ADDED,
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
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val modCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val addCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
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
                        val rawSec = cursor.getLong(modCol).let { if (it > 0L) it else cursor.getLong(addCol) }
                        val timeMs = if (rawSec > 100_000_000_000L) rawSec else rawSec * 1000L
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

                loaded.sortByDescending { it.timestampMs }
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
                    MediaStore.Audio.Media.DATE_MODIFIED
                )
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    audProjection,
                    null,
                    null,
                    "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
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
                val changed = (loaded.size != deviceMediaItems.size) ||
                    (loaded.firstOrNull()?.id != deviceMediaItems.firstOrNull()?.id) ||
                    (loadedAudio.size != deviceAudioTracks.size)
                deviceMediaItems.clear()
                deviceMediaItems.addAll(loaded)
                deviceAudioTracks.clear()
                deviceAudioTracks.addAll(loadedAudio)
                refreshCachedVisibleItems()
                rebuildDynamicStoriesFromRealMedia()
                if (changed || cachedVisibleItems.isEmpty()) {
                    renderActiveScreen()
                } else {
                    activePicturesAdapter?.updateData(buildPictureListRows(cachedVisibleItems, picturesColumnCount))
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

        // الترتيب المطابق لسامسونج: ☰ (أقصى اليسار) | القصص | ألبومات | الصور (أقصى اليمين)
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
            makeBottomTabItem("القصص", 2),
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
            if (appLockEnabled && appLockPin.isNotEmpty()) {
                lockGalleryNow(autoPromptBiometric = true)
            } else {
                showAppLockSettingsDialog()
            }
        }
        normalTopBar.addView(
            lockBtn,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) }
        )

        // زر تصغير/تكبير عدد الأعمدة السريع
        val zoomColBtn = TextView(this).apply {
            text = "${toArabicDigits(picturesColumnCount)}×"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorSamsungBlue)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(colorCardDark)
                cornerRadius = dpF(14f)
                setStroke(dp(1), colorBorderSubtle)
            }
            setOnClickListener {
                picturesColumnCount = when (picturesColumnCount) {
                    2 -> 3
                    3 -> 4
                    4 -> 5
                    else -> 2
                }
                savePersistedState()
                renderActiveScreen()
            }
        }
        normalTopBar.addView(
            zoomColBtn,
            LinearLayout.LayoutParams(-2, dp(30)).apply { leftMargin = dp(6) }
        )

        normalTopBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // الهوية البصرية الجديدة: اسم الاستديو + اللوجو الأزرق الجديد في أقصى اليمين
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { showAboutBrandDialog() }
        }
        val brandTitle = TextView(this).apply {
            text = "الاستديو"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            setPadding(0, 0, dp(8), 0)
        }
        brandRow.addView(brandTitle)
        brandRow.addView(createAppLogoBadge(28))
        normalTopBar.addView(brandRow)

        wrapper.addView(normalTopBar, LinearLayout.LayoutParams(-1, -2))

        // شريط الفلترة السريعة (الكل | الصور | مقاطع فيديو | المفضلة) بدون أي لاج
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = if (isSelectionMode) View.GONE else View.VISIBLE
        }
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val allItemsRaw = getVisibleMediaItems()
        val vidCount = allItemsRaw.count { it.isVideo }
        val imgCount = allItemsRaw.size - vidCount
        val favCount = allItemsRaw.count { favoriteIds.contains(it.id) }

        val filterDefs = listOf(
            Pair("الكل (${toArabicDigits(allItemsRaw.size)})", 0),
            Pair("الصور (${toArabicDigits(imgCount)})", 1),
            Pair("فيديوهات (${toArabicDigits(vidCount)})", 2),
            Pair("المفضلة (${toArabicDigits(favCount)})", 3),
            Pair("🎵 الصوتيات (${toArabicDigits(deviceAudioTracks.size)})", 99)
        )
        for ((fText, fCode) in filterDefs) {
            val active = (picturesQuickFilter == fCode)
            val chip = TextView(this).apply {
                text = fText
                textSize = 12f
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (active) Color.WHITE else colorTextSecondary)
                setPadding(dp(13), dp(6), dp(13), dp(6))
                background = GradientDrawable().apply {
                    setColor(if (active) colorSamsungBlue else colorCardDark)
                    cornerRadius = dpF(16f)
                    setStroke(dp(1), if (active) colorSamsungBlue else colorBorderSubtle)
                }
                setOnClickListener {
                    if (fCode == 99) {
                        showProAudioPlayerOverlay()
                    } else if (picturesQuickFilter != fCode) {
                        picturesQuickFilter = fCode
                        val filteredNow = when (picturesQuickFilter) {
                            1 -> allItemsRaw.filter { !it.isVideo }
                            2 -> allItemsRaw.filter { it.isVideo }
                            3 -> allItemsRaw.filter { favoriteIds.contains(it.id) }
                            else -> allItemsRaw
                        }
                        activePicturesAdapter?.updateData(buildPictureListRows(filteredNow, picturesColumnCount))
                        renderActiveScreen()
                    }
                }
            }
            filterRow.addView(
                chip,
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) }
            )
        }
        filterScroll.addView(filterRow)
        wrapper.addView(filterScroll, LinearLayout.LayoutParams(-1, -2))

        // شريط وضع التحديد العلوي
        val selectionTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(16), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        }

        val cancelBtn = TextView(this).apply {
            text = "إلغاء"
            textSize = 15f
            setTextColor(colorWhite)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                selectionTopBar.visibility = View.GONE
                normalTopBar.visibility = View.VISIBLE
                exitSelectionModeFast()
            }
        }
        selectionTopBar.addView(cancelBtn)

        val selectAllTv = TextView(this).apply {
            text = "الكل"
            textSize = 14f
            setTextColor(colorSamsungBlue)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                val allVisible = getVisibleMediaItems()
                if (selectedMediaIds.size >= allVisible.size && allVisible.isNotEmpty()) {
                    selectedMediaIds.clear()
                } else {
                    selectedMediaIds.addAll(allVisible.map { it.id })
                }
                updateSelectionTopBarAndListInPlace()
            }
        }
        activeSelectAllTv = selectAllTv
        selectionTopBar.addView(selectAllTv)

        val countTv = TextView(this).apply {
            text = if (selectedMediaIds.isEmpty()) "تحديد عناصر"
            else "تم تحديد ${toArabicDigits(selectedMediaIds.size)}"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorWhite)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        activeSelectionTitleTv = countTv
        selectionTopBar.addView(countTv, LinearLayout.LayoutParams(0, -2, 1f))
        wrapper.addView(selectionTopBar, LinearLayout.LayoutParams(-1, -2))

        val rawVisible = getVisibleMediaItems()
        if (rawVisible.isEmpty()) {
            wrapper.addView(buildEmptyOrPermissionPromptView(), LinearLayout.LayoutParams(-1, 0, 1f))
            return wrapper
        }
        val visibleItems = when (picturesQuickFilter) {
            1 -> rawVisible.filter { !it.isVideo }
            2 -> rawVisible.filter { it.isVideo }
            3 -> rawVisible.filter { favoriteIds.contains(it.id) }
            else -> rawVisible
        }
        if (visibleItems.isEmpty()) {
            val emptyFilterTv = TextView(this).apply {
                text = "لا توجد عناصر في هذا التصنيف حالياً"
                textSize = 15f
                setTextColor(colorTextMuted)
                gravity = Gravity.CENTER
            }
            wrapper.addView(emptyFilterTv, LinearLayout.LayoutParams(-1, 0, 1f))
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
                gridScaleDetector.onTouchEvent(ev)
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            this.adapter = adapter
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            isFastScrollEnabled = true
            setPadding(dp(4), 0, dp(4), dp(24))
            clipToPadding = false
        }

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
    ) : BaseAdapter() {

        val totalMediaCount: Int
            get() = contextList.size

        private val spacing = dp(2)
        private val cellSize = (resources.displayMetrics.widthPixels - dp(8) - spacing * (cols * 2)) / cols

        fun updateData(newRows: List<PictureListRow>) {
            rows = newRows
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
                    cell.bind(
                        media = media,
                        cellPx = cellSize,
                        isSelected = selectedMediaIds.contains(media.id),
                        inSelectionMode = isSelectionMode && !isRecycleBinMode,
                        isFav = favoriteIds.contains(media.id),
                        trashDaysLeft = if (isRecycleBinMode) getRemainingTrashDays(media.id) else 0
                    )
                    cell.setOnClickListener {
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
                val label = if (media.durationText.isNotEmpty()) "▶ ${media.durationText}" else "▶"
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
            else "يحتاج الاستديو إلى إذن الوصول للصور والفيديوهات"
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
                "اضغط على الزر بالأسفل للسماح لتطبيق الاستديو بقراءة وعرض جميع صور وفيديوهات هاتفك الحقيقية."
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

        val scroll = ScrollView(this).apply {
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

        val availWidth = resources.displayMetrics.widthPixels - dp(24)
        val colGap = dp(14)
        val cardSize = (availWidth - colGap) / 2

        val albumRows = albumsToShow.chunked(2)
        for (pair in albumRows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }

            val rightAlbum = pair[0]
            row.addView(
                buildAlbumCardView(rightAlbum, cardSize),
                LinearLayout.LayoutParams(cardSize, -2)
            )

            row.addView(View(this), LinearLayout.LayoutParams(colGap, 1))

            if (pair.size > 1) {
                val leftAlbum = pair[1]
                row.addView(
                    buildAlbumCardView(leftAlbum, cardSize),
                    LinearLayout.LayoutParams(cardSize, -2)
                )
            } else {
                row.addView(View(this), LinearLayout.LayoutParams(cardSize, 1))
            }

            mainCol.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
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

        when {
            album.isRecentSpecial && album.items.isNotEmpty() -> {
                val miniGrid = RecentMiniGridView(this, album.items.take(9), dpF(22f))
                thumbFrame.addView(miniGrid, FrameLayout.LayoutParams(-1, -1))
            }
            album.items.isEmpty() -> {
                val emptyCard = EmptyFavoritesCardView(this, dpF(22f))
                thumbFrame.addView(emptyCard, FrameLayout.LayoutParams(-1, -1))
            }
            else -> {
                val coverItem = album.items.first()
                val coverView = AsyncMediaThumbnailView(
                    context = this,
                    media = coverItem,
                    cornerRadiusPx = dpF(22f),
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

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(16), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val moreBtn = makeTopIconButton(SamsungIconView.IconType.MORE_VERT) {
            showStoriesMorePopover()
        }
        topBar.addView(moreBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val searchBtn = makeTopIconButton(SamsungIconView.IconType.SEARCH) {
            showSearchOverlay()
        }
        topBar.addView(
            searchBtn,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) }
        )

        val favFilterIcon = if (storiesFavoritesOnly) {
            SamsungIconView.IconType.HEART_FILLED
        } else {
            SamsungIconView.IconType.HEART
        }
        val favBtn = makeTopIconButton(favFilterIcon) {
            storiesFavoritesOnly = !storiesFavoritesOnly
            renderActiveScreen()
        }
        topBar.addView(
            favBtn,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) }
        )

        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        wrapper.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        val visibleMedia = getVisibleMediaItems()
        if (visibleMedia.isEmpty()) {
            wrapper.addView(buildEmptyOrPermissionPromptView(), LinearLayout.LayoutParams(-1, 0, 1f))
            return wrapper
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val highlightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val highlightCard = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener {
                showStoryPlayerOverlay(
                    StoryInfo(
                        id = "highlights_recent",
                        title = "التمييزات الأخيرة",
                        yearText = toArabicDigits(SimpleDateFormat("yyyy", Locale.US).format(Date())),
                        isNew = true,
                        items = visibleMedia.take(15)
                    )
                )
            }
        }
        val highlightThumb = AsyncMediaThumbnailView(
            context = this,
            media = visibleMedia.first(),
            cornerRadiusPx = dpF(18f),
            strokeColor = colorBorderSubtle,
            preferredDecodePx = dp(180)
        )
        highlightCard.addView(highlightThumb, FrameLayout.LayoutParams(-1, -1))

        val highlightLabel = TextView(this).apply {
            text = "التمييزات الأخيرة"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        val hlLabelLp = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        }
        highlightCard.addView(highlightLabel, hlLabelLp)

        highlightRow.addView(highlightCard, LinearLayout.LayoutParams(dp(136), dp(136)))
        col.addView(highlightRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val exploreTitle = TextView(this).apply {
            text = "استكشاف القصص الخاصة بك"
            textSize = 17f
            setTextColor(colorTextPrimary)
            gravity = Gravity.RIGHT
            setPadding(dp(4), dp(22), dp(4), dp(14))
        }
        col.addView(exploreTitle, LinearLayout.LayoutParams(-1, -2))

        val filteredStories = if (storiesFavoritesOnly) {
            storiesList.filter { it.isFavorite }
        } else {
            storiesList
        }

        if (filteredStories.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "لا توجد قصص مطابقة حالياً"
                textSize = 14f
                setTextColor(colorTextMuted)
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
            }
            col.addView(emptyTv, LinearLayout.LayoutParams(-1, -2))
        } else {
            for (story in filteredStories) {
                val card = buildLargeRealStoryCard(story)
                col.addView(
                    card,
                    LinearLayout.LayoutParams(-1, dp(390)).apply { bottomMargin = dp(16) }
                )
            }
        }

        scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
        wrapper.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return wrapper
    }

    private fun buildLargeRealStoryCard(story: StoryInfo): View {
        val cardFrame = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener {
                showStoryPlayerOverlay(story)
            }
        }

        val coverMedia = story.items.firstOrNull() ?: getVisibleMediaItems().firstOrNull()
        if (coverMedia != null) {
            val thumb = AsyncMediaThumbnailView(
                context = this,
                media = coverMedia,
                cornerRadiusPx = dpF(28f),
                strokeColor = colorBorderSubtle,
                preferredDecodePx = 960
            )
            cardFrame.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        }

        val bottomScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xAA000000.toInt())
            ).apply {
                cornerRadius = dpF(28f)
            }
        }
        cardFrame.addView(bottomScrim, FrameLayout.LayoutParams(-1, -1))

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val titleTv = TextView(this).apply {
            text = story.title
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        val yearTv = TextView(this).apply {
            text = story.yearText
            textSize = 13f
            setTextColor(0xFFE5E5EA.toInt())
            gravity = Gravity.CENTER
        }
        titleBox.addView(titleTv)
        titleBox.addView(yearTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })

        val titleLp = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        cardFrame.addView(titleBox, titleLp)

        val topLeftBadges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val heartIconType = if (story.isFavorite) {
            SamsungIconView.IconType.HEART_FILLED
        } else {
            SamsungIconView.IconType.HEART
        }
        val heartColor = if (story.isFavorite) 0xFFFF3B30.toInt() else Color.WHITE
        val heartView = SamsungIconView(this, heartIconType, heartColor, 1.8f).apply {
            isClickable = true
            setOnClickListener {
                story.isFavorite = !story.isFavorite
                renderActiveScreen()
            }
        }
        topLeftBadges.addView(heartView, LinearLayout.LayoutParams(dp(24), dp(24)))

        if (story.isNew) {
            val newBadge = TextView(this).apply {
                text = "جديد"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(9), dp(2), dp(9), dp(3))
                background = GradientDrawable().apply {
                    setColor(colorSamsungOrange)
                    cornerRadius = dpF(10f)
                }
            }
            topLeftBadges.addView(
                newBadge,
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) }
            )
        }

        val badgeLp = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = dp(20)
            topMargin = dp(18)
        }
        cardFrame.addView(topLeftBadges, badgeLp)

        return cardFrame
    }

    private fun showStoriesMorePopover() {
        val items = listOf(
            MenuOption("إنشاء قصة") { showCreateStoryDialog() },
            MenuOption("تحديث القصص التلقائية") {
                rebuildDynamicStoriesFromRealMedia()
                renderActiveScreen()
            },
            MenuOption("بدء عرض الشرائح") {
                startSlideshow(getVisibleMediaItems())
            }
        )
        showTopLeftFloatingMenu(items)
    }

    // ============================================================
    // 4. النافذة السفلية المنبثقة (☰ Hamburger Menu)
    // ============================================================
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
                            for (itItem in items) {
                                permanentlyDeleteItem(itItem.id)
                            }
                            savePersistedState()
                            openRecycleBinSubPage()
                            Toast.makeText(this@MainActivity, "تم إفراغ سلة المحذوفات نهائياً", Toast.LENGTH_SHORT).show()
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
                savePersistedState()
                openRecycleBinSubPage()
                Toast.makeText(this, "تم الحذف نهائياً", Toast.LENGTH_SHORT).show()
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
    private fun showFullScreenPhotoViewer(
        initialMedia: MediaItem,
        sourceList: List<MediaItem> = getVisibleMediaItems()
    ) {
        val list = sourceList.ifEmpty { listOf(initialMedia) }
        var currentIndex = list.indexOfFirst { it.id == initialMedia.id }.coerceAtLeast(0)
        var barsVisible = true

        overlayLayer.removeAllViews()
        overlayLayer.visibility = View.VISIBLE

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            isClickable = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val centerContainer = FrameLayout(this)
        root.addView(centerContainer, FrameLayout.LayoutParams(-1, -1))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(14), dp(12))
            setBackgroundColor(0xBB000000.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val nameTv = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subTv = TextView(this).apply {
            textSize = 11.5f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.RIGHT
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(0xDD000000.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        lateinit var bindCurrentMediaToViewer: () -> Unit

        fun toggleBarsVisibility() {
            barsVisible = !barsVisible
            val vis = if (barsVisible) View.VISIBLE else View.GONE
            topBar.visibility = vis
            bottomBar.visibility = vis
        }

        val zoomView = TouchZoomableImageView(
            context = this,
            initialMedia = list[currentIndex],
            onSingleTap = { toggleBarsVisibility() },
            onSwipeNext = {
                if (currentIndex < list.size - 1) {
                    currentIndex++
                    bindCurrentMediaToViewer()
                    centerContainer.translationX = dpF(36f)
                    centerContainer.alpha = 0.7f
                    centerContainer.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(130L)
                        .setInterpolator(smoothInterpolator)
                        .start()
                }
            },
            onSwipePrev = {
                if (currentIndex > 0) {
                    currentIndex--
                    bindCurrentMediaToViewer()
                    centerContainer.translationX = -dpF(36f)
                    centerContainer.alpha = 0.7f
                    centerContainer.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(130L)
                        .setInterpolator(smoothInterpolator)
                        .start()
                }
            },
            onSwipeUpDetails = { showMediaDetailsSheet(list[currentIndex]) }
        )

        val videoContainer = FrameLayout(this).apply {
            visibility = View.GONE
        }

        val playVideoOverlayBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xBB000000.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            visibility = View.GONE
        }
        val playIcon = SamsungIconView(this, SamsungIconView.IconType.VIDEO_PLAY, Color.WHITE, 2.2f)
        playVideoOverlayBtn.addView(
            playIcon,
            FrameLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.CENTER }
        )

        centerContainer.addView(zoomView, FrameLayout.LayoutParams(-1, -1))
        centerContainer.addView(videoContainer, FrameLayout.LayoutParams(-1, -1))
        centerContainer.addView(
            playVideoOverlayBtn,
            FrameLayout.LayoutParams(dp(74), dp(74)).apply { gravity = Gravity.CENTER }
        )

        // أزرار الشريط العلوي
        val moreViewerBtn = makeTopIconButton(SamsungIconView.IconType.MORE_VERT, Color.WHITE) {
            showViewerMoreMenu(list[currentIndex]) { bindCurrentMediaToViewer() }
        }
        topBar.addView(moreViewerBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        val infoBtn = TextView(this).apply {
            text = "ⓘ"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { showMediaDetailsSheet(list[currentIndex]) }
        }
        topBar.addView(infoBtn)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.RIGHT
        }
        titleCol.addView(nameTv)
        titleCol.addView(subTv)
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) })

        val closeBtn = TextView(this).apply {
            text = "›"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(6), 0)
            setOnClickListener {
                videoContainer.removeAllViews()
                closeOverlay()
                updateSelectionTopBarAndListInPlace()
            }
        }
        topBar.addView(closeBtn)

        root.addView(
            topBar,
            FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP }
        )

        // أزرار الشريط السفلي
        val delBtn = makeViewerActionItem("حذف", SamsungIconView.IconType.TRASH, Color.WHITE) {
            val curr = list[currentIndex]
            moveItemToTrash(curr.id)
            savePersistedState()
            Toast.makeText(
                this@MainActivity,
                "تم النقل إلى سلة المحذوفات (تبقى لمدة ٣٠ يوماً)",
                Toast.LENGTH_SHORT
            ).show()
            videoContainer.removeAllViews()
            closeOverlay()
            renderActiveScreen()
        }
        bottomBar.addView(delBtn, LinearLayout.LayoutParams(0, -2, 1f))

        val shareBtn = makeViewerActionItem("مشاركة", SamsungIconView.IconType.SHARE, Color.WHITE) {
            shareMediaItem(list[currentIndex])
        }
        bottomBar.addView(shareBtn, LinearLayout.LayoutParams(0, -2, 1f))

        val editBtn = makeViewerActionItem("تعديل", SamsungIconView.IconType.EDIT_PEN, Color.WHITE) {
            val curr = list[currentIndex]
            if (curr.isVideo) {
                Toast.makeText(this@MainActivity, "محرر الصور مخصص للصور الثابتة", Toast.LENGTH_SHORT).show()
            } else {
                showSamsungProPhotoEditor(curr)
            }
        }
        bottomBar.addView(editBtn, LinearLayout.LayoutParams(0, -2, 1f))

        val favBtnContainer = FrameLayout(this)
        bottomBar.addView(favBtnContainer, LinearLayout.LayoutParams(0, -2, 1f))

        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(-1, dp(64)).apply { gravity = Gravity.BOTTOM }
        )

        bindCurrentMediaToViewer = {
            val current = list[currentIndex]
            videoContainer.removeAllViews()
            videoContainer.visibility = View.GONE
            zoomView.visibility = View.VISIBLE
            zoomView.setMediaItem(current)

            nameTv.text = current.title
            subTv.text = "${current.fullDateText} • ${toArabicDigits(currentIndex + 1)}/${toArabicDigits(list.size)}"

            if (current.isVideo) {
                playVideoOverlayBtn.visibility = View.VISIBLE
                animateFadeScaleIn(playVideoOverlayBtn, 140L)
                playVideoOverlayBtn.setOnClickListener {
                    playVideoOverlayBtn.visibility = View.GONE
                    mountCustomProVideoPlayer(
                        videoContainer = videoContainer,
                        media = current,
                        onToggleTopBar = { toggleBarsVisibility() }
                    )
                }
            } else {
                playVideoOverlayBtn.visibility = View.GONE
            }

            favBtnContainer.removeAllViews()
            val isFav = favoriteIds.contains(current.id)
            val favIcon = if (isFav) SamsungIconView.IconType.HEART_FILLED else SamsungIconView.IconType.HEART
            val favColor = if (isFav) 0xFFFF3B30.toInt() else Color.WHITE
            val favBtn = makeViewerActionItem("المفضلة", favIcon, favColor) {
                if (isFav) favoriteIds.remove(current.id) else favoriteIds.add(current.id)
                savePersistedState()
                bindCurrentMediaToViewer()
            }
            favBtnContainer.addView(favBtn, FrameLayout.LayoutParams(-1, -2))
        }

        bindCurrentMediaToViewer()
        overlayLayer.addView(root, FrameLayout.LayoutParams(-1, -1))
    }

    // ============================================================
    // مشغل الفيديو الاحترافي المدمج (Samsung One UI Pro Video Player)
    // يدعم: تقديم وتأخير، قفز ±10 ثوانٍ بالنقر المزدوج أو الأزرار، سرعة التشغيل (0.5x..2.0x)،
    // كتم/تشغيل الصوت، تكرار الفيديو، والتقاط صورة فورية من الفيديو وحفظها!
    // ============================================================
    private fun mountCustomProVideoPlayer(
        videoContainer: FrameLayout,
        media: MediaItem,
        onToggleTopBar: () -> Unit
    ) {
        videoContainer.removeAllViews()
        videoContainer.visibility = View.VISIBLE
        videoContainer.setBackgroundColor(Color.BLACK)

        var mediaPlayerRef: MediaPlayer? = null
        var isMuted = false
        var isLoopingVideo = true
        var currentSpeed = 1.0f
        var controlsVisible = true
        var isUserSeeking = false

        val videoSurfaceWrap = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val videoView = VideoView(this).apply {
            setVideoURI(media.contentUri)
        }
        val vLp = FrameLayout.LayoutParams(-1, -1).apply {
            gravity = Gravity.CENTER
        }
        videoSurfaceWrap.addView(videoView, vLp)
        videoContainer.addView(videoSurfaceWrap, FrameLayout.LayoutParams(-1, -1))

        // شارة القفز السريع (±10 ثوانٍ) عند النقر المزدوج على يمين أو يسار الفيديو
        val skipFeedbackBadge = TextView(this).apply {
            text = ""
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                setColor(0xBB0A78D6.toInt())
                cornerRadius = dpF(22f)
            }
            alpha = 0f
        }
        videoContainer.addView(
            skipFeedbackBadge,
            FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER }
        )

        fun flashSkipBadge(text: String) {
            skipFeedbackBadge.text = text
            skipFeedbackBadge.alpha = 1f
            skipFeedbackBadge.scaleX = 0.85f
            skipFeedbackBadge.scaleY = 0.85f
            skipFeedbackBadge.animate()
                .alpha(0f)
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(520L)
                .start()
        }

        // لوحة تحكم الفيديو العائمة بتصميم One UI
        val controlsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xD8141418.toInt())
                cornerRadius = dpF(24f)
                setStroke(dp(1), 0x44FFFFFF)
            }
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // الصف الأول في لوحة التحكم: الوقت الحالي | شريط التمرير SeekBar | المدة الكلية
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val currentTimeTv = TextView(this).apply {
            text = "٠:٠٠"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val totalTimeTv = TextView(this).apply {
            text = media.durationText.ifEmpty { "٠:٠٠" }
            textSize = 11.5f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        }
        val videoSeekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = videoView.duration.coerceAtLeast(1)
                        val targetMs = (dur * progress) / 1000
                        currentTimeTv.text = formatVideoTimeArabic(targetMs)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val dur = videoView.duration.coerceAtLeast(1)
                    val targetMs = (dur * (sb?.progress ?: 0)) / 1000
                    videoView.seekTo(targetMs)
                    isUserSeeking = false
                }
            })
        }
        seekRow.addView(currentTimeTv, LinearLayout.LayoutParams(dp(44), -2))
        seekRow.addView(videoSeekBar, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(totalTimeTv, LinearLayout.LayoutParams(dp(44), -2))
        controlsCard.addView(seekRow, LinearLayout.LayoutParams(-1, -2))

        // الصف الثاني: أزرار التحكم (كتم الصوت | -10ث | تشغيل/إيقاف | +10ث | السرعة | لقطة شاشة)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val muteBtn = makeVideoPillButton("🔊") {}
        muteBtn.setOnClickListener {
            isMuted = !isMuted
            try {
                val vol = if (isMuted) 0f else 1f
                mediaPlayerRef?.setVolume(vol, vol)
            } catch (_: Exception) {}
            muteBtn.text = if (isMuted) "🔇" else "🔊"
        }

        val rwBtn = makeVideoPillButton("⏪ ١٠ث") {
            val nextPos = (videoView.currentPosition - 10_000).coerceAtLeast(0)
            videoView.seekTo(nextPos)
            flashSkipBadge("⏪ -١٠ ثوانٍ")
        }

        val playPauseBtn = TextView(this).apply {
            text = "⏸"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorSamsungBlue)
            }
            setOnClickListener {
                if (videoView.isPlaying) {
                    videoView.pause()
                    text = "▶"
                } else {
                    videoView.start()
                    text = "⏸"
                }
            }
        }

        val ffBtn = makeVideoPillButton("١٠ث ⏩") {
            val dur = videoView.duration.coerceAtLeast(1)
            val nextPos = (videoView.currentPosition + 10_000).coerceAtMost(dur)
            videoView.seekTo(nextPos)
            flashSkipBadge("+١٠ ثوانٍ ⏩")
        }

        val speedBtn = makeVideoPillButton("1.0x") {}
        speedBtn.setOnClickListener {
            currentSpeed = when (currentSpeed) {
                1.0f -> 1.5f
                1.5f -> 2.0f
                2.0f -> 0.5f
                else -> 1.0f
            }
            speedBtn.text = "${currentSpeed}x"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val mp = mediaPlayerRef
                    if (mp != null) {
                        mp.playbackParams = (mp.playbackParams ?: PlaybackParams()).setSpeed(currentSpeed)
                    }
                } catch (_: Exception) {}
            }
        }

        val captureBtn = makeVideoPillButton("📸 لقطة") {
            val posUs = videoView.currentPosition.toLong() * 1000L
            viewerExecutor.execute {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, media.contentUri)
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

        btnRow.addView(muteBtn, LinearLayout.LayoutParams(0, dp(36), 0.9f).apply { rightMargin = dp(4) })
        btnRow.addView(rwBtn, LinearLayout.LayoutParams(0, dp(36), 1.1f).apply { rightMargin = dp(4) })
        btnRow.addView(playPauseBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(4), 0, dp(4), 0) })
        btnRow.addView(ffBtn, LinearLayout.LayoutParams(0, dp(36), 1.1f).apply { leftMargin = dp(4) })
        btnRow.addView(speedBtn, LinearLayout.LayoutParams(0, dp(36), 0.9f).apply { leftMargin = dp(4) })
        btnRow.addView(captureBtn, LinearLayout.LayoutParams(0, dp(36), 1.2f).apply { leftMargin = dp(4) })

        controlsCard.addView(btnRow, LinearLayout.LayoutParams(-1, -2))

        val cardLp = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(74)
        }
        videoContainer.addView(controlsCard, cardLp)
        animateSlideUpIn(controlsCard, distanceDp = 40, durationMs = 160L)

        // إيماءات اللمس على شاشة الفيديو: نقر فردي لإخفاء/إظهار التحكم، نقر مزدوج يمين/يسار للقفز ±10 ثوانٍ
        val videoGesture = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    controlsVisible = !controlsVisible
                    controlsCard.visibility = if (controlsVisible) View.VISIBLE else View.GONE
                    onToggleTopBar()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val w = videoSurfaceWrap.width.coerceAtLeast(1)
                    if (e.x < w * 0.45f) {
                        val nextPos = (videoView.currentPosition - 10_000).coerceAtLeast(0)
                        videoView.seekTo(nextPos)
                        flashSkipBadge("⏪ -١٠ ثوانٍ")
                    } else if (e.x > w * 0.55f) {
                        val dur = videoView.duration.coerceAtLeast(1)
                        val nextPos = (videoView.currentPosition + 10_000).coerceAtMost(dur)
                        videoView.seekTo(nextPos)
                        flashSkipBadge("+١٠ ثوانٍ ⏩")
                    } else {
                        if (videoView.isPlaying) {
                            videoView.pause()
                            playPauseBtn.text = "▶"
                        } else {
                            videoView.start()
                            playPauseBtn.text = "⏸"
                        }
                    }
                    return true
                }
            }
        )
        videoSurfaceWrap.setOnTouchListener { _, ev ->
            videoGesture.onTouchEvent(ev)
            true
        }

        // تحديث شريط التقدم كل 250ms بدون أي حمل على المعالج
        val progressUpdater = object : Runnable {
            override fun run() {
                if (videoContainer.visibility != View.VISIBLE || !videoContainer.isAttachedToWindow) return
                try {
                    if (videoView.isPlaying && !isUserSeeking) {
                        val pos = videoView.currentPosition
                        val dur = videoView.duration.coerceAtLeast(1)
                        currentTimeTv.text = formatVideoTimeArabic(pos)
                        totalTimeTv.text = formatVideoTimeArabic(dur)
                        videoSeekBar.progress = ((pos.toLong() * 1000L) / dur).toInt().coerceIn(0, 1000)
                    }
                } catch (_: Exception) {}
                handler.postDelayed(this, 250L)
            }
        }

        videoView.setOnPreparedListener { mp ->
            mediaPlayerRef = mp
            mp.isLooping = isLoopingVideo
            totalTimeTv.text = formatVideoTimeArabic(videoView.duration)
            videoView.start()
            playPauseBtn.text = "⏸"
            handler.post(progressUpdater)
        }
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
            text = "استديو سامسونج (One UI Gallery)"
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
        val options = arrayOf(
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
                    0 -> showMediaDetailsSheet(media)
                    1 -> showRenameMediaDialog(media, onRefresh)
                    2 -> {
                        selectedMediaIds.clear()
                        selectedMediaIds.add(media.id)
                        showAddSelectedToAlbumDialog()
                    }
                    3 -> setAsDeviceWallpaper(media)
                    4 -> startSlideshow(getVisibleMediaItems())
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
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    media.title = newName
                    customNamesMap[media.id] = newName
                    savePersistedState()
                    onRenamed()
                    updateSelectionTopBarAndListInPlace()
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
            startActivity(Intent.createChooser(intent, "تعيين كخلفية"))
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
        // يسار الشاشة: [ ⋮ ] [ حفظ ] [ إرجاع ]  <--->  يمين الشاشة: الدائرتان [ ↶ ] [ ↷ ]
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
            setOnClickListener { shareMediaItem(media) }
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
                            val rNames = arrayOf("حر (Free)", "1:1 مربع", "4:3", "3:4", "16:9", "تطبيق القص الآن ✂")
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
                        text = "↺"
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        setPadding(dp(14), 0, dp(14), 0)
                        setOnClickListener { editorCanvasView.rotate90() }
                    }
                    val flipBtn = TextView(this).apply {
                        text = "‹|›"
                        textSize = 13f
                        setTextColor(Color.WHITE)
                        setPadding(dp(10), 0, dp(10), 0)
                        setOnClickListener { editorCanvasView.flipHorizontal() }
                    }
                    miniPill.addView(ratioBtn)
                    miniPill.addView(rotBtn)
                    miniPill.addView(flipBtn)
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
                    // التبويب 4: البطاقات الثلاث المتقدمة (تحديد حر | لون البقعة | مزيج الألوان) كما في 1000036301.png
                    // ====================================================
                    val cardsRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                    }

                    val advancedCards = listOf(
                        BottomAction("مزيج الألوان", SamsungIconView.IconType.PALETTE_MIX) {
                            editorCanvasView.warmthLevel = if (editorCanvasView.warmthLevel == 0) 35 else 0
                            editorCanvasView.saturationLevel = 25
                            editorCanvasView.invalidate()
                            Toast.makeText(this@MainActivity, "تم تطبيق مزيج الألوان الاحترافي", Toast.LENGTH_SHORT).show()
                        },
                        BottomAction("لون البقعة", SamsungIconView.IconType.EYEDROPPER) {
                            editorCanvasView.presetFilterCode = if (editorCanvasView.presetFilterCode == 4) 0 else 4
                            editorCanvasView.invalidate()
                            Toast.makeText(this@MainActivity, "تم تفعيل عزل الألوان (Spot Color)", Toast.LENGTH_SHORT).show()
                        },
                        BottomAction("تحديد حر", SamsungIconView.IconType.LASSO_SCISSORS) {
                            editorCanvasView.vignetteActive = !editorCanvasView.vignetteActive
                            editorCanvasView.invalidate()
                            Toast.makeText(this@MainActivity, "تم تطبيق التركيز البؤري الحر", Toast.LENGTH_SHORT).show()
                        }
                    )

                    for ((cLabel, cIcon, cAction) in advancedCards) {
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(dp(12), dp(16), dp(12), dp(16))
                            isClickable = true
                            background = GradientDrawable().apply {
                                setColor(0xFF1A1A1E.toInt())
                                cornerRadius = dpF(20f)
                            }
                            setOnClickListener { cAction() }
                        }
                        val ic = SamsungIconView(this, cIcon, Color.WHITE, 1.9f)
                        card.addView(ic, LinearLayout.LayoutParams(dp(28), dp(28)))
                        val tv = TextView(this).apply {
                            text = cLabel
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                        }
                        card.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
                        cardsRow.addView(
                            card,
                            LinearLayout.LayoutParams(dp(96), dp(104)).apply { setMargins(dp(6), 0, dp(6), 0) }
                        )
                    }
                    dynamicToolArea.addView(cardsRow, LinearLayout.LayoutParams(-1, -2))
                }
            }

            // رسم شريط الأيقونات الخمسة السفلي (من اليسار لليمين كما في الصور المرفقة):
            // [ ⊞ (4) ]  [ 😊✎ (3) ]  [ ☀ (2) ]  [ Circles (1) ]  [ Crop (0) ]
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
        val stickers = arrayOf("🌸", "💙", "✨", "🔥", "👑", "🎉", "⭐", "💎", "🎵", "📸")
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
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val rawBmp = contentResolver.openInputStream(media.contentUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val rotationDeg = resolveRotationDegrees(media)
            if (rotationDeg != 0) {
                val m = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
                if (rotated != rawBmp) rawBmp.recycle()
                rotated
            } else {
                rawBmp
            }
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
            val fileName = "SamsungGallery_Edit_$ts.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/SamsungGallery"
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
        val themeLabel = when (themeMode) {
            1 -> "المظهر: داكن دائماً"
            2 -> "المظهر: فاتح دائماً"
            else -> "المظهر: تلقائي حسب الجهاز (${if (isDarkModeActive()) "داكن حالياً" else "فاتح حالياً"})"
        }
        val lockStatusLabel = if (appLockEnabled && appLockPin.isNotEmpty()) {
            "قفل الاستديو: مفعّل (كلمة سر + بصمة)"
        } else {
            "قفل الاستديو: غير مفعّل (اضغط للتفعيل)"
        }

        val items = arrayOf(
            themeLabel,
            lockStatusLabel,
            "قفل الاستديو الآن 🔒",
            "سلة المحذوفات (حفظ لمدة ٣٠ يوماً • ${toArabicDigits(getTrashedMediaItems().size)} عنصر)",
            "إعادة إظهار بطاقة تخصيص الألبومات",
            "تغيير عدد أعمدة الصور (حالياً: ${toArabicDigits(picturesColumnCount)} أعمدة)",
            "إظهار كل الألبومات المخفية",
            "إعادة فحص وتحديث صور الهاتف"
        )
        AlertDialog.Builder(this)
            .setTitle("ضبط الاستديو (Samsung One UI)")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showThemeModeSelectorDialog()
                    1 -> showAppLockSettingsDialog()
                    2 -> {
                        if (appLockEnabled && appLockPin.isNotEmpty()) {
                            lockGalleryNow(autoPromptBiometric = true)
                        } else {
                            showSetPasswordDialog(lockImmediatelyAfter = true)
                        }
                    }
                    3 -> openRecycleBinSubPage()
                    4 -> {
                        showCustomizeAlbumsBanner = true
                        savePersistedState()
                        renderActiveScreen()
                    }
                    5 -> {
                        picturesColumnCount = when (picturesColumnCount) {
                            3 -> 4
                            4 -> 5
                            5 -> 2
                            else -> 3
                        }
                        savePersistedState()
                        renderActiveScreen()
                    }
                    6 -> {
                        hiddenAlbumNames.clear()
                        nonEssentialAlbumNames.clear()
                        savePersistedState()
                        renderActiveScreen()
                    }
                    7 -> {
                        requestMediaPermissionsIfNeeded()
                        Toast.makeText(this, "جاري تحديث صور وفيديوهات الهاتف...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setPositiveButton("إغلاق", null)
            .show()
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
            "قفل الاستديو الآن 🔒",
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
                    p1 != p2 -> {
                        Toast.makeText(this, "كلمتا السر غير متطابقتين", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        appLockPin = p1
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
        val targetLen = appLockPin.length.coerceAtLeast(4)

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
            if (enteredPin.length < 12) {
                enteredPin += digit
                refreshDotsUI(false)
                if (enteredPin == appLockPin) {
                    unlockGalleryNow()
                    return
                }
                if (appLockPin.isNotEmpty() && enteredPin.length >= appLockPin.length) {
                    refreshDotsUI(true)
                    statusTv.text = "رمز الحماية غير صحيح، حاول مرة أخرى"
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
                            text = "⌫"
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
            text = "إدخال كلمة سر بالحروف ⌨"
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
                        if (input.text.toString().trim() == appLockPin) {
                            unlockGalleryNow()
                        } else {
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

        try {
            audioPlayer?.stop()
            audioPlayer?.release()
        } catch (_: Exception) {}

        try {
            val mp = MediaPlayer().apply {
                setDataSource(this@MainActivity, track.contentUri)
                prepare()
                isLooping = isAudioRepeatOne
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioSpeed != 1.0f) {
                    try {
                        playbackParams = (playbackParams ?: PlaybackParams()).setSpeed(audioSpeed)
                    } catch (_: Exception) {}
                }
                setOnCompletionListener {
                    if (!isAudioRepeatOne) {
                        val nextIdx = if (isAudioShuffle) {
                            (Math.random() * deviceAudioTracks.size).toInt()
                        } else {
                            currentAudioIndex + 1
                        }
                        playAudioTrackAtIndex(nextIdx)
                    }
                }
                start()
            }
            audioPlayer = mp
            isAudioPlaying = true
            updateMiniAudioPlayerBar()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر تشغيل المقطع الصوتي", Toast.LENGTH_SHORT).show()
        }
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

        val nextBtn = TextView(this).apply {
            text = "⏮"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { playAudioTrackAtIndex(currentAudioIndex + 1) }
        }
        miniAudioPlayerBar.addView(nextBtn)

        val playPauseMini = TextView(this).apply {
            text = if (audioPlayer?.isPlaying == true) "⏸" else "▶"
            textSize = 17f
            setTextColor(colorBrandIceBlue)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                val mp = audioPlayer ?: return@setOnClickListener
                if (mp.isPlaying) {
                    mp.pause()
                    isAudioPlaying = false
                } else {
                    mp.start()
                    isAudioPlaying = true
                }
                updateMiniAudioPlayerBar()
            }
        }
        miniAudioPlayerBar.addView(playPauseMini)

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

        val playMainBtn = TextView(this).apply {
            text = if (audioPlayer?.isPlaying == true) "⏸" else "▶"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
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
            playMainBtn.text = if (audioPlayer?.isPlaying == true) "⏸" else "▶"
            visualizerView.isAnimatingWaves = (audioPlayer?.isPlaying == true)
            visualizerView.invalidate()
        }

        val repeatBtn = makeVideoPillButton(if (isAudioRepeatOne) "🔂 واحد" else "🔁 الكل") {}
        repeatBtn.setOnClickListener {
            isAudioRepeatOne = !isAudioRepeatOne
            audioPlayer?.isLooping = isAudioRepeatOne
            repeatBtn.text = if (isAudioRepeatOne) "🔂 واحد" else "🔁 الكل"
        }

        val prevTrackBtn = makeVideoPillButton("⏮") {
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

        val nextTrackBtn = makeVideoPillButton("⏭") {
            if (deviceAudioTracks.isNotEmpty()) {
                playAudioTrackAtIndex(currentAudioIndex + 1)
                refreshAudioDeckUI()
            }
        }

        val shuffleBtn = makeVideoPillButton(if (isAudioShuffle) "🔀 مفعّل" else "🔀") {}
        shuffleBtn.setOnClickListener {
            isAudioShuffle = !isAudioShuffle
            shuffleBtn.text = if (isAudioShuffle) "🔀 مفعّل" else "🔀"
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
                startActivity(Intent.createChooser(intent, "مشاركة عبر"))
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
            startActivity(Intent.createChooser(intent, "مشاركة عبر"))
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
        val bucketSize = when {
            cellPx >= 700 -> 1024
            cellPx >= 420 -> 640
            else -> 480
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
    private inner class TouchZoomableImageView(
        context: Context,
        initialMedia: MediaItem,
        private val onSingleTap: () -> Unit,
        private val onSwipeNext: () -> Unit,
        private val onSwipePrev: () -> Unit,
        private val onSwipeUpDetails: () -> Unit
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val matrixDraw = Matrix()
        private var currentMedia: MediaItem = initialMedia
        private var highResBmp: Bitmap? = null

        private var scaleFactor = 1f
        private var transX = 0f
        private var transY = 0f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var activePointerId = MotionEvent.INVALID_POINTER_ID

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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
                    onSingleTap()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (scaleFactor > 1.05f) {
                        scaleFactor = 1f
                        transX = 0f
                        transY = 0f
                    } else {
                        scaleFactor = 2.8f
                        transX = (width * 0.5f - e.x) * 1.5f
                        transY = (height * 0.5f - e.y) * 1.5f
                    }
                    invalidate()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null || scaleFactor > 1.15f) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) > abs(dy) && abs(dx) > dp(55) && abs(velocityX) > 200f) {
                        if (dx < 0) {
                            onSwipeNext()
                        } else {
                            onSwipePrev()
                        }
                        return true
                    } else if (dy < -dp(80) && abs(dy) > abs(dx)) {
                        onSwipeUpDetails()
                        return true
                    }
                    return false
                }
            }
        )

        init {
            setMediaItem(initialMedia)
        }

        fun setMediaItem(newMedia: MediaItem) {
            currentMedia = newMedia
            scaleFactor = 1f
            transX = 0f
            transY = 0f
            val fullCacheKey = "${newMedia.id}_full2560"
            val cachedFull = bitmapCache.get(fullCacheKey)
            if (cachedFull != null && !cachedFull.isRecycled) {
                highResBmp = cachedFull
                invalidate()
            } else {
                highResBmp = bitmapCache.get("${newMedia.id}_640")
                    ?: bitmapCache.get("${newMedia.id}_480")
                invalidate()
                val targetId = newMedia.id
                // فك الصورة بدقة الشاشة الكاملة على المحرك الفوري المستقل (viewerExecutor) بدون انتظار الشبكة!
                viewerExecutor.execute {
                    val decoded = decodeFullResolutionWithExif(newMedia, 2560)
                    if (decoded != null) {
                        bitmapCache.put(fullCacheKey, decoded)
                        if (currentMedia.id == targetId) {
                            highResBmp = decoded
                            postInvalidate()
                        }
                    }
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val bmp = highResBmp ?: requestThumbnailAsync(currentMedia, 900, this)
            if (bmp == null || bmp.isRecycled) return

            val bw = bmp.width.toFloat().coerceAtLeast(1f)
            val bh = bmp.height.toFloat().coerceAtLeast(1f)
            val baseScale = min(w / bw, h / bh)

            matrixDraw.reset()
            matrixDraw.postTranslate(-bw * 0.5f, -bh * 0.5f)
            matrixDraw.postScale(baseScale * scaleFactor, baseScale * scaleFactor)
            matrixDraw.postTranslate(w * 0.5f + transX, h * 0.5f + transY)

            canvas.drawBitmap(bmp, matrixDraw, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    activePointerId = event.getPointerId(0)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && scaleFactor > 1.02f) {
                        val idx = event.findPointerIndex(activePointerId)
                        if (idx >= 0) {
                            val x = event.getX(idx)
                            val y = event.getY(idx)
                            transX += (x - lastTouchX)
                            transY += (y - lastTouchY)
                            val maxPanX = width * (scaleFactor - 1f) * 0.55f
                            val maxPanY = height * (scaleFactor - 1f) * 0.55f
                            transX = transX.coerceIn(-maxPanX, maxPanX)
                            transY = transY.coerceIn(-maxPanY, maxPanY)
                            lastTouchX = x
                            lastTouchY = y
                            invalidate()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
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
            PALETTE_MIX
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
            }
        }
    }
}