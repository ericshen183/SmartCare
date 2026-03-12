package com.example.smartcare.services

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.smartcare.ble.*
import com.example.smartcare.cloud.FirebaseManager
import com.google.android.gms.location.*
import java.util.*

class GatewayService : Service() {

    private lateinit var bleManager: BleManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var timer: Timer? = null
    private var locationTimer: Timer? = null
    private lateinit var prefs: SharedPreferences
    private var lastRelayTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val firebaseManager by lazy { FirebaseManager.getInstance() }

    private var currentHr = 0
    private var currentLat = 0.0
    private var currentLng = 0.0
    private var currentIsFall = false
    private var currentSteps = 0
    private var currentDistance = 0
    private var isConnected = false
    private var connectionStateText = "Disconnected"

    private val binder = LocalBinder()
    private var dataListener: OnDataUpdateListener? = null

    interface OnDataUpdateListener {
        fun onDataUpdate(hr: Int, steps: Int, distance: Int, lat: Double, lng: Double, isFall: Boolean, connStatus: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): GatewayService = this@GatewayService
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = getSharedPreferences("smartcare_prefs", MODE_PRIVATE)

        bleManager = BleManager(
            context = this,
            onReady = {
                Log.d("Gateway", "Handshake Ready - Initializing Watch Configuration")
                isConnected = true
                connectionStateText = "Connected & Ready"

                // Sequential commands
                mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createHandshake()) }, 500)
                mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createTimeSync()) }, 1500)
                mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createUserInfoSync()) }, 2500)
                mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createHrIntervalSync(5)) }, 3500)
                mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createDynamicHrToggle(true)) }, 4500)
                mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createVitalsRealtime(true)) }, 5500)
                mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createManualHrRequest()) }, 6500)

                mainHandler.postDelayed({ startPolling() }, 8000)
                relayData()
            },
            onConnectionStateChanged = { connected ->
                isConnected = connected
                connectionStateText = if (connected) "Connected" else "Disconnected (Watch Offline)"
                if (!connected) {
                    currentHr = 0
                    showWatchDisconnectedNotification()
                }
                relayData()
            },
            onDataReceived = { update ->
                if (update.heartRate > 0) currentHr = update.heartRate
                if (update.steps > currentSteps) currentSteps = update.steps
                if (update.distance > 0) currentDistance = update.distance
                currentIsFall = update.isFallLikely
                
                val now = System.currentTimeMillis()
                if (now - lastRelayTime >= 2000 || update.isFallLikely) {
                    relayData()
                    lastRelayTime = now
                }
                
                if (update.isFallLikely) showFallNotification()
            }
        )

        startIndependentLocationTracking()
        
        val wearerName = prefs.getString("wearer_name", null)
        if (wearerName != null) {
            firebaseManager.listenForCommands(wearerName) { type, value ->
                handleRemoteCommand(type, value)
            }
        }
    }

    private fun handleRemoteCommand(type: String, value: Any) {
        when (type) {
            "notification" -> {
                val msg = value as? String ?: ""
                if (msg.isNotEmpty()) {
                    bleManager.sendCommand(MoyoungEncoder.createNotification(msg))
                }
            }
            "vitals_request" -> {
                bleManager.sendCommand(MoyoungEncoder.createManualHrRequest())
            }
        }
    }

    private fun startIndependentLocationTracking() {
        locationTimer?.cancel()
        locationTimer = Timer()
        locationTimer?.schedule(object : TimerTask() {
            override fun run() { updateFreshLocation() }
        }, 0, 15000)
    }

    private fun updateFreshLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                location?.let {
                    currentLat = it.latitude
                    currentLng = it.longitude
                    relayData()
                }
            }
    }

    private fun relayData() {
        mainHandler.post {
            dataListener?.onDataUpdate(currentHr, currentSteps, currentDistance, currentLat, currentLng, currentIsFall, connectionStateText)
        }

        val rawName = prefs.getString("wearer_name", "Unknown") ?: "Unknown"
        val wearerName = rawName.filter { it.isLetterOrDigit() }
        if (wearerName.isEmpty()) return

        firebaseManager.updateVitals(wearerName, currentHr, currentSteps, currentLat, currentLng, currentIsFall)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }

        val mac = prefs.getString("watch_mac", null)
        if (!mac.isNullOrEmpty()) {
            connectionStateText = "Connecting..."
            relayData()
            bleManager.connect(mac)
        }
        
        return START_STICKY
    }

    private fun startPolling() {
        timer?.cancel()
        timer = Timer()
        var cycle = 0
        timer?.schedule(object : TimerTask() {
            override fun run() {
                if (isConnected) {
                    when (cycle % 4) {
                        0 -> bleManager.sendCommand(MoyoungEncoder.getSummaryRequest())
                        1 -> bleManager.sendCommand(MoyoungEncoder.createVitalsRealtime(true))
                        2 -> bleManager.sendCommand(MoyoungEncoder.getStatusRequestAB())
                        3 -> bleManager.sendCommand(MoyoungEncoder.queryLastDynamicRate())
                    }
                    cycle++
                }
            }
        }, 0, 8000)
    }

    fun requestManualHr() {
        bleManager.sendCommand(MoyoungEncoder.createManualHrRequest())
    }

    fun sendWatchCommand(command: ByteArray) = bleManager.sendCommand(command)
    
    fun setOnDataUpdateListener(listener: OnDataUpdateListener?) { 
        this.dataListener = listener 
        listener?.onDataUpdate(currentHr, currentSteps, currentDistance, currentLat, currentLng, currentIsFall, connectionStateText)
    }

    private fun showWatchDisconnectedNotification() {
        val channelId = "watch_status_channel"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Watch Status", NotificationManager.IMPORTANCE_HIGH))
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚠️ WATCH DISCONNECTED")
            .setContentText("The wearer's smartwatch has gone offline.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try { manager.notify(3, notification) } catch (_: SecurityException) {}
    }

    private fun showFallNotification() {
        val channelId = "fall_alert_channel"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Fall Alerts", NotificationManager.IMPORTANCE_HIGH))
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚠️ FALL DETECTED")
            .setContentText("Potential impact detected. Check wearer.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try { manager.notify(2, notification) } catch (_: SecurityException) {}
    }

    private fun createNotification(): Notification {
        val channelId = "gateway_channel"
        val channel = NotificationChannel(channelId, "SmartCare Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, com.example.smartcare.ui.DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SmartCare Active")
            .setContentText("Monitoring vitals and location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() { 
        timer?.cancel()
        locationTimer?.cancel()
        bleManager.cleanup()
        super.onDestroy() 
    }
}
