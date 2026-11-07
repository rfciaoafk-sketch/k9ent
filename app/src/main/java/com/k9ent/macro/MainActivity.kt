package com.k9ent.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ====================== PENGATURAN ======================
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("k9ent", Context.MODE_PRIVATE)

    var opacity: Int
        get() = sp.getInt("op", 70)
        set(v) { sp.edit().putInt("op", v).apply() }

    var size: Int
        get() = sp.getInt("sz", 100)
        set(v) { sp.edit().putInt("sz", v).apply() }

    var ms: Int
        get() = sp.getInt("ms", 50).coerceAtLeast(MIN_MS)
        set(v) { sp.edit().putInt("ms", v.coerceAtLeast(MIN_MS)).apply() }

    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

    var orbX: Int get() = sp.getInt("ox", -1); set(v) { sp.edit().putInt("ox", v).apply() }
    var orbY: Int get() = sp.getInt("oy", -1); set(v) { sp.edit().putInt("oy", v).apply() }
    var tgtX: Int get() = sp.getInt("tx", -1); set(v) { sp.edit().putInt("tx", v).apply() }
    var tgtY: Int get() = sp.getInt("ty", -1); set(v) { sp.edit().putInt("ty", v).apply() }

    companion object {
        const val MIN_MS = 20
        const val MAX_MS = 120
        val THEMES = arrayOf("Default", "Merah", "Ungu", "Biru", "Oranye")
        val ACCENTS = intArrayOf(
            0xFF2FE6CF.toInt(), 0xFFFF4D5E.toInt(), 0xFFA66BFF.toInt(),
            0xFF3D9BFF.toInt(), 0xFFFF9A2F.toInt()
        )
    }
}

// ====================== LAYANAN KLIK ======================
class ClickService : AccessibilityService() {

    companion object {
        @Volatile var instance: ClickService? = null
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var orb: TextView? = null
    private var target: View? = null
    private var orbLp: WindowManager.LayoutParams? = null
    private var tgtLp: WindowManager.LayoutParams? = null

    var overlayVisible = false
        private set
    private var running = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() { stopClicking() }

    override fun onUnbind(intent: Intent?): Boolean {
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun accent() = Prefs.ACCENTS[prefs.theme.coerceIn(0, Prefs.ACCENTS.size - 1)]

    private fun newParams(x: Int, y: Int) = WindowManager.LayoutParams(
        dp(64f), dp(64f),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    private fun attachDrag(
        v: View, p: WindowManager.LayoutParams,
        onTap: (() -> Unit)?, save: (Int, Int) -> Unit
    ) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (abs(dx) + abs(dy) > dp(6f)) moved = true
                    if (moved) {
                        p.x = (ox + dx).toInt()
                        p.y = (oy + dy).toInt()
                        wm.updateViewLayout(v, p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) save(p.x, p.y) else onTap?.invoke()
                }
            }
            true
        }
    }

    fun showOverlay() {
        if (overlayVisible) return
        val dm = resources.displayMetrics

        val o = TextView(this).apply {
            text = "KLIK"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val t = View(this)

        val ol = newParams(
            if (prefs.orbX >= 0) prefs.orbX else dm.widthPixels - dp(90f),
            if (prefs.orbY >= 0) prefs.orbY else dm.heightPixels / 2
        )
        val tl = newParams(
            if (prefs.tgtX >= 0) prefs.tgtX else dm.widthPixels / 2 - dp(20f),
            if (prefs.tgtY >= 0) prefs.tgtY else dm.heightPixels / 2 - dp(20f)
        )
        orb = o; target = t; orbLp = ol; tgtLp = tl

        wm.addView(t, tl)
        wm.addView(o, ol)
        attachDrag(o, ol, { toggleClicking() }) { x, y -> prefs.orbX = x; prefs.orbY = y }
        attachDrag(t, tl, null) { x, y -> prefs.tgtX = x; prefs.tgtY = y }

        overlayVisible = true
        applySettings()
    }

    fun hideOverlay() {
        stopClicking()
        try { orb?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { target?.let { wm.removeView(it) } } catch (_: Exception) {}
        orb = null; target = null; orbLp = null; tgtLp = null
        overlayVisible = false
    }

    fun applySettings() {
        val o = orb ?: return
        val t = target ?: return
        val ol = orbLp ?: return
        val tl = tgtLp ?: return
        val s = prefs.size / 100f
        val a = prefs.opacity / 100f

        ol.width = dp(64f * s); ol.height = ol.width
        tl.width = dp(40f * s); tl.height = tl.width
        o.alpha = a; t.alpha = a
        o.textSize = 12f * s

        o.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) 0xFFD93636.toInt() else accent())
            setStroke(dp(2f), Color.WHITE)
        }
        t.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(3f), accent())
        }
        o.text = if (running) "STOP" else "KLIK"

        tl.flags = if (running) tl.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else tl.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()

        wm.updateViewLayout(o, ol)
        wm.updateViewLayout(t, tl)
    }

    private fun toggleClicking() { if (running) stopClicking() else startClicking() }

    private fun startClicking() {
        if (running || !overlayVisible) return
        running = true
        applySettings()
        tick()
    }

    private fun stopClicking() {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (overlayVisible) applySettings()
    }

    private fun tick() {
        if (!running) return
        val t = target ?: return
        val ms = prefs.ms.coerceAtLeast(Prefs.MIN_MS)
        val loc = IntArray(2)
        t.getLocationOnScreen(loc)
        val x = loc[0] + t.width / 2f
        val y = loc[1] + t.height / 2f
        val start = SystemClock.uptimeMillis()

        val path = Path().apply { moveTo(x, y) }
        val dur = (ms / 2L).coerceIn(1L, 10L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()

        val scheduleNext = {
            if (running) {
                val wait = (ms - (SystemClock.uptimeMillis() - start)).coerceAtLeast(0)
                handler.postDelayed({ tick() }, wait)
            }
        }

        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { scheduleNext() }
            override fun onCancelled(g: GestureDescription?) { scheduleNext() }
        }, null)

        if (!ok) handler.postDelayed({ tick() }, ms.toLong())
    }
}

// ====================== LAYAR UTAMA ======================
class MainActivity : Activity() {

    private lateinit var p: Prefs
    private lateinit var tvStatus: TextView
    private lateinit var tvSub: TextView
    private lateinit var btn: Button
    private val seekBars = mutableListOf<SeekBar>()
    private val valueViews = mutableListOf<TextView>()
    private var dens = 1f

    private fun dp(v: Int) = (v * dens).toInt()
    private fun accent() = Prefs.ACCENTS[p.theme.coerceIn(0, Prefs.ACCENTS.size - 1)]

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(horizontal: Boolean = false) = LinearLayout(this).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = GradientDrawable().apply {
            setColor(0xFF050A0A.toInt())
            setStroke(dp(2), 0xFF1F8F83.toInt())
            cornerRadius = dp(24).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) }
    }

    private fun slider(
        parent: LinearLayout, label: String, min: Int, max: Int,
        init: Int, unit: String, save: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(tv(label, 14f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP, 1f))
        val value = tv("$init$unit", 14f, accent(), true)
        row.addView(value)
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = init - min
            setPadding(dp(16), dp(8), dp(16), dp(16))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + min
                    value.text = "$v$unit"
                    if (fromUser) {
                        save(v)
                        ClickService.instance?.applySettings()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(sb, LinearLayout.LayoutParams(MATCH, WRAP))
        seekBars.add(sb)
        valueViews.add(value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        dens = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
        }

        root.addView(
            tv("K9-ENT", 40f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD_ITALIC)
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        root.addView(
            tv("GAME MODE", 12f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.4f
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )

        // Kartu status
        val st = card(true)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(tv("Macro Status", 14f, 0xFFAEB6B5.toInt()))
        tvStatus = tv("Inactive", 30f, Color.WHITE, true)
        left.addView(tvStatus)
        tvSub = tv("Aktifkan macro untuk mulai.", 12f, 0xFF9AA3A2.toInt())
        left.addView(tvSub)
        st.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        btn = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener { toggleLaunch() }
        }
        st.addView(btn, LinearLayout.LayoutParams(WRAP, dp(56)))
        root.addView(st)

        // Kartu pengaturan macro
        val mc = card()
        mc.addView(tv("Pengaturan Macro", 17f, Color.WHITE, true))
        slider(mc, "Opacity (transparansi overlay)", 20, 100, p.opacity, "%") { p.opacity = it }
        slider(mc, "Size (ukuran tombol)", 50, 130, p.size, "%") { p.size = it }
        slider(mc, "Ms per Click (kecepatan klik)", Prefs.MIN_MS, Prefs.MAX_MS, p.ms, " ms") {
            p.ms = it
            if (it == Prefs.MIN_MS) {
                Toast.makeText(this, "Batas tercepat: ${Prefs.MIN_MS} ms", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(mc)

        // Kartu tema
        val tc = card(true)
        tc.addView(tv("Tema (ubah tema tampilan)", 14f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP, 1f))
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.THEMES)
        sp.setSelection(p.theme)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                p.theme = pos
                applyTheme()
                ClickService.instance?.applySettings()
            }
            override fun onNothingSelected(a: AdapterView<*>?) {}
        }
        tc.addView(sp)
        root.addView(tc)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        })
        applyTheme()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun toggleLaunch() {
        val s = ClickService.instance
        if (s == null) {
            AlertDialog.Builder(this)
                .setTitle("Aktifkan layanan")
                .setMessage("Buka Aksesibilitas, pilih \"K9-ENT Macro\", lalu aktifkan. Setelah itu kembali ke aplikasi dan tekan Luncurkan.")
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        if (s.overlayVisible) s.hideOverlay() else s.showOverlay()
        refreshStatus()
    }

    private fun refreshStatus() {
        val active = ClickService.instance?.overlayVisible == true
        val c = accent()
        tvStatus.text = if (active) "Active" else "Inactive"
        tvStatus.setTextColor(if (active) c else Color.WHITE)
        tvSub.text = if (active) "Tap bulatan untuk mulai/stop klik." else "Aktifkan macro untuk mulai."
        btn.text = if (active) "■  Hentikan" else "▶  Luncurkan"
        btn.backgroundTintList = ColorStateList.valueOf(if (active) 0xFFD93636.toInt() else c)
    }

    private fun applyTheme() {
        val c = accent()
        for (sb in seekBars) {
            sb.progressTintList = ColorStateList.valueOf(c)
            sb.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        }
        for (v in valueViews) v.setTextColor(c)
        refreshStatus()
    }
}
