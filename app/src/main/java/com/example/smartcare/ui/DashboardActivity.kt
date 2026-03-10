package com.example.smartcare.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.example.smartcare.R
import com.example.smartcare.ble.MoyoungEncoder
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

    private var gatewayService: GatewayService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as GatewayService.LocalBinder
            val s = binder.getService()
            gatewayService = s
            isBound = true
            
            s.setOnDataUpdateListener(object : GatewayService.OnDataUpdateListener {
                override fun onDataUpdate(hr: Int, steps: Int, lat: Double, lng: Double, isFall: Boolean, connStatus: String) {
                    runOnUiThread { updateUI(hr, steps, lat, lng, isFall, connStatus) }
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
                gatewayService?.sendWatchCommand(MoyoungEncoder.createNotification(msg))
                binding.editNotification.text.clear()
                Toast.makeText(this, "Message sent", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnSetAlarm.setOnClickListener {
            val cmd = MoyoungEncoder.createAlarm(0, binding.alarmTimePicker.hour, binding.alarmTimePicker.minute)
            gatewayService?.sendWatchCommand(cmd)
            Toast.makeText(this, "Watch alarm set", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(hr: Int, steps: Int, lat: Double, lng: Double, isFall: Boolean, connStatus: String) {
        val hrDisplay = if (hr > 0) getString(R.string.bpm_format, hr) else getString(R.string.status_detecting)
        binding.hrText.text = hrDisplay
        binding.hrTextLarge.text = hrDisplay

        val fallText = if (isFall) getString(R.string.fall_detected) else getString(R.string.status_normal)
        val fallTextShort = if (isFall) getString(R.string.fall_detected) else getString(R.string.status_normal_short)
        
        binding.fallStatus.text = fallText
        binding.fallStatusSmall.text = fallTextShort
        
        val fallColor = if (isFall) Color.RED else Color.GREEN
        binding.fallStatus.setTextColor(fallColor)
        binding.fallStatusSmall.setTextColor(fallColor)
        
        binding.imgFall.setImageResource(if (isFall) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info)
        binding.tabFall.setBackgroundColor(if (isFall) "#B71C1C".toColorInt() else "#4CAF50".toColorInt())

        // Update status header with connection status
        val prefs = getSharedPreferences("smartcare_prefs", MODE_PRIVATE)
        val watchName = prefs.getString("watch_name", "Smartwatch") ?: "Smartwatch"
        binding.txtStatusHeader.text = "$watchName | $connStatus"

        mMap?.let { map ->
            if (lat != 0.0 && lng != 0.0) {
                val position = LatLng(lat, lng)
                if (wearerMarker == null) {
                    wearerMarker = map.addMarker(MarkerOptions().position(position).title(getString(R.string.marker_title_wearer)))
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 17f))
                } else {
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(lastMapLat, lastMapLng, lat, lng, distance)
                    if (distance[0] > 2) { // 2 meter threshold for movement
                        wearerMarker?.position = position
                        map.animateCamera(CameraUpdateFactory.newLatLng(position))
                    }
                }
                lastMapLat = lat
                lastMapLng = lng
            }
        }
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
