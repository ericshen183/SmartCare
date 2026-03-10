package com.example.smartcare.ble

import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class BleManager(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onDataReceived: (MoyoungDecoder.WatchUpdate) -> Unit
) {

    private var bluetoothGatt: BluetoothGatt? = null
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

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("BLE", "Connected. Initializing Secure Handshake Queue.")
                    isBusy = false
                    isReadySignaled = false
                    operationQueue.clear()
                    enqueue(BleOp.RequestMtu)
                    enqueue(BleOp.DiscoverServices)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("BLE", "Disconnected")
                    isBusy = false
                    operationQueue.clear()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BLE", "MTU confirmed: $mtu")
            operationFinished()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val notifyChar = service?.getCharacteristic(CHARACTERISTIC_NOTIFY)
                if (notifyChar != null) {
                    Log.d("BLE", "Service and Notify Characteristic found. Enabling notifications.")
                    
                    // CRITICAL: Must enable notifications locally in Android's BLE stack
                    try {
                        gatt.setCharacteristicNotification(notifyChar, true)
                    } catch (_: SecurityException) {
                        Log.e("BLE", "SecurityException enabling notifications locally")
                    }

                    val descriptor = notifyChar.getDescriptor(CONFIG_DESCRIPTOR)
                    if (descriptor != null) {
                        enqueue(BleOp.WriteDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
                    } else {
                        Log.e("BLE", "Notification descriptor NOT found")
                    }
                } else {
                    Log.e("BLE", "Moyoung Service/Notify Characteristic NOT found")
                }
            }
            operationFinished()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CONFIG_DESCRIPTOR && !isReadySignaled) {
                Log.d("BLE", "Notification descriptor written successfully.")
                isReadySignaled = true
                onReady()
            }
            operationFinished()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            operationFinished()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val update = MoyoungDecoder.decode(value)
            mainHandler.post { update?.let { onDataReceived(it) } }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: byteArrayOf())
        }
    }

    private fun enqueue(op: BleOp) {
        operationQueue.add(op)
        // Ensure processNext runs on the main thread to prevent race conditions on isBusy
        mainHandler.post { processNext() }
    }

    private fun operationFinished() {
        // Essential 250ms gap to let the Bluetooth stack and watch reset between commands
        mainHandler.postDelayed({
            isBusy = false
            processNext()
        }, 250)
    }

    private fun processNext() {
        if (isBusy) return
        val op = operationQueue.poll() ?: return
        val gatt = bluetoothGatt ?: return

        isBusy = true

        // Safety timeout to prevent queue stall if watch fails to respond
        mainHandler.postDelayed({
            if (isBusy) {
                Log.w("BLE", "Operation timeout - skipping to next task")
                operationFinished()
            }
        }, 2500)

        val success = try {
            when (op) {
                is BleOp.RequestMtu -> gatt.requestMtu(247)
                is BleOp.DiscoverServices -> gatt.discoverServices()
                is BleOp.WriteDescriptor -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(op.descriptor, op.value) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        op.descriptor.value = op.value
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(op.descriptor)
                    }
                }
                is BleOp.WriteCharacteristic -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(op.characteristic, op.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        op.characteristic.value = op.value
                        @Suppress("DEPRECATION")
                        op.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(op.characteristic)
                    }
                }
            }
        } catch (_: SecurityException) { false }

        if (!success) operationFinished()
    }

    fun sendCommand(data: ByteArray) {
        val service = bluetoothGatt?.getService(SERVICE_UUID)
        val writeChar = service?.getCharacteristic(CHARACTERISTIC_WRITE)
        if (writeChar != null) {
            enqueue(BleOp.WriteCharacteristic(writeChar, data))
        } else {
            Log.w("BLE", "Cannot send command - Write Characteristic not found")
        }
    }

    fun connect(mac: String) {
        val device = bluetoothAdapter?.getRemoteDevice(mac) ?: return
        try {
            Log.d("BLE", "Connecting to $mac")
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            Log.e("BLE", "SecurityException during connectGatt")
        }
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: SecurityException) {}
        bluetoothGatt = null
        operationQueue.clear()
        isBusy = false
    }
}
