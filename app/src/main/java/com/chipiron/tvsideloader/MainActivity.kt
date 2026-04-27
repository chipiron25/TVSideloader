package com.chipiron.tvsideloader

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Paso 1 — Servicio de cursor (Accesibilidad)
        findViewById<Button>(R.id.btn_enable_cursor).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Paso 2 — Ventana flotante
        findViewById<Button>(R.id.btn_overlay_perm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
        }

        // Paso 3 — Instalar APKs desconocidos
        findViewById<Button>(R.id.btn_install_perm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                ))
            } else {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        }

        // Paso 4 — WRITE_SETTINGS para rotación
        findViewById<Button>(R.id.btn_rotation_perm).setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")
            ))
        }

        // Botón principal: Instalador
        findViewById<Button>(R.id.btn_installer).setOnClickListener {
            startActivity(Intent(this, InstallerActivity::class.java))
        }

        findViewById<Button>(R.id.btn_enable_cursor).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val cursorOk  = isCursorServiceEnabled()
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                            Settings.canDrawOverlays(this) else true
        val installOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            packageManager.canRequestPackageInstalls() else true
        val rotationOk = Settings.System.canWrite(this)

        setStatus(R.id.tv_cursor_status, R.id.btn_enable_cursor,
            cursorOk,
            "Cursor virtual: ACTIVO",
            "Cursor virtual: INACTIVO  ← activa esto primero",
            "Ver ajuste", "ACTIVAR AHORA")

        setStatus(R.id.tv_overlay_status, R.id.btn_overlay_perm,
            overlayOk,
            "Ventana flotante: OK",
            "Ventana flotante: falta permiso",
            "Gestionar", "Conceder permiso")

        setStatus(R.id.tv_install_status, R.id.btn_install_perm,
            installOk,
            "Instalar APKs: OK",
            "Instalar APKs: falta permiso",
            "Gestionar", "Conceder permiso")

        setStatus(R.id.tv_rotation_status, R.id.btn_rotation_perm,
            rotationOk,
            "Rotacion de pantalla: OK",
            "Rotacion de pantalla: falta permiso",
            "Gestionar", "Conceder permiso")
    }

    private fun setStatus(
        textId: Int, btnId: Int,
        ok: Boolean,
        okText: String, failText: String,
        okBtnText: String, failBtnText: String
    ) {
        val tv  = findViewById<TextView>(textId)
        val btn = findViewById<Button>(btnId)

        tv.text = if (ok) okText else failText
        tv.setTextColor(ContextCompat.getColor(this,
            if (ok) R.color.status_ok else R.color.status_warn))
        btn.text = if (ok) okBtnText else failBtnText
    }

    private fun isCursorServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name.contains("CursorAccessibilityService")
        }
    }
}
