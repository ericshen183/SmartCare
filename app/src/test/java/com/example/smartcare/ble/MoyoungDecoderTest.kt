package com.example.smartcare.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MoyoungDecoderTest {

    @Test
    fun testDecodeSummaryPacket() {
        // Command 0x08 (Summary/Status)
        // Data: [Steps(4)][Kcal(4)][Dist(4)][HR(1)][Battery(1)]
        // Steps: 1000 (0xE8 0x03 0x00 0x00)
        // Kcal: 50 (0x32 0x00 0x00 0x00)
        // Dist: 750 (0xEE 0x02 0x00 0x00)
        // HR: 75 (0x4B)
        // Battery: 100 (0x64)
        
        val data = byteArrayOf(
            0xFE.toByte(), 0xEA.toByte(), 0x00.toByte(), 0x0E.toByte(), 0x08.toByte(),
            0xE8.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(), // 5-8
            0x32.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // 9-12
            0xEE.toByte(), 0x02.toByte(), 0x00.toByte(), 0x00.toByte(), // 13-16
            0x4B.toByte(), // 17
            0x64.toByte()  // 18
        )
        
        val update = MoyoungDecoder.decode(data)
        
        assertNotNull(update)
        assertEquals("Steps mismatch", 1000, update?.steps)
        assertEquals("Distance mismatch", 750, update?.distance)
        assertEquals("HR mismatch", 75, update?.heartRate)
    }

    @Test
    fun testBPFiltering() {
        // Command 0x22 (Potential BP packet - not in hrRelevantCommands)
        // Even if it contains a value in HR range (e.g. 120), it should NOT be picked up as HR
        
        val data = byteArrayOf(
            0xFE.toByte(), 0xEA.toByte(), 0x00.toByte(), 0x02.toByte(), 0x22.toByte(),
            0x78.toByte(), // 120 (Systolic?)
            0x50.toByte()  // 80 (Diastolic?)
        )
        
        val update = MoyoungDecoder.decode(data)
        
        assertNotNull(update)
        assertEquals("HR should be 0 for non-HR commands", 0, update?.heartRate)
    }

    @Test
    fun testStepSyncParsing() {
        // Command 0x33
        // Header: 0xFE, 0xEA, 0x00, 0x06 (size 6), 0x33
        // Data: [unhandled(1)][Steps(4)]
        val data = byteArrayOf(
            0xFE.toByte(), 0xEA.toByte(), 0x00.toByte(), 0x06.toByte(), 0x33.toByte(),
            0x00.toByte(), // unhandled
            0x40.toByte(), 0x1F.toByte(), 0x00.toByte(), 0x00.toByte() // Steps: 8000
        )

        val update = MoyoungDecoder.decode(data)
        assertNotNull(update)
        assertEquals(8000, update?.steps)
    }
}
