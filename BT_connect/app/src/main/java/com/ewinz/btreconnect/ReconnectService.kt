package com.ewinz.btreconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReconnectService : Service() {

    companion object {
        private const val TAG = "BTReconnect"

        const val ACTION_RECONNECT = "com.ewinz.btreconnect.ACTION_RECONNECT"
        const val ACTION_STOP      = "com.ewinz.btreconnect.ACTION_STOP"

        private const val TIMEOUT_REMOVE_BOND  = 15_000L
        private const val TIMEOUT_SCAN         = 30_000L
        private const val TIMEOUT_CREATE_BOND  = 30_000L

        private const val CHANNEL_ID = "bt_reconnect_channel"
        private const val NOTIF_ID   = 1001
    }

    private lateinit var adapter: BluetoothAdapter
    private var targetName: String? = null

    // Devices yang namanya match (bond lama) — perlu dihapus
    private val bondsToRemove = mutableSetOf<String>()
    private var scanStarted = false
    private var foundMac: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false

    // Timeouts
    private val removeBondTimeout = Runnable {
        Log.w(TAG, "Timeout removeBond, lanjut scan")
        startScan()
    }
    private val scanTimeout = Runnable {
        if (scanStarted) {
            Log.w(TAG, "Timeout scan, gagal")
            try { adapter.cancelDiscovery() } catch (_: Exception) {}
            fail("Perangkat \"$targetName\" tidak ditemukan saat scan")
        }
    }
    private val createBondTimeout = Runnable {
        Log.w(TAG, "Timeout createBond")
        fail("Timeout saat pairing ulang")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = mgr.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelfSafely(); return START_NOT_STICKY }
            ACTION_RECONNECT, null -> {
                val name = PrefsHelper.getName(this)
                if (name.isNullOrBlank()) {
                    Log.e(TAG, "Nama target kosong, batal")
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                startForeground(NOTIF_ID, buildNotification("Mencari \"$name\"..."))
                beginReconnect(name)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { if (adapter.isDiscovering) adapter.cancelDiscovery() } catch (_: Exception) {}
        unregisterReceivers()
        super.onDestroy()
    }

    // ============================================================
    // TAHAP 1: HAPUS BOND LAMA (yang namanya match)
    // ============================================================

    private fun beginReconnect(name: String) {
        if (adapter == null) { fail("Adapter Bluetooth tidak tersedia"); return }
        if (!adapter.isEnabled) { fail("Bluetooth mati. Nyalakan dulu."); return }
        if (!hasConnectPermission()) { fail("Izin BLUETOOTH_CONNECT belum ada"); return }

        targetName = name
        registerBondReceiver()

        // Kumpulkan bond lama yang namanya match
        val bonded = try { adapter.bondedDevices } catch (_: SecurityException) { null }
        bondsToRemove.clear()
        bonded?.forEach { dev ->
            val devName = try { dev.name } catch (_: SecurityException) { null }
            if (devName != null && devName.equals(name, ignoreCase = true)) {
                bondsToRemove += dev.address
            }
        }

        Log.i(TAG, "Bond lama yang match \"$name\": ${bondsToRemove.size} device")

        if (bondsToRemove.isEmpty()) {
            // Tidak ada bond lama → langsung scan
            startScan()
            return
        }

        // Hapus semua bond yang match
        updateNotification("Menghapus ikatan lama (${bondsToRemove.size} perangkat)...")
        var anyFail = false
        for (mac in bondsToRemove.toList()) {
            val dev = try { adapter.getRemoteDevice(mac) } catch (_: Exception) { null }
            if (dev == null) { bondsToRemove.remove(mac); continue }
            if (!invokeRemoveBond(dev)) {
                Log.w(TAG, "removeBond gagal untuk $mac")
                bondsToRemove.remove(mac)
                anyFail = true
            }
        }

        if (bondsToRemove.isEmpty()) {
            // Semua gagal di-remove → tetap lanjut scan
            Log.w(TAG, "Semua removeBond gagal, lanjut scan anyway")
            startScan()
            return
        }

        // Pasang timeout, tunggu broadcast BOND_NONE
        handler.postDelayed(removeBondTimeout, TIMEOUT_REMOVE_BOND)
    }

    // ============================================================
    // TAHAP 2: SCAN UNTUK CARI MAC BARU
    // ============================================================

    private fun startScan() {
        handler.removeCallbacks(removeBondTimeout)
        if (scanStarted) return
        scanStarted = true
        foundMac = null

        registerScanReceiver()

        updateNotification("Scan mencari \"$targetName\"...")
        Log.i(TAG, "Mulai scan untuk \"$targetName\"")

        try {
            if (!adapter.startDiscovery()) {
                fail("startDiscovery() gagal")
                return
            }
        } catch (e: SecurityException) {
            fail("Izin scan ditolak")
            return
        }

        handler.postDelayed(scanTimeout, TIMEOUT_SCAN)
    }

    // ============================================================
    // TAHAP 3: PAIRING ULANG DENGAN MAC BARU
    // ============================================================

    private fun startCreateBond(mac: String) {
        handler.removeCallbacks(scanTimeout)
        if (scanStarted) {
            try { adapter.cancelDiscovery() } catch (_: Exception) {}
            scanStarted = false
        }

        val dev = try { adapter.getRemoteDevice(mac) } catch (_: Exception) { null }
        if (dev == null) { fail("MAC tidak valid: $mac"); return }

        PrefsHelper.saveLastMac(this, mac)
        updateNotification("Pairing ke $mac...")

        if (invokeCreateBond(dev)) {
            handler.postDelayed(createBondTimeout, TIMEOUT_CREATE_BOND)
        } else {
            fail("createBond() gagal dipanggil")
        }
    }

    private fun onBonded(device: BluetoothDevice) {
        handler.removeCallbacks(createBondTimeout)
        handler.removeCallbacks(removeBondTimeout)
        handler.removeCallbacks(scanTimeout)

        updateNotification("Pairing sukses. Menghubungkan profil...")
        val connected = connectAnyProfile(device)
        Log.i(TAG, "Connect profil: $connected")

        updateNotification("Terhubung ke ${device.address}")
        handler.postDelayed({ stopSelfSafely() }, 2000)
    }

    // ============================================================
    // RECEIVER: BOND STATE + SCAN FOUND
    // ============================================================

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = getDeviceExtra(intent) ?: return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            Log.i(TAG, "Bond ${device.address} (${device.name}) -> $state")

            val address = device.address

            // === Fase hapus bond ===
            if (address in bondsToRemove && state == BluetoothDevice.BOND_NONE) {
                bondsToRemove.remove(address)
                Log.i(TAG, "Bond $address dihapus. Sisa: ${bondsToRemove.size}")
                if (bondsToRemove.isEmpty()) {
                    handler.removeCallbacks(removeBondTimeout)
                    startScan()
                }
                return
            }

            // === Fase pairing ===
            if (address == foundMac) {
                when (state) {
                    BluetoothDevice.BOND_BONDED -> onBonded(device)
                    BluetoothDevice.BOND_NONE -> fail("Pairing ditolak/gagal")
                }
            }
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = getDeviceExtra(intent) ?: return
                    val name = try { device.name } catch (_: SecurityException) { null }
                    Log.d(TAG, "Found: ${device.address} name=$name")
                    if (name != null && name.equals(targetName, ignoreCase = true)) {
                        Log.i(TAG, "MATCH! $name -> ${device.address}")
                        foundMac = device.address
                        startCreateBond(device.address)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanStarted = false
                    if (foundMac == null) {
                        fail("Scan selesai, \"$targetName\" tidak ditemukan")
                    }
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (receiverRegistered) return
        val f = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiverSafe(bondReceiver, f)
    }

    private fun registerScanReceiver() {
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiverSafe(scanReceiver, f)
        receiverRegistered = true
    }

    private fun registerReceiverSafe(r: BroadcastReceiver, f: IntentFilter) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(r, f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver error", e)
        }
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        receiverRegistered = false
    }

    private fun getDeviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    // ============================================================
    // REFLECTION + PROFILE
    // ============================================================

    private fun invokeRemoveBond(device: BluetoothDevice): Boolean {
        return try {
            val m: Method = device.javaClass.getMethod("removeBond")
            (m.invoke(device) as? Boolean) ?: false
        } catch (e: Exception) { Log.e(TAG, "removeBond error", e); false }
    }

    private fun invokeCreateBond(device: BluetoothDevice): Boolean {
        return try {
            val m: Method = device.javaClass.getMethod("createBond")
            (m.invoke(device) as? Boolean) ?: false
        } catch (e: Exception) { Log.e(TAG, "createBond error", e); false }
    }

    private fun connectAnyProfile(device: BluetoothDevice): Boolean {
        if (!hasConnectPermission()) return false
        var ok = tryConnectProfile(device, BluetoothProfile.A2DP, "connect")
        ok = tryConnectProfile(device, BluetoothProfile.HEADSET, "connect") || ok
        return ok
    }

    private fun tryConnectProfile(device: BluetoothDevice, profile: Int, methodName: String): Boolean {
        var connected = false
        val latch = CountDownLatch(1)
        try {
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile?) {
                    try {
                        if (proxy != null) {
                            val m = proxy.javaClass.getMethod(methodName, BluetoothDevice::class.java)
                            connected = (m.invoke(proxy, device) as? Boolean) ?: false
                        }
                    } catch (_: Exception) {}
                    finally {
                        try { adapter.closeProfileProxy(p, proxy) } catch (_: Exception) {}
                        latch.countDown()
                    }
                }
                override fun onServiceDisconnected(p: Int) { latch.countDown() }
            }, profile)
            latch.await(3, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        return connected
    }

    // ============================================================
    // NOTIFIKASI & UTIL
    // ============================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Reconnect Bluetooth",
                        NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reconnect Bluetooth")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun fail(message: String) {
        Log.e(TAG, "FAILED: $message")
        updateNotification("Gagal: $message")
        handler.postDelayed({ stopSelfSafely() }, 3000)
    }

    private fun stopSelfSafely() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}