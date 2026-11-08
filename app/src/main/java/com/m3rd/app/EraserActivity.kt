package com.m3rd.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * شاشة المسح الذكي للأجسام بالذكاء الاصطناعي العصبي بهوية Mango Gallery (Samsung One UI Edition).
 * - مصممة بالكامل بأيقونات المتجهات المرسومة بدقة بدون أي إيموجيات.
 * - تعمل محلياً بالكامل على معالج الهاتف (NPU / NNAPI / Multi-Core CPU) بدون إنترنت.
 * - تدعم الفرشاة الذكية، التحديد الحر المغناطيسي (Lasso)، وعدسة التكبير الدقيقة أثناء اللمس.
 */
class EraserActivity : Activity() {

    companion object {
        const val EXTRA_URI = "eraser_uri"
    }

    private val colorPureBlack = 0xFF000000.toInt()
    private val colorCardDark = 0xFF1C1C1E.toInt()
    private val colorBorderSubtle = 0xFF2C2C32.toInt()
    private val accent = 0xFF0A78D6.toInt()
    private val accentLight = 0xFF8CD0FA.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var cancelFlag = false
    private var processing = false
    private var erasedOnce = false
    private var qualityMode = 0 // 0 = تلقائي حسب المعالج، 1 = سريع للأجهزة الضعيفة، 2 = فائق الدقة

    private lateinit var canvasView: EraseCanvasView
    private lateinit var overlay: FrameLayout
    private lateinit var progressTv: TextView
    private lateinit var progressSubTv: TextView
    private lateinit var saveBtn: TextView
    private lateinit var chipBadgeTv: TextView
    private var sourceUri: Uri? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = r * resources.displayMetrics.density
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun pill(t: String, filled: Boolean, primaryColor: Int = accent, onClick: () -> Unit): TextView = TextView(this).apply {
        text = t
        textSize = 13.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = if (filled) round(primaryColor, 18f) else round(colorCardDark, 18f, colorBorderSubtle)
        setPadding(dp(14), dp(11), dp(14), dp(11))
        setOnClickListener { onClick() }
    }

    private fun makeCircleVectorBtn(iconType: MainActivity.SamsungIconView.IconType, onClick: () -> Unit): View {
        return FrameLayout(this).apply {
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorCardDark)
                setStroke(dp(1), colorBorderSubtle)
            }
            addView(
                MainActivity.SamsungIconView(this@EraserActivity, iconType, Color.WHITE, 1.9f),
                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            )
            setOnClickListener { onClick() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = colorPureBlack
        window.navigationBarColor = colorPureBlack
        val uri = intent?.getParcelableExtra<Uri>(EXTRA_URI)
        if (uri == null) {
            finish()
            return
        }
        sourceUri = uri

        val hwProfile = ObjectEraser.detectHardwareProfile(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(colorPureBlack)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // الشريط العلوي الاحترافي بدون إيموجي
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(32), dp(12), dp(10))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        saveBtn = pill("حفظ", true, accent) { if (!processing) saveResult() }
        saveBtn.alpha = 0.4f
        top.addView(saveBtn)

        top.addView(
            makeCircleVectorBtn(MainActivity.SamsungIconView.IconType.UNDO_CURVE) { if (!processing) canvasView.undoStroke() },
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(8) }
        )
        top.addView(
            makeCircleVectorBtn(MainActivity.SamsungIconView.IconType.REDO_CURVE) { if (!processing) canvasView.redoStroke() },
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(6) }
        )

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.RIGHT
            setPadding(dp(10), 0, dp(10), 0)
        }
        titleCol.addView(TextView(this).apply {
            text = "ممحاة العناصر بالذكاء الاصطناعي"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
        })
        chipBadgeTv = TextView(this).apply {
            text = "${hwProfile.acceleratorName} • ${hwProfile.displayTitleAr}"
            textSize = 11f
            setTextColor(accentLight)
            gravity = Gravity.RIGHT
            setOnClickListener { showHardwareEngineDialog(hwProfile) }
        }
        titleCol.addView(chipBadgeTv)
        top.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        top.addView(
            makeCircleVectorBtn(MainActivity.SamsungIconView.IconType.CHEVRON_RIGHT) { if (!processing) finish() },
            LinearLayout.LayoutParams(dp(38), dp(38))
        )
        page.addView(top, LinearLayout.LayoutParams(-1, -2))

        // لوحة الرسم والمعاينة
        canvasView = EraseCanvasView(this)
        page.addView(canvasView, LinearLayout.LayoutParams(-1, 0, 1f))

        // الشريط السفلي لأدوات الذكاء الاصطناعي
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colorCardDark)
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1), colorBorderSubtle)
            }
            setPadding(dp(16), dp(14), dp(16), dp(18))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        lateinit var brushModeBtn: TextView
        lateinit var lassoModeBtn: TextView
        lateinit var engineModeBtn: TextView

        fun refreshModeButtons() {
            val isLasso = canvasView.lassoMode
            brushModeBtn.background = if (!isLasso) round(accent, 14f) else round(colorPureBlack, 14f, colorBorderSubtle)
            lassoModeBtn.background = if (isLasso) round(accent, 14f) else round(colorPureBlack, 14f, colorBorderSubtle)
            engineModeBtn.text = when (qualityMode) {
                1 -> "سرعة قصوى"
                2 -> "دقة فائقة"
                else -> "تلقائي ذكي"
            }
        }

        brushModeBtn = TextView(this).apply {
            text = "فرشاة ذكية"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                canvasView.lassoMode = false
                refreshModeButtons()
            }
        }
        lassoModeBtn = TextView(this).apply {
            text = "تحديد حر (Lasso)"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                canvasView.lassoMode = true
                refreshModeButtons()
            }
        }
        engineModeBtn = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentLight)
            gravity = Gravity.CENTER
            background = round(colorPureBlack, 14f, colorBorderSubtle)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                qualityMode = (qualityMode + 1) % 3
                refreshModeButtons()
            }
        }
        refreshModeButtons()

        modeRow.addView(brushModeBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        modeRow.addView(lassoModeBtn, LinearLayout.LayoutParams(0, -2, 1.15f).apply { rightMargin = dp(6) })
        modeRow.addView(engineModeBtn, LinearLayout.LayoutParams(0, -2, 0.95f))
        bottom.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // مسطرة حجم الفرشاة
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val sizeLabel = TextView(this).apply {
            text = "حجم الفرشاة: ٣٢"
            textSize = 12.5f
            setTextColor(0xFFB0B6C6.toInt())
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = 40
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val b = 8f + p * 0.6f
                    canvasView.brushDp = b
                    sizeLabel.text = "حجم الفرشاة: ${b.toInt()}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        canvasView.brushDp = 8f + 40 * 0.6f
        sizeRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) })
        sizeRow.addView(sizeLabel)
        bottom.addView(sizeRow, LinearLayout.LayoutParams(-1, -2))

        // أزرار التنفيذ والمقارنة
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        row.addView(
            pill("مسح التحديد", false) { if (!processing) canvasView.clearStrokes() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) }
        )
        val compare = pill("الأصل / النتيجة", false) {}
        compare.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> canvasView.showPrevious(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> canvasView.showPrevious(false)
            }
            true
        }
        row.addView(compare, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5); rightMargin = dp(5) })
        row.addView(
            pill("إزالة العنصر الآن", true, accent) { if (!processing) runErase() },
            LinearLayout.LayoutParams(0, -2, 1.35f).apply { leftMargin = dp(5) }
        )
        bottom.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        page.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        overlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = round(colorCardDark, 22f, colorBorderSubtle)
            setPadding(dp(28), dp(24), dp(28), dp(20))
        }
        progressTv = TextView(this).apply {
            text = "جاري المسح العصبي... 0%"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        progressSubTv = TextView(this).apply {
            text = "توليد الخلفية محلياً عبر ${hwProfile.acceleratorName}"
            textSize = 12.5f
            setTextColor(accentLight)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(progressTv)
        box.addView(progressSubTv)
        box.addView(
            pill("إلغاء", false) { cancelFlag = true },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        )
        overlay.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        loadImage(uri)
    }

    private fun showHardwareEngineDialog(profile: ObjectEraser.HardwareProfile) {
        val msg = "المعالج المكتشف: ${profile.chipSummary}\n" +
            "المسرّع النشط: ${profile.acceleratorName}\n" +
            "نمط التكيف التلقائي: ${profile.displayTitleAr}\n" +
            "دقة نافذة التوليد العصبي: ${profile.workDim}px\n\n" +
            "يعمل محرك Mango Neural AI محلياً 100% على جهازك بدون إرسال صورك لأي خادم خارجي، ويضبط عدد التمريرات الهرمية تلقائياً حسب قوة هاتفك."
        AlertDialog.Builder(this)
            .setTitle("معلومات المحرك العصبي المحلي")
            .setMessage(msg)
            .setPositiveButton("حسناً", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelFlag = true
        io.shutdownNow()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (processing) cancelFlag = true else super.onBackPressed()
    }

    private fun loadImage(uri: Uri) {
        val tier = ObjectEraser.detectHardwareProfile(this)
        io.execute {
            val bmp = decodeScaled(this, uri, tier.maxImageDim)
            handler.post {
                if (isFinishing) return@post
                if (bmp == null) {
                    Toast.makeText(this, "تعذر فتح الصورة", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    canvasView.setBase(bmp)
                }
            }
        }
    }

    private fun decodeScaled(ctx: Context, uri: Uri, maxDim: Int): Bitmap? {
        try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
            if (b.outWidth <= 0) return null
            var sample = 1
            while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val o = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
            val deg = try {
                ctx.contentResolver.openInputStream(uri)?.use { s ->
                    when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            } catch (_: Throwable) { 0 }
            val big = max(bmp.width, bmp.height)
            val f = if (big > maxDim) maxDim.toFloat() / big else 1f
            if (deg != 0 || f < 1f) {
                val m = Matrix()
                if (f < 1f) m.postScale(f, f)
                if (deg != 0) m.postRotate(deg.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            return bmp
        } catch (t: Throwable) {
            return null
        }
    }

    private fun runErase() {
        val base = canvasView.currentBitmap() ?: return
        val mask = canvasView.buildMask()
        if (mask == null) {
            Toast.makeText(this, "حدد الجسم المراد مسحه أولاً بالفرشاة أو التحديد الحر", Toast.LENGTH_SHORT).show()
            return
        }
        processing = true
        cancelFlag = false
        progressTv.text = "جاري المسح العصبي... 0%"
        overlay.visibility = View.VISIBLE
        val t0 = System.currentTimeMillis()
        io.execute {
            var result: Bitmap? = null
            var failed = false
            try {
                result = ObjectEraser.erase(
                    this, base, mask,
                    { p ->
                        handler.post {
                            progressTv.text = "جاري المسح العصبي... $p%"
                            progressSubTv.text = when {
                                p < 25 -> "تحليل حدود الجسم والظلال المحيطة..."
                                p < 50 -> "بناء الهيكل الهرمي للخلفية (Multi-Scale Pyramid)..."
                                p < 90 -> "توليد ومطابقة الأنسجة بالذكاء الاصطناعي..."
                                else -> "دمج الحواف بسلاسة (Feather & Bilateral Blend)..."
                            }
                        }
                    },
                    { cancelFlag },
                    qualityMode = qualityMode
                )
            } catch (oom: OutOfMemoryError) {
                failed = true
            } catch (t: Throwable) {
                failed = true
            }
            val elapsedMs = System.currentTimeMillis() - t0
            handler.post {
                processing = false
                overlay.visibility = View.GONE
                if (isFinishing) return@post
                val r = result
                if (r != null) {
                    canvasView.applyResult(r)
                    erasedOnce = true
                    saveBtn.alpha = 1f
                    Toast.makeText(
                        this,
                        "تمت إزالة العنصر بنجاح في ${String.format(Locale.US, "%.1f", elapsedMs / 1000f)} ثانية",
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (failed) {
                    Toast.makeText(this, "الذاكرة غير كافية، جرّب وضع السرعة القصوى أو تحديد أصغر", Toast.LENGTH_LONG).show()
                } else if (cancelFlag) {
                    Toast.makeText(this, "تم الإلغاء", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveResult() {
        if (!erasedOnce) {
            Toast.makeText(this, "نفّذ الإزالة أولاً قبل الحفظ", Toast.LENGTH_SHORT).show()
            return
        }
        val bmp = canvasView.currentBitmap() ?: return
        processing = true
        io.execute {
            var ok = false
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "MangoGallery_AI_Erase_$ts.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mango Gallery")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        ok = bmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
                    }
                }
            } catch (t: Throwable) {
                ok = false
            }
            handler.post {
                processing = false
                if (ok) {
                    Toast.makeText(this, "تم حفظ الصورة في ألبوم Mango Gallery", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "تعذر حفظ الصورة", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** لوحة الرسم الذكية: تدعم الفرشاة + التحديد الحر المغناطيسي (Lasso) + عدسة التكبير العلوية (Magnifier Loupe) */
class EraseCanvasView(context: Context) : View(context) {

    var brushDp = 30f
    var lassoMode = false

    private var base: Bitmap? = null
    private var previous: Bitmap? = null
    private var maskBmp: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var showPrev = false

    private class Stroke(val path: Path, val widthPx: Float, val isLassoFill: Boolean)
    private val strokes = ArrayList<Stroke>()
    private val redoStack = ArrayList<Stroke>()
    private var current: Stroke? = null

    private var touchActive = false
    private var lastTouchViewX = 0f
    private var lastTouchViewY = 0f
    private var lastTouchBmpX = 0f
    private var lastTouchBmpY = 0f

    private val matrixM = Matrix()
    private val inv = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xB30A78D6.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xB30A78D6.toInt()
    }
    private val loupeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = 0xFF8CD0FA.toInt()
    }
    private val loupeCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0xFF0A78D6.toInt()
    }

    private var fitted = false
    private var lastFx = 0f
    private var lastFy = 0f
    private var zooming = false
    private val pt = FloatArray(2)
    private val loupeClipPath = Path()
    private val loupeRect = RectF()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val cur = currentScale()
            var f = d.scaleFactor
            val target = cur * f
            if (target < minScale()) f = minScale() / cur
            if (target > minScale() * 10f) f = minScale() * 10f / cur
            matrixM.postScale(f, f, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrixM.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun minScale(): Float {
        val b = base ?: return 1f
        return min(width.toFloat() / b.width, height.toFloat() / b.height) * 0.9f
    }

    fun setBase(bmp: Bitmap) {
        base = bmp
        previous = null
        strokes.clear()
        redoStack.clear()
        rebuildMask()
        fitted = false
        fit()
        invalidate()
    }

    fun currentBitmap(): Bitmap? = base

    fun applyResult(result: Bitmap) {
        previous = base
        base = result
        strokes.clear()
        redoStack.clear()
        rebuildMask()
        invalidate()
    }

    fun showPrevious(show: Boolean) {
        showPrev = show && previous != null
        invalidate()
    }

    fun undoStroke() {
        if (strokes.isNotEmpty()) {
            val removed = strokes.removeAt(strokes.size - 1)
            redoStack.add(removed)
            rebuildMask()
            invalidate()
        }
    }

    fun redoStroke() {
        if (redoStack.isNotEmpty()) {
            val restored = redoStack.removeAt(redoStack.size - 1)
            strokes.add(restored)
            rebuildMask()
            invalidate()
        }
    }

    fun clearStrokes() {
        strokes.clear()
        redoStack.clear()
        rebuildMask()
        invalidate()
    }

    fun buildMask(): BooleanArray? {
        val m = maskBmp ?: return null
        if (strokes.isEmpty()) return null
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        m.getPixels(px, 0, w, 0, 0, w, h)
        val out = BooleanArray(w * h)
        var any = false
        for (i in px.indices) {
            if ((px[i] ushr 24) > 35) {
                out[i] = true
                any = true
            }
        }
        return if (any) out else null
    }

    private fun rebuildMask() {
        val b = base ?: return
        if (maskBmp == null || maskBmp!!.width != b.width || maskBmp!!.height != b.height) {
            maskBmp?.recycle()
            maskBmp = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            maskCanvas = Canvas(maskBmp!!)
        }
        maskBmp!!.eraseColor(Color.TRANSPARENT)
        for (s in strokes) drawStroke(s)
    }

    private fun drawStroke(s: Stroke) {
        val c = maskCanvas ?: return
        if (s.isLassoFill) {
            fillPaint.strokeWidth = s.widthPx * 0.4f
            c.drawPath(s.path, fillPaint)
        } else {
            strokePaint.strokeWidth = s.widthPx
            c.drawPath(s.path, strokePaint)
        }
    }

    private fun fit() {
        val b = base ?: return
        if (width == 0 || height == 0) return
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        matrixM.reset()
        matrixM.postScale(s, s)
        matrixM.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        fitted = true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!fitted) fit()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = (if (showPrev) previous else base) ?: return
        canvas.save()
        canvas.concat(matrixM)
        canvas.drawBitmap(b, 0f, 0f, bitmapPaint)
        if (!showPrev) maskBmp?.let { canvas.drawBitmap(it, 0f, 0f, maskPaint) }
        canvas.restore()

        if (touchActive && !zooming && !showPrev) {
            val loupeRadius = 56f * resources.displayMetrics.density
            val margin = 16f * resources.displayMetrics.density
            val lx = if (lastTouchViewX < width * 0.45f) width - loupeRadius - margin else loupeRadius + margin
            val ly = loupeRadius + margin
            loupeRect.set(lx - loupeRadius, ly - loupeRadius, lx + loupeRadius, ly + loupeRadius)

            canvas.save()
            loupeClipPath.reset()
            loupeClipPath.addOval(loupeRect, Path.Direction.CW)
            canvas.clipPath(loupeClipPath)
            canvas.drawColor(0xFF000000.toInt())

            val zoom = currentScale() * 2.2f
            canvas.save()
            canvas.translate(lx, ly)
            canvas.scale(zoom, zoom)
            canvas.translate(-lastTouchBmpX, -lastTouchBmpY)
            canvas.drawBitmap(b, 0f, 0f, bitmapPaint)
            maskBmp?.let { canvas.drawBitmap(it, 0f, 0f, maskPaint) }
            canvas.restore()
            canvas.restore()

            canvas.drawOval(loupeRect, loupeBorderPaint)
            val ch = 10f * resources.displayMetrics.density
            canvas.drawLine(lx - ch, ly, lx + ch, ly, loupeCrossPaint)
            canvas.drawLine(lx, ly - ch, lx, ly + ch, loupeCrossPaint)
        }
    }

    private fun toBitmapCoords(x: Float, y: Float): FloatArray {
        matrixM.invert(inv)
        pt[0] = x
        pt[1] = y
        inv.mapPoints(pt)
        return pt
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (base == null) return true
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zooming = false
                touchActive = true
                lastTouchViewX = ev.x
                lastTouchViewY = ev.y
                val p = toBitmapCoords(ev.x, ev.y)
                lastTouchBmpX = p[0]
                lastTouchBmpY = p[1]
                val wpx = brushDp * resources.displayMetrics.density / currentScale()
                val path = Path()
                path.moveTo(p[0], p[1])
                path.lineTo(p[0] + 0.01f, p[1])
                val s = Stroke(path, wpx, lassoMode)
                current = s
                strokes.add(s)
                redoStack.clear()
                drawStroke(s)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                current?.let {
                    strokes.remove(it)
                    rebuildMask()
                }
                current = null
                touchActive = false
                zooming = true
                lastFx = (ev.getX(0) + ev.getX(1)) / 2f
                lastFy = (ev.getY(0) + ev.getY(1)) / 2f
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2 && zooming) {
                    val fx = (ev.getX(0) + ev.getX(1)) / 2f
                    val fy = (ev.getY(0) + ev.getY(1)) / 2f
                    matrixM.postTranslate(fx - lastFx, fy - lastFy)
                    lastFx = fx
                    lastFy = fy
                    invalidate()
                } else if (!zooming) {
                    val s = current
                    if (s != null) {
                        lastTouchViewX = ev.x
                        lastTouchViewY = ev.y
                        val p = toBitmapCoords(ev.x, ev.y)
                        lastTouchBmpX = p[0]
                        lastTouchBmpY = p[1]
                        s.path.lineTo(p[0], p[1])
                        drawStroke(s)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount <= 2) {
                    val idx = if (ev.actionIndex == 0) 1 else 0
                    lastFx = ev.getX(idx)
                    lastFy = ev.getY(idx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = current
                if (s != null) {
                    if (s.isLassoFill) {
                        s.path.close()
                    }
                    rebuildMask()
                }
                current = null
                touchActive = false
                zooming = false
                invalidate()
            }
        }
        return true
    }
}
