package com.example.smartcare.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.smartcare.databinding.ActivityMainBinding
import com.example.smartcare.services.GatewayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("smartcare_prefs", Context.MODE_PRIVATE)

        binding.btnStartTracking.setOnClickListener {
            if (hasRequiredPermissions()) {
                val mac = prefs.getString("watch_mac", null)
                if (mac == null) {
                    startActivity(Intent(this, SetupActivity::class.java))
                } else {
                    startTrackingService()
                    startActivity(Intent(this, DashboardActivity::class.java))
                }
            } else {
                requestPermissions()
            }
        }

        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        )
        return permissions.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        )
        ActivityCompat.requestPermissions(this, permissions, 100)
    }

    private fun startTrackingService() {
        val serviceIntent = Intent(this, GatewayService::class.java)
        try {
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
