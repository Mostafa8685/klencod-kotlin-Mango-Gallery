package com.m3rd.app

import android.app.Activity
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
 * شاشة "إزالة عنصر": ارسم بإصبعك فوق الجسم المراد إزالته (قرّب بإصبعين للدقة) ثم اضغط "إزالة".
 * المعالجة محلية بالكامل على المعالج وتتكيف مع قوة الجهاز (انظر ObjectEraser).
 */
class EraserActivity : Activity() {

    companion object {
        const val EXTRA_URI = "eraser_uri"
    }

    private val accent = 0xFFFF9800.toInt()
    private val bg = 0xFF0E0E10.toInt()
    private val card = 0xFF1C1C20.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var cancelFlag = false
    private var processing = false
    private var erasedOnce = false

    private lateinit var canvasView: EraseCanvasView
    private lateinit var overlay: FrameLayout
    private lateinit var progressTv: TextView
    private lateinit var saveBtn: TextView
    private var sourceUri: Uri? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = r * resources.displayMetrics.density
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun pill(t: String, filled: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = t
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (filled) Color.BLACK else Color.WHITE)
        background = if (filled) round(accent, 14f) else round(card, 14f, 0xFF2E2E34.toInt())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.getParcelableExtra<Uri>(EXTRA_URI)
        if (uri == null) { finish(); return }
        sourceUri = uri

        val root = FrameLayout(this)
        root.setBackgroundColor(bg)

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(34), dp(8), dp(8))
        }
        top.addView(iconBtn("✕") { if (!processing) finish() })
        top.addView(
            TextView(this).apply {
                text = "إزالة عنصر"
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) }
        )
        top.addView(iconBtn("↶") { if (!processing) canvasView.undoStroke() })
        saveBtn = pill("حفظ", true) { if (!processing) saveResult() }
        saveBtn.alpha = 0.4f
        top.addView(saveBtn)
        page.addView(top, LinearLayout.LayoutParams(-1, -2))

        canvasView = EraseCanvasView(this)
        page.addView(canvasView, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(card)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val sizeLabel = TextView(this).apply {
            text = "حجم الفرشاة"
            textSize = 13f
            setTextColor(0xFF98989E.toInt())
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = 40
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    canvasView.brushDp = 8f + p * 0.6f
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        canvasView.brushDp = 8f + 40 * 0.6f
        bottom.addView(sizeLabel)
        bottom.addView(seek, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            pill("مسح التحديد", false) { if (!processing) canvasView.clearStrokes() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) }
        )
        val compare = pill("قبل / بعد", false) {}
        compare.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> canvasView.showPrevious(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> canvasView.showPrevious(false)
            }
            true
        }
        row.addView(compare, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        row.addView(
            pill("إزالة ✨", true) { if (!processing) runErase() },
            LinearLayout.LayoutParams(0, -2, 1.3f).apply { leftMargin = dp(6) }
        )
        bottom.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        page.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        // طبقة التقدم
        overlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = round(card, 20f, 0xFF2E2E34.toInt())
            setPadding(dp(28), dp(24), dp(28), dp(20))
        }
        progressTv = TextView(this).apply {
            text = "جاري الإزالة... 0%"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        box.addView(progressTv)
        box.addView(
            pill("إلغاء", false) { cancelFlag = true },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        )
        overlay.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        loadImage(uri)
    }

    private fun iconBtn(g: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = g
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
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

    // ------------------------------------------------------------- تحميل
    private fun loadImage(uri: Uri) {
        val tier = ObjectEraser.tierFor(this)
        Toast.makeText(this, "ارسم على الجسم المراد إزالته", Toast.LENGTH_SHORT).show()
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

    // ------------------------------------------------------------- الإزالة
    private fun runErase() {
        val base = canvasView.currentBitmap() ?: return
        val mask = canvasView.buildMask()
        if (mask == null) {
            Toast.makeText(this, "ارسم على الجسم الأول", Toast.LENGTH_SHORT).show()
            return
        }
        processing = true
        cancelFlag = false
        progressTv.text = "جاري الإزالة... 0%"
        overlay.visibility = View.VISIBLE
        io.execute {
            var result: Bitmap? = null
            var failed = false
            try {
                result = ObjectEraser.erase(
                    this, base, mask,
                    { p -> handler.post { progressTv.text = "جاري الإزالة... $p%" } },
                    { cancelFlag }
                )
            } catch (oom: OutOfMemoryError) {
                failed = true
            } catch (t: Throwable) {
                failed = true
            }
            handler.post {
                processing = false
                overlay.visibility = View.GONE
                if (isFinishing) return@post
                val r = result
                if (r != null) {
                    canvasView.applyResult(r)
                    erasedOnce = true
                    saveBtn.alpha = 1f
                } else if (failed) {
                    Toast.makeText(this, "الجهاز مش قادر يعالج الصورة دي، جرّب تحديد أصغر", Toast.LENGTH_LONG).show()
                } else if (cancelFlag) {
                    Toast.makeText(this, "تم الإلغاء", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --------------------------------------------------------------- الحفظ
    private fun saveResult() {
        if (!erasedOnce) {
            Toast.makeText(this, "نفّذ الإزالة الأول", Toast.LENGTH_SHORT).show()
            return
        }
        val bmp = canvasView.currentBitmap() ?: return
        processing = true
        io.execute {
            var ok = false
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "MangoGallery_Erase_$ts.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mango Gallery")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        ok = bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                }
            } catch (t: Throwable) {
                ok = false
            }
            handler.post {
                processing = false
                if (ok) {
                    Toast.makeText(this, "تم حفظ نسخة جديدة في Mango Gallery", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "تعذر الحفظ", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** لوحة الرسم: صورة + قناع شبه شفاف، تكبير بإصبعين، رسم بإصبع واحد. */
class EraseCanvasView(context: Context) : View(context) {

    var brushDp = 30f

    private var base: Bitmap? = null
    private var previous: Bitmap? = null
    private var maskBmp: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var showPrev = false

    private class Stroke(val path: Path, val widthPx: Float)
    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null

    private val matrixM = Matrix()
    private val inv = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xB3FF9800.toInt()
    }
    private var fitted = false
    private var lastFx = 0f
    private var lastFy = 0f
    private var zooming = false
    private val pt = FloatArray(2)

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
        rebuildMask()
        invalidate()
    }

    fun showPrevious(show: Boolean) {
        showPrev = show && previous != null
        invalidate()
    }

    fun undoStroke() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            rebuildMask()
            invalidate()
        }
    }

    fun clearStrokes() {
        strokes.clear()
        rebuildMask()
        invalidate()
    }

    /** يرجع null لو لا يوجد رسم. */
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
            if ((px[i] ushr 24) > 40) { out[i] = true; any = true }
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
        strokePaint.strokeWidth = s.widthPx
        c.drawPath(s.path, strokePaint)
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
    }

    private fun toBitmapCoords(x: Float, y: Float): FloatArray {
        matrixM.invert(inv)
        pt[0] = x; pt[1] = y
        inv.mapPoints(pt)
        return pt
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (base == null) return true
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zooming = false
                val p = toBitmapCoords(ev.x, ev.y)
                val wpx = brushDp * resources.displayMetrics.density / currentScale()
                val path = Path()
                path.moveTo(p[0], p[1])
                path.lineTo(p[0] + 0.01f, p[1])
                val s = Stroke(path, wpx)
                current = s
                strokes.add(s)
                drawStroke(s)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // إصبعان: إلغاء الخط الجاري والتحول للتكبير/التحريك
                current?.let {
                    strokes.remove(it)
                    rebuildMask()
                }
                current = null
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
                    lastFx = fx; lastFy = fy
                    invalidate()
                } else if (!zooming) {
                    val s = current
                    if (s != null) {
                        val p = toBitmapCoords(ev.x, ev.y)
                        s.path.lineTo(p[0], p[1])
                        drawStroke(s)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount <= 2) {
                    // يبقى في وضع التكبير حتى يُرفع الإصبعان
                    val idx = if (ev.actionIndex == 0) 1 else 0
                    lastFx = ev.getX(idx); lastFy = ev.getY(idx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = current
                if (s != null) {
                    // إعادة رسم الخط كاملاً لضمان تغطية متصلة
                    rebuildMask()
                }
                current = null
                zooming = false
                invalidate()
            }
        }
        return true
    }
}
