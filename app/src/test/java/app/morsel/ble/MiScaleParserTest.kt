package app.morsel.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiScaleParserTest {
    /** Builds a frame the way the scale lays it out; the date bytes are irrelevant to the parser. */
    private fun frame(unit: Int = 0x02, flags: Int, impedance: Int = 0, weightRaw: Int): ByteArray = byteArrayOf(
        unit.toByte(), flags.toByte(),
        0xEA.toByte(), 0x07, 5, 14, 8, 30, 0, // 2026-05-14 08:30:00
        (impedance and 0xFF).toByte(), (impedance shr 8).toByte(),
        (weightRaw and 0xFF).toByte(), (weightRaw shr 8).toByte(),
    )

    @Test
    fun stableWeightWithImpedance() {
        // 76.35 kg is 15270 in the scale's 1/200 kg units; flags 0x22 = stable + impedance present
        val r = MiScaleParser.parse(frame(flags = 0x22, impedance = 512, weightRaw = 15270))!!
        assertEquals(76.35, r.weightKg, 0.001)
        assertEquals(512, r.impedance)
        assertTrue(r.stable)
    }

    @Test
    fun settlingFrameIsNotStable() {
        val r = MiScaleParser.parse(frame(flags = 0x00, weightRaw = 15000))!!
        assertFalse(r.stable)
        assertNull(r.impedance)
    }

    @Test
    fun stableWeightBeforeImpedanceHasNoImpedance() {
        val r = MiScaleParser.parse(frame(flags = 0x20, weightRaw = 15270))!!
        assertTrue(r.stable)
        assertNull(r.impedance)
    }

    @Test
    fun steppedOffFrameIsIgnored() {
        assertNull(MiScaleParser.parse(frame(flags = 0xA2, impedance = 512, weightRaw = 15270)))
    }

    @Test
    fun poundsAreConvertedToKilograms() {
        // 168.00 lb = 16800 in 1/100 lb units
        val r = MiScaleParser.parse(frame(unit = 0x03, flags = 0x20, weightRaw = 16800))!!
        assertEquals(76.204, r.weightKg, 0.001)
    }

    @Test
    fun cattyAreConvertedToKilograms() {
        // 150.00 catty (jin) = 75 kg
        val r = MiScaleParser.parse(frame(unit = 0x02, flags = 0x60, weightRaw = 15000))!!
        assertEquals(75.0, r.weightKg, 0.001)
    }

    @Test
    fun impedanceOutOfRangeIsDropped() {
        assertNull(MiScaleParser.parse(frame(flags = 0x22, impedance = 3500, weightRaw = 15270))!!.impedance)
        assertNull(MiScaleParser.parse(frame(flags = 0x22, impedance = 0, weightRaw = 15270))!!.impedance)
    }

    @Test
    fun weightAboveSignedRangeStaysPositive() {
        // 163.84 kg = 32768, which is negative if read as a signed 16-bit number
        assertEquals(163.84, MiScaleParser.parse(frame(flags = 0x20, weightRaw = 32768))!!.weightKg, 0.001)
    }

    @Test
    fun malformedDataIsRejected() {
        assertNull(MiScaleParser.parse(null))
        assertNull(MiScaleParser.parse(ByteArray(10)))
        assertNull(MiScaleParser.parse(frame(flags = 0x20, weightRaw = 0)))
        assertNull(MiScaleParser.parse(frame(flags = 0x20, weightRaw = 100))) // 0.5 kg: below what the scale can weigh
        assertNotNull(MiScaleParser.parse(frame(flags = 0x20, weightRaw = 400)))
    }
}
