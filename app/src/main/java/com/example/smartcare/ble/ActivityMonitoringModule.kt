package com.example.smartcare.ble

import android.util.Log

/**
 * Dedicated module for monitoring wearer activity levels using pedometer data.
 * This module is decoupled from fall detection and vital signs to allow for
 * specialized logic related to wandering or activity reporting.
 */
class ActivityMonitoringModule {

    interface ActivityListener {
        fun onSignificantMovement(stepsTaken: Int, distanceMeters: Int)
        fun onStateChanged(isMoving: Boolean)
    }

    private var listener: ActivityListener? = null

    // Thresholds for "Significant Movement"
    private val STEP_THRESHOLD = 5 // Reduced to 5 steps for faster detection
    private val DISTANCE_THRESHOLD = 8 // meters
    private val STATIONARY_COOLDOWN_MS = 120000L // 2 minutes of no steps to be "stationary"

    private var startSteps = -1
    private var startDistance = -1
    private var lastMovementTime = 0L
    private var isCurrentlyMoving = false

    fun setActivityListener(listener: ActivityListener) {
        this.listener = listener
    }

    /**
     * Processes current pedometer data and determines if significant movement has occurred.
     */
    fun updatePedometerData(steps: Int, distanceMeters: Int) {
        val now = System.currentTimeMillis()

        // Initialize markers if this is the first data point
        if (startSteps == -1) {
            Log.d("ActivityMonitor", "Initializing markers: $steps steps, ${distanceMeters}m")
            startSteps = steps
            startDistance = distanceMeters
            lastMovementTime = now
            return
        }

        val deltaSteps = steps - startSteps
        val deltaDistance = distanceMeters - startDistance

        // Check for immediate significant movement
        if (deltaSteps >= STEP_THRESHOLD || deltaDistance >= DISTANCE_THRESHOLD) {
            Log.d("ActivityMonitor", "Movement detected: $deltaSteps steps, ${deltaDistance}m")
            listener?.onSignificantMovement(deltaSteps, deltaDistance)
            
            // Reset markers to the current point so we can detect the NEXT block of movement
            startSteps = steps
            startDistance = distanceMeters
            lastMovementTime = now
            
            if (!isCurrentlyMoving) {
                isCurrentlyMoving = true
                listener?.onStateChanged(true)
            }
        } else {
            // Check if wearer has become stationary
            if (now - lastMovementTime >= STATIONARY_COOLDOWN_MS) {
                if (isCurrentlyMoving) {
                    Log.d("ActivityMonitor", "Wearer is now stationary")
                    isCurrentlyMoving = false
                    listener?.onStateChanged(false)
                }
                // Only reset start markers if there is absolutely no movement, 
                // allowing slow movement to eventually cross the threshold.
                if (deltaSteps <= 0 && deltaDistance <= 0) {
                    startSteps = steps
                    startDistance = distanceMeters
                }
            }
        }
    }

    fun reset() {
        startSteps = -1
        startDistance = -1
        lastMovementTime = 0L
        isCurrentlyMoving = false
    }
}
