package com.chipiron.tvsideloader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class InstallerActivity : AppCompatActivity() {

    // Tabs
    private lateinit var tabBrowse: Button
    private lateinit var tabDownload: Button
    private lateinit var panelBrowse: View
    private lateinit var panelDownload: View

    // Browse
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvBrowseStatus: TextView

    // Download
    private lateinit var etUrl: EditText
    private lateinit var etFilename: EditText
    private lateinit var btnDownload: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvDlStatus: TextView

    private var isDownloading = false
    private val apkFiles = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_installer)

        tabBrowse   = findViewById(R.id.tab_browse)
        tabDownload = findViewById(R.id.tab_download)
        panelBrowse = findViewById(R.id.panel_browse)
        panelDownload = findViewById(R.id.panel_download)

        recyclerView  = findViewById(R.id.recycler_view)
        tvBrowseStatus = findViewById(R.id.tv_browse_status)

        etUrl       = findViewById(R.id.et_url)
        etFilename  = findViewById(R.id.et_filename)
        btnDownload = findViewById(R.id.btn_download)
        progressBar = findViewById(R.id.progress_bar)
        tvProgress  = findViewById(R.id.tv_progress)
        tvDlStatus  = findViewById(R.id.tv_dl_status)

        recyclerView.layoutManager = LinearLayoutManager(this)

        tabBrowse.setOnClickListener   { showTab(browse = true) }
        tabDownload.setOnClickListener { showTab(browse = false) }

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { scanForApks() }

        btnDownload.setOnClickListener {
            if (isDownloading) cancelDownload() else startDownload()
        }

        showTab(browse = true)
        scanForApks()
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    private fun showTab(browse: Boolean) {
        panelBrowse.visibility   = if (browse) View.VISIBLE else View.GONE
        panelDownload.visibility = if (browse) View.GONE    else View.VISIBLE
        tabBrowse.alpha   = if (browse) 1f else 0.5f
        tabDownload.alpha = if (browse) 0.5f else 1f
        if (!browse) etUrl.requestFocus()
        else recyclerView.requestFocus()
    }

    // ── Browse: escanear almacenamiento ───────────────────────────────────────

    private fun scanForApks() {
        apkFiles.clear()
        tvBrowseStatus.text = "Buscando archivos APK..."
        recyclerView.adapter = null

        Thread {
            // Almacenamiento principal
            scanDir(Environment.getExternalStorageDirectory())
            // Carpeta Downloads pública
            scanDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            // USBs y tarjetas SD en /storage/
            File("/storage").listFiles()?.forEach { mount ->
                if (mount.name != "emulated" && mount.name != "self") scanDir(mount)
            }
            // Carpeta privada de la app (donde guardamos descargas)
            getExternalFilesDir(null)?.let { scanDir(it) }

            apkFiles.sortByDescending { it.lastModified() }

            runOnUiThread {
                tvBrowseStatus.text = if (apkFiles.isEmpty())
                    "No se encontraron APKs.\nConecta un USB con APKs o usa la pestaña Descargar."
                else "${apkFiles.size} archivo(s) APK encontrado(s) — Selecciona uno para instalarlo"

                if (apkFiles.isNotEmpty()) {
                    recyclerView.adapter = ApkAdapter(apkFiles) { installApk(it) }
                    recyclerView.requestFocus()
                }
            }
        }.start()
    }

    private fun scanDir(dir: File) {
        try {
            if (!dir.exists() || !dir.canRead()) return
            dir.listFiles()?.forEach { f ->
                when {
                    f.isFile && f.extension.lowercase() == "apk" -> apkFiles.add(f)
                    f.isDirectory && !f.name.startsWith(".") &&
                    f.name !in setOf("Android", "data", "obb", "proc", "sys") -> scanDir(f)
                }
            }
        } catch (_: Exception) { }
    }

    // ── Download: descargar APK desde URL ────────────────────────────────────

    private fun startDownload() {
        val url = etUrl.text.toString().trim()
        if (!url.startsWith("http")) { tvDlStatus.text = "URL no válida"; return }

        isDownloading = true
        btnDownload.text = "Cancelar descarga"
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        tvProgress.visibility = View.VISIBLE
        tvProgress.text = "Conectando..."
        tvDlStatus.text = ""

        Thread {
            try {
                // Seguir redirecciones
                var finalUrl = url
                var conn: HttpURLConnection
                var redirects = 0
                while (true) {
                    conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 30_000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "TVSideloader/2.0")
                        connect()
                    }
                    val code = conn.responseCode
                    if (code in listOf(301,302,303,307,308) && redirects++ < 8) {
                        finalUrl = conn.getHeaderField("Location") ?: break
                        conn.disconnect()
                    } else break
                }

                val total = conn.contentLengthLong
                val name = etFilename.text.toString().trim().let {
                    when { it.isNotEmpty() -> if (it.endsWith(".apk")) it else "$it.apk"
                           else -> finalUrl.substringAfterLast("/")
                               .substringBefore("?")
                               .takeIf { n -> n.endsWith(".apk") }
                               ?: "descarga_${System.currentTimeMillis()}.apk"
                    }
                }

                val outFile = File(getExternalFilesDir(null), name)

                runOnUiThread {
                    progressBar.isIndeterminate = total <= 0
                    progressBar.max = 100
                    tvProgress.text = "Descargando $name..."
                }

                val input = conn.inputStream
                val out   = FileOutputStream(outFile)
                val buf   = ByteArray(8_192)
                var downloaded = 0L
                var n: Int

                while (input.read(buf).also { n = it } != -1 && isDownloading) {
                    out.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt()
                        runOnUiThread {
                            progressBar.progress = pct
                            tvProgress.text = "${fmtSize(downloaded)} / ${fmtSize(total)}  ($pct%)"
                        }
                    }
                }
                out.flush(); out.close(); input.close(); conn.disconnect()

                if (!isDownloading) { outFile.delete(); return@Thread }

                runOnUiThread {
                    isDownloading = false
                    btnDownload.text = "Descargar e instalar"
                    progressBar.visibility = View.GONE
                    tvProgress.text = "Descarga completa: $name"
                    tvDlStatus.text = "Lanzando instalador del sistema..."
                    installApk(outFile)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isDownloading = false
                    btnDownload.text = "Descargar e instalar"
                    progressBar.visibility = View.GONE
                    tvProgress.text = ""
                    tvDlStatus.text = "Error: ${e.message}"
                }
            }
        }.start()
    }

    private fun cancelDownload() {
        isDownloading = false
        btnDownload.text = "Descargar e instalar"
        progressBar.visibility = View.GONE
        tvDlStatus.text = "Descarga cancelada"
    }

    // ── Instalar APK ──────────────────────────────────────────────────────────

    private fun installApk(file: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        else @Suppress("DEPRECATION") Uri.fromFile(file)

        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        })
    }

    private fun fmtSize(b: Long) = when {
        b < 1_024 -> "$b B"
        b < 1_048_576 -> "${b / 1_024} KB"
        else -> "${"%.1f".format(b / 1_048_576.0)} MB"
    }

    // ── RecyclerView Adapter ──────────────────────────────────────────────────

    inner class ApkAdapter(
        private val files: List<File>,
        private val onInstall: (File) -> Unit
    ) : RecyclerView.Adapter<ApkAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView  = v.findViewById(R.id.tv_file_name)
            val path: TextView  = v.findViewById(R.id.tv_file_path)
            val size: TextView  = v.findViewById(R.id.tv_file_size)
            val btn:  Button    = v.findViewById(R.id.btn_install)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_apk, parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val f = files[pos]
            h.name.text = f.name.removeSuffix(".apk")
            h.path.text = f.absolutePath
            h.size.text = fmtSize(f.length())
            h.btn.setOnClickListener  { onInstall(f) }
            h.itemView.setOnClickListener { onInstall(f) }
            h.itemView.setOnFocusChangeListener { v, focus ->
                v.animate().scaleX(if (focus) 1.03f else 1f)
                           .scaleY(if (focus) 1.03f else 1f)
                           .setDuration(100).start()
            }
        }

        override fun getItemCount() = files.size
    }
}
