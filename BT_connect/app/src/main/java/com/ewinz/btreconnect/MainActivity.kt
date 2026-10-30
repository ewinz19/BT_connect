package com.ewinz.btreconnect

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var tvStatus: TextView

    companion object { private const val REQ_BT_PERMS = 100 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etName   = findViewById(R.id.etName)
        tvStatus = findViewById(R.id.tvStatus)

        PrefsHelper.getName(this)?.let {
            etName.setText(it)
            tvStatus.text = "Nama tersimpan: \"$it\"\nMAC terakhir: ${PrefsHelper.getLastMac(this) ?: "-"}"
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(this, "Nama tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PrefsHelper.saveName(this, name)
            tvStatus.text = "Nama tersimpan: \"$name\""
            Toast.makeText(this, "Tersimpan", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            if (ensurePermissions()) startReconnectService()
        }

        findViewById<Button>(R.id.btnAddWidget).setOnClickListener { requestPinWidget() }

        ensurePermissions()
    }

    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.BLUETOOTH_CONNECT
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_BT_PERMS)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERMS) {
            val ok = grantResults.isNotEmpty() &&
                     grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(this, if (ok) "Izin diberikan" else "Izin wajib diberikan",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun startReconnectService() {
        if (PrefsHelper.getName(this).isNullOrBlank()) {
            Toast.makeText(this, "Simpan nama dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, ReconnectService::class.java).apply {
            action = ReconnectService.ACTION_RECONNECT
        }
        ContextCompat.startForegroundService(this, intent)
        tvStatus.text = "Proses berjalan, lihat notifikasi..."
        Toast.makeText(this, "Proses berjalan", Toast.LENGTH_SHORT).show()
    }

    private fun requestPinWidget() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Butuh Android 8+", Toast.LENGTH_SHORT).show()
            return
        }
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr == null || !mgr.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, "Launcher tidak mendukung", Toast.LENGTH_SHORT).show()
            return
        }
        mgr.requestPinAppWidget(ComponentName(this, ReconnectWidgetProvider::class.java), null, null)
    }
}