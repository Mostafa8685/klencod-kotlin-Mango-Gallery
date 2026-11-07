package com.m3rd.app

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * إزالة الأجسام من الصور محلياً على المعالج (بدون إنترنت).
 *
 * الخوارزمية: تعبئة بالرقع (Patch-based / PatchMatch) على نسخة مصغّرة من منطقة العمل فقط،
 * ثم دمج ناعم مع الصورة الأصلية بدقتها الكاملة. الإعدادات تتغير حسب قوة الجهاز:
 * الأجهزة الضعيفة تستخدم دقة أقل وعدد محاولات أقل، والقوية تستخدم دقة أعلى وتمريرة تحسين.
 *
 * ملاحظة: هذا المحرك كلاسيكي (غير عصبي). يمكن توصيل نموذج عصبي (LaMa وغيره) عبر
 * [neural]: لو وُضع هناك مُنفّذ، يُجرَّب أولاً ويرجع للمحرك الكلاسيكي عند الفشل.
 */
object ObjectEraser {

    interface NeuralInpainter {
        /** يرجع صورة بنفس أبعاد [image] بعد ملء المناطق البيضاء في [mask] (ALPHA>0)، أو null للفشل. */
        fun inpaint(image: Bitmap, mask: Bitmap): Bitmap?
    }

    @Volatile
    var neural: NeuralInpainter? = null

    class Tier(val name: String, val workDim: Int, val samples: Int, val patchR: Int, val refine: Boolean, val maxImageDim: Int)

    fun tierFor(ctx: Context): Tier {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = am.memoryClass
        val cores = Runtime.getRuntime().availableProcessors()
        val low = am.isLowRamDevice || mem <= 128 || cores <= 4
        val high = !low && mem >= 256 && cores >= 8
        return when {
            low -> Tier("low", 320, 20, 2, false, 1600)
            high -> Tier("high", 768, 56, 3, true, 3072)
            else -> Tier("mid", 512, 36, 2, true, 2304)
        }
    }

    /**
     * @param src صورة ARGB_8888 (لا تُعدَّل)
     * @param mask مصفوفة بحجم src.width*src.height، true = المنطقة المراد إزالتها
     * @return صورة جديدة أو null عند الإلغاء/الفشل
     */
    fun erase(
        ctx: Context,
        src: Bitmap,
        mask: BooleanArray,
        progress: (Int) -> Unit,
        cancelled: () -> Boolean
    ): Bitmap? {
        val w = src.width
        val h = src.height
        if (mask.size != w * h) return null

        // 1) حدود القناع
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (mask[row + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null

        // 2) منطقة العمل = القناع + هامش سياق
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val margin = max(32, max(bw, bh))
        val cx0 = max(0, minX - margin)
        val cy0 = max(0, minY - margin)
        val cx1 = min(w - 1, maxX + margin)
        val cy1 = min(h - 1, maxY + margin)
        val cw = cx1 - cx0 + 1
        val ch = cy1 - cy0 + 1

        val tier = tierFor(ctx)

        // 3) محاولة النموذج العصبي (لو متوفر)
        val nn = neural
        if (nn != null) {
            try {
                val m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val mp = IntArray(w * h)
                for (i in mp.indices) if (mask[i]) mp[i] = -1
                m.setPixels(mp, 0, w, 0, 0, w, h)
                val r = nn.inpaint(src, m)
                m.recycle()
                if (r != null && r.width == w && r.height == h) return r
            } catch (_: Throwable) {
            }
        }

        // 4) القص والتصغير
        val cropPx = IntArray(cw * ch)
        src.getPixels(cropPx, 0, cw, cx0, cy0, cw, ch)
        val cropMask = BooleanArray(cw * ch)
        for (y in 0 until ch) {
            System.arraycopy(mask, (y + cy0) * w + cx0, cropMask, y * cw, cw)
        }

        val big = max(cw, ch)
        val scale = if (big > tier.workDim) tier.workDim.toFloat() / big else 1f
        val lw = max(8, (cw * scale).toInt())
        val lh = max(8, (ch * scale).toInt())

        val workPx: IntArray
        val workMask: BooleanArray
        if (scale < 1f) {
            val cb = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            cb.setPixels(cropPx, 0, cw, 0, 0, cw, ch)
            val sb = Bitmap.createScaledBitmap(cb, lw, lh, true)
            workPx = IntArray(lw * lh)
            sb.getPixels(workPx, 0, lw, 0, 0, lw, lh)
            if (sb !== cb) sb.recycle()
            cb.recycle()
            val lm = BooleanArray(lw * lh)
            for (y in 0 until lh) {
                val sy = min(ch - 1, (y / scale).toInt())
                for (x in 0 until lw) {
                    val sx = min(cw - 1, (x / scale).toInt())
                    lm[y * lw + x] = cropMask[sy * cw + sx]
                }
            }
            workMask = dilate(lm, lw, lh, 1)
        } else {
            workPx = cropPx.copyOf()
            workMask = dilate(cropMask, cw, ch, 1)
        }
        val ww = if (scale < 1f) lw else cw
        val wh = if (scale < 1f) lh else ch

        // 5) التعبئة
        val ok = inpaint(workPx, ww, wh, workMask, tier, progress, cancelled)
        if (!ok || cancelled()) return null

        // 6) رجوع للدقة الأصلية + دمج ناعم
        val filledFull: IntArray
        if (scale < 1f) {
            val fb = Bitmap.createBitmap(ww, wh, Bitmap.Config.ARGB_8888)
            fb.setPixels(workPx, 0, ww, 0, 0, ww, wh)
            val up = Bitmap.createScaledBitmap(fb, cw, ch, true)
            filledFull = IntArray(cw * ch)
            up.getPixels(filledFull, 0, cw, 0, 0, cw, ch)
            if (up !== fb) up.recycle()
            fb.recycle()
        } else {
            filledFull = workPx
        }

        val d = if (scale < 1f) max(2, (1f / scale).toInt() + 1) else 2
        val grown = dilate(cropMask, cw, ch, d)
        val alpha = FloatArray(cw * ch)
        for (i in alpha.indices) if (grown[i]) alpha[i] = 1f
        boxBlur(alpha, cw, ch, max(1, d))

        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val outRow = IntArray(cw)
        for (y in 0 until ch) {
            val base = y * cw
            for (x in 0 until cw) {
                val a = alpha[base + x]
                val o = cropPx[base + x]
                outRow[x] = if (a <= 0.001f) o else if (a >= 0.999f) filledFull[base + x] else mix(o, filledFull[base + x], a)
            }
            out.setPixels(outRow, 0, cw, cx0, cy0 + y, cw, 1)
        }
        progress(100)
        return out
    }

    // ----------------------------------------------------------------- أدوات

    private fun mix(a: Int, b: Int, t: Float): Int {
        val inv = 1f - t
        val r = (((a shr 16) and 255) * inv + ((b shr 16) and 255) * t).toInt()
        val g = (((a shr 8) and 255) * inv + ((b shr 8) and 255) * t).toInt()
        val bl = ((a and 255) * inv + (b and 255) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** تمديد القناع بمقدار d بكسل (نافذة مربعة) بزمن خطي. */
    private fun dilate(m: BooleanArray, w: Int, h: Int, d: Int): BooleanArray {
        if (d <= 0) return m.copyOf()
        val tmp = IntArray(w * h)
        val pre = IntArray(max(w, h) + 1)
        // أفقي
        for (y in 0 until h) {
            pre[0] = 0
            val row = y * w
            for (x in 0 until w) pre[x + 1] = pre[x] + (if (m[row + x]) 1 else 0)
            for (x in 0 until w) {
                val a = max(0, x - d)
                val b = min(w, x + d + 1)
                tmp[row + x] = pre[b] - pre[a]
            }
        }
        // رأسي
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            pre[0] = 0
            for (y in 0 until h) pre[y + 1] = pre[y] + (if (tmp[y * w + x] > 0) 1 else 0)
            for (y in 0 until h) {
                val a = max(0, y - d)
                val b = min(h, y + d + 1)
                out[y * w + x] = (pre[b] - pre[a]) > 0
            }
        }
        return out
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(w * h)
        val pre = FloatArray(max(w, h) + 1)
        for (y in 0 until h) {
            pre[0] = 0f
            val row = y * w
            for (x in 0 until w) pre[x + 1] = pre[x] + a[row + x]
            for (x in 0 until w) {
                val lo = max(0, x - r)
                val hi = min(w, x + r + 1)
                tmp[row + x] = (pre[hi] - pre[lo]) / (hi - lo)
            }
        }
        for (x in 0 until w) {
            pre[0] = 0f
            for (y in 0 until h) pre[y + 1] = pre[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val lo = max(0, y - r)
                val hi = min(h, y + r + 1)
                a[y * w + x] = (pre[hi] - pre[lo]) / (hi - lo)
            }
        }
    }

    private fun d2(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
        val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
        val db = (a and 255) - (b and 255)
        return dr * dr + dg * dg + db * db
    }

    // ------------------------------------------------------------ التعبئة بالرقع

    private fun inpaint(
        px: IntArray, w: Int, h: Int, mask: BooleanArray,
        tier: Tier, progress: (Int) -> Unit, cancelled: () -> Boolean
    ): Boolean {
        val r = tier.patchR
        val n = w * h
        // 0 = مجهول، 1 = معروف أصلي، 2 = مُعبّأ
        val state = ByteArray(n)
        var unknown = 0
        for (i in 0 until n) {
            if (mask[i]) unknown++ else state[i] = 1
        }
        if (unknown == 0) return true

        // مصادر صالحة: رقعة كاملة داخل الصورة وبدون أي بكسل من القناع
        val ii = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                rowSum += if (mask[y * w + x]) 1 else 0
                ii[(y + 1) * (w + 1) + (x + 1)] = ii[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        val valid = BooleanArray(n)
        var validCount = 0
        for (y in r until h - r) {
            for (x in r until w - r) {
                val x0 = x - r; val y0 = y - r; val x1 = x + r + 1; val y1 = y + r + 1
                val s = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
                if (s == 0) { valid[y * w + x] = true; validCount++ }
            }
        }

        if (validCount < 24) {
            diffusionFill(px, w, h, state)
            return !cancelled()
        }
        // قائمة المصادر (نأخذ عينة لو كبيرة جداً)
        val stride = max(1, validCount / 60000)
        val vlist = IntArray(validCount / stride + 1)
        var vn = 0
        var seen = 0
        for (i in 0 until n) {
            if (valid[i]) {
                if (seen % stride == 0 && vn < vlist.size) vlist[vn++] = i
                seen++
            }
        }

        val ox = IntArray(n)
        val oy = IntArray(n)
        var rng = 0x9E3779B9.toInt() xor (w * 31 + h)
        fun nextInt(bound: Int): Int {
            rng = rng xor (rng shl 13)
            rng = rng xor (rng ushr 17)
            rng = rng xor (rng shl 5)
            val v = rng and 0x7FFFFFFF
            return v % bound
        }

        val window = max(24, max(w, h) / 4)
        val half = max(4, tier.samples / 2)
        val maxWeight = 2 * (2 * r + 1) * (2 * r + 1)

        // يقيّم مرشحاً c (مركز المصدر) للهدف (x,y). pass2: كل البكسلات المعبّأة تُحسب بوزن كامل
        fun cost(x: Int, y: Int, cx: Int, cy: Int, limit: Int, pass2: Boolean): Int {
            var total = 0
            var cnt = 0
            val threshold = if (limit == Int.MAX_VALUE) Long.MAX_VALUE else limit.toLong() * maxWeight
            for (dy in -r..r) {
                val ty = y + dy
                if (ty < 0 || ty >= h) continue
                for (dx in -r..r) {
                    val tx = x + dx
                    if (tx < 0 || tx >= w) continue
                    val ti = ty * w + tx
                    val st = state[ti].toInt()
                    if (st == 0) continue
                    val wgt = if (st == 1 || pass2) 2 else 1
                    total += wgt * d2(px[ti], px[(cy + dy) * w + (cx + dx)])
                    cnt += wgt
                    if (total.toLong() > threshold) return Int.MAX_VALUE
                }
            }
            if (cnt == 0) return Int.MAX_VALUE
            return total / cnt
        }

        fun search(x: Int, y: Int, pass2: Boolean, hasCurrent: Boolean) {
            val idx = y * w + x
            var bestCx = 0
            var bestCy = 0
            var best = Int.MAX_VALUE
            if (hasCurrent) {
                bestCx = x + ox[idx]; bestCy = y + oy[idx]
                best = cost(x, y, bestCx, bestCy, Int.MAX_VALUE, pass2)
            }
            fun tryCand(cx: Int, cy: Int) {
                if (cx < r || cy < r || cx >= w - r || cy >= h - r) return
                if (!valid[cy * w + cx]) return
                val c = cost(x, y, cx, cy, best, pass2)
                if (c < best) { best = c; bestCx = cx; bestCy = cy }
            }
            // انتشار: عوض الإزاحة من الجيران المعبّأين
            if (x > 0 && state[idx - 1].toInt() == 2) tryCand(x + ox[idx - 1], y + oy[idx - 1])
            if (x < w - 1 && state[idx + 1].toInt() == 2) tryCand(x + ox[idx + 1], y + oy[idx + 1])
            if (y > 0 && state[idx - w].toInt() == 2) tryCand(x + ox[idx - w], y + oy[idx - w])
            if (y < h - 1 && state[idx + w].toInt() == 2) tryCand(x + ox[idx + w], y + oy[idx + w])
            // عشوائي محلي
            for (k in 0 until half) {
                tryCand(x + nextInt(2 * window + 1) - window, y + nextInt(2 * window + 1) - window)
            }
            // عشوائي عام
            for (k in 0 until half) {
                val v = vlist[nextInt(vn)]
                tryCand(v % w, v / w)
            }
            if (best == Int.MAX_VALUE) {
                // لا مرشح صالح: خذ أقرب مصدر عشوائي
                val v = vlist[nextInt(vn)]
                bestCx = v % w; bestCy = v / w
            }
            px[idx] = px[bestCy * w + bestCx]
            ox[idx] = bestCx - x
            oy[idx] = bestCy - y
            state[idx] = 2
        }

        // ترتيب "قشرة البصل": من حدود القناع للداخل
        var ring = ArrayList<Int>()
        val queued = BooleanArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (state[i].toInt() != 0) continue
            val edge = (x > 0 && state[i - 1].toInt() == 1) || (x < w - 1 && state[i + 1].toInt() == 1) ||
                (y > 0 && state[i - w].toInt() == 1) || (y < h - 1 && state[i + w].toInt() == 1)
            if (edge) { ring.add(i); queued[i] = true }
        }
        val order = IntArray(unknown)
        var oc = 0
        while (ring.isNotEmpty() && oc < unknown) {
            val next = ArrayList<Int>()
            for (i in ring) {
                if (oc < unknown) order[oc++] = i
                val x = i % w; val y = i / w
                if (x > 0 && state[i - 1].toInt() == 0 && !queued[i - 1]) { queued[i - 1] = true; next.add(i - 1) }
                if (x < w - 1 && state[i + 1].toInt() == 0 && !queued[i + 1]) { queued[i + 1] = true; next.add(i + 1) }
                if (y > 0 && state[i - w].toInt() == 0 && !queued[i - w]) { queued[i - w] = true; next.add(i - w) }
                if (y < h - 1 && state[i + w].toInt() == 0 && !queued[i + w]) { queued[i + w] = true; next.add(i + w) }
            }
            ring = next
        }
        // أي بكسل مجهول لم تصله القشرة (مناطق معزولة)
        for (i in 0 until n) if (state[i].toInt() == 0 && !queued[i] && oc < unknown) order[oc++] = i

        val passes = if (tier.refine) 2 else 1
        val total = oc * passes
        var done = 0
        for (k in 0 until oc) {
            val i = order[k]
            search(i % w, i / w, false, false)
            done++
            if ((done and 255) == 0) {
                if (cancelled()) return false
                progress(done * 95 / max(total, 1))
            }
        }
        if (tier.refine) {
            for (k in oc - 1 downTo 0) {
                val i = order[k]
                search(i % w, i / w, true, true)
                done++
                if ((done and 255) == 0) {
                    if (cancelled()) return false
                    progress(done * 95 / max(total, 1))
                }
            }
        }
        return !cancelled()
    }

    /** خطة احتياطية: انتشار متوسط الألوان من الحواف للداخل. */
    private fun diffusionFill(px: IntArray, w: Int, h: Int, state: ByteArray) {
        var changed = true
        var guard = 0
        while (changed && guard < w + h + 10) {
            changed = false
            guard++
            val fillIdx = ArrayList<Int>()
            val fillCol = ArrayList<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (state[i].toInt() != 0) continue
                var r = 0; var g = 0; var b = 0; var c = 0
                fun acc(j: Int) {
                    if (state[j].toInt() != 0) {
                        val p = px[j]
                        r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; c++
                    }
                }
                if (x > 0) acc(i - 1)
                if (x < w - 1) acc(i + 1)
                if (y > 0) acc(i - w)
                if (y < h - 1) acc(i + w)
                if (c > 0) {
                    fillIdx.add(i)
                    fillCol.add((0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c))
                }
            }
            for (k in fillIdx.indices) {
                px[fillIdx[k]] = fillCol[k]
                state[fillIdx[k]] = 2
                changed = true
            }
        }
    }
}
