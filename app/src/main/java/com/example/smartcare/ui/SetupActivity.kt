package com.example.smartcare.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.smartcare.R
import com.example.smartcare.ble.MoyoungDecoder
import com.example.smartcare.ble.MoyoungEncoder
import com.example.smartcare.databinding.ActivitySetupBinding
import com.example.smartcare.services.GatewayService
import java.util.UUID

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val deviceList = mutableListOf<String>()
    private val deviceMap = mutableMapOf<String, String>()
    private var selectedDeviceMac: String? = null
    private var selectedDeviceName: String? = null
    
    private var pendingDeviceName: String? = null
    private var pendingDeviceMac: String? = null
    
    private var setupGatt: BluetoothGatt? = null
    private var setupWriteCharacteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val pairingTimeoutRunnable = Runnable {
        val mac = pendingDeviceMac ?: return@Runnable
        val device = bluetoothAdapter?.getRemoteDevice(mac)
        if (device != null && device.bondState == BluetoothDevice.BOND_NONE) {
            Log.d("Setup", "Pairing timeout reached. Forcing manual bond request.")
            try {
                device.createBond()
            } catch (_: SecurityException) {}
        }
    }

    private val setupHandshakeRunnable = Runnable {
        // Send Moyoung Binding command first to trigger system pairing prompt if not already bonded
        sendSetupProbeCommand(MoyoungEncoder.createMoyoungPairing())
        // Sending User Info command often forces the watch to enforce security/pairing
        mainHandler.postDelayed({ sendSetupProbeCommand(MoyoungEncoder.createUserInfoSync()) }, 500)
        // Time sync also helps trigger bonding prompts
        mainHandler.postDelayed({ sendSetupProbeCommand(MoyoungEncoder.createTimeSync()) }, 1000)
        mainHandler.postDelayed({ sendSetupProbeCommand(MoyoungEncoder.createHandshake()) }, 1500)
    }

    private val setupGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread {
                        binding.txtStatus.text = getString(R.string.status_connected)
                    }
                    try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {}
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (setupGatt === gatt) {
                        closeSetupGatt()
                    } else {
                        try {
                            gatt.close()
                        } catch (_: SecurityException) {}
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            setupWriteCharacteristic = null
            var notifyEnabled = false

            for (service in gatt.services) {
                for (characteristic in service.characteristics) {
                    val uuid = characteristic.uuid.toString().lowercase()
                    if (uuid.contains("feea")) {
                        setupWriteCharacteristic = characteristic
                        Log.d("Setup", "Found Write Char: $uuid")
                    }
                    if (uuid.contains("fee8") || uuid.contains("fee3")) {
                        Log.d("Setup", "Found Notify Char: $uuid. Enabling...")
                        try {
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
                            if (descriptor != null) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                notifyEnabled = true
                            }
                        } catch (_: SecurityException) {}
                    }
                }
            }

            if (notifyEnabled) {
                // Once notifications are enabled, we wait for descriptor write or start probe
            } else {
                mainHandler.post(setupHandshakeRunnable)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CLIENT_CONFIG_UUID) {
                mainHandler.post(setupHandshakeRunnable)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val update = MoyoungDecoder.decode(value)
            
            // If the watch signals it's ready for pairing (status 0x02) OR sends ANY protocol response while unbonded
            // Many watches won't send 0x02 but will "hang" on subsequent commands until bonded
            if ((update?.isPairingRequest == true || !isWatchBonded(gatt)) && gatt.device.bondState == BluetoothDevice.BOND_NONE) {
                Log.d("Setup", "Watch needs pairing (via protocol or unbonded status). Triggering bond.")
                mainHandler.removeCallbacks(pairingTimeoutRunnable)
                try {
                    gatt.device.createBond()
                } catch (_: SecurityException) {}
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: byteArrayOf())
        }
    }

    companion object {
        private val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return

            if (device.address != pendingDeviceMac) return

            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            when (bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    mainHandler.removeCallbacks(pairingTimeoutRunnable)
                    closeSetupGatt()
                    val name = try {
                        pendingDeviceName ?: device.name ?: device.address
                    } catch (_: SecurityException) {
                        pendingDeviceName ?: device.address
                    }
                    saveWatchSetting(name, device.address)
                    binding.txtStatus.text = getString(R.string.status_watch_paired)
                    startMonitoring()
                }
                BluetoothDevice.BOND_NONE -> {
                    binding.txtStatus.text = "Pairing not completed"
                    closeSetupGatt()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bondReceiver, filter, RECEIVER_EXPORTED)

        val prefs = getSharedPreferences("smartcare_prefs", MODE_PRIVATE)
        binding.editWearerName.setText(prefs.getString("wearer_name", ""))
        binding.editCaregiverPhone.setText(prefs.getString("caregiver_phone", ""))

        val adapter = ArrayAdapter(this, R.layout.list_item_device, deviceList)
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
                selectedDeviceMac = mac
                selectedDeviceName = name
                binding.txtStatus.text = getString(R.string.linked_format, name)
            }
        }

        binding.btnStartService.setOnClickListener {
            val wearerName = binding.editWearerName.text.toString().trim()
            val mac = selectedDeviceMac ?: prefs.getString("watch_mac", null)

            if (wearerName.isEmpty() || mac == null) {
                Toast.makeText(this, "Complete Step 1 and 2 (Scan and select watch)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit {
                putString("wearer_name", wearerName)
                putString("caregiver_phone", binding.editCaregiverPhone.text.toString().trim())
            }

            val device = bluetoothAdapter?.getRemoteDevice(mac)
            if (device?.bondState != BluetoothDevice.BOND_BONDED) {
                binding.txtStatus.text = "Initiating pairing..."
                pairWatch(selectedDeviceName ?: "Watch", mac)
            } else {
                saveWatchSetting(selectedDeviceName ?: device.name ?: "Watch", mac)
                startMonitoring()
            }
        }
    }

    private fun startMonitoring() {
        if (checkNotificationPermission()) {
            startForegroundService(Intent(this, GatewayService::class.java))
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
    }

    private fun saveWatchSetting(name: String, mac: String) {
        getSharedPreferences("smartcare_prefs", MODE_PRIVATE).edit {
            putString("watch_name", name)
            putString("watch_mac", mac)
        }
        Toast.makeText(this, "Linked: $name", Toast.LENGTH_SHORT).show()
    }

    private fun pairWatch(name: String, mac: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth permission is required to pair", Toast.LENGTH_SHORT).show()
            return
        }

        val device = try {
            bluetoothAdapter?.getRemoteDevice(mac)
        } catch (_: IllegalArgumentException) {
            null
        }

        if (device == null) {
            Toast.makeText(this, "Unable to access selected device", Toast.LENGTH_SHORT).show()
            return
        }

        pendingDeviceName = name
        pendingDeviceMac = mac
        closeSetupGatt()
        
        // Ensure service is stopped to prevent conflicts during pairing
        stopService(Intent(this, GatewayService::class.java))

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            saveWatchSetting(name, mac)
            binding.txtStatus.text = getString(R.string.status_watch_paired)
            startMonitoring()
            return
        }

        binding.txtStatus.text = "Initializing pairing flow..."
        mainHandler.postDelayed(pairingTimeoutRunnable, 6000)
        startSetupGattProbe(device)
    }

    private fun startSetupGattProbe(device: BluetoothDevice) {
        closeSetupGatt()
        setupGatt = try {
            device.connectGatt(this, false, setupGattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun isWatchBonded(gatt: BluetoothGatt): Boolean {
        return try {
            gatt.device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }
    }

    private fun sendSetupProbeCommand(command: ByteArray) {
        val gatt = setupGatt ?: return
        val characteristic = setupWriteCharacteristic ?: return
        
        // Pairing and handshake commands often require WRITE_TYPE_DEFAULT for reliability
        val isMoyoungPairingOrHandshake = command.size >= 6 && command[0] == 0xFE.toByte() && command[4] == 0x01.toByte()
        val writeType = if (isMoyoungPairingOrHandshake) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        try {
            gatt.writeCharacteristic(
                characteristic,
                command,
                writeType
            )
        } catch (_: SecurityException) {}
    }

    private fun closeSetupGatt() {
        mainHandler.removeCallbacks(setupHandshakeRunnable)
        mainHandler.removeCallbacks(pairingTimeoutRunnable)
        try {
            setupGatt?.disconnect()
            setupGatt?.close()
        } catch (_: SecurityException) {}
        setupGatt = null
        setupWriteCharacteristic = null
    }

    private fun checkNotificationPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            return false
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
        val permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val missing = permissions.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            return false
        }
        return true
    }

    override fun onDestroy() {
        closeSetupGatt()
        try {
            unregisterReceiver(bondReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
