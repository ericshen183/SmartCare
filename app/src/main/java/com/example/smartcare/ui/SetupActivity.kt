package com.example.smartcare.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.smartcare.R
import com.example.smartcare.databinding.ActivitySetupBinding
import com.example.smartcare.services.GatewayService

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val deviceList = mutableListOf<String>()
    private val deviceMap = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("smartcare_prefs", MODE_PRIVATE)
        binding.editWearerName.setText(prefs.getString("wearer_name", ""))
        binding.editCaregiverPhone.setText(prefs.getString("caregiver_phone", ""))

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceList)
        binding.deviceList.adapter = adapter

        binding.btnScan.setOnClickListener {
            if (checkPermissions()) {
                if (bluetoothAdapter?.isEnabled == true) {
                    startBleScan(adapter)
                } else {
                    Toast.makeText(this, "Enable Bluetooth", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.deviceList.setOnItemClickListener { _, _, position, _ ->
            val name = deviceList[position]
            val mac = deviceMap[name]
            if (mac != null) {
                saveWatchSetting(name, mac)
            }
        }

        binding.btnStartService.setOnClickListener {
            val wearerName = binding.editWearerName.text.toString().trim()
            val mac = prefs.getString("watch_mac", null)

            if (wearerName.isEmpty() || mac == null) {
                Toast.makeText(this, "Complete Step 1 and 2", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit { putString("wearer_name", wearerName) }

            if (checkNotificationPermission()) {
                startForegroundService(Intent(this, GatewayService::class.java))
                startActivity(Intent(this, DashboardActivity::class.java))
                finish()
            }
        }
    }

    private fun saveWatchSetting(name: String, mac: String) {
        getSharedPreferences("smartcare_prefs", MODE_PRIVATE).edit {
            putString("watch_name", name)
            putString("watch_mac", mac)
        }
        Toast.makeText(this, getString(R.string.linked_format, name), Toast.LENGTH_SHORT).show()
    }

    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                return false
            }
        }
        return true
    }

    private fun startBleScan(adapter: ArrayAdapter<String>) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        deviceList.clear()
        deviceMap.clear()
        adapter.notifyDataSetChanged()
        binding.txtStatus.text = getString(R.string.status_scanning)

        try {
            scanner.startScan(object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val name = try { device.name } catch (_: SecurityException) { null } ?: device.address
                    if (!deviceMap.values.contains(device.address)) {
                        deviceList.add(name)
                        deviceMap[name] = device.address
                        runOnUiThread { adapter.notifyDataSetChanged() }
                    }
                }
            })
        } catch (_: SecurityException) {}
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = permissions.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            return false
        }
        return true
    }
}
