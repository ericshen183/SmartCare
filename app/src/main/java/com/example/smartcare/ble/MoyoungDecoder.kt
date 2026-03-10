package com.example.smartcare.ble

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MoyoungDecoder {
    private val HEADERS = arrayOf(
        byteArrayOf(0xFE.toByte(), 0xEA.toByte()),
        byteArrayOf(0xFE.toByte(), 0xAB.toByte()),
        byteArrayOf(0xFE.toByte(), 0xAA.toByte())
    )

    data class WatchUpdate(
        val heartRate: Int,
        val steps: Int,
        val movement: Int,
        val isFallLikely: Boolean = false
    )

    private var lastSteps = 0
    private var impactTime = 0L
    private var stepsAtImpact = 0

    fun decode(data: ByteArray): WatchUpdate? {
        if (data.size < 5) return null
        
        val matchedHeader = HEADERS.find { h -> data[0] == h[0] && data[1] == h[1] }
        if (matchedHeader == null) {
            Log.v("BleDecoder", "Unknown packet: ${data.joinToString("") { "%02X ".format(it) }}")
            return null
        }

        val cmd = data[4].toInt() and 0xFF
        var hr = 0
        var steps = lastSteps
        var movement = 0

        when (cmd) {
            0x08 -> { // Summary
                if (data.size >= 14) {
                    hr = data[13].toInt() and 0xFF
                    if (hr == 0 && data.size >= 15) hr = data[14].toInt() and 0xFF
                }
                if (data.size >= 9) {
                    try {
                        val buffer = ByteBuffer.wrap(data, 5, 4)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        steps = buffer.int
                        lastSteps = steps
                    } catch (_: Exception) {}
                }
            }
            0x32, 0x37, 0x6D, 0x68, 0x29 -> { // Sensor responses
                if (data.size > 5) {
                    // Try different indices for HR in sensor responses
                    val val5 = data[5].toInt() and 0xFF
                    val val6 = if (data.size > 6) data[6].toInt() and 0xFF else 0
                    
                    hr = if (val5 in 40..200) val5 else if (val6 in 40..200) val6 else 0
                    movement = if (hr == val5) val6 else val5
                }
                
                if (cmd == 0x32 && data.size >= 10) {
                    try {
                        val buffer = ByteBuffer.wrap(data, 6, 4)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        steps = buffer.int
                        lastSteps = steps
                    } catch (_: Exception) {}
                }
            }
        }

        // AGGRESSIVE HR SNIFFER
        if (hr == 0 && data.size > 5) {    for (i in 5 until data.size) {
            val value = data[i].toInt() and 0xFF
            if (value in 45..195) {
                hr = value
                break
            }
        }
        }

        // Fall Detection
        val now = System.currentTimeMillis()
        var fallConfirmed = false
        if (movement > 130) {
            impactTime = now
            stepsAtImpact = steps
        }
        if (impactTime > 0 && (now - impactTime > 5000)) {
            if (steps <= stepsAtImpact + 1) fallConfirmed = true
            impactTime = 0
        }

        return WatchUpdate(hr, steps, movement, fallConfirmed)
    }
}
