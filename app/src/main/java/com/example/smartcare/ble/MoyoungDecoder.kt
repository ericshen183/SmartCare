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
        val hourlySteps: Map<Int, Int>? = null
    )

    private var lastSteps = 0
    private var lastDistance = 0
    private var lastMovement = 0
    private var impactTime = 0L
    private var stepsAtImpact = 0
    private var distanceAtImpact = 0

    fun decode(data: ByteArray): WatchUpdate? {
        if (data.size < 5 || data[0] != 0xFE.toByte()) return null

        val cmd = data[4].toInt() and 0xFF
        var hr = 0
        var steps = lastSteps
        var movement = 0
        var distance = lastDistance
        var hourlySteps: MutableMap<Int, Int>? = null

        when (cmd) {
            0x08 -> { // Summary/Status Response
                if (data.size >= 14) {
                    val hr1 = data[13].toInt() and 0xFF
                    val hr2 = if (data.size >= 15) data[14].toInt() and 0xFF else 0
                    hr = if (hr1 in 35..220) hr1 else if (hr2 in 35..220) hr2 else 0
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
            0x6D -> { // Manual HR measurement
                if (data.size > 6) {
                    val potentialHr = data[6].toInt() and 0xFF
                    if (potentialHr in 35..220) hr = potentialHr
                }
            }
            0x29, 0x32, 0x34, 0x35, 0x36, 0x37, 0x68 -> { // Sensor/Real-time Responses
                if (data.size > 5) {
                    val val5 = data[5].toInt() and 0xFF
                    val val6 = if (data.size > 6) data[6].toInt() and 0xFF else 0
                    hr = if (val5 in 35..220) val5 else if (val6 in 35..220) val6 else 0
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
            0xB2 -> { // Workout
                if (data.size >= 6) {
                    val subCmd = data[5].toInt() and 0xFF
                    if (subCmd == 0x03 && data.size >= 27) { // Workout detail response
                        try {
                            val buffer = ByteBuffer.wrap(data, 23, 4)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            distance = buffer.int
                            lastDistance = distance
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // BPM Sniffer
        if (hr <= 0) {
            for (i in 5 until data.size) {
                if ((cmd == 0x08 || cmd == 0x32) && i in 5..8) continue
                val value = data[i].toInt() and 0xFF
                if (value in 40..200) {
                    hr = value
                    break
                }
            }
        }

        // --- REFINED FALL DETECTION ---
        val now = System.currentTimeMillis()
        var fallConfirmed = false
        
        // Calculate "Jerk": sudden change in movement intensity
        val movementJerk = Math.abs(movement - lastMovement)
        
        // Qualify Jerky Movement as Potential Fall:
        // Either a massive spike (>=130) OR a sudden sharp jerk (>100 delta) while moving
        if (movement >= 130 || (movementJerk > 100 && movement > 100)) {
            impactTime = now
            stepsAtImpact = steps
            distanceAtImpact = distance
            Log.d("FallLogic", "Potential Jerky Impact: Move=$movement, Jerk=$movementJerk")
        }

        // Verify state after window
        if (impactTime > 0) {
            val elapsed = now - impactTime
            if (elapsed in 4000..12000) {
                // If neither steps nor distance have changed significantly, confirm fall
                val distanceChanged = Math.abs(distance - distanceAtImpact) > 5 // 5 meters
                if (steps <= stepsAtImpact + 1 && !distanceChanged) {
                    fallConfirmed = true
                    Log.d("FallLogic", "Fall Confirmed: Stationary for ${elapsed/1000}s")
                } else {
                    impactTime = 0 // User recovered/moved
                }
            } else if (elapsed > 12000) {
                impactTime = 0
            }
        }
        
        lastMovement = movement
        return WatchUpdate(hr, steps, movement, distance, fallConfirmed, hourlySteps)
    }
}
