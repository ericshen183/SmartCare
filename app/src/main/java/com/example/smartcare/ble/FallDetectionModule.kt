package com.example.smartcare.ble

import kotlin.math.abs

/**
 * Dedicated module for identifying potential fall events based on Moyoung protocol sensor data.
 * Logic is decoupled from the primary protocol decoding to allow for more complex state analysis.
 */
class FallDetectionModule {

    private var lastMovement = 0
    private var impactTime = 0L
    private var stepsAtImpact = 0
    private var distanceAtImpact = 0
    private var isManualSos = false

    /**
     * Processes new sensor data and returns true if a fall is confirmed.
     */
    fun processData(movement: Int, heartRate: Int, steps: Int, distance: Int, isRealSensorData: Boolean = true): Boolean {
        if (isManualSos) {
            isManualSos = false
            return true
        }
        
        val now = System.currentTimeMillis()
        var fallConfirmed = false
        
        if (isRealSensorData) {
            val movementJerk = abs(movement - lastMovement)
            
            // Step 1: Detect potential impact
            // Increased sensitivity: Movement magnitude > 50 OR a significant sudden change (jerk) > 35
            if (movement >= 50 || ((movementJerk > 35) && (movement > 30))) {
                if (impactTime == 0L) { // Only record the first impact in a sequence
                    impactTime = now
                    stepsAtImpact = steps
                    distanceAtImpact = distance
                }
            }
            lastMovement = movement
        }
        
        // Step 2: Confirmation window (1s to 12s after impact)
        if (impactTime > 0) {
            val elapsed = now - impactTime
            
            // Wait at least 1 second to ensure we are looking at POST-impact data
            if (elapsed in 1000..12000) {
                // If wearer moved significantly, likely not a disabling fall
                // Relaxed thresholds to ignore sensor noise during the hard stop
                val distanceChanged = abs(distance - distanceAtImpact) > 8
                val stepsChanged = steps > (stepsAtImpact + 5)
                
                if (stepsChanged || distanceChanged) {
                    impactTime = 0
                } else if (elapsed > 2500) {
                    // Confirmed: Impact followed by 2.5 seconds of stillness
                    fallConfirmed = true
                    impactTime = 0
                }
            } else if (elapsed > 12000) {
                // Timeout reached without confirmation
                impactTime = 0
            }
        }
        
        return fallConfirmed
    }

    /**
     * Handles manual SOS signals or watch button operations.
     */
    fun processOperation(opCode: Int) {
        // opCode 1 is usually "Find Phone" / Long press on button
        if (opCode == 1) {
            isManualSos = true
        }
    }
    
    fun reset() {
        impactTime = 0
        lastMovement = 0
        isManualSos = false
    }
}
