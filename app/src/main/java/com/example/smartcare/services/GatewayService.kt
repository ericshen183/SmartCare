package com.example.smartcare.services

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.smartcare.R
import com.example.smartcare.ble.*
import com.example.smartcare.cloud.FirebaseManager
import com.google.android.gms.location.*
import java.util.*

class GatewayService : Service() {

    private lateinit var bleManager: BleManager
    private val fallDetectionModule = FallDetectionModule()
    private val activityMonitoringModule = ActivityMonitoringModule()
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
    private var currentRssi = 0
    private var currentMovement = 0
    private var lastHrTime = 0L
    private var isConnected = false
    private var connectionStateText = "Disconnected"
    private var hasReceivedData = false
    private var suppressDisconnectAlerts = false

    private val binder = LocalBinder()
    private var dataListener: OnDataUpdateListener? = null

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (!isConnected) {
                val mac = prefs.getString("watch_mac", null)
                if (!mac.isNullOrEmpty()) {
                    Log.d("Gateway", "Auto-reconnect attempt for: $mac")
                    bleManager.connect(mac)
                }
                mainHandler.postDelayed(this, 15000)
            }
        }
    }

    interface OnDataUpdateListener {
        fun onDataUpdate(hr: Int, steps: Int, distance: Int, lat: Double, lng: Double, isFall: Boolean, connStatus: String, protocol: String, movement: Int)
    }

    inner class LocalBinder : Binder() {
        fun getService(): GatewayService = this@GatewayService
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = getSharedPreferences("smartcare_prefs", MODE_PRIVATE)

        activityMonitoringModule.setActivityListener(object : ActivityMonitoringModule.ActivityListener {
            override fun onSignificantMovement(stepsTaken: Int, distanceMeters: Int) {
                showActivityNotification("The wearer is moving: $stepsTaken steps taken.")
            }

            override fun onStateChanged(isMoving: Boolean) {
                // Future: Update UI icon or caretaker status
                Log.d("Gateway", "Wearer moving state: $isMoving")
            }
        })

        bleManager = BleManager(
            context = this,
            onReady = {
                Log.d("Gateway", "Handshake Ready - Initializing Handshake Sequence")
                isConnected = true
                hasReceivedData = false
                fallDetectionModule.reset()
                activityMonitoringModule.reset()
                
                connectionStateText = "Initializing Moyoung..."
                mainHandler.removeCallbacks(reconnectRunnable)
                startMoyoungHandshake()
                mainHandler.postDelayed({ startPolling() }, 10000)
                relayData()
            },
            onConnectionStateChanged = { state ->
                isConnected = state != BleManager.State.DISCONNECTED
                connectionStateText = when (state) {
                    BleManager.State.DISCONNECTED -> "Disconnected (Watch Offline)"
                    BleManager.State.CONNECTING -> "Connecting..."
                    BleManager.State.CONNECTED -> "GATT Connected"
                    BleManager.State.DISCOVERING_SERVICES -> "Discovering Services..."
                    BleManager.State.READY -> "Connected"
                }

                if (state == BleManager.State.DISCONNECTED) {
                    currentHr = 0
                    currentRssi = 0
                    hasReceivedData = false
                    mainHandler.removeCallbacks(reconnectRunnable)
                    if (!suppressDisconnectAlerts) {
                        showProximityAlertNotification("The wearer's smartwatch has gone offline.")
                        mainHandler.postDelayed(reconnectRunnable, 5000)
                    }
                } else if (state == BleManager.State.READY) {
                    suppressDisconnectAlerts = false
                    mainHandler.removeCallbacks(reconnectRunnable)
                }
                relayData()
            },
            onDataReceived = { update ->
                if (!hasReceivedData && isMeaningfulProtocolResponse(update)) {
                    hasReceivedData = true
                    connectionStateText = "Connected & Ready"
                }

                // Handle pairing request from watch if still unbonded
                if (update.isPairingRequest && !bleManager.isBonded()) {
                    Log.d("Gateway", "Watch requested pairing. Initiating system bond.")
                    bleManager.connect(prefs.getString("watch_mac", "") ?: "")
                }

                if (update.rssi != 0) {
                    currentRssi = update.rssi
                    if (currentRssi < -95 && isConnected) {
                        showProximityAlertNotification("Signal weak: Watch may be too far.")
                    }
                }

                val hrChanged = update.heartRate > 0 && update.heartRate != currentHr
                if (update.heartRate > 0) {
                    currentHr = update.heartRate
                    lastHrTime = System.currentTimeMillis()
                }
                
                if (update.steps > 0 && update.steps < currentSteps - 500) {
                    Log.d("Gateway", "Step count reset detected: $currentSteps -> ${update.steps}")
                    currentSteps = update.steps
                    activityMonitoringModule.reset()
                } else if (update.steps > currentSteps) {
                    currentSteps = update.steps
                }
                
                if (update.distance > 0 && update.distance < currentDistance - 1000) {
                    currentDistance = update.distance
                } else if (update.distance > currentDistance) {
                    currentDistance = update.distance
                }
                
                currentMovement = update.movement
                
                // Process activity monitoring
                activityMonitoringModule.updatePedometerData(currentSteps, currentDistance)
                
                // Debug log for fall detection tuning
                if (update.movement > 20) {
                    Log.d("FallDebug", "Movement: ${update.movement}, Steps: $currentSteps, Distance: $currentDistance")
                }

                // Process manual SOS / Operation buttons
                if (update.findPhoneState > 0) {
                    fallDetectionModule.processOperation(update.findPhoneState)
                }
                
                // Process fall detection independently
                val fallResult = fallDetectionModule.processData(
                    update.movement, 
                    currentHr, 
                    currentSteps, 
                    currentDistance
                )
                
                currentIsFall = fallResult
                
                val now = System.currentTimeMillis()
                // Reduced throttle to 250ms for real-time feel. 
                // Any heart rate update also triggers immediate relay.
                if (now - lastRelayTime >= 250 || hrChanged || fallResult) {
                    relayData()
                    lastRelayTime = now
                }
                if (fallResult) showFallNotification()
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
                if (msg.isNotEmpty()) sendWatchNotification(msg)
            }
            "vitals_request" -> requestManualHr()
        }
    }

    fun setWatchAlarm(id: Int, hour: Int, minute: Int) {
        bleManager.sendCommand(MoyoungEncoder.createAlarm(id, hour, minute))
    }

    fun sendWatchNotification(message: String) {
        bleManager.sendCommand(MoyoungEncoder.createNotification(message))
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
        val statusWithRssi = if (isConnected && currentRssi != 0) "$connectionStateText (RSSI: $currentRssi)" else connectionStateText
        mainHandler.post {
            dataListener?.onDataUpdate(currentHr, currentSteps, currentDistance, currentLat, currentLng, currentIsFall, statusWithRssi, "Moyoung", currentMovement)
        }
        val rawName = prefs.getString("wearer_name", "Unknown") ?: "Unknown"
        val wearerName = rawName.filter { it.isLetterOrDigit() }
        if (wearerName.isEmpty()) return
        firebaseManager.updateVitals(wearerName, currentHr, currentSteps, currentLat, currentLng, currentIsFall)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        suppressDisconnectAlerts = false
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
                if (isConnected && hasReceivedData) {
                    val now = System.currentTimeMillis()
                    
                    // Drive the fall detection state machine even if watch is silent
                    // Pass isRealSensorData = false to preserve impact history
                    val backgroundFallCheck = fallDetectionModule.processData(0, currentHr, currentSteps, currentDistance, isRealSensorData = false)
                    if (backgroundFallCheck) {
                        currentIsFall = true
                        relayData()
                        showFallNotification()
                    }

                    // Unconditional 5-second trigger for Manual HR (replaces the manual button press)
                    bleManager.sendCommand(MoyoungEncoder.createManualHrRequest())

                    // Every 10 seconds, reinforce the high-frequency sensor stream
                    if (cycle % 2 == 0) {
                        bleManager.sendCommand(MoyoungEncoder.createVitalsRealtime(true))
                        bleManager.sendCommand(MoyoungEncoder.createExerciseToggle(true))
                    }

                    when (cycle % 6) {
                        0 -> bleManager.sendCommand(MoyoungEncoder.getSummaryRequest())
                        1 -> bleManager.sendCommand(MoyoungEncoder.createVitalsRealtime(true))
                        2 -> bleManager.sendCommand(MoyoungEncoder.getStatusRequestAB())
                        3 -> bleManager.sendCommand(MoyoungEncoder.queryLastDynamicRate())
                        4 -> bleManager.sendCommand(MoyoungEncoder.createTimeSync()) // Periodic time sync
                        5 -> bleManager.sendCommand(MoyoungEncoder.createStepSync())
                    }
                    cycle++
                }
            }
        }, 0, 5000)
    }

    private fun showActivityNotification(message: String) {
        val channelId = "activity_alert_channel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Wearer Activity", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wearer Active")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        try { manager.notify(4, notification) } catch (_: SecurityException) {}
    }

    fun requestManualHr() {
        bleManager.sendCommand(MoyoungEncoder.createManualHrRequest())
    }

    fun sendWatchCommand(command: ByteArray) = bleManager.sendCommand(command)
    
    fun setOnDataUpdateListener(listener: OnDataUpdateListener?) { 
        this.dataListener = listener 
        listener?.onDataUpdate(currentHr, currentSteps, currentDistance, currentLat, currentLng, currentIsFall, "$connectionStateText (RSSI: $currentRssi)", "Moyoung", currentMovement)
    }

    private fun showProximityAlertNotification(message: String) {
        val channelId = "proximity_alert_channel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Proximity Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, audioAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
        }
        manager.createNotificationChannel(channel)
        val fullScreenIntent = Intent(this, com.example.smartcare.ui.DashboardActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, channelId).setContentTitle("⚠️ PROXIMITY ALERT").setContentText(message).setSmallIcon(android.R.drawable.ic_dialog_alert).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(fullScreenPendingIntent, true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setAutoCancel(true).build()
        try { manager.notify(3, notification) } catch (_: SecurityException) {}
    }

    private fun showFallNotification() {
        val channelId = "fall_alert_channel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Fall Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, audioAttributes)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
        val fullScreenIntent = Intent(this, com.example.smartcare.ui.DashboardActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, channelId).setContentTitle("⚠️ FALL DETECTED").setContentText("Potential impact detected. Check wearer.").setSmallIcon(android.R.drawable.ic_dialog_alert).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(fullScreenPendingIntent, true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setAutoCancel(true).build()
        try { manager.notify(2, notification) } catch (_: SecurityException) {}
    }

    private fun createNotification(): Notification {
        val channelId = "gateway_channel"
        val channel = NotificationChannel(channelId, "SmartCare Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, com.example.smartcare.ui.DashboardActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId).setContentTitle("SmartCare Active").setContentText("Monitoring vitals and location...").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(pendingIntent).setOngoing(true).build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() { 
        timer?.cancel()
        locationTimer?.cancel()
        mainHandler.removeCallbacks(reconnectRunnable)
        bleManager.cleanup()
        super.onDestroy() 
    }

    private fun startMoyoungHandshake() {
        // Send Moyoung Pairing (Bind) command first to ensure system prompt if not bonded
        mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createMoyoungPairing()) }, 100)
        mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createHandshake()) }, 800)
        // Time sync needs the channel to be very stable, increased delay to 5000ms
        mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createTimeSync()) }, 5000)
        mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.getSummaryRequest()) }, 1600)
        mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createVitalsRealtime(true)) }, 2400)
        mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.getStatusRequestAB()) }, 3000)
        mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createDynamicHrToggle(true)) }, 3600)
        // Enable Exercise mode to force high-frequency sensor updates
        mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createExerciseToggle(true)) }, 4200)
        mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.queryMovementHr()) }, 6000)
        mainHandler.postDelayed({ bleManager.sendCommand(MoyoungEncoder.createStepSync()) }, 7000)
    }

    private fun isMeaningfulProtocolResponse(update: MoyoungDecoder.WatchUpdate): Boolean {
        return update.heartRate > 0 ||
            update.steps > 0 ||
            update.movement > 0 ||
            update.distance > 0 ||
            update.hourlySteps?.isNotEmpty() == true
    }
}
