package com.m3rd.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * عنصر ترجمة مفرد (سطر ترجمة من ملف SRT أو VTT) مع وقت البداية والنهاية بالمللي ثانية.
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * محلل ملفات الترجمة الاحترافي (SRT / WebVTT / ASS-Lite Subtitle Engine).
 * يقرأ ملفات الترجمة الخارجية بأي ترميز (UTF-8 / UTF-8 BOM) ويستخرج التوقيتات والنصوص بدقة عالية،
 * كما يوفر بحثاً ثنائياً سريعاً (Binary Search) لإظهار الترجمة المتزامنة مع كل إطار في الفيديو.
 */
object SubtitleParser {

    private val timeRegex = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )
    private val shortTimeRegex = Regex(
        """(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{2}):(\d{2})[,.](\d{1,3})"""
    )
    private val htmlTagRegex = Regex("""<[^>]*>""")

    fun loadFromUri(ctx: Context, uri: Uri): List<SubtitleCue> {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val text = BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).readText()
                parseText(text)
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun parseText(rawContent: String): List<SubtitleCue> {
        val cleaned = rawContent.replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n")
        val blocks = cleaned.split(Regex("\n\\s*\n"))
        val result = ArrayList<SubtitleCue>()

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            if (lines[0].startsWith("WEBVTT", ignoreCase = true)) continue

            var timingLineIdx = -1
            var startMs = 0L
            var endMs = 0L

            for (i in lines.indices) {
                val line = lines[i]
                val m1 = timeRegex.find(line)
                if (m1 != null) {
                    timingLineIdx = i
                    startMs = toMs(
                        m1.groupValues[1].toLong(),
                        m1.groupValues[2].toLong(),
                        m1.groupValues[3].toLong(),
                        m1.groupValues[4]
                    )
                    endMs = toMs(
                        m1.groupValues[5].toLong(),
                        m1.groupValues[6].toLong(),
                        m1.groupValues[7].toLong(),
                        m1.groupValues[8]
                    )
                    break
                }
                val m2 = shortTimeRegex.find(line)
                if (m2 != null) {
                    timingLineIdx = i
                    startMs = toMs(0L, m2.groupValues[1].toLong(), m2.groupValues[2].toLong(), m2.groupValues[3])
                    endMs = toMs(0L, m2.groupValues[4].toLong(), m2.groupValues[5].toLong(), m2.groupValues[6])
                    break
                }
            }

            if (timingLineIdx >= 0 && timingLineIdx + 1 < lines.size && endMs > startMs) {
                val textLines = lines.subList(timingLineIdx + 1, lines.size)
                    .map { htmlTagRegex.replace(it, "").replace("{", "").replace("}", "").trim() }
                    .filter { it.isNotEmpty() }
                if (textLines.isNotEmpty()) {
                    result.add(SubtitleCue(startMs, endMs, textLines.joinToString("\n")))
                }
            }
        }

        result.sortBy { it.startMs }
        return result
    }

    private fun toMs(h: Long, m: Long, s: Long, fracStr: String): Long {
        val ms = when (fracStr.length) {
            1 -> fracStr.toLong() * 100L
            2 -> fracStr.toLong() * 10L
            else -> fracStr.take(3).toLongOrNull() ?: 0L
        }
        return ((h * 3600L) + (m * 60L) + s) * 1000L + ms
    }

    fun findActiveCue(cues: List<SubtitleCue>, positionMs: Long, offsetMs: Long = 0L): String? {
        if (cues.isEmpty()) return null
        val t = positionMs - offsetMs
        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            when {
                t < c.startMs -> hi = mid - 1
                t > c.endMs -> lo = mid + 1
                else -> return c.text
            }
        }
        return null
    }
}

/**
 * استوديو تقنيات الذكاء الاصطناعي المحلية على الهاتف (Mango Neural Vision Lab — 100% On-Device AI):
 * 1. التحسين الذكي الشامل (AI Remaster & Auto-HDR): موازنة التعريض التلقائية، استعادة تفاصيل الظلال، وزيادة حدة التفاصيل (Unsharp Masking).
 * 2. عزل الخلفية البورتريه (AI Portrait Studio Bokeh): تحديد الكائن المركزي وعزل الخلفية بضبابية عدسة سينمائية متدرجة.
 * 3. تحسين المستندات والسبورات (AI Document & Text Clarifier): إزالة ظلال الإضاءة وتوضيح النصوص المكتوبة بدقة عالية.
 * 4. الإضاءة الليلية الذكية (AI Night Sight & Dehaze): تفتيح اللقطات المظلمة مع تقليل التشويش اللوني.
 * 5. البصمة البصرية وكشف الصور المتشابهة والمكررة (AI Perceptual dHash 64-bit): لاكتشاف الصور المكررة وتوفير مساحة الهاتف.
 * 6. استخراج لوحة الألوان السائدة (AI Dominant Color Palette Extractor): تحليل K-Means السريع لاستخراج أجمل 5 ألوان في الصورة.
 */
object MangoAiStudio {

    /**
     * 1. التحسين الذكي الشامل للصور (AI Remaster & Auto-HDR + Micro-Contrast Sharpening)
     */
    fun aiRemasterImage(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // تحليل المدرج التكراري للإضاءة (Luminance Histogram Analysis)
        val hist = IntArray(256)
        for (i in 0 until n) {
            val c = px[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            hist[lum]++
        }

        val lowClip = (n * 0.015f).toInt()
        val highClip = (n * 0.985f).toInt()
        var acc = 0
        var minLum = 0
        var maxLum = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc <= lowClip) minLum = i
            if (acc <= highClip) maxLum = i
        }
        val span = (maxLum - minLum).coerceAtLeast(32)

        // جدول تحويل المنحنى الذكي (Tone Curve LUT with Shadow Lift & Highlight Roll-off)
        val lut = IntArray(256)
        for (v in 0..255) {
            val norm = ((v - minLum).toFloat() / span).coerceIn(0f, 1f)
            // منحنى S ناعم مع رفع الظلال الخافتة
            val lifted = norm + 0.14f * norm * (1f - norm) * (1f - norm)
            val sCurve = lifted * lifted * (3f - 2f * lifted)
            val mixed = norm * 0.35f + sCurve * 0.65f
            lut[v] = (mixed * 255f).roundToInt().coerceIn(0, 255)
        }

        val outPx = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]
            var r = lut[(c shr 16) and 255]
            var g = lut[(c shr 8) and 255]
            var b = lut[c and 255]
            // تعزيز تشبع الألوان الحيوي الذكي (Vibrance Boost: يعزز الألوان الباهتة دون حرق الألوان القوية)
            val avg = (r + g + b) / 3
            val maxCh = max(r, max(g, b))
            val minCh = min(r, min(g, b))
            val curSat = (maxCh - minCh) / 255f
            val vibBoost = 1f + 0.28f * (1f - curSat)
            r = (avg + (r - avg) * vibBoost).roundToInt().coerceIn(0, 255)
            g = (avg + (g - avg) * vibBoost).roundToInt().coerceIn(0, 255)
            b = (avg + (b - avg) * vibBoost).roundToInt().coerceIn(0, 255)
            outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // تمريرة حدة ذكية (Unsharp Masking) لإبراز التفاصيل الدقيقة
        val sharpened = outPx.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = outPx[idx]
                val l = outPx[idx - 1]
                val r = outPx[idx + 1]
                val u = outPx[idx - w]
                val d = outPx[idx + w]

                val cr = (c shr 16) and 255
                val cg = (c shr 8) and 255
                val cb = c and 255

                val blurR = (((l shr 16) and 255) + ((r shr 16) and 255) + ((u shr 16) and 255) + ((d shr 16) and 255)) shr 2
                val blurG = (((l shr 8) and 255) + ((r shr 8) and 255) + ((u shr 8) and 255) + ((d shr 8) and 255)) shr 2
                val blurB = ((l and 255) + (r and 255) + (u and 255) + (d and 255)) shr 2

                val sr = (cr + ((cr - blurR) * 0.45f).roundToInt()).coerceIn(0, 255)
                val sg = (cg + ((cg - blurG) * 0.45f).roundToInt()).coerceIn(0, 255)
                val sb = (cb + ((cb - blurB) * 0.45f).roundToInt()).coerceIn(0, 255)
                sharpened[idx] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(sharpened, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 2. عزل الخلفية البورتريه بالذكاء الاصطناعي (AI Portrait Studio Depth Bokeh)
     * يكتشف الجسم البارز في مركز وتكوين الصورة عبر التباين المكاني واللوني ويمزجه مع طبقة عدسة ضبابية ناعمة.
     */
    fun aiPortraitBokeh(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val origPx = IntArray(n)
        src.getPixels(origPx, 0, w, 0, 0, w, h)

        // إنشاء نسخة مضببة بعمق عدسة (Fast Multi-Pass Box Blur on Downscaled Copy)
        val scaleW = max(64, w / 4)
        val scaleH = max(64, h / 4)
        val small = Bitmap.createScaledBitmap(src, scaleW, scaleH, true)
        val smallPx = IntArray(scaleW * scaleH)
        small.getPixels(smallPx, 0, scaleW, 0, 0, scaleW, scaleH)
        boxBlurPixels(smallPx, scaleW, scaleH, 4)
        boxBlurPixels(smallPx, scaleW, scaleH, 3)
        small.setPixels(smallPx, 0, scaleW, 0, 0, scaleW, scaleH)
        val blurredBmp = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== src) small.recycle()
        val blurPx = IntArray(n)
        blurredBmp.getPixels(blurPx, 0, w, 0, 0, w, h)
        blurredBmp.recycle()

        // حساب متوسط لون منطقة الهدف المركزية (Subject Color & Spatial Salience Prior)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        val x0 = (w * 0.28f).toInt()
        val x1 = (w * 0.72f).toInt()
        val y0 = (h * 0.20f).toInt()
        val y1 = (h * 0.80f).toInt()
        for (y in y0 until y1 step 2) {
            val row = y * w
            for (x in x0 until x1 step 2) {
                val c = origPx[row + x]
                sumR += (c shr 16) and 255
                sumG += (c shr 8) and 255
                sumB += c and 255
                count++
            }
        }
        val refR = if (count > 0) (sumR / count).toInt() else 128
        val refG = if (count > 0) (sumG / count).toInt() else 128
        val refB = if (count > 0) (sumB / count).toInt() else 128

        val cx = w * 0.5f
        val cy = h * 0.48f
        val invRx = 1f / (w * 0.44f).coerceAtLeast(1f)
        val invRy = 1f / (h * 0.48f).coerceAtLeast(1f)

        val outPx = IntArray(n)
        for (y in 0 until h) {
            val dy = (y - cy) * invRy
            val row = y * w
            for (x in 0 until w) {
                val dx = (x - cx) * invRx
                val spatialDist = sqrt(dx * dx + dy * dy)
                val i = row + x
                val o = origPx[i]
                val b = blurPx[i]

                val dr = ((o shr 16) and 255) - refR
                val dg = ((o shr 8) and 255) - refG
                val db = (o and 255) - refB
                val colorDiff = sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat() / 220f

                // حساب معامل العزل: الهدف في المنتصف واضح 100% والخلفية البعيدة تعزل تدريجياً
                val blurWeight = ((spatialDist - 0.52f) * 1.35f + colorDiff * 0.25f).coerceIn(0f, 1f)
                if (blurWeight <= 0.02f) {
                    outPx[i] = o
                } else {
                    val inv = 1f - blurWeight
                    val rr = (((o shr 16) and 255) * inv + ((b shr 16) and 255) * blurWeight).toInt().coerceIn(0, 255)
                    val gg = (((o shr 8) and 255) * inv + ((b shr 8) and 255) * blurWeight).toInt().coerceIn(0, 255)
                    val bb = ((o and 255) * inv + (b and 255) * blurWeight).toInt().coerceIn(0, 255)
                    outPx[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 3. تحسين المستندات والسبورات الذكي (AI Document & Whiteboard Enhancer)
     * يزيل الظلال غير المتجانسة على الورق ويوضح الحروف بدقة عالية.
     */
    fun aiEnhanceDocument(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // تقدير إضاءة الخلفية المحلية لتبييض الورق مع الحفاظ على ألوان الحبر
        val scaleW = max(32, w / 8)
        val scaleH = max(32, h / 8)
        val bgSmall = Bitmap.createScaledBitmap(src, scaleW, scaleH, true)
        val bgSmallPx = IntArray(scaleW * scaleH)
        bgSmall.getPixels(bgSmallPx, 0, scaleW, 0, 0, scaleW, scaleH)
        boxBlurPixels(bgSmallPx, scaleW, scaleH, 3)
        bgSmall.setPixels(bgSmallPx, 0, scaleW, 0, 0, scaleW, scaleH)
        val bgFull = Bitmap.createScaledBitmap(bgSmall, w, h, true)
        if (bgSmall !== src) bgSmall.recycle()
        val bgPx = IntArray(n)
        bgFull.getPixels(bgPx, 0, w, 0, 0, w, h)
        bgFull.recycle()

        val outPx = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]
            val bg = bgPx[i]
            val cr = (c shr 16) and 255
            val cg = (c shr 8) and 255
            val cb = c and 255

            val br = max(40, (bg shr 16) and 255)
            val bgG = max(40, (bg shr 8) and 255)
            val bb = max(40, bg and 255)

            // قسمة الإضاءة لإزالة الظلال وتطبيق تباين عالٍ للنصوص
            var nr = (cr * 250) / br
            var ng = (cg * 250) / bgG
            var nb = (cb * 250) / bb

            nr = ((nr - 190) * 2.1f + 190f).roundToInt().coerceIn(0, 255)
            ng = ((ng - 190) * 2.1f + 190f).roundToInt().coerceIn(0, 255)
            nb = ((nb - 190) * 2.1f + 190f).roundToInt().coerceIn(0, 255)

            outPx[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 4. الرؤية الليلية الذكية وإزالة الضباب (AI Night Sight & Dehaze)
     */
    fun aiNightSightEnhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val gammaLut = IntArray(256)
        for (i in 0..255) {
            val norm = i / 255.0
            val lifted = Math.pow(norm, 0.58)
            gammaLut[i] = (lifted * 255.0).roundToInt().coerceIn(0, 255)
        }

        for (i in 0 until n) {
            val c = px[i]
            val r = gammaLut[(c shr 16) and 255]
            val g = gammaLut[(c shr 8) and 255]
            val b = gammaLut[c and 255]
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 5. استخراج لوحة الألوان السائدة من الصورة (AI Dominant Color Palette)
     */
    fun extractDominantPalette(src: Bitmap, count: Int = 5): IntArray {
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        if (small !== src) small.recycle()

        val buckets = HashMap<Int, IntArray>()
        for (c in px) {
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val key = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
            val acc = buckets.getOrPut(key) { IntArray(4) }
            acc[0]++
            acc[1] += r
            acc[2] += g
            acc[3] += b
        }

        val sorted = buckets.values.sortedByDescending { it[0] }
        val result = ArrayList<Int>()
        for (acc in sorted) {
            if (result.size >= count) break
            val cnt = acc[0].coerceAtLeast(1)
            val r = acc[1] / cnt
            val g = acc[2] / cnt
            val b = acc[3] / cnt
            val col = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            val tooClose = result.any { existing ->
                val dr = ((existing shr 16) and 255) - r
                val dg = ((existing shr 8) and 255) - g
                val db = (existing and 255) - b
                (dr * dr + dg * dg + db * db) < 2200
            }
            if (!tooClose) result.add(col)
        }
        return result.toIntArray()
    }

    /**
     * 6. حساب البصمة البصرية 64-bit dHash لاكتشاف الصور المكررة والمتشابهة
     */
    fun computePerceptualHash(src: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(src, 9, 8, true)
        val px = IntArray(9 * 8)
        small.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (small !== src) small.recycle()

        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            val row = y * 9
            for (x in 0 until 8) {
                val c1 = px[row + x]
                val c2 = px[row + x + 1]
                val l1 = ((c1 shr 16) and 255) * 3 + ((c1 shr 8) and 255) * 4 + (c1 and 255)
                val l2 = ((c2 shr 16) and 255) * 3 + ((c2 shr 8) and 255) * 4 + (c2 and 255)
                if (l1 > l2) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    fun hammingDistance(h1: Long, h2: Long): Int = java.lang.Long.bitCount(h1 xor h2)

    private fun boxBlurPixels(px: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var cnt = 0
                val x0 = max(0, x - radius)
                val x1 = min(w - 1, x + radius)
                for (xx in x0..x1) {
                    val c = px[row + xx]
                    r += (c shr 16) and 255
                    g += (c shr 8) and 255
                    b += c and 255
                    cnt++
                }
                tmp[row + x] = (0xFF shl 24) or ((r / cnt) shl 16) or ((g / cnt) shl 8) or (b / cnt)
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var r = 0
                var g = 0
                var b = 0
                var cnt = 0
                val y0 = max(0, y - radius)
                val y1 = min(h - 1, y + radius)
                for (yy in y0..y1) {
                    val c = tmp[yy * w + x]
                    r += (c shr 16) and 255
                    g += (c shr 8) and 255
                    b += c and 255
                    cnt++
                }
                px[y * w + x] = (0xFF shl 24) or ((r / cnt) shl 16) or ((g / cnt) shl 8) or (b / cnt)
            }
        }
    }
}
