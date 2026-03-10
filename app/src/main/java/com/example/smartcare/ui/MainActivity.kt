package com.example.smartcare.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.smartcare.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("smartcare_prefs", Context.MODE_PRIVATE)

        // Unified Entry Point
        binding.btnStartTracking.setOnClickListener {
            val mac = prefs.getString("watch_mac", null)
            if (mac == null) {
                startActivity(Intent(this, SetupActivity::class.java))
            } else {
                startActivity(Intent(this, DashboardActivity::class.java))
            }
        }

        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }
}