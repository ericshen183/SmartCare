package com.example.smartcare.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone

object MoyoungEncoder {

    private const val HEADER_EA = 0xEA.toByte()
    private const val HEADER_AB = 0xAB.toByte()

    private fun createPacket(header: Byte, command: Byte, data: ByteArray = byteArrayOf()): ByteArray {
        val size = (data.size + 1).toShort()
        val buffer = ByteBuffer.allocate(size + 4)

        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0xFE.toByte())
        buffer.put(header)
        buffer.putShort(size)
        buffer.put(command)
        buffer.put(data)

        return buffer.array()
    }

    fun createHandshake(): ByteArray = createPacket(HEADER_EA, 0x01.toByte(), byteArrayOf(0x01.toByte()))

    fun getSummaryRequest(): ByteArray = createPacket(HEADER_EA, 0x08.toByte())

    fun createVitalsRealtime(start: Boolean = true): ByteArray = 
        createPacket(HEADER_EA, 0x37.toByte(), byteArrayOf(if (start) 1.toByte() else 0.toByte()))

    fun createDynamicHrToggle(enabled: Boolean): ByteArray = 
        createPacket(HEADER_EA, 0x68.toByte(), byteArrayOf(if (enabled) 1.toByte() else 0.toByte()))

    fun createManualHrRequest(): ByteArray = createPacket(HEADER_EA, 0x6D.toByte(), byteArrayOf(0x01.toByte()))

    fun createTimeSync(): ByteArray {
        val nowSeconds = (System.currentTimeMillis() / 1000).toInt()
        val offsetHours = (TimeZone.getDefault().rawOffset / 3600000).toByte()
        
        val data = ByteBuffer.allocate(5).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(nowSeconds)
            put(offsetHours)
        }.array()
        
        return createPacket(HEADER_EA, 0x31.toByte(), data)
    }

    fun createUserInfoSync(height: Int = 175, weight: Int = 70, age: Int = 30, gender: Int = 1): ByteArray {
        val data = ByteArray(4)
        data[0] = height.toByte()
        data[1] = weight.toByte()
        data[2] = age.toByte()
        data[3] = gender.toByte()
        return createPacket(HEADER_EA, 0x12.toByte(), data)
    }

    fun createHrIntervalSync(minutes: Int): ByteArray {
        val intervalValue = (minutes / 5).coerceAtLeast(1)
        return createPacket(HEADER_EA, 0x1F.toByte(), byteArrayOf(intervalValue.toByte()))
    }

    fun queryLastDynamicRate(): ByteArray = createPacket(HEADER_EA, 0x34.toByte())

    fun getStatusRequestAB(): ByteArray = createPacket(HEADER_AB, 0x08.toByte())

    fun createNotification(message: String): ByteArray {
        val textBytes = message.toByteArray(Charsets.UTF_8).take(40).toByteArray()
        val data = ByteArray(textBytes.size + 1).apply {
            this[0] = 0x01.toByte()
            System.arraycopy(textBytes, 0, this, 1, textBytes.size)
        }
        return createPacket(HEADER_EA, 0x41.toByte(), data)
    }

    fun createAlarm(id: Int, hour: Int, minute: Int): ByteArray {
        val data = ByteArray(8).apply {
            this[0] = id.toByte()
            this[1] = 1.toByte()
            this[2] = 1.toByte()
            this[3] = hour.toByte()
            this[4] = minute.toByte()
            this[7] = 0x7F.toByte()
        }
        return createPacket(HEADER_EA, 0x11.toByte(), data)
    }
}
