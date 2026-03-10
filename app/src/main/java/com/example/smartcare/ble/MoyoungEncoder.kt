package com.example.smartcare.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone

object MoyoungEncoder {

    private const val HEADER_EA = 0xEA.toByte()
    private const val HEADER_AB = 0xAB.toByte()

    private fun createPacket(header: Byte, command: Byte, data: ByteArray = byteArrayOf()): ByteArray {
        // Size includes the command byte and the data bytes
        val size = (data.size + 1).toShort()
        val buffer = ByteBuffer.allocate(size + 4)
        
        // Protocol header and size are Big Endian
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(0xFE.toByte())
        buffer.put(header)
        buffer.putShort(size)
        buffer.put(command)
        buffer.put(data)

        return buffer.array()
    }

    /** [0x01] = Binding/Handshake */
    fun createHandshake(): ByteArray = createPacket(HEADER_EA, 0x01.toByte(), byteArrayOf(0x01.toByte()))

    /** [0x08] = Summary/Status Request */
    fun getSummaryRequest(): ByteArray = createPacket(HEADER_EA, 0x00.toByte())

    /** [0x37] = Get Movement HR (Real-time vitals) */
    fun createVitalsRealtime(start: Boolean): ByteArray = 
        createPacket(HEADER_EA, 0x37.toByte(), byteArrayOf(if (start) 1.toByte() else 0.toByte()))

    /** [0x68] = Start/Stop Dynamic HR measurement */
    fun createDynamicHrToggle(enabled: Boolean): ByteArray = 
        createPacket(HEADER_EA, 0x68.toByte(), byteArrayOf(if (enabled) 1.toByte() else 0.toByte()))

    /** [0x6D] = Manual HR measurement */
    fun createManualHrStart(): ByteArray = 
        createPacket(HEADER_EA, 0x6D.toByte(), byteArrayOf(0x01.toByte()))

    /** [0x31] = Time Sync - Dissector indicates Big Endian for time */
    fun createTimeSync(): ByteArray {
        val nowSeconds = (System.currentTimeMillis() / 1000).toInt()
        val offsetHours = (TimeZone.getDefault().rawOffset / 3600000).toByte()
        
        val data = ByteBuffer.allocate(5).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(nowSeconds)
            put(offsetHours)
        }.array()
        
        return createPacket(HEADER_EA, 0x31.toByte(), data)
    }

    /** [0x12] = User Info (Height, Weight, Age, Gender) */
    fun createUserInfoSync(height: Int = 175, weight: Int = 70, age: Int = 30, gender: Int = 1): ByteArray {
        val data = ByteArray(4)
        data[0] = height.toByte()
        data[1] = weight.toByte()
        data[2] = age.toByte()
        data[3] = gender.toByte()
        return createPacket(HEADER_EA, 0x12.toByte(), data)
    }

    /** [0x1F] = Set HR measurement interval (value * 5 minutes) */
    fun createHrIntervalSync(minutes: Int): ByteArray {
        val intervalValue = (minutes / 5).coerceAtLeast(1)
        return createPacket(HEADER_EA, 0x1F.toByte(), byteArrayOf(intervalValue.toByte()))
    }

    /** [0x34] = Query last dynamic rate (often sent after workout) */
    fun queryLastDynamicRate(): ByteArray = createPacket(HEADER_EA, 0x34.toByte())

    /** Using HEADER_AB for certain status checks */
    fun getStatusRequestAB(): ByteArray = createPacket(HEADER_AB, 0x08.toByte())

    /** [0x41] = Send notification */
    fun createNotification(message: String): ByteArray {
        val textBytes = message.toByteArray(Charsets.UTF_8).take(40).toByteArray()
        val data = ByteArray(textBytes.size + 1).apply {
            this[0] = 0x01.toByte() // App type
            System.arraycopy(textBytes, 0, this, 1, textBytes.size)
        }
        return createPacket(HEADER_EA, 0x41.toByte(), data)
    }

    /** [0x11] = Set Alarm */
    fun createAlarm(id: Int, hour: Int, minute: Int): ByteArray {
        val data = ByteArray(8).apply {
            this[0] = id.toByte()
            this[1] = 1.toByte() // Enabled
            this[2] = 1.toByte() // Once
            this[3] = hour.toByte()
            this[4] = minute.toByte()
            this[7] = 0x7F.toByte() // All days
        }
        return createPacket(HEADER_EA, 0x11.toByte(), data)
    }
}
