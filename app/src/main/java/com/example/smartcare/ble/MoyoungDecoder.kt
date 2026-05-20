package com.example.smartcare.ble

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MoyoungDecoder {
    data class WatchUpdate(
        val heartRate: Int = 0,
        val steps: Int = 0,
        val movement: Int = 0,
        val distance: Int = 0,
        val isFallLikely: Boolean = false,
        val hourlySteps: Map<Int, Int>? = null,
        val protocol: String = "Moyoung",
        val isPairingRequest: Boolean = false,
        val findPhoneState: Int = 0, // 0: Idle, 1: Start, 2: Stop
        val batteryLevel: Int = -1,
        val rssi: Int = 0
    )

    private var lastSteps = 0
    private var lastDistance = 0
    private var lastMovement = 0
    private var lastHr = 0

    fun decode(data: ByteArray): WatchUpdate? {
        if (data.size < 5) return null

        return when {
            data[0] == 0xFE.toByte() -> decodeMoyoung(data)
            else -> null
        }
    }

    private fun decodeMoyoung(data: ByteArray): WatchUpdate? {
        val cmd = data[4].toInt() and 0xFF
        var hr = 0
        var steps = lastSteps
        var movement = 0
        var distance = lastDistance
        var hourlySteps: MutableMap<Int, Int>? = null
        var isPairingRequest = false
        var findPhoneState = 0
        var batteryLevel = -1

        when (cmd) {
            0x01 -> { // Handshake/Bind Response
                if (data.size > 5) {
                    val status = data[5].toInt() and 0xFF
                    // status 0x02 often means the watch is waiting for a confirmation prompt to be confirmed
                    // Or that the app should now initiate the system pairing.
                    if (status == 0x02) {
                        isPairingRequest = true
                    }
                }
            }
            0x08 -> { // Summary/Status Response
                // Format: [Steps(4)][Kcal(4)][Dist(4)][HR(1)][...]
                if (data.size >= 17) {
                    try {
                        val buffer = ByteBuffer.wrap(data, 5, 12)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        steps = buffer.int
                        buffer.int // skip kcal
                        distance = buffer.int
                        lastSteps = steps
                        lastDistance = distance
                    } catch (_: Exception) {}
                }
                // Try multiple common offsets for HR in Summary packet
                for (offset in listOf(13, 17, 21)) {
                    if (data.size > offset) {
                        val valHr = data[offset].toInt() and 0xFF
                        if (valHr in 35..220) {
                            hr = valHr
                            break
                        }
                    }
                }
                if (data.size >= 17) {
                    batteryLevel = data[16].toInt() and 0xFF
                }
            }
            0x6D -> { // Manual HR measurement
                if (data.size > 6) {
                    val potentialHr = data[6].toInt() and 0xFF
                    if (potentialHr in 35..220) hr = potentialHr
                }
            }
            0x21, 0x22 -> { // Ignore Blood Pressure packets
                // BP packets often contain values like 120/80 which the sniffer might mistake for HR
            }
            0x33 -> { // Step Sync Response
                if (data.size >= 10) {
                    try {
                        val buffer = ByteBuffer.wrap(data, 6, 4)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        steps = buffer.int
                        lastSteps = steps
                    } catch (_: Exception) {}
                }
            }
            0x29, 0x32, 0x34, 0x35, 0x36, 0x37, 0x3C, 0x68 -> { // Sensor/Real-time Responses
                // Scan bytes 5, 6, and 7 for the highest value (likely impact) and stable value (likely HR)
                val vals = mutableListOf<Int>()
                for (i in 5..7) {
                    if (data.size > i) vals.add(data[i].toInt() and 0xFF)
                }
                
                if (vals.isNotEmpty()) {
                    val maxVal = vals.maxOrNull() ?: 0
                    val stableVal = vals.minByOrNull { Math.abs(it - lastHr) } ?: 0
                    
                    // Heuristic: If we have a significant spike (>50) and a stable value near last HR,
                    // separate them. Otherwise, default to standard range check.
                    if (maxVal >= 45 && Math.abs(stableVal - lastHr) < 15 && lastHr > 0) {
                        hr = stableVal
                        movement = maxVal
                    } else {
                        val val5 = vals[0]
                        val val6 = if (vals.size > 1) vals[1] else 0
                        hr = if (val5 in 35..220) val5 else if (val6 in 35..220) val6 else 0
                        movement = if (hr == val5) val6 else val5
                    }
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
            0x5A -> { // Hourly Step Details
                if (data.size > 6) {
                    hourlySteps = mutableMapOf()
                    var idx = 6
                    var hour = 0
                    while (idx + 1 < data.size && hour < 24) {
                        val hourSteps = ByteBuffer.wrap(data, idx, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        hourlySteps[hour] = hourSteps
                        idx += 2
                        hour++
                    }
                }
            }
            0x67 -> { // Notify phone operation
                if (data.size > 5) {
                    val op = data[5].toInt() and 0xFF
                    when (op) {
                        0x01 -> findPhoneState = 1 // Start
                        0x02 -> findPhoneState = 2 // Stop
                    }
                }
            }
            0xB2 -> { // Workout
                if (data.size >= 6) {
                    val subCmd = data[5].toInt() and 0xFF
                    if (subCmd == 0x03 && data.size >= 27) { // Workout detail response
                        try {
                            val stepsBuffer = ByteBuffer.wrap(data, 19, 4)
                            stepsBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            steps = stepsBuffer.int
                            lastSteps = steps
                            val distBuffer = ByteBuffer.wrap(data, 23, 4)
                            distBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            distance = distBuffer.int
                            lastDistance = distance
                        } catch (e: Exception) {
                            Log.e("MoyoungDecoder", "Error decoding B2 subcmd 03: ${e.message}")
                        }
                    } else if (subCmd == 0x05 && data.size >= 10) { // Workout HR stream
                        val valHr = data[9].toInt() and 0xFF
                        if (valHr in 35..220) hr = valHr
                    }
                }
            }
        }

        // BPM Sniffer
        val hrRelevantCommands = listOf(0x08, 0x6D, 0x37, 0x68, 0xB2)
        if (hr <= 0 && cmd in hrRelevantCommands) {
            for (i in 5 until data.size) {
                if ((cmd == 0x08 || cmd == 0x32) && i in 5..8) continue
                val value = data[i].toInt() and 0xFF
                if (value in 40..200) {
                    hr = value
                    break
                }
            }
        }

        return finalizeUpdate(hr, steps, movement, distance, hourlySteps, "Moyoung", isPairingRequest, findPhoneState, batteryLevel)
    }

    private fun finalizeUpdate(
        hr: Int, steps: Int, movement: Int, distance: Int, 
        hourlySteps: Map<Int, Int>?, protocol: String, 
        isPairingRequest: Boolean, findPhoneState: Int, batteryLevel: Int
    ): WatchUpdate {
        lastMovement = movement
        if (hr > 0) lastHr = hr
        if (steps > 0) lastSteps = steps
        if (distance > 0) lastDistance = distance
        
        return WatchUpdate(hr, steps, movement, distance, false, hourlySteps, protocol, isPairingRequest, findPhoneState, batteryLevel)
    }
}
