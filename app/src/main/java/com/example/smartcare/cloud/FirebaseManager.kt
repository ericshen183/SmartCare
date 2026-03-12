package com.example.smartcare.cloud

import com.google.firebase.database.FirebaseDatabase

class FirebaseManager {
    private val databaseUrl = "https://smartcare-9063c-default-rtdb.firebaseio.com/"
    private val database = FirebaseDatabase.getInstance(databaseUrl)
    private val vitalsRef = database.getReference("vitals")

    companion object {
        private var instance: FirebaseManager? = null
        fun getInstance(): FirebaseManager {
            if (instance == null) instance = FirebaseManager()
            return instance!!
        }
    }

    /**
     * Pushes vitals and location to Firebase under a specific wearer's name.
     */
    fun updateVitals(wearerName: String, hr: Int, steps: Int, lat: Double, lng: Double, isFall: Boolean) {
        val sanitizedName = wearerName.replace(Regex("[.#$\\[\\] ]"), "").trim()
        if (sanitizedName.isEmpty()) return

        val data = mapOf(
            "heartRate" to hr,
            "steps" to steps,
            "latitude" to lat,
            "longitude" to lng,
            "isFall" to isFall,
            "timestamp" to System.currentTimeMillis()
        )
        vitalsRef.child(sanitizedName).setValue(data)
    }

    // Listens for remote commands (e.g., from a web portal or caregiver app)
    fun listenForCommands(wearerName: String, onCommandReceived: (type: String, value: Any) -> Unit) {
        val sanitizedName = wearerName.replace(Regex("[.#$\\[\\] ]"), "").trim()
        if (sanitizedName.isEmpty()) return
        
        database.getReference("remote_commands").child(sanitizedName)
            .addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val type = snapshot.child("type").getValue(String::class.java)
                    val value = snapshot.child("value").value
                    if (type != null && value != null) {
                        onCommandReceived(type, value)
                        snapshot.ref.removeValue() // Clear after processing
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
    }
}
