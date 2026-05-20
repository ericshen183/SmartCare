package com.example.smartcare.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class MoyoungEncoderTest {

    @Test
    fun testCreatePacketEndianness() {
        // Test with a simple command and no data
        // size = data.size + 1 = 0 + 1 = 1
        // Expected: 0xFE, HEADER_EA(0xEA), sizeH(0x00), sizeL(0x01), command(0x08)
        val packet = MoyoungEncoder.getSummaryRequest()
        
        assertEquals(0xFE.toByte(), packet[0])
        assertEquals(0xEA.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2]) // Size High
        assertEquals(0x01.toByte(), packet[3]) // Size Low
        assertEquals(0x08.toByte(), packet[4])
    }

    @Test
    fun testCreateTimeSyncEndianness() {
        // We can't easily mock System.currentTimeMillis() without a library,
        // but we can check if the output matches the expected Big Endian pattern
        // for whatever time it generates.
        
        val packet = MoyoungEncoder.createTimeSync()
        
        // Packet structure:
        // [0] 0xFE
        // [1] 0xEA
        // [2, 3] Size (Short) -> data(5) + 1 = 6. Big Endian: 0x00, 0x06
        // [4] Command (0x31)
        // [5, 6, 7, 8] Timestamp (Int) -> Big Endian
        // [9] Offset (Byte)
        
        assertEquals(0xFE.toByte(), packet[0])
        assertEquals(0xEA.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2])
        assertEquals(0x06.toByte(), packet[3])
        assertEquals(0x31.toByte(), packet[4])
        
        // Extract timestamp from packet[5..8] and check if it's reasonable
        val timestamp = ((packet[5].toInt() and 0xFF) shl 24) or
                        ((packet[6].toInt() and 0xFF) shl 16) or
                        ((packet[7].toInt() and 0xFF) shl 8) or
                        (packet[8].toInt() and 0xFF)
        
        val nowSeconds = (System.currentTimeMillis() / 1000).toInt()
        // Allow a small delta for execution time
        assert(Math.abs(nowSeconds - timestamp) < 5) { "Timestamp $timestamp is not close to $nowSeconds" }
        
        // Check offset byte
        val expectedOffset = (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000).toByte()
        assertEquals(expectedOffset, packet[9])
    }
}
