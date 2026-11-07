package com.m3rd.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.util.Base64
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.max
import kotlin.math.min

/**
 * الخزنة الخاصة في Mango Gallery.
 * - كلمة سر مستقلة (PBKDF2 + salt) + سؤال أمان لاسترجاعها + بصمة/وجه (حسب الجهاز).
 * - الملفات مشفّرة (انظر PrivateVault) ولا تظهر في المعرض أو التطبيقات الأخرى.
 * - FLAG_SECURE دائماً + قفل تلقائي عند الخروج من الشاشة.
 */
class VaultActivity : Activity() {

    companion object {
        const val EXTRA_IMPORT_URIS = "vault_import_uris"
        const val EXTRA_IMPORT_NAMES = "vault_import_names"
        const val EXTRA_IMPORT_MIMES = "vault_import_mimes"
        private const val REQ_DELETE_ORIGINALS = 4711
        private const val REQ_PICK = 4712
        private val QUESTIONS = arrayOf(
            "اسم أول مدرسة التحقت بها؟",
            "اسم أول حيوان أليف لك؟",
            "في أي مدينة وُلدت؟",
            "ما اسم أعز أصدقاء الطفولة؟",
            "ما أول سيارة أو دراجة امتلكتها؟"
        )
    }

    private val accent = 0xFFFF9800.toInt()
    private val bg = 0xFF0E0E10.toInt()
    private val card = 0xFF1C1C20.toInt()
    private val textPrimary = 0xFFF5F5F7.toInt()
    private val textSecondary = 0xFF98989E.toInt()
    private val danger = 0xFFFF5A52.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private lateinit var root: FrameLayout
    private lateinit var prefs: SharedPreferences

    private var busyExternal = false
    private var unlocked = false
    private var pendingUris: ArrayList<Uri> = ArrayList()
    private var pendingNames: ArrayList<String> = ArrayList()
    private var pendingMimes: ArrayList<String> = ArrayList()

    private var entries: List<VaultEntry> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var gridAdapter: GridAdapter? = null
    private var titleView: TextView? = null
    private var selectionBar: LinearLayout? = null
    private var emptyView: TextView? = null

    private var viewerLayer: FrameLayout? = null
    private var viewerIndex = 0
    private var viewerVideo: VideoView? = null

    private val thumbCache = object : LruCache<String, Bitmap>(
        max(2, (Runtime.getRuntime().maxMemory() / 16).toInt() / 1024).coerceAtMost(24 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    // ============================================================ دورة الحياة
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        prefs = getSharedPreferences("mango_vault", MODE_PRIVATE)
        root = FrameLayout(this)
        root.setBackgroundColor(bg)
        setContentView(root)

        intent?.getParcelableArrayListExtra<Uri>(EXTRA_IMPORT_URIS)?.let { pendingUris = it }
        intent?.getStringArrayListExtra(EXTRA_IMPORT_NAMES)?.let { pendingNames = it }
        intent?.getStringArrayListExtra(EXTRA_IMPORT_MIMES)?.let { pendingMimes = it }

        if (hasPin()) showLock() else showSetup()
    }

    override fun onStop() {
        super.onStop()
        // قفل تلقائي: أي خروج من الشاشة (إلا الحوارات الخارجية التي بدأناها) يغلق الخزنة
        if (!busyExternal && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { viewerVideo?.stopPlayback() } catch (_: Throwable) {}
        PrivateVault.clearTemp(this)
        io.shutdown()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        when {
            viewerLayer != null -> closeViewer()
            selected.isNotEmpty() -> { selected.clear(); refreshSelectionUi() }
            else -> super.onBackPressed()
        }
    }

    // ================================================================ أدوات UI
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Float, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (strokeColor != 0) setStroke(dp(1), strokeColor)
        }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false, center: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (center) gravity = Gravity.CENTER
        }

    private fun button(t: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = t
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (filled) Color.BLACK else accent)
            background = if (filled) roundBg(accent, 14f) else roundBg(Color.TRANSPARENT, 14f, accent)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun pinField(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(textSecondary)
            setTextColor(textPrimary)
            textSize = 20f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
            background = roundBg(card, 14f, 0xFF2E2E34.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

    private fun textField(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(textSecondary)
            setTextColor(textPrimary)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            background = roundBg(card, 14f, 0xFF2E2E34.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

    private fun gap(h: Int): View = View(this).also { it.layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun setScreen(v: View) {
        root.removeAllViews()
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
    }

    private fun centeredPage(build: LinearLayout.() -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(48), dp(28), dp(32))
        }
        col.build()
        return ScrollView(this).apply {
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(-1, -2))
        }
    }

    private fun lockBadge(): View =
        TextView(this).apply {
            text = "🔒"
            textSize = 34f
            gravity = Gravity.CENTER
            background = roundBg(card, 36f, accent)
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
        }

    // ============================================================ كلمة السر
    private fun hasPin(): Boolean = prefs.getString("pin_hash", null) != null

    private fun pbkdf2(secret: String, salt: ByteArray, algo: String): String {
        val spec = PBEKeySpec(secret.toCharArray(), salt, 60000, 256)
        val bytes = SecretKeyFactory.getInstance(algo).generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun bestAlgo(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    private fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun storePin(pin: String) {
        val salt = newSalt()
        val algo = bestAlgo()
        prefs.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_algo", algo)
            .putString("pin_hash", pbkdf2(pin, salt, algo))
            .putInt("fails", 0)
            .putLong("lock_until", 0L)
            .apply()
    }

    private fun sameHash(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun checkPin(pin: String): Boolean {
        val salt = Base64.decode(prefs.getString("pin_salt", "") ?: "", Base64.NO_WRAP)
        val algo = prefs.getString("pin_algo", bestAlgo()) ?: bestAlgo()
        val stored = prefs.getString("pin_hash", "") ?: ""
        return try { sameHash(pbkdf2(pin, salt, algo), stored) } catch (e: Throwable) { false }
    }

    private fun normalizeAnswer(a: String): String = a.trim().lowercase()

    private fun storeAnswer(question: String, answer: String) {
        val salt = newSalt()
        val algo = bestAlgo()
        prefs.edit()
            .putString("q_text", question)
            .putString("a_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("a_algo", algo)
            .putString("a_hash", pbkdf2(normalizeAnswer(answer), salt, algo))
            .apply()
    }

    private fun checkAnswer(answer: String): Boolean {
        val salt = Base64.decode(prefs.getString("a_salt", "") ?: "", Base64.NO_WRAP)
        val algo = prefs.getString("a_algo", bestAlgo()) ?: bestAlgo()
        val stored = prefs.getString("a_hash", "") ?: ""
        return try { sameHash(pbkdf2(normalizeAnswer(answer), salt, algo), stored) } catch (e: Throwable) { false }
    }

    private fun lockoutRemainingMs(): Long =
        max(0L, prefs.getLong("lock_until", 0L) - System.currentTimeMillis())

    private fun registerFail() {
        val f = prefs.getInt("fails", 0) + 1
        val e = prefs.edit().putInt("fails", f)
        if (f % 5 == 0) {
            val step = min(f / 5 - 1, 5)
            e.putLong("lock_until", System.currentTimeMillis() + 30_000L * (1L shl step))
        }
        e.apply()
    }

    private fun resetFails() {
        prefs.edit().putInt("fails", 0).putLong("lock_until", 0L).apply()
    }

    // ================================================================ البصمة
    private fun biometricAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        }
        return try {
            val bm = getSystemService(BiometricManager::class.java)
            @Suppress("DEPRECATION")
            bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Throwable) {
            false
        }
    }

    private fun promptBiometric(subtitle: String, onOk: () -> Unit, onFail: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onFail("البصمة تحتاج Android 9 أو أحدث")
            return
        }
        try {
            busyExternal = true
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("الخزنة الخاصة")
                .setSubtitle(subtitle)
                .setNegativeButton("استخدام كلمة السر", mainExecutor) { _, _ -> }
                .build()
            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        handler.post { busyExternal = false; onOk() }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        handler.post { busyExternal = false; onFail(errString?.toString() ?: "") }
                    }
                }
            )
        } catch (e: Throwable) {
            busyExternal = false
            onFail("البصمة غير مفعّلة في إعدادات الهاتف")
        }
    }

    // ============================================================= إنشاء الخزنة
    private fun showSetup() {
        var chosenQuestion = QUESTIONS[0]
        val pin1 = pinField("كلمة السر (٤ أرقام على الأقل)")
        val pin2 = pinField("أعد كتابة كلمة السر")
        val answer = textField("إجابة سؤال الأمان")
        val qBtn = TextView(this).apply {
            text = chosenQuestion + "  ▾"
            textSize = 15f
            setTextColor(accent)
            background = roundBg(card, 14f, 0xFF2E2E34.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener {
                AlertDialog.Builder(this@VaultActivity)
                    .setTitle("اختر سؤال الأمان")
                    .setItems(QUESTIONS) { _, which ->
                        chosenQuestion = QUESTIONS[which]
                        text = chosenQuestion + "  ▾"
                    }
                    .show()
            }
        }
        val canBio = biometricAvailable()
        var useBio = canBio
        val bioBtn = TextView(this).apply {
            textSize = 15f
            setTextColor(textPrimary)
            setPadding(dp(4), dp(10), dp(4), dp(10))
            fun refresh() { text = if (useBio) "☑  السماح بالفتح بالبصمة / الوجه" else "☐  السماح بالفتح بالبصمة / الوجه" }
            refresh()
            setOnClickListener { useBio = !useBio; refresh() }
        }
        val err = label("", 13f, danger, center = true)

        setScreen(centeredPage {
            addView(lockBadge())
            addView(gap(16))
            addView(label("إنشاء الخزنة الخاصة", 22f, textPrimary, bold = true, center = true))
            addView(gap(6))
            addView(
                label(
                    "الصور والفيديوهات اللي تحطها هنا بتتشفّر وبتختفي من المعرض والتطبيقات التانية.",
                    13.5f, textSecondary, center = true
                )
            )
            addView(gap(22))
            addView(pin1, LinearLayout.LayoutParams(-1, -2))
            addView(gap(10))
            addView(pin2, LinearLayout.LayoutParams(-1, -2))
            addView(gap(18))
            addView(label("سؤال الأمان (لاسترجاع كلمة السر لو نسيتها)", 13f, textSecondary))
            addView(gap(6))
            addView(qBtn, LinearLayout.LayoutParams(-1, -2))
            addView(gap(10))
            addView(answer, LinearLayout.LayoutParams(-1, -2))
            if (canBio) {
                addView(gap(8))
                addView(bioBtn)
            }
            addView(gap(10))
            addView(err)
            addView(gap(10))
            addView(button("إنشاء الخزنة", true) {
                val p1 = pin1.text.toString()
                val p2 = pin2.text.toString()
                val a = answer.text.toString()
                when {
                    p1.length < 4 -> err.text = "كلمة السر لازم تكون ٤ أرقام على الأقل"
                    p1 != p2 -> err.text = "كلمتا السر مش متطابقتين"
                    normalizeAnswer(a).length < 2 -> err.text = "اكتب إجابة سؤال الأمان"
                    else -> {
                        storePin(p1)
                        storeAnswer(chosenQuestion, a)
                        prefs.edit().putBoolean("biometric", useBio).apply()
                        onUnlocked()
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        })
    }

    // ============================================================== شاشة القفل
    private fun showLock() {
        val pin = pinField("كلمة السر")
        val err = label("", 13f, danger, center = true)
        fun tryUnlock() {
            val wait = lockoutRemainingMs()
            if (wait > 0) {
                err.text = "محاولات كتير غلط. استنى ${(wait / 1000) + 1} ثانية"
                return
            }
            if (checkPin(pin.text.toString())) {
                resetFails()
                onUnlocked()
            } else {
                registerFail()
                pin.setText("")
                val w2 = lockoutRemainingMs()
                err.text = if (w2 > 0) "تم القفل مؤقتاً ${(w2 / 1000) + 1} ثانية" else "كلمة السر غلط"
            }
        }
        val useBio = prefs.getBoolean("biometric", false) && biometricAvailable()
        setScreen(centeredPage {
            addView(lockBadge())
            addView(gap(16))
            addView(label("الخزنة الخاصة", 22f, textPrimary, bold = true, center = true))
            addView(gap(24))
            addView(pin, LinearLayout.LayoutParams(-1, -2))
            addView(gap(8))
            addView(err)
            addView(gap(10))
            addView(button("فتح", true) { tryUnlock() }, LinearLayout.LayoutParams(-1, -2))
            if (useBio) {
                addView(gap(10))
                addView(button("🔓  بصمة / وجه", false) {
                    promptBiometric("افتح الخزنة", { resetFails(); onUnlocked() }, { m -> err.text = m })
                }, LinearLayout.LayoutParams(-1, -2))
            }
            addView(gap(16))
            addView(
                label("نسيت كلمة السر؟", 14f, accent, center = true).apply {
                    setOnClickListener { showForgot() }
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
            )
        })
        if (useBio) {
            handler.postDelayed({
                if (!unlocked && !isFinishing) {
                    promptBiometric("افتح الخزنة", { resetFails(); onUnlocked() }, { })
                }
            }, 350)
        }
    }

    private fun showForgot() {
        val answer = textField("الإجابة")
        val pin1 = pinField("كلمة سر جديدة")
        val pin2 = pinField("أعد كتابتها")
        val err = label("", 13f, danger, center = true)
        val q = prefs.getString("q_text", "سؤال الأمان") ?: "سؤال الأمان"
        setScreen(centeredPage {
            addView(lockBadge())
            addView(gap(16))
            addView(label("استرجاع كلمة السر", 22f, textPrimary, bold = true, center = true))
            addView(gap(10))
            addView(label(q, 15f, accent, bold = true, center = true))
            addView(gap(14))
            addView(answer, LinearLayout.LayoutParams(-1, -2))
            addView(gap(10))
            addView(pin1, LinearLayout.LayoutParams(-1, -2))
            addView(gap(10))
            addView(pin2, LinearLayout.LayoutParams(-1, -2))
            addView(gap(8))
            addView(err)
            addView(gap(10))
            addView(button("تغيير كلمة السر", true) {
                val wait = lockoutRemainingMs()
                when {
                    wait > 0 -> err.text = "استنى ${(wait / 1000) + 1} ثانية"
                    pin1.text.length < 4 -> err.text = "كلمة السر لازم ٤ أرقام على الأقل"
                    pin1.text.toString() != pin2.text.toString() -> err.text = "كلمتا السر مش متطابقتين"
                    !checkAnswer(answer.text.toString()) -> {
                        registerFail()
                        err.text = "الإجابة غلط"
                    }
                    else -> {
                        storePin(pin1.text.toString())
                        Toast.makeText(this@VaultActivity, "تم تغيير كلمة السر", Toast.LENGTH_SHORT).show()
                        onUnlocked()
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2))
            addView(gap(8))
            addView(
                label("رجوع", 14f, textSecondary, center = true).apply {
                    setOnClickListener { showLock() }
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
            )
        })
    }

    private fun showChangePinDialog() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        val old = pinField("كلمة السر الحالية")
        val n1 = pinField("كلمة السر الجديدة")
        val n2 = pinField("أعد كتابتها")
        col.addView(old, LinearLayout.LayoutParams(-1, -2))
        col.addView(gap(8))
        col.addView(n1, LinearLayout.LayoutParams(-1, -2))
        col.addView(gap(8))
        col.addView(n2, LinearLayout.LayoutParams(-1, -2))
        val dlg = AlertDialog.Builder(this)
            .setTitle("تغيير كلمة السر")
            .setView(col)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when {
                    lockoutRemainingMs() > 0 -> toast("استنى شوية وحاول تاني")
                    !checkPin(old.text.toString()) -> { registerFail(); toast("كلمة السر الحالية غلط") }
                    n1.text.length < 4 -> toast("كلمة السر الجديدة لازم ٤ أرقام على الأقل")
                    n1.text.toString() != n2.text.toString() -> toast("كلمتا السر مش متطابقتين")
                    else -> {
                        storePin(n1.text.toString())
                        toast("تم تغيير كلمة السر")
                        dlg.dismiss()
                    }
                }
            }
        }
        dlg.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // =========================================================== بعد فتح القفل
    private fun onUnlocked() {
        unlocked = true
        if (pendingUris.isNotEmpty()) {
            val u = ArrayList(pendingUris)
            val n = ArrayList(pendingNames)
            val m = ArrayList(pendingMimes)
            pendingUris = ArrayList(); pendingNames = ArrayList(); pendingMimes = ArrayList()
            showMain()
            importUris(u, n, m, deleteOriginals = true)
        } else {
            showMain()
        }
    }

    // ================================================================== الرئيسية
    private fun showMain() {
        selected.clear()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(34), dp(8), dp(8))
        }
        bar.addView(iconButton("✕") { finish() })
        val title = label("الخزنة الخاصة", 19f, textPrimary, bold = true)
        titleView = title
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        bar.addView(iconButton("＋") { pickFromDevice() })
        bar.addView(iconButton("⋮") { showVaultMenu() })
        page.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val frame = FrameLayout(this)
        val grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setBackgroundColor(bg)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        val adapter = GridAdapter()
        gridAdapter = adapter
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            val e = entries.getOrNull(pos) ?: return@setOnItemClickListener
            if (selected.isNotEmpty()) toggleSelect(e.id) else openViewer(pos)
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val e = entries.getOrNull(pos) ?: return@setOnItemLongClickListener true
            toggleSelect(e.id)
            true
        }
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        val empty = label("الخزنة فاضية\nحدد صور في المعرض واختار «نقل للخزنة»", 15f, textSecondary, center = true)
        emptyView = empty
        frame.addView(empty, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        page.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val sel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(card)
            setPadding(dp(12), dp(10), dp(12), dp(14))
            visibility = View.GONE
        }
        sel.addView(
            button("استعادة للمعرض", true) { restoreSelected() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) }
        )
        sel.addView(
            button("حذف نهائي", false) { confirmDeleteSelected() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) }
        )
        selectionBar = sel
        page.addView(sel, LinearLayout.LayoutParams(-1, -2))

        setScreen(page)
        reloadEntries()
    }

    private fun iconButton(glyph: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 22f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }

    private fun reloadEntries() {
        io.execute {
            val list = PrivateVault.list(this)
            handler.post {
                if (isFinishing) return@post
                entries = list
                gridAdapter?.notifyDataSetChanged()
                emptyView?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                refreshSelectionUi()
            }
        }
    }

    private fun toggleSelect(id: String) {
        if (!selected.add(id)) selected.remove(id)
        refreshSelectionUi()
        gridAdapter?.notifyDataSetChanged()
    }

    private fun refreshSelectionUi() {
        selectionBar?.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        titleView?.text = if (selected.isEmpty()) "الخزنة الخاصة (${entries.size})" else "تم تحديد ${selected.size}"
        gridAdapter?.notifyDataSetChanged()
    }

    private fun showVaultMenu() {
        val bio = prefs.getBoolean("biometric", false)
        val canBio = biometricAvailable()
        val items = ArrayList<String>()
        items.add("تغيير كلمة السر")
        items.add("تغيير سؤال الأمان")
        if (canBio) items.add(if (bio) "إيقاف الفتح بالبصمة" else "تفعيل الفتح بالبصمة")
        items.add("قفل الآن")
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "تغيير كلمة السر" -> showChangePinDialog()
                    "تغيير سؤال الأمان" -> showChangeQuestionDialog()
                    "قفل الآن" -> finish()
                    else -> {
                        if (bio) {
                            prefs.edit().putBoolean("biometric", false).apply()
                            toast("تم إيقاف البصمة")
                        } else {
                            promptBiometric("تأكيد تفعيل البصمة", {
                                prefs.edit().putBoolean("biometric", true).apply()
                                toast("تم تفعيل البصمة")
                            }, { m -> toast(m) })
                        }
                    }
                }
            }
            .show()
    }

    private fun showChangeQuestionDialog() {
        var chosen = QUESTIONS[0]
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        val pin = pinField("كلمة السر الحالية")
        val ans = textField("الإجابة الجديدة")
        val q = TextView(this).apply {
            text = chosen + "  ▾"
            textSize = 15f
            setTextColor(accent)
            setPadding(dp(4), dp(12), dp(4), dp(12))
            setOnClickListener {
                AlertDialog.Builder(this@VaultActivity).setItems(QUESTIONS) { _, w ->
                    chosen = QUESTIONS[w]
                    text = chosen + "  ▾"
                }.show()
            }
        }
        col.addView(pin, LinearLayout.LayoutParams(-1, -2))
        col.addView(q)
        col.addView(ans, LinearLayout.LayoutParams(-1, -2))
        AlertDialog.Builder(this)
            .setTitle("تغيير سؤال الأمان")
            .setView(col)
            .setPositiveButton("حفظ") { _, _ ->
                if (!checkPin(pin.text.toString())) {
                    toast("كلمة السر غلط")
                } else if (normalizeAnswer(ans.text.toString()).length < 2) {
                    toast("اكتب إجابة")
                } else {
                    storeAnswer(chosen, ans.text.toString())
                    toast("تم الحفظ")
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ================================================================= الشبكة
    private inner class GridAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell: FrameLayout
            val img: ImageView
            val badge: TextView
            val check: TextView
            if (convertView is FrameLayout) {
                cell = convertView
                img = cell.getChildAt(0) as ImageView
                badge = cell.getChildAt(1) as TextView
                check = cell.getChildAt(2) as TextView
            } else {
                cell = FrameLayout(this@VaultActivity)
                img = ImageView(this@VaultActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                badge = TextView(this@VaultActivity).apply {
                    text = "▶"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
                check = TextView(this@VaultActivity).apply {
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTextColor(Color.BLACK)
                }
                cell.addView(img, FrameLayout.LayoutParams(-1, -1))
                cell.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(6); bottomMargin = dp(4)
                })
                cell.addView(check, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(6); rightMargin = dp(6)
                })
            }
            val size = (resources.displayMetrics.widthPixels - dp(4)) / 3
            cell.layoutParams = AbsListView.LayoutParams(size, size)

            val e = entries[position]
            badge.visibility = if (e.isVideo) View.VISIBLE else View.GONE
            val isSel = selected.contains(e.id)
            check.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            check.text = if (isSel) "✓" else ""
            check.background = roundBg(if (isSel) accent else 0x66000000, 11f, Color.WHITE)
            cell.setBackgroundColor(card)
            img.alpha = if (isSel) 0.6f else 1f

            img.tag = e.id
            val cached = thumbCache.get(e.id)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(null)
                io.execute {
                    val bmp = PrivateVault.thumbnail(this@VaultActivity, e)
                    if (bmp != null) {
                        thumbCache.put(e.id, bmp)
                        handler.post { if (img.tag == e.id) img.setImageBitmap(bmp) }
                    }
                }
            }
            return cell
        }
    }

    // ================================================================= الاستيراد
    private fun pickFromDevice() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            busyExternal = true
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (e: Throwable) {
            busyExternal = false
            toast("تعذر فتح المنتقي")
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        busyExternal = false
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            val names = ArrayList<String>()
            val mimes = ArrayList<String>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } else if (data.data != null) {
                uris.add(data.data!!)
            }
            for (u in uris) {
                names.add(displayNameOf(u))
                mimes.add(contentResolver.getType(u) ?: "image/jpeg")
            }
            importUris(uris, names, mimes, deleteOriginals = false)
        } else if (requestCode == REQ_DELETE_ORIGINALS) {
            if (resultCode == RESULT_OK) toast("تم حذف الأصول من المعرض")
            else toast("الأصول لسه موجودة في المعرض")
        }
    }

    private fun displayNameOf(u: Uri): String {
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Throwable) {}
        return "item_" + System.currentTimeMillis()
    }

    private fun importUris(
        uris: List<Uri>, names: List<String>, mimes: List<String>, deleteOriginals: Boolean
    ) {
        if (uris.isEmpty()) return
        val dlg = AlertDialog.Builder(this)
            .setMessage("جاري التشفير والنقل للخزنة...")
            .setCancelable(false)
            .show()
        io.execute {
            val moved = ArrayList<Uri>()
            for (i in uris.indices) {
                val name = names.getOrNull(i) ?: displayNameOf(uris[i])
                val mime = mimes.getOrNull(i) ?: (contentResolver.getType(uris[i]) ?: "image/jpeg")
                if (PrivateVault.add(this, uris[i], name, mime) != null) moved.add(uris[i])
            }
            handler.post {
                try { dlg.dismiss() } catch (_: Throwable) {}
                if (isFinishing) return@post
                reloadEntries()
                if (moved.size == uris.size) toast("تم نقل ${moved.size} للخزنة")
                else toast("تم نقل ${moved.size} من ${uris.size}")
                if (deleteOriginals && moved.isNotEmpty()) deleteOriginalsFromGallery(moved)
            }
        }
    }

    private fun deleteOriginalsFromGallery(uris: List<Uri>) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                busyExternal = true
                @Suppress("DEPRECATION")
                startIntentSenderForResult(pi.intentSender, REQ_DELETE_ORIGINALS, null, 0, 0, 0)
            } else {
                var failed = 0
                for (u in uris) {
                    try { if (contentResolver.delete(u, null, null) <= 0) failed++ } catch (_: Throwable) { failed++ }
                }
                if (failed > 0) toast("$failed ملف لسه موجود في المعرض، احذفه يدوياً")
            }
        } catch (e: Throwable) {
            busyExternal = false
            toast("الصور اتنقلت للخزنة، بس احذف الأصول من المعرض يدوياً")
        }
    }

    // ================================================================== الاستعادة والحذف
    private fun selectedEntries(): List<VaultEntry> = entries.filter { selected.contains(it.id) }

    private fun restoreSelected() {
        val list = selectedEntries()
        if (list.isEmpty()) return
        io.execute {
            var ok = 0
            for (e in list) if (PrivateVault.restoreToGallery(this, e)) ok++
            handler.post {
                toast(if (ok == list.size) "تمت الاستعادة للمعرض" else "تمت استعادة $ok من ${list.size}")
                selected.clear()
                reloadEntries()
            }
        }
    }

    private fun confirmDeleteSelected() {
        val list = selectedEntries()
        if (list.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("حذف نهائي")
            .setMessage("هيتم حذف ${list.size} عنصر نهائياً ومش هينفع تسترجعهم.")
            .setPositiveButton("حذف") { _, _ ->
                io.execute {
                    for (e in list) PrivateVault.delete(this, e)
                    handler.post { selected.clear(); reloadEntries() }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ===================================================================== العارض
    private fun openViewer(index: Int) {
        if (entries.isEmpty()) return
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isClickable = true }
        viewerLayer = layer
        viewerIndex = index.coerceIn(0, entries.size - 1)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                if (kotlin.math.abs(dx) > dp(80) && kotlin.math.abs(vx) > 400) {
                    // اللغة عربية: السحب لليمين = التالي
                    showViewerItem(if (dx > 0) viewerIndex + 1 else viewerIndex - 1)
                    return true
                }
                return false
            }
        })
        layer.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        showViewerItem(viewerIndex)
    }

    private fun showViewerItem(rawIndex: Int) {
        val layer = viewerLayer ?: return
        if (rawIndex < 0 || rawIndex >= entries.size) return
        viewerIndex = rawIndex
        val e = entries[rawIndex]
        try { viewerVideo?.stopPlayback() } catch (_: Throwable) {}
        viewerVideo = null
        layer.removeAllViews()

        val spinner = label("جاري فك التشفير...", 14f, textSecondary, center = true)
        layer.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        io.execute {
            if (e.isVideo) {
                val f = PrivateVault.exportToTemp(this, e)
                handler.post {
                    if (viewerLayer !== layer || viewerIndex != rawIndex) return@post
                    layer.removeView(spinner)
                    if (f == null) { spinner.text = "تعذر فتح الفيديو"; layer.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)); addViewerChrome(layer, e); return@post }
                    val vv = VideoView(this)
                    viewerVideo = vv
                    layer.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                    val mc = MediaController(this)
                    mc.setAnchorView(vv)
                    vv.setMediaController(mc)
                    vv.setVideoURI(Uri.fromFile(f))
                    vv.setOnPreparedListener { it.start() }
                    addViewerChrome(layer, e)
                }
            } else {
                val dm = resources.displayMetrics
                val bmp = PrivateVault.loadImage(this, e, max(dm.widthPixels, dm.heightPixels))
                handler.post {
                    if (viewerLayer !== layer || viewerIndex != rawIndex) return@post
                    layer.removeView(spinner)
                    if (bmp == null) {
                        spinner.text = "تعذر فتح الصورة"
                        layer.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                    } else {
                        val iv = ImageView(this).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageBitmap(bmp)
                        }
                        layer.addView(iv, FrameLayout.LayoutParams(-1, -1))
                    }
                    addViewerChrome(layer, e)
                }
            }
        }
    }

    private fun addViewerChrome(layer: FrameLayout, e: VaultEntry) {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(34), dp(8), dp(8))
            setBackgroundColor(0x66000000)
        }
        top.addView(iconButton("✕") { closeViewer() })
        top.addView(
            label("${viewerIndex + 1} / ${entries.size}", 15f, Color.WHITE, center = true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        top.addView(iconButton("↩") {
            closeViewer()
            selected.clear(); selected.add(e.id)
            restoreSelected()
        })
        top.addView(iconButton("🗑") {
            closeViewer()
            selected.clear(); selected.add(e.id)
            confirmDeleteSelected()
        })
        layer.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }

    private fun closeViewer() {
        try { viewerVideo?.stopPlayback() } catch (_: Throwable) {}
        viewerVideo = null
        viewerLayer?.let { root.removeView(it) }
        viewerLayer = null
    }
}
