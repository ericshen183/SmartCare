package com.example.smartcare.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onConnectionStateChanged: (State) -> Unit,
    private val onDataReceived: (MoyoungDecoder.WatchUpdate) -> Unit
) {

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCOVERING_SERVICES,
        READY
    }

    private var connectionState = State.DISCONNECTED
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingMac: String? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }

    companion object {
        private const val TAG = "BleManager"
        private val SERVICE_UUID = UUID.fromString("0000fee7-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_WRITE = UUID.fromString("0000feea-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_NOTIFY = UUID.fromString("0000fee8-0000-1000-8000-00805f9b34fb")
        private val CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val operationQueue = ConcurrentLinkedQueue<BleOp>()
    private var isBusy = false
    private var isReadySignaled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val rssiRunnable = object : Runnable {
        override fun run() {
            if (connectionState == State.READY) {
                bluetoothGatt?.readRemoteRssi()
                mainHandler.postDelayed(this, 5000)
            }
        }
    }

    private val bondFallbackRunnable = Runnable {
        val mac = pendingMac ?: return@Runnable
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return@Runnable
        val device = bluetoothAdapter?.getRemoteDevice(mac) ?: return@Runnable
        if (bluetoothGatt == null && device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.d("BLE", "Bond fallback: starting direct GATT for $mac")
            startGattConnection(device)
        }
    }

    private sealed class BleOp {
        object RequestMtu : BleOp()
        object DiscoverServices : BleOp()
        class WriteDescriptor(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : BleOp()
        class WriteCharacteristic(val characteristic: BluetoothGattCharacteristic, val value: ByteArray, val writeType: Int) : BleOp()
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

                if (device != null && device.address == pendingMac) {
                    Log.d(TAG, "Bond state changed for $pendingMac to $bondState")
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        mainHandler.removeCallbacks(bondFallbackRunnable)
                        startGattConnection(device)
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun updateState(newState: State) {
        if (connectionState != newState) {
            Log.d(TAG, "Connection state: $connectionState -> $newState")
            connectionState = newState
            mainHandler.post { onConnectionStateChanged(newState) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "GATT Error: status=$status. Disconnecting.")
                    updateState(State.DISCONNECTED)
                    disconnect()
                    return@post
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "GATT Connected. Discovering services...")
                    updateState(State.CONNECTED)
                    isBusy = false
                    isReadySignaled = false
                    operationQueue.clear()

                    mainHandler.postDelayed({
                        updateState(State.DISCOVERING_SERVICES)
                        enqueue(BleOp.RequestMtu)
                        enqueue(BleOp.DiscoverServices)
                    }, 1200)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "GATT Disconnected.")
                    updateState(State.DISCONNECTED)
                    disconnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu, status: $status")
            operationFinished()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "Services discovered: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                setupWatchProfile(gatt)
            } else {
                operationFinished()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CONFIG_DESCRIPTOR) {
                Log.d(TAG, "Notification enabled for ${descriptor.characteristic.uuid}")
                // If this was the last descriptor or we are satisfied:
                signalReady()
            }
            operationFinished()
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post {
                    onDataReceived(MoyoungDecoder.WatchUpdate(rssi = rssi))
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: ${characteristic.uuid}, status: $status")
            }
            operationFinished()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val update = MoyoungDecoder.decode(value)
            
            // Handle pairing request from watch
            if (update?.isPairingRequest == true && !isBonded()) {
                Log.d(TAG, "Pairing requested by watch. Initiating system bond.")
                try {
                    gatt.device.createBond()
                } catch (_: SecurityException) {}
            }

            mainHandler.post { update?.let { onDataReceived(it) } }
        }
    }

    private fun setupWatchProfile(gatt: BluetoothGatt) {
        var foundNotify = false
        writeCharacteristic = null

        for (s in gatt.services) {
            for (c in s.characteristics) {
                val uuid = c.uuid.toString().lowercase()

                if (uuid.contains("feea")) {
                    writeCharacteristic = c
                    Log.d(TAG, "Found Write Characteristic: $uuid")
                }

                if (uuid.contains("fee8") || uuid.contains("fee3")) {
                    Log.d(TAG, "Found Notify Char: $uuid. Enabling notifications...")
                    gatt.setCharacteristicNotification(c, true)
                    c.getDescriptor(CONFIG_DESCRIPTOR)?.let {
                        enqueue(BleOp.WriteDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
                        foundNotify = true
                    }
                }
            }
        }

        if (!foundNotify) {
            Log.w(TAG, "No notification characteristic found for protocol profile.")
            signalReady()
        }

        operationFinished()
    }

    private fun signalReady() {
        if (!isReadySignaled) {
            isReadySignaled = true
            updateState(State.READY)
            mainHandler.post { onReady() }
            mainHandler.postDelayed(rssiRunnable, 1000)
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
        // Watchdog for operations
        mainHandler.postDelayed({
            if (isBusy) {
                Log.w(TAG, "Operation timeout: ${op.javaClass.simpleName}")
                isBusy = false
                processNext()
            }
        }, 1000)

        try {
            val status = when (op) {
                is BleOp.RequestMtu -> if (gatt.requestMtu(517)) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
                is BleOp.DiscoverServices -> if (gatt.discoverServices()) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
                is BleOp.WriteDescriptor -> gatt.writeDescriptor(op.descriptor, op.value)
                is BleOp.WriteCharacteristic -> {
                    gatt.writeCharacteristic(
                        op.characteristic,
                        op.value,
                        op.writeType
                    )
                }
            }

            if (status != BluetoothStatusCodes.SUCCESS) {
                Log.e(TAG, "Initiating ${op.javaClass.simpleName} failed with status: $status")
                isBusy = false
                processNext()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Op Exception: ${e.message}")
            isBusy = false
            processNext()
        }
    }

    fun sendCommand(data: ByteArray) {
        val char = writeCharacteristic
        if (char != null) {
            // Handshake, pairing, time sync, and user info often require WRITE_TYPE_DEFAULT
            // 0x01: Handshake/Bind, 0x12: User Info, 0x31: Time Sync
            val cmd = if (data.size >= 5) data[4].toInt() and 0xFF else -1
            val isMoyoungSettingsOrHandshake = data.size >= 6 && data[0] == 0xFE.toByte() && 
                (cmd == 0x01 || cmd == 0x12 || cmd == 0x31)
            
            val writeType = if (isMoyoungSettingsOrHandshake) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            enqueue(BleOp.WriteCharacteristic(char, data, writeType))
        }
    }

    fun isBonded(): Boolean {
        val mac = pendingMac ?: return false
        val device = try { bluetoothAdapter?.getRemoteDevice(mac) } catch (e: Exception) { null } ?: return false
        return device.bondState == BluetoothDevice.BOND_BONDED
    }

    fun connect(mac: String) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter is disabled")
            return
        }

        pendingMac = mac
        mainHandler.removeCallbacks(bondFallbackRunnable)
        val device = adapter.getRemoteDevice(mac)

        val currentState = connectionState
        if (currentState != State.DISCONNECTED && bluetoothGatt != null) {
            Log.d(TAG, "Already connecting or connected to ${device.address}. Current state: $currentState")
            return
        }

        updateState(State.CONNECTING)

        // For Moyoung, we prefer to connect GATT first and then trigger bonding via command (Bind).
        // This is more reliable than calling createBond() directly on a cold device.
        startGattConnection(device)
    }

    private fun startGattConnection(device: BluetoothDevice) {
        mainHandler.removeCallbacks(bondFallbackRunnable)
        disconnect()
        mainHandler.postDelayed({
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }, 500)
    }

    fun disconnect() {
        mainHandler.removeCallbacks(rssiRunnable)
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
        mainHandler.removeCallbacks(bondFallbackRunnable)
    }

    fun cleanup() {
        try { context.unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        disconnect()
    }
}
