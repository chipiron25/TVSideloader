package com.chipiron.tvsideloader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * CursorAccessibilityService v2
 *
 * Dos funciones en uno:
 *   1. Cursor virtual — D-pad del mando → ratón táctil sobre cualquier app
 *   2. Botón flotante de rotación — fuerza landscape en apps portrait
 *
 * Controles del cursor:
 *   D-pad              → mover cursor
 *   OK                 → tap
 *   OK largo (600ms)   → long press
 *   Rebobinar (<<)     → scroll arriba
 *   Avanzar  (>>)      → scroll abajo
 *   Menú               → mostrar/ocultar cursor
 *
 * Control de rotación:
 *   Mueve el cursor sobre el botón rojo "⟳" (esquina superior derecha) y pulsa OK
 *   O: pulsa la tecla Info / Amarilla del mando para rotar directamente
 */
class CursorAccessibilityService : AccessibilityService() {

    // ── Estado cursor ────────────────────────────────────────────────────────

    private var cursorX = 0f
    private var cursorY = 0f
    private var screenW = 1920f
    private var screenH = 1080f

    private val BASE_SPEED = 8f
    private val MAX_SPEED  = 64f
    private val ACCEL_STEP = 4f

    private var dirX = 0f
    private var dirY = 0f
    private var currentSpeed = BASE_SPEED

    // ── Estado rotación ──────────────────────────────────────────────────────

    // 0=AUTO, 1=LANDSCAPE, 2=LANDSCAPE_REVERSE, 3=PORTRAIT
    private var rotationState = 0
    private val rotationLabels  = arrayOf("AUTO", "LAND 0", "LAND 180", "PORTRAIT")
    private val rotationUserRot = intArrayOf(0, 0, 2, 1)  // Surface.ROTATION_*
    private val rotationAutoRot = intArrayOf(1, 0, 0, 0)  // 1=auto, 0=locked

    // ── Overlay views ────────────────────────────────────────────────────────

    private var wm: WindowManager? = null
    private var cursorView: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var rotBtn: RotationButtonView? = null
    private var rotParams: WindowManager.LayoutParams? = null
    private var cursorVisible = true

    // ── Handler ──────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())

    private val moveTick = object : Runnable {
        override fun run() {
            if (dirX == 0f && dirY == 0f) return
            currentSpeed = (currentSpeed + ACCEL_STEP).coerceAtMost(MAX_SPEED)
            cursorX = (cursorX + dirX * currentSpeed).coerceIn(0f, screenW - 1f)
            cursorY = (cursorY + dirY * currentSpeed).coerceIn(0f, screenH - 1f)
            updateCursorPosition()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val okDownTime  = longArrayOf(0L)
    private var isLongPress = false

    // ── Ciclo de vida ────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels.toFloat()
        screenH = metrics.heightPixels.toFloat()
        cursorX = screenW / 2f
        cursorY = screenH / 2f

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addCursorOverlay()
        addRotationButton()
    }

    override fun onDestroy() {
        removeCursorOverlay()
        removeRotationButton()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { handler.removeCallbacksAndMessages(null) }

    // ── Teclas del mando ─────────────────────────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> { handleDpad(event); true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> { handleOk(event); true }

            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) dispatchScroll(up = true); true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) dispatchScroll(up = false); true
            }

            // Menú: toggle visibilidad cursor
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                if (event.action == KeyEvent.ACTION_DOWN) toggleCursorVisibility(); true
            }

            // Tecla Info / Amarilla del mando: rotar directamente
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                if (event.action == KeyEvent.ACTION_DOWN) cycleRotation(); true
            }

            else -> false
        }
    }

    // ── D-pad ─────────────────────────────────────────────────────────────────

    private fun handleDpad(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val nx = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT  -> -1f
                    KeyEvent.KEYCODE_DPAD_RIGHT ->  1f
                    else -> 0f
                }
                val ny = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP   -> -1f
                    KeyEvent.KEYCODE_DPAD_DOWN ->  1f
                    else -> 0f
                }
                if (nx != dirX || ny != dirY) {
                    dirX = nx; dirY = ny
                    currentSpeed = BASE_SPEED
                    handler.removeCallbacks(moveTick)
                    handler.post(moveTick)
                }
            }
            KeyEvent.ACTION_UP -> {
                dirX = 0f; dirY = 0f
                currentSpeed = BASE_SPEED
                handler.removeCallbacks(moveTick)
            }
        }
    }

    // ── OK / tap ──────────────────────────────────────────────────────────────

    private fun handleOk(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                okDownTime[0] = System.currentTimeMillis()
                isLongPress = false
                handler.postDelayed({
                    if (okDownTime[0] > 0) {
                        isLongPress = true
                        if (isCursorOverRotBtn()) cycleRotation()
                        else dispatchTap(longPress = true)
                    }
                }, 600L)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacksAndMessages(null)
                if (!isLongPress) {
                    if (isCursorOverRotBtn()) cycleRotation()
                    else dispatchTap(longPress = false)
                }
                okDownTime[0] = 0L
                isLongPress = false
            }
        }
    }

    /** ¿Está el cursor encima del botón de rotación? */
    private fun isCursorOverRotBtn(): Boolean {
        val p = rotParams ?: return false
        // El botón está en la esquina superior derecha: gravity END + offset
        val bx = (screenW - ROT_BTN_W - p.x).toFloat()
        val by = p.y.toFloat()
        return cursorX in bx..(bx + ROT_BTN_W) && cursorY in by..(by + ROT_BTN_H)
    }

    // ── Gestos táctiles ───────────────────────────────────────────────────────

    private fun dispatchTap(longPress: Boolean) {
        val path   = Path().apply { moveTo(cursorX, cursorY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, if (longPress) 800L else 50L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun dispatchScroll(up: Boolean) {
        val delta  = screenH * 0.35f
        val startY = (if (up) cursorY + delta else cursorY - delta).coerceIn(0f, screenH)
        val endY   = (if (up) cursorY - delta else cursorY + delta).coerceIn(0f, screenH)
        val path   = Path().apply { moveTo(cursorX, startY); lineTo(cursorX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 200L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ── Rotación de pantalla ──────────────────────────────────────────────────

    /**
     * Cicla: AUTO → LANDSCAPE → LANDSCAPE 180° → PORTRAIT → AUTO → ...
     *
     * Escribe en Settings.System (requiere permiso WRITE_SETTINGS).
     * El usuario lo concede desde TV Sideloader: Ajustes → Modificar ajustes del sistema.
     */
    private fun cycleRotation() {
        if (!Settings.System.canWrite(this)) {
            handler.post {
                Toast.makeText(
                    this,
                    "Abre TV Sideloader → Paso 4 para conceder permiso de rotación",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        rotationState = (rotationState + 1) % rotationLabels.size

        try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                rotationAutoRot[rotationState]
            )
            if (rotationAutoRot[rotationState] == 0) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.USER_ROTATION,
                    rotationUserRot[rotationState]
                )
            }
        } catch (e: Exception) {
            handler.post {
                Toast.makeText(this, "Error de rotación: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        rotBtn?.label = rotationLabels[rotationState]
        rotBtn?.invalidate()
    }

    // ── Overlay: cursor ───────────────────────────────────────────────────────

    private fun addCursorOverlay() {
        val p = overlayParams(CURSOR_SIZE, CURSOR_SIZE).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = (cursorX - CURSOR_SIZE / 2).toInt()
            it.y = (cursorY - CURSOR_SIZE / 2).toInt()
        }
        cursorParams = p
        cursorView   = CursorView(this)
        wm?.addView(cursorView, p)
    }

    private fun removeCursorOverlay() {
        cursorView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        cursorView = null
    }

    private fun toggleCursorVisibility() {
        cursorVisible = !cursorVisible
        cursorView?.visibility = if (cursorVisible) View.VISIBLE else View.GONE
    }

    private fun updateCursorPosition() {
        cursorParams?.let { p ->
            p.x = (cursorX - CURSOR_SIZE / 2).toInt()
            p.y = (cursorY - CURSOR_SIZE / 2).toInt()
            try { cursorView?.let { wm?.updateViewLayout(it, p) } } catch (_: Exception) {}
        }
    }

    // ── Overlay: botón de rotación ────────────────────────────────────────────

    private fun addRotationButton() {
        val p = overlayParams(ROT_BTN_W, ROT_BTN_H).also {
            it.gravity = Gravity.TOP or Gravity.END
            it.x = 24   // margen respecto al borde derecho
            it.y = 24
        }
        rotParams = p
        rotBtn    = RotationButtonView(this).also { it.label = rotationLabels[rotationState] }
        wm?.addView(rotBtn, p)
    }

    private fun removeRotationButton() {
        rotBtn?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        rotBtn = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    // ── Vistas ────────────────────────────────────────────────────────────────

    /** Flecha-cursor blanca con borde negro */
    inner class CursorView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val arrow = Path().apply {
                moveTo(0f, 0f); lineTo(0f, h * .75f); lineTo(w * .30f, h * .55f)
                lineTo(w * .45f, h * .90f); lineTo(w * .58f, h * .85f)
                lineTo(w * .43f, h * .50f); lineTo(w * .70f, h * .45f); close()
            }
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, border)
        }
    }

    /** Pastilla roja con icono ⟳, estado actual y hint "OK = rotar" */
    inner class RotationButtonView(ctx: Context) : View(ctx) {
        var label = "AUTO"

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6161628")
        }
        private val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E94560"); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val stateP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF7096"); textSize = 22f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val hintP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#707090"); textSize = 16f; textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            rect.set(3f, 3f, w - 3f, h - 3f)
            canvas.drawRoundRect(rect, 18f, 18f, bg)
            canvas.drawRoundRect(rect, 18f, 18f, borderP)
            canvas.drawText("⟳", w / 2, h * 0.40f, iconP)
            canvas.drawText(label, w / 2, h * 0.65f, stateP)
            canvas.drawText("OK = cambiar", w / 2, h * 0.85f, hintP)
        }
    }

    companion object {
        private const val CURSOR_SIZE = 64
        private const val ROT_BTN_W  = 200
        private const val ROT_BTN_H  = 110
        private const val TICK_MS    = 16L
    }
}
