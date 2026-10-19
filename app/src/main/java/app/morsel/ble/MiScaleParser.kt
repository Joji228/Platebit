package app.morsel.ble

/**
 * One frame broadcast by the Xiaomi Mi Body Composition Scale 2.
 *
 * [weightKg] is always in kilograms whatever unit the scale is set to. [impedance] is null until
 * the scale has finished measuring it (and always null if it was weighed with shoes on).
 */
data class MiScaleReading(
    val weightKg: Double,
    val impedance: Int?,
    /** The weight stopped changing. Earlier frames are the number still settling. */
    val stable: Boolean,
)

/**
 * Reads the 13-byte service data the scale advertises under the Body Composition UUID (0x181B).
 * Nothing is paired or connected and the data isn't encrypted on this model.
 *
 *  byte 0      unit flags (bit 0 = pounds)
 *  byte 1      bit 1 = impedance present, bit 5 = stable, bit 6 = catty, bit 7 = stepped off
 *  bytes 2-8   date and time (unused, the phone's clock is used instead)
 *  bytes 9-10  impedance in ohms, little endian
 *  bytes 11-12 weight, little endian: kg x 200, or pounds / catty x 100
 */
object MiScaleParser {
    const val PACKET_SIZE = 13

    private const val POUNDS = 0x01
    private const val HAS_IMPEDANCE = 0x02
    private const val STABLE = 0x20
    private const val CATTY = 0x40
    private const val REMOVED = 0x80
    private const val KG_PER_POUND = 0.45359237

    /** Impedance outside this range means the scale couldn't get a clean reading. */
    private val VALID_IMPEDANCE = 1..2999
    private val VALID_WEIGHT = 2.0..250.0

    /** Returns null for anything that isn't a usable frame from this scale. */
    fun parse(data: ByteArray?): MiScaleReading? {
        if (data == null || data.size < PACKET_SIZE) return null
        val unitFlags = data[0].toInt() and 0xFF
        val flags = data[1].toInt() and 0xFF
        if (flags and REMOVED != 0) return null

        val raw = u16(data, 11)
        val weightKg = when {
            unitFlags and POUNDS != 0 -> raw / 100.0 * KG_PER_POUND
            flags and CATTY != 0 -> raw / 100.0 / 2.0
            else -> raw / 200.0
        }
        if (weightKg !in VALID_WEIGHT) return null

        val impedance = if (flags and HAS_IMPEDANCE != 0) u16(data, 9).takeIf { it in VALID_IMPEDANCE } else null
        return MiScaleReading(weightKg, impedance, stable = flags and STABLE != 0)
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
