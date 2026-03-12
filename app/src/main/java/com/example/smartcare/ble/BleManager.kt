package com.example.smartcare.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onDataReceived: (MoyoungDecoder.WatchUpdate) -> Unit
) {

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingMac: String? = null
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    companion object {
        private val SERVICE_UUID = UUID.fromString("0000fee7-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_WRITE = UUID.fromString("0000feea-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_NOTIFY = UUID.fromString("0000fee8-0000-1000-8000-00805f9b34fb")
        private val CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val operationQueue = ConcurrentLinkedQueue<BleOp>()
    private var isBusy = false
    private var isReadySignaled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private sealed class BleOp {
        object RequestMtu : BleOp()
        object DiscoverServices : BleOp()
        class WriteDescriptor(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : BleOp()
        class WriteCharacteristic(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : BleOp()
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                
                if (device != null && device.address == pendingMac) {
                    Log.d("BLE", "Bond state changed for $pendingMac to $bondState")
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        startGattConnection(device)
                    }
                }
            }
        }
    }

    init {
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e("BLE", "GATT Error: status=$status. Disconnecting.")
                    onConnectionStateChanged(false)
                    disconnect()
                    return@post
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("BLE", "GATT Connected. Discovering services...")
                    onConnectionStateChanged(true)
                    isBusy = false
                    isReadySignaled = false
                    operationQueue.clear()
                    
                    mainHandler.postDelayed({
                        enqueue(BleOp.RequestMtu)
                        enqueue(BleOp.DiscoverServices)
                    }, 1200)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("BLE", "GATT Disconnected.")
                    onConnectionStateChanged(false)
                    disconnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BLE", "MTU: $mtu")
            operationFinished()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d("BLE", "Services discovered: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                setupWatchProfile(gatt)
            } else {
                operationFinished()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CONFIG_DESCRIPTOR) {
                signalReady()
            }
            operationFinished()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            operationFinished()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val update = MoyoungDecoder.decode(value)
            mainHandler.post { update?.let { onDataReceived(it) } }
            Log.d("BLE_RAW", "Data [${value.size} bytes]: ${value.joinToString(" ") { "%02X".format(it) }} from ${characteristic.uuid.toString().substring(4,8)}")
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: byteArrayOf())
        }
    }

    private fun setupWatchProfile(gatt: BluetoothGatt) {
        var foundNotify = false
        writeCharacteristic = null

        // Try to find the characteristics across all services
        for (s in gatt.services) {
            for (c in s.characteristics) {
                val uuid = c.uuid.toString().lowercase()
                
                // Write characteristic: feea
                if (uuid.contains("feea")) {
                    writeCharacteristic = c
                    Log.d("BLE", "Found Write Characteristic: $uuid")
                }
                
                // Notify characteristics: fee8 or fee3 (from your capture)
                if (uuid.contains("fee8") || uuid.contains("fee3")) {
                    Log.d("BLE", "Found Notify Characteristic: $uuid. Enabling...")
                    gatt.setCharacteristicNotification(c, true)
                    c.getDescriptor(CONFIG_DESCRIPTOR)?.let {
                        enqueue(BleOp.WriteDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
                        foundNotify = true
                    }
                }
            }
        }

        if (writeCharacteristic == null) Log.e("BLE", "Write characteristic NOT FOUND")
        if (!foundNotify) {
            Log.w("BLE", "Notify characteristic NOT FOUND. Forcing ready state.")
            signalReady()
        }
        
        operationFinished()
    }

    private fun enableNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        char.getDescriptor(CONFIG_DESCRIPTOR)?.let {
            enqueue(BleOp.WriteDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
        }
    }

    private fun signalReady() {
        if (!isReadySignaled) {
            isReadySignaled = true
            mainHandler.post { onReady() }
        }
    }

    private fun enqueue(op: BleOp) {
        operationQueue.add(op)
        mainHandler.post { processNext() }
    }

    private fun operationFinished() {
        mainHandler.post {
            isBusy = false
            processNext()
        }
    }

    private fun processNext() {
        if (isBusy) return
        val op = operationQueue.poll() ?: return
        val gatt = bluetoothGatt ?: return

        isBusy = true
        mainHandler.postDelayed({ if (isBusy) { isBusy = false; processNext() } }, 5000)

        try {
            when (op) {
                is BleOp.RequestMtu -> gatt.requestMtu(517)
                is BleOp.DiscoverServices -> gatt.discoverServices()
                is BleOp.WriteDescriptor -> gatt.writeDescriptor(op.descriptor, op.value)
                is BleOp.WriteCharacteristic -> {
                    gatt.writeCharacteristic(
                        op.characteristic,
                        op.value,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("BLE", "Op Error: ${e.message}")
            isBusy = false
            processNext()
        }
    }

    fun sendCommand(data: ByteArray) {
        val char = writeCharacteristic
        if (char != null) {
            enqueue(BleOp.WriteCharacteristic(char, data))
        } else {
            Log.e("BLE", "Cannot send: Write char missing")
        }
    }

    fun connect(mac: String) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        
        pendingMac = mac
        val device = adapter.getRemoteDevice(mac)
        
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Log.d("BLE", "Initiating pairing...")
            device.createBond()
        } else {
            startGattConnection(device)
        }
    }

    private fun startGattConnection(device: BluetoothDevice) {
        disconnect()
        mainHandler.postDelayed({
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }, 500)
    }

    fun disconnect() {
        try {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
        } catch (_: Exception) {}
        bluetoothGatt = null
        writeCharacteristic = null
        operationQueue.clear()
        isBusy = false
        isReadySignaled = false
    }

    fun cleanup() {
        try { context.unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        disconnect()
    }
}
