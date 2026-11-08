package com.m3rd.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.view.HapticFeedbackConstants
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
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * الخزنة الخاصة الاحترافية في Mango Gallery (Mango Secure Vault).
 * - خزنة معزولة ومشفّرة بـ AES-256-GCM (وليست قفلاً للمعرض بالكامل)، تنقل إليها الصور والفيديوهات الخاصة فتختفي من المعرض.
 * - لوحة مفاتيح PIN تفاعلية فاخرة + صفحة مخصصة كاملة لتعديل كلمة المرور وسؤال الأمان وإعدادات البصمة.
 * - دعم تلقائي ذكي لكافة أنواع البصمة والقياسات الحيوية في الهاتف:
 *   بصمة الوجه (Face Unlock)، البصمة الجانبية على زر الباور، البصمة المدمجة في الشاشة، أو البصمة الخلفية.
 * - FLAG_SECURE مفعّل دائماً لمنع تصوير الشاشة + قفل فوري عند الخروج.
 */
class VaultActivity : Activity() {

    companion object {
        const val EXTRA_IMPORT_URIS = "vault_import_uris"
        const val EXTRA_IMPORT_NAMES = "vault_import_names"
        const val EXTRA_IMPORT_MIMES = "vault_import_mimes"
        const val EXTRA_OPEN_SECURITY_PAGE = "vault_open_security_page"
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

    private val accent = 0xFF0A78D6.toInt()
    private val accentLight = 0xFF8CD0FA.toInt()
    private val mangoOrange = 0xFFFF9800.toInt()
    private val bg = 0xFF07090E.toInt()
    private val card = 0xFF151922.toInt()
    private val border = 0xFF252C3A.toInt()
    private val textPrimary = 0xFFF5F7FA.toInt()
    private val textSecondary = 0xFF98A1B3.toInt()
    private val danger = 0xFFFF453A.toInt()
    private val successGreen = 0xFF32D74B.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private lateinit var root: FrameLayout
    private lateinit var prefs: SharedPreferences

    private var busyExternal = false
    private var unlocked = false
    private var openSecurityAfterUnlock = false
    private var filterMode = 0 // 0 = الكل، 1 = صور، 2 = فيديو
    private var pendingUris: ArrayList<Uri> = ArrayList()
    private var pendingNames: ArrayList<String> = ArrayList()
    private var pendingMimes: ArrayList<String> = ArrayList()

    private var allEntries: List<VaultEntry> = emptyList()
    private var entries: List<VaultEntry> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var gridAdapter: GridAdapter? = null
    private var titleView: TextView? = null
    private var statsSubtitleView: TextView? = null
    private var selectionBar: LinearLayout? = null
    private var emptyView: LinearLayout? = null

    private var viewerLayer: FrameLayout? = null
    private var viewerIndex = 0
    private var viewerVideo: VideoView? = null
    private var securityPageOpen = false

    private val thumbCache = object : LruCache<String, Bitmap>(
        max(2, (Runtime.getRuntime().maxMemory() / 16).toInt() / 1024).coerceAtMost(24 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        prefs = getSharedPreferences("mango_vault", MODE_PRIVATE)
        root = FrameLayout(this)
        root.setBackgroundColor(bg)
        setContentView(root)

        intent?.getParcelableArrayListExtra<Uri>(EXTRA_IMPORT_URIS)?.let { pendingUris = it }
        intent?.getStringArrayListExtra(EXTRA_IMPORT_NAMES)?.let { pendingNames = it }
        intent?.getStringArrayListExtra(EXTRA_IMPORT_MIMES)?.let { pendingMimes = it }
        openSecurityAfterUnlock = intent?.getBooleanExtra(EXTRA_OPEN_SECURITY_PAGE, false) == true

        if (hasPin()) showLock() else showSetup()
    }

    override fun onStop() {
        super.onStop()
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
            securityPageOpen -> {
                securityPageOpen = false
                showMain()
            }
            selected.isNotEmpty() -> {
                selected.clear()
                refreshSelectionUi()
            }
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

    private fun button(t: String, filled: Boolean, primaryColor: Int = accent, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = t
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = if (filled) roundBg(primaryColor, 16f) else roundBg(card, 16f, border)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun pinField(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(textSecondary)
            setTextColor(textPrimary)
            textSize = 19f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
            background = roundBg(card, 16f, border)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

    private fun textField(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(textSecondary)
            setTextColor(textPrimary)
            textSize = 15.5f
            inputType = InputType.TYPE_CLASS_TEXT
            background = roundBg(card, 16f, border)
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
            setPadding(dp(24), dp(38), dp(24), dp(28))
        }
        col.build()
        return ScrollView(this).apply {
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(-1, -2))
        }
    }

    private fun lockBadge(): View {
        val halo = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x260A78D6)
                setStroke(dp(1), accentLight)
            }
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80))
        }
        val ic = MainActivity.SamsungIconView(this, MainActivity.SamsungIconView.IconType.LOCK, accentLight, 2.2f)
        halo.addView(ic, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        return halo
    }

    // ============================================================ كلمة السر والتشفير
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
            .putInt("pin_len", pin.length)
            .putInt("fails", 0)
            .putLong("lock_until", 0L)
            .apply()
    }

    private fun storedPinLength(): Int = prefs.getInt("pin_len", 4).coerceIn(4, 12)

    private fun sameHash(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun checkPin(pin: String): Boolean {
        val salt = Base64.decode(prefs.getString("pin_salt", "") ?: "", Base64.NO_WRAP)
        val algo = prefs.getString("pin_algo", bestAlgo()) ?: bestAlgo()
        val stored = prefs.getString("pin_hash", "") ?: ""
        return try { sameHash(pbkdf2(pin, salt, algo), stored) } catch (e: Throwable) { false }
    }

    private fun normalizeAnswer(a: String): String = a.trim().lowercase(Locale.ROOT)

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

    // ================================================================ فحص البصمة والقياسات الحيوية الذكي
    data class BiometricHardwareInfo(
        val available: Boolean,
        val hasFingerprint: Boolean,
        val hasFace: Boolean,
        val hasIris: Boolean,
        val sensorDescriptionAr: String
    )

    private fun detectBiometricHardware(): BiometricHardwareInfo {
        val pm = packageManager
        val hasFp = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        val hasFace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pm.hasSystemFeature(PackageManager.FEATURE_FACE)
        } else false
        val hasIris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pm.hasSystemFeature(PackageManager.FEATURE_IRIS)
        } else false

        var canAuth = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val bm = getSystemService(BiometricManager::class.java)
                if (bm != null) {
                    val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    } else {
                        @Suppress("DEPRECATION")
                        bm.canAuthenticate()
                    }
                    canAuth = (res == BiometricManager.BIOMETRIC_SUCCESS)
                }
            } catch (_: Throwable) {}
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            canAuth = hasFp
        }

        val desc = when {
            hasFp && hasFace -> "بصمة الإصبع (شاشة/جانبية/خلفية) + بصمة الوجه"
            hasFace -> "التعرف على الوجه (Face Unlock)"
            hasFp -> "مستشعر بصمة الإصبع (مدمج بالشاشة / جانبي / خلفي)"
            else -> "القياسات الحيوية للجهاز"
        }
        return BiometricHardwareInfo(canAuth, hasFp, hasFace, hasIris, desc)
    }

    private fun biometricAvailable(): Boolean = detectBiometricHardware().available

    private fun promptBiometric(subtitle: String, onOk: () -> Unit, onFail: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onFail("البصمة تحتاج Android 9 أو أحدث")
            return
        }
        try {
            busyExternal = true
            val bioInfo = detectBiometricHardware()
            val builder = BiometricPrompt.Builder(this)
                .setTitle("الخزنة الخاصة • Mango Gallery")
                .setSubtitle(subtitle)
                .setDescription(bioInfo.sensorDescriptionAr)
                .setNegativeButton("إدخال رمز PIN", mainExecutor) { _, _ ->
                    handler.post { busyExternal = false }
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
            }
            val prompt = builder.build()
            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        handler.post {
                            busyExternal = false
                            onOk()
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        handler.post {
                            busyExternal = false
                            onFail(errString?.toString() ?: "")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            busyExternal = false
            onFail("البصمة غير مفعّلة في إعدادات الهاتف")
        }
    }

    // ============================================================= إنشاء الخزنة لأول مرة
    private fun showSetup() {
        var chosenQuestion = QUESTIONS[0]
        val pin1 = pinField("رمز PIN للخزنة (٤ إلى ١٢ رقماً)")
        val pin2 = pinField("تأكيد رمز PIN")
        val answer = textField("إجابة سؤال الأمان (لاستعادة كلمة السر)")
        val qBtn = TextView(this).apply {
            text = chosenQuestion + "  ▾"
            textSize = 15f
            setTextColor(accentLight)
            background = roundBg(card, 16f, border)
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
        val bioInfo = detectBiometricHardware()
        var useBio = bioInfo.available
        val bioBtn = TextView(this).apply {
            textSize = 14.5f
            setTextColor(textPrimary)
            background = roundBg(card, 16f, border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            fun refresh() {
                text = if (useBio) "✓  تفعيل الفتح السريع: ${bioInfo.sensorDescriptionAr}"
                else "☐  تفعيل الفتح السريع: ${bioInfo.sensorDescriptionAr}"
            }
            refresh()
            setOnClickListener { useBio = !useBio; refresh() }
        }
        val err = label("", 13f, danger, center = true)

        setScreen(centeredPage {
            addView(lockBadge())
            addView(gap(14))
            addView(label("إعداد الخزنة الخاصة المشفّرة", 22f, textPrimary, bold = true, center = true))
            addView(gap(6))
            addView(
                label(
                    "المعرض بالكامل يظل مفتوحاً وسريعاً، بينما الصور والفيديوهات التي تنقلها إلى هنا تُشفّر بـ AES-256 وتختفي تماماً من المعرض والتطبيقات الأخرى.",
                    13.5f, textSecondary, center = true
                )
            )
            addView(gap(20))
            addView(pin1, LinearLayout.LayoutParams(-1, -2))
            addView(gap(10))
            addView(pin2, LinearLayout.LayoutParams(-1, -2))
            addView(gap(16))
            addView(label("سؤال الأمان الاحتياطي (لاسترجاع كلمة السر عند نسيانها)", 13f, textSecondary))
            addView(gap(6))
            addView(qBtn, LinearLayout.LayoutParams(-1, -2))
            addView(gap(10))
            addView(answer, LinearLayout.LayoutParams(-1, -2))
            if (bioInfo.available) {
                addView(gap(12))
                addView(bioBtn, LinearLayout.LayoutParams(-1, -2))
            }
            addView(gap(10))
            addView(err)
            addView(gap(10))
            addView(button("تفعيل الخزنة الخاصة 🔒", true, accent) {
                val p1 = pin1.text.toString().trim()
                val p2 = pin2.text.toString().trim()
                val a = answer.text.toString()
                when {
                    p1.length < 4 -> err.text = "رمز PIN يجب أن يتكون من ٤ أرقام على الأقل"
                    p1 != p2 -> err.text = "رمزا PIN غير متطابقين"
                    normalizeAnswer(a).length < 2 -> err.text = "يرجى إدخال إجابة سؤال الأمان"
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

    // ============================================================== شاشة القفل الفاخرة بلوحة PIN والبصمة
    private fun showLock() {
        val bioInfo = detectBiometricHardware()
        val useBio = prefs.getBoolean("biometric", true) && bioInfo.available
        val targetLen = storedPinLength()
        var enteredPin = ""

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF071529.toInt(), 0xFF090C14.toInt(), 0xFF05060A.toInt())
            )
            setPadding(dp(24), dp(42), dp(24), dp(22))
        }

        val topCloseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topCloseRow.addView(iconButton("✕") { finish() })
        topCloseRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        page.addView(topCloseRow, LinearLayout.LayoutParams(-1, -2))

        page.addView(lockBadge())
        page.addView(gap(12))
        page.addView(label("الخزنة الخاصة", 23f, Color.WHITE, bold = true, center = true))
        val statusTv = label(
            if (useBio) "أدخل رمز الخزنة أو استخدم ${bioInfo.sensorDescriptionAr}"
            else "أدخل رمز PIN لفتح الخزنة المشفّرة",
            13f, accentLight, center = true
        )
        page.addView(statusTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val dotViews = Array(targetLen.coerceIn(4, 8)) {
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), 0xFF5C7496.toInt())
                }
            }
        }
        for (dv in dotViews) {
            dotsRow.addView(dv, LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                setMargins(dp(8), dp(20), dp(8), dp(14))
            })
        }
        page.addView(dotsRow, LinearLayout.LayoutParams(-1, -2))

        fun refreshDots(isError: Boolean = false) {
            for (i in dotViews.indices) {
                val filled = i < enteredPin.length
                dotViews[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    when {
                        isError -> setColor(danger)
                        filled -> setColor(accentLight)
                        else -> {
                            setColor(Color.TRANSPARENT)
                            setStroke(dp(2), 0xFF5C7496.toInt())
                        }
                    }
                }
            }
        }

        fun onDigit(d: String) {
            val wait = lockoutRemainingMs()
            if (wait > 0L) {
                statusTv.text = "محاولات خاطئة متعددة، انتظر ${(wait / 1000) + 1} ثانية"
                statusTv.setTextColor(danger)
                return
            }
            if (enteredPin.length < 12) {
                enteredPin += d
                refreshDots(false)
                if (enteredPin.length >= targetLen && checkPin(enteredPin)) {
                    resetFails()
                    onUnlocked()
                    return
                }
                if (enteredPin.length >= targetLen) {
                    registerFail()
                    try { root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
                    refreshDots(true)
                    val w2 = lockoutRemainingMs()
                    statusTv.text = if (w2 > 0L) "تم القفل مؤقتاً ${(w2 / 1000) + 1} ثانية" else "رمز PIN غير صحيح"
                    statusTv.setTextColor(danger)
                    handler.postDelayed({
                        enteredPin = ""
                        refreshDots(false)
                    }, 300L)
                }
            }
        }

        page.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // لوحة أرقام دائرية احترافية
        val keypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val rows = arrayOf(
            arrayOf("1", "2", "3"),
            arrayOf("4", "5", "6"),
            arrayOf("7", "8", "9"),
            arrayOf("BIO", "0", "DEL")
        )
        for (r in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            for (k in r) {
                val cell = FrameLayout(this).apply {
                    isClickable = true
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (k == "BIO") 0x330A78D6 else 0x1AFFFFFF)
                        setStroke(dp(1), 0x33FFFFFF)
                    }
                }
                when (k) {
                    "BIO" -> {
                        val fp = MainActivity.SamsungIconView(this, MainActivity.SamsungIconView.IconType.FINGERPRINT, accentLight, 2.0f)
                        cell.addView(fp, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
                        cell.setOnClickListener {
                            if (useBio) {
                                promptBiometric("افتح الخزنة الخاصة", { resetFails(); onUnlocked() }, { m ->
                                    if (m.isNotBlank()) statusTv.text = m
                                })
                            } else {
                                toast("البصمة غير مفعّلة أو غير مدعومة")
                            }
                        }
                    }
                    "DEL" -> {
                        cell.addView(label("⌫", 21f, Color.WHITE, bold = true, center = true), FrameLayout.LayoutParams(-1, -1))
                        cell.setOnClickListener {
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                                refreshDots(false)
                            }
                        }
                    }
                    else -> {
                        cell.addView(label(k, 24f, Color.WHITE, bold = true, center = true), FrameLayout.LayoutParams(-1, -1))
                        cell.setOnClickListener {
                            cell.scaleX = 0.9f
                            cell.scaleY = 0.9f
                            cell.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                            onDigit(k)
                        }
                    }
                }
                rowLayout.addView(cell, LinearLayout.LayoutParams(dp(70), dp(70)).apply {
                    setMargins(dp(14), dp(6), dp(14), dp(6))
                })
            }
            keypad.addView(rowLayout)
        }
        page.addView(keypad, LinearLayout.LayoutParams(-1, -2))

        val bottomLinks = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        bottomLinks.addView(
            label("نسيت كلمة السر؟ (سؤال الأمان)", 13.5f, accentLight, bold = true, center = true).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { showForgot() }
            }
        )
        page.addView(bottomLinks, LinearLayout.LayoutParams(-1, -2))

        setScreen(page)

        if (useBio) {
            handler.postDelayed({
                if (!unlocked && !isFinishing) {
                    promptBiometric("افتح الخزنة الخاصة", { resetFails(); onUnlocked() }, { })
                }
            }, 300)
        }
    }

    private fun showForgot() {
        val answer = textField("إجابة سؤال الأمان")
        val pin1 = pinField("رمز PIN الجديد (٤ أرقام على الأقل)")
        val pin2 = pinField("تأكيد رمز PIN الجديد")
        val err = label("", 13f, danger, center = true)
        val q = prefs.getString("q_text", "سؤال الأمان") ?: "سؤال الأمان"
        setScreen(centeredPage {
            addView(lockBadge())
            addView(gap(14))
            addView(label("استرجاع كلمة سر الخزنة", 22f, textPrimary, bold = true, center = true))
            addView(gap(10))
            addView(label(q, 15f, accentLight, bold = true, center = true))
            addView(gap(14))
            addView(answer, LinearLayout.LayoutParams(-1, -2))
            addView(gap(10))
            addView(pin1, LinearLayout.LayoutParams(-1, -2))
            addView(gap(10))
            addView(pin2, LinearLayout.LayoutParams(-1, -2))
            addView(gap(8))
            addView(err)
            addView(gap(10))
            addView(button("حفظ كلمة السر الجديدة وفتح الخزنة", true, accent) {
                val wait = lockoutRemainingMs()
                when {
                    wait > 0 -> err.text = "انتظر ${(wait / 1000) + 1} ثانية"
                    pin1.text.length < 4 -> err.text = "كلمة السر يجب أن تكون ٤ أرقام على الأقل"
                    pin1.text.toString() != pin2.text.toString() -> err.text = "كلمتا السر غير متطابقتين"
                    !checkAnswer(answer.text.toString()) -> {
                        registerFail()
                        err.text = "إجابة سؤال الأمان غير صحيحة"
                    }
                    else -> {
                        storePin(pin1.text.toString().trim())
                        toast("تم تحديث كلمة السر بنجاح")
                        onUnlocked()
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2))
            addView(gap(8))
            addView(
                label("العودة لشاشة القفل", 14f, textSecondary, center = true).apply {
                    setOnClickListener { showLock() }
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
            )
        })
    }

    // =========================================================== صفحة مخصصة لإدارة كلمة السر والبصمة والأمان
    private fun showSecuritySettingsPage() {
        securityPageOpen = true
        val bioInfo = detectBiometricHardware()
        var bioEnabled = prefs.getBoolean("biometric", true) && bioInfo.available

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(34), dp(14), dp(10))
        }
        topBar.addView(iconButton("‹") {
            securityPageOpen = false
            showMain()
        })
        topBar.addView(
            label("إدارة كلمة السر والحماية البيومترية", 18f, textPrimary, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) }
        )
        page.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(28))
        }

        // بطاقة حالة التشفير والبصمة المكتشفة في الهاتف
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(card, 20f, border)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        statusCard.addView(label("🛡️ حالة أمان الخزنة الخاصة", 16f, accentLight, bold = true))
        statusCard.addView(gap(6))
        statusCard.addView(label("• التشفير النشط: AES-256-GCM عبر Android Keystore", 13.5f, textPrimary))
        statusCard.addView(gap(4))
        statusCard.addView(label("• حماية الشاشة: منع اللقطات والتسجيل (FLAG_SECURE)", 13.5f, textPrimary))
        statusCard.addView(gap(4))
        statusCard.addView(
            label(
                "• مستشعر البصمة في هاتفك: ${if (bioInfo.available) bioInfo.sensorDescriptionAr else "غير مفعّل في إعدادات النظام"}",
                13.5f,
                if (bioInfo.available) successGreen else mangoOrange
            )
        )
        content.addView(statusCard, LinearLayout.LayoutParams(-1, -2))
        content.addView(gap(14))

        // بطاقة تفعيل/إيقاف البصمة (بصمة وجه / جانبية / خلفية / شاشة)
        val bioCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(card, 20f, border)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        bioCard.addView(label("🔓 الفتح بالبصمة والتعرف الحيوي", 16f, textPrimary, bold = true))
        bioCard.addView(gap(4))
        bioCard.addView(
            label(
                "يدعم الهاتف تلقائياً بصمة الوجه أو البصمة الجانبية أو الخلفية أو المدمجة في الشاشة حسب عتاد جهازك.",
                13f, textSecondary
            )
        )
        bioCard.addView(gap(12))
        val bioToggleBtn = button(
            if (bioEnabled) "✓ الفتح بالبصمة مفعّل (اضغط للإيقاف)" else "تفعيل الفتح بالبصمة الآن",
            bioEnabled,
            if (bioEnabled) accent else card
        ) {}
        bioToggleBtn.setOnClickListener {
            if (!bioInfo.available) {
                toast("يرجى تسجيل بصمة أو وجه في إعدادات الهاتف أولاً")
                return@setOnClickListener
            }
            if (bioEnabled) {
                bioEnabled = false
                prefs.edit().putBoolean("biometric", false).apply()
                toast("تم إيقاف الفتح بالبصمة")
                showSecuritySettingsPage()
            } else {
                promptBiometric("تأكيد تفعيل البصمة للخزنة", {
                    bioEnabled = true
                    prefs.edit().putBoolean("biometric", true).apply()
                    toast("تم تفعيل الفتح بالبصمة بنجاح")
                    showSecuritySettingsPage()
                }, { err -> if (err.isNotBlank()) toast(err) })
            }
        }
        bioCard.addView(bioToggleBtn, LinearLayout.LayoutParams(-1, -2))
        content.addView(bioCard, LinearLayout.LayoutParams(-1, -2))
        content.addView(gap(14))

        // بطاقة تعديل كلمة المرور / رمز PIN
        val pinCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(card, 20f, border)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        pinCard.addView(label("🔑 تعديل كلمة المرور (رمز PIN)", 16f, textPrimary, bold = true))
        pinCard.addView(gap(10))
        val oldPinInput = pinField("كلمة السر الحالية")
        val newPin1Input = pinField("كلمة السر الجديدة (٤ إلى ١٢ رقماً)")
        val newPin2Input = pinField("تأكيد كلمة السر الجديدة")
        pinCard.addView(oldPinInput, LinearLayout.LayoutParams(-1, -2))
        pinCard.addView(gap(8))
        pinCard.addView(newPin1Input, LinearLayout.LayoutParams(-1, -2))
        pinCard.addView(gap(8))
        pinCard.addView(newPin2Input, LinearLayout.LayoutParams(-1, -2))
        pinCard.addView(gap(12))
        pinCard.addView(button("حفظ كلمة المرور الجديدة", true, accent) {
            val oldP = oldPinInput.text.toString().trim()
            val n1 = newPin1Input.text.toString().trim()
            val n2 = newPin2Input.text.toString().trim()
            when {
                lockoutRemainingMs() > 0 -> toast("يرجى الانتظار قليلاً قبل المحاولة")
                !checkPin(oldP) -> {
                    registerFail()
                    toast("كلمة السر الحالية غير صحيحة")
                }
                n1.length < 4 -> toast("كلمة السر الجديدة يجب أن تكون ٤ أرقام على الأقل")
                n1 != n2 -> toast("كلمتا السر الجديدتان غير متطابقتين")
                else -> {
                    storePin(n1)
                    oldPinInput.setText("")
                    newPin1Input.setText("")
                    newPin2Input.setText("")
                    toast("تم تحديث كلمة المرور بنجاح 🔒")
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(pinCard, LinearLayout.LayoutParams(-1, -2))
        content.addView(gap(14))

        // بطاقة تحديث سؤال الأمان الاحتياطي
        val qCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(card, 20f, border)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        var chosenQ = prefs.getString("q_text", QUESTIONS[0]) ?: QUESTIONS[0]
        qCard.addView(label("❓ تحديث سؤال الأمان لاسترجاع كلمة السر", 16f, textPrimary, bold = true))
        qCard.addView(gap(10))
        val curPinForQ = pinField("أدخل كلمة السر الحالية للتأكيد")
        val qSelector = TextView(this).apply {
            text = chosenQ + "  ▾"
            textSize = 14.5f
            setTextColor(accentLight)
            background = roundBg(bg, 14f, border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                AlertDialog.Builder(this@VaultActivity)
                    .setTitle("اختر سؤال الأمان")
                    .setItems(QUESTIONS) { _, w ->
                        chosenQ = QUESTIONS[w]
                        text = chosenQ + "  ▾"
                    }
                    .show()
            }
        }
        val newAnsInput = textField("الإجابة الجديدة لسؤال الأمان")
        qCard.addView(curPinForQ, LinearLayout.LayoutParams(-1, -2))
        qCard.addView(gap(8))
        qCard.addView(qSelector, LinearLayout.LayoutParams(-1, -2))
        qCard.addView(gap(8))
        qCard.addView(newAnsInput, LinearLayout.LayoutParams(-1, -2))
        qCard.addView(gap(12))
        qCard.addView(button("تحديث سؤال الأمان", false) {
            when {
                !checkPin(curPinForQ.text.toString().trim()) -> toast("كلمة السر الحالية غير صحيحة")
                normalizeAnswer(newAnsInput.text.toString()).length < 2 -> toast("يرجى كتابة إجابة واضحة")
                else -> {
                    storeAnswer(chosenQ, newAnsInput.text.toString())
                    curPinForQ.setText("")
                    newAnsInput.setText("")
                    toast("تم تحديث سؤال الأمان بنجاح")
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(qCard, LinearLayout.LayoutParams(-1, -2))

        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreen(page)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // =========================================================== بعد فتح القفل
    private fun onUnlocked() {
        unlocked = true
        if (pendingUris.isNotEmpty()) {
            val u = ArrayList(pendingUris)
            val n = ArrayList(pendingNames)
            val m = ArrayList(pendingMimes)
            pendingUris = ArrayList()
            pendingNames = ArrayList()
            pendingMimes = ArrayList()
            showMain()
            importUris(u, n, m, deleteOriginals = true)
        } else if (openSecurityAfterUnlock) {
            openSecurityAfterUnlock = false
            showSecuritySettingsPage()
        } else {
            showMain()
        }
    }

    // ================================================================== الشاشة الرئيسية للخزنة
    private fun showMain() {
        securityPageOpen = false
        selected.clear()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // الشريط العلوي
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(34), dp(10), dp(8))
        }
        bar.addView(iconButton("✕") { finish() })

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val title = label("الخزنة الخاصة 🔒", 19f, textPrimary, bold = true)
        val sub = label("مشفّرة بـ AES-256 • معزولة عن المعرض", 11.5f, accentLight)
        titleView = title
        statsSubtitleView = sub
        titleCol.addView(title)
        titleCol.addView(sub)
        bar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        bar.addView(iconButton("🔑") { showSecuritySettingsPage() })
        bar.addView(iconButton("＋") { pickFromDevice() })
        bar.addView(iconButton("⋮") { showVaultMenu() })
        page.addView(bar, LinearLayout.LayoutParams(-1, -2))

        // شريط الفلترة السريعة (الكل / الصور / الفيديوهات / إعدادات الأمان)
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(10))
        }
        val filterLabels = arrayOf("الكل", "الصور", "الفيديوهات")
        val filterBtns = ArrayList<TextView>()
        fun updateFilterPills() {
            for (i in filterBtns.indices) {
                val active = (filterMode == i)
                filterBtns[i].background = if (active) roundBg(accent, 14f) else roundBg(card, 14f, border)
                filterBtns[i].setTextColor(if (active) Color.WHITE else textSecondary)
            }
        }
        for (i in filterLabels.indices) {
            val b = TextView(this).apply {
                text = filterLabels[i]
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener {
                    filterMode = i
                    updateFilterPills()
                    applyEntriesFilter()
                }
            }
            filterBtns.add(b)
            filterRow.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        updateFilterPills()
        filterRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val secQuickBtn = TextView(this).apply {
            text = "إعدادات كلمة السر والبصمة ⚙️"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentLight)
            background = roundBg(card, 14f, border)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { showSecuritySettingsPage() }
        }
        filterRow.addView(secQuickBtn)
        page.addView(filterRow, LinearLayout.LayoutParams(-1, -2))

        val frame = FrameLayout(this)
        val grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(3)
            verticalSpacing = dp(3)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setBackgroundColor(bg)
            setPadding(dp(4), 0, dp(4), dp(16))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
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

        val emptyCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        emptyCol.addView(lockBadge())
        emptyCol.addView(gap(14))
        emptyCol.addView(label("الخزنة الخاصة فارغة حالياً", 17f, textPrimary, bold = true, center = true))
        emptyCol.addView(gap(6))
        emptyCol.addView(
            label(
                "يمكنك تحديد أي صور أو فيديوهات من المعرض والضغط على «للخزنة» لإخفائها وتشفيرها هنا، أو إضافتها مباشرة بالزر أدناه.",
                13.5f, textSecondary, center = true
            )
        )
        emptyCol.addView(gap(16))
        emptyCol.addView(button("＋ إضافة صور أو فيديو للخزنة", true, accent) { pickFromDevice() })
        emptyView = emptyCol
        frame.addView(emptyCol, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        page.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val sel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(card)
            setPadding(dp(12), dp(10), dp(12), dp(14))
            visibility = View.GONE
        }
        sel.addView(
            button("استعادة للمعرض", true, accent) { restoreSelected() },
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
            textSize = 20f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }

    private fun reloadEntries() {
        io.execute {
            val list = PrivateVault.list(this)
            handler.post {
                if (isFinishing) return@post
                allEntries = list
                applyEntriesFilter()
            }
        }
    }

    private fun applyEntriesFilter() {
        entries = when (filterMode) {
            1 -> allEntries.filter { !it.isVideo }
            2 -> allEntries.filter { it.isVideo }
            else -> allEntries
        }
        gridAdapter?.notifyDataSetChanged()
        emptyView?.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val pCount = allEntries.count { !it.isVideo }
        val vCount = allEntries.count { it.isVideo }
        statsSubtitleView?.text = "$pCount صورة • $vCount فيديو • مشفّرة بـ AES-256"
        refreshSelectionUi()
    }

    private fun toggleSelect(id: String) {
        if (!selected.add(id)) selected.remove(id)
        refreshSelectionUi()
        gridAdapter?.notifyDataSetChanged()
    }

    private fun refreshSelectionUi() {
        selectionBar?.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        titleView?.text = if (selected.isEmpty()) {
            "الخزنة الخاصة (${entries.size})"
        } else {
            "تم تحديد ${selected.size}"
        }
        gridAdapter?.notifyDataSetChanged()
    }

    private fun showVaultMenu() {
        val items = arrayOf(
            "صفحة تعديل كلمة السر والبصمة وسؤال الأمان",
            "إضافة صور أو فيديوهات إلى الخزنة",
            "تحديد كل العناصر",
            "قفل الخزنة الآن"
        )
        AlertDialog.Builder(this)
            .setTitle("خيارات الخزنة الخاصة")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSecuritySettingsPage()
                    1 -> pickFromDevice()
                    2 -> {
                        selected.clear()
                        entries.forEach { selected.add(it.id) }
                        refreshSelectionUi()
                    }
                    3 -> finish()
                }
            }
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
                    text = "▶ فيديو"
                    textSize = 10.5f
                    setTextColor(Color.WHITE)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = roundBg(0xAA000000.toInt(), 8f)
                }
                check = TextView(this@VaultActivity).apply {
                    gravity = Gravity.CENTER
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }
                cell.addView(img, FrameLayout.LayoutParams(-1, -1))
                cell.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(6)
                    bottomMargin = dp(6)
                })
                cell.addView(check, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(6)
                    rightMargin = dp(6)
                })
            }
            val size = (resources.displayMetrics.widthPixels - dp(16)) / 3
            cell.layoutParams = AbsListView.LayoutParams(size, size)

            val e = entries[position]
            badge.visibility = if (e.isVideo) View.VISIBLE else View.GONE
            val isSel = selected.contains(e.id)
            check.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            check.text = if (isSel) "✓" else ""
            check.background = roundBg(if (isSel) accent else 0x66000000, 11f, Color.WHITE)
            cell.setBackgroundColor(card)
            img.alpha = if (isSel) 0.65f else 1f

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
            toast("تعذر فتح منتقي الملفات")
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
            if (resultCode == RESULT_OK) toast("تم إخفاء وحذف النسخ الأصلية من المعرض العام")
            else toast("تم حفظ النسخ المشفّرة في الخزنة")
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
            .setMessage("جاري تشفير الملفات بـ AES-256 ونقلها إلى الخزنة الخاصة...")
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
                if (moved.size == uris.size) toast("تم تأمين ونقل ${moved.size} عنصر للخزنة 🔒")
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
                if (failed > 0) toast("$failed ملف ما زال ظاهراً في المعرض، يمكنك حذفه يدوياً")
            }
        } catch (e: Throwable) {
            busyExternal = false
            toast("تم حفظ الملفات في الخزنة المشفّرة")
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
                toast(if (ok == list.size) "تمت استعادة العناصر إلى المعرض" else "تمت استعادة $ok من ${list.size}")
                selected.clear()
                reloadEntries()
            }
        }
    }

    private fun confirmDeleteSelected() {
        val list = selectedEntries()
        if (list.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("حذف نهائي من الخزنة")
            .setMessage("سيتم إتلاف ${list.size} عنصر مشفّر نهائياً ولا يمكن استرجاعها.")
            .setPositiveButton("حذف نهائي") { _, _ ->
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
                if (abs(dx) > dp(80) && abs(vx) > 400) {
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

        val spinner = label("جاري فك التشفير اللحظي...", 14f, textSecondary, center = true)
        layer.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        io.execute {
            if (e.isVideo) {
                val f = PrivateVault.exportToTemp(this, e)
                handler.post {
                    if (viewerLayer !== layer || viewerIndex != rawIndex) return@post
                    layer.removeView(spinner)
                    if (f == null) {
                        spinner.text = "تعذر تشغيل الفيديو المشفّر"
                        layer.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                        addViewerChrome(layer, e)
                        return@post
                    }
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
            label("${viewerIndex + 1} / ${entries.size} • ${e.name}", 14f, Color.WHITE, center = true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        top.addView(iconButton("↩") {
            closeViewer()
            selected.clear()
            selected.add(e.id)
            restoreSelected()
        })
        top.addView(iconButton("🗑") {
            closeViewer()
            selected.clear()
            selected.add(e.id)
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
