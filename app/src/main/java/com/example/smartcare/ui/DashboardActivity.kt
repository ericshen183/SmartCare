package com.example.smartcare.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.*
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.example.smartcare.R
import com.example.smartcare.databinding.ActivityDashboardBinding
import com.example.smartcare.services.GatewayService
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*

class DashboardActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityDashboardBinding
    private var mMap: GoogleMap? = null
    private var wearerMarker: Marker? = null
    private var lastMapLat = 0.0
    private var lastMapLng = 0.0
    private var lastUiHr = 0
    private var lastPulseTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatewayService: GatewayService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as GatewayService.LocalBinder
            val s = binder.getService()
            gatewayService = s
            isBound = true
            
            s.setOnDataUpdateListener(object : GatewayService.OnDataUpdateListener {
                override fun onDataUpdate(hr: Int, steps: Int, distance: Int, lat: Double, lng: Double, isFall: Boolean, connStatus: String, protocol: String, movement: Int) {
                    runOnUiThread { updateUI(hr, steps, distance, lat, lng, isFall, connStatus, protocol, movement) }
                }
            })
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            gatewayService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("smartcare_prefs", MODE_PRIVATE)
        val watchName = prefs.getString("watch_name", "Smartwatch") ?: "Smartwatch"
        val watchMac = prefs.getString("watch_mac", "") ?: ""
        binding.txtStatusHeader.text = getString(R.string.tracking_status, watchName, watchMac)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragmentContainer) as SupportMapFragment?
        mapFragment?.getMapAsync(this)

        setupNavigation()
        setupControls()

        bindService(Intent(this, GatewayService::class.java), connection, BIND_AUTO_CREATE)
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_bpm -> {
                    showTab(binding.tabBpm)
                    true
                }
                R.id.nav_location -> {
                    showTab(binding.mapFragmentContainer)
                    true
                }
                R.id.nav_fall -> {
                    showTab(binding.tabFall)
                    true
                }
                R.id.nav_controls -> {
                    showTab(binding.tabControls)
                    true
                }
                else -> false
            }
        }
    }

    private fun showTab(view: View) {
        binding.tabBpm.visibility = View.GONE
        binding.mapFragmentContainer.visibility = View.GONE
        binding.tabFall.visibility = View.GONE
        binding.tabControls.visibility = View.GONE
        view.visibility = View.VISIBLE
    }

    private fun setupControls() {
        binding.btnSendNotification.setOnClickListener {
            val msg = binding.editNotification.text.toString()
            if (msg.isNotEmpty()) {
                gatewayService?.sendWatchNotification(msg)
                binding.editNotification.text.clear()
                Toast.makeText(this, "Message sent", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnSetAlarm.setOnClickListener {
            gatewayService?.setWatchAlarm(0, binding.alarmTimePicker.hour, binding.alarmTimePicker.minute)
            Toast.makeText(this, "Watch alarm set", Toast.LENGTH_SHORT).show()
        }
        binding.btnTestHr.setOnClickListener {
            gatewayService?.requestManualHr()
            Toast.makeText(this, "Requesting Heart Rate...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(hr: Int, steps: Int, distance: Int, lat: Double, lng: Double, isFall: Boolean, connStatus: String, protocol: String, movement: Int) {
        android.util.Log.d("DashboardUI", "Received Update. Status: $connStatus")
        
        val now = System.currentTimeMillis()
        if (hr > 0 && (hr != lastUiHr || now - lastPulseTime > 10000)) {
            animateHeartbeat()
            lastUiHr = hr
            lastPulseTime = now
        }

        val hrDisplay = if (hr > 0) getString(R.string.bpm_format, hr) else getString(R.string.status_detecting)
        binding.hrText.text = hrDisplay
        binding.hrTextLarge.text = hrDisplay

        binding.movementIntensity.text = getString(R.string.motion_format, movement)
        if (movement >= 50) {
            binding.movementIntensity.setTextColor(Color.RED)
            mainHandler.postDelayed({ binding.movementIntensity.setTextColor("#000000".toColorInt()) }, 500)
        }

        val fallText = if (isFall) getString(R.string.fall_detected) else getString(R.string.status_normal)
        val fallTextShort = if (isFall) getString(R.string.fall_detected) else getString(R.string.status_normal_short)
        
        binding.fallStatus.text = fallText
        binding.fallStatusSmall.text = fallTextShort
        
        val fallColor = if (isFall) Color.RED else Color.GREEN
        binding.fallStatus.setTextColor(fallColor)
        binding.fallStatusSmall.setTextColor(fallColor)
        
        binding.imgFall.setImageResource(if (isFall) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info)
        binding.tabFall.setBackgroundColor(if (isFall) "#B71C1C".toColorInt() else "#4CAF50".toColorInt())

        // Update status header with connection status and protocol info
        val prefs = getSharedPreferences("smartcare_prefs", MODE_PRIVATE)
        val watchName = prefs.getString("watch_name", "Smartwatch") ?: "Smartwatch"
        val activityInfo = if (steps > 0) " | $steps Steps (${distance/1000.0}km)" else ""
        binding.txtStatusHeader.text = "${watchName}${activityInfo}\n$connStatus ($protocol)"

        mMap?.let { map ->
            if (lat != 0.0 && lng != 0.0) {
                val position = LatLng(lat, lng)
                if (wearerMarker == null) {
                    wearerMarker = map.addMarker(MarkerOptions().position(position).title(getString(R.string.marker_title_wearer)))
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 17f))
                } else {
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(lastMapLat, lastMapLng, lat, lng, results)
                    if (results[0] > 2) { // 2 meter threshold for movement
                        wearerMarker?.position = position
                        map.animateCamera(CameraUpdateFactory.newLatLng(position))
                    }
                }
                lastMapLat = lat
                lastMapLng = lng
            }
        }
    }

    private fun animateHeartbeat() {
        binding.hrTextLarge.animate()
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(100)
            .withEndAction {
                binding.hrTextLarge.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    override fun onMapReady(googleMap: GoogleMap) { mMap = googleMap }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            gatewayService?.setOnDataUpdateListener(null)
            unbindService(connection)
        }
    }
}
