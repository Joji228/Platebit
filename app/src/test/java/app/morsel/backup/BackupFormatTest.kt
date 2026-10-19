package app.morsel.backup

import app.morsel.data.Backup
import app.morsel.data.SettingsBackup
import app.morsel.data.db.FoodItemEntity
import app.morsel.data.db.MealEntity
import app.morsel.data.db.WaterEntity
import app.morsel.data.db.WeightEntity
import app.morsel.domain.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private val sample = Backup(
        profile = UserProfile(),
        meals = listOf(MealEntity(id = 1, date = "2026-10-19", timestamp = 1_000, mealType = "LUNCH", name = "Toast", photoPath = "/data/x.jpg")),
        items = listOf(FoodItemEntity(id = 1, mealId = 1, name = "Bread", grams = 60.0, kcalPer100 = 265.0, proteinPer100 = 9.0, carbsPer100 = 49.0, fatPer100 = 3.2)),
        water = listOf(WaterEntity(id = 1, date = "2026-10-19", timestamp = 2_000, ml = 250)),
        weights = listOf(WeightEntity(id = 1, date = "2026-10-19", timestamp = 3_000, kg = 80.4, impedance = 480, fatPct = 21.5)),
        settings = SettingsBackup(theme = "DARK", accent = "BLUE", engines = "DEEPSEEK,GEMINI", models = mapOf("GEMINI" to "gemini-x")),
    )

    @Test
    fun roundTripsThroughGzip() {
        val bytes = BackupCodec.encode(sample)
        assertEquals(0x1f.toByte(), bytes[0])
        assertEquals(0x8b.toByte(), bytes[1])
        assertEquals(sample, BackupCodec.decode(bytes))
    }

    @Test
    fun readsPlainVersionOneExports() {
        // A file from Settings → Export backup made by Platebit 1.0: no settings, not gzipped.
        val v1 = """{"version":1,"profile":null,"meals":[],"items":[],"water":[{"id":3,"date":"2026-10-01","timestamp":5,"ml":500}],"weights":[]}"""
        val backup = BackupCodec.decode(v1.toByteArray())
        assertEquals(500, backup.water.single().ml)
        assertNull(backup.settings)
    }

    @Test
    fun ignoresFieldsFromNewerVersions() {
        val future = """{"version":9,"meals":[],"somethingNew":{"a":1}}"""
        assertTrue(BackupCodec.decode(future.toByteArray()).isEmpty)
    }

    @Test
    fun fingerprintFollowsTheData() {
        assertEquals(BackupCodec.fingerprint(sample), BackupCodec.fingerprint(sample.copy()))
        assertNotEquals(BackupCodec.fingerprint(sample), BackupCodec.fingerprint(sample.copy(water = emptyList())))
    }

    @Test
    fun emptyMeansNothingLogged() {
        assertTrue(Backup(profile = UserProfile()).isEmpty)
        assertTrue(!sample.isEmpty)
    }

    private fun cloud(rev: String? = "r1", device: String? = "phone-a", meals: Int? = 10) =
        CloudFile(id = "f", name = CloudFile.LATEST, rev = rev, deviceId = device, deviceName = "Galaxy", savedAt = 1L, meals = meals)

    private fun decide(cloud: CloudFile?, lastRev: String? = "r1", device: String = "phone-a", empty: Boolean = false, same: Boolean = false) =
        decideUpload(cloud, lastRev, device, empty, fingerprint = "now", lastFingerprint = if (same) "now" else "before")

    @Test
    fun firstBackupCreatesTheFile() {
        assertEquals(UploadDecision.Create, decide(null, lastRev = null))
    }

    @Test
    fun ownBackupIsOverwritten() {
        assertEquals(UploadDecision.Overwrite(cloud()), decide(cloud()))
    }

    @Test
    fun unchangedDataUploadsNothing() {
        assertEquals(UploadDecision.Unchanged, decide(cloud(), same = true))
    }

    @Test
    fun newPhoneAsksBeforeTouchingAnExistingBackup() {
        assertEquals(UploadDecision.Conflict(cloud()), decide(cloud(), lastRev = null, device = "phone-b"))
    }

    @Test
    fun oldPhoneAsksAfterAnotherPhoneTookOver() {
        // Phone B restored and has since uploaded (new rev, its own id); phone A wakes up.
        val newer = cloud(rev = "r2", device = "phone-b")
        assertEquals(UploadDecision.Conflict(newer), decide(newer, lastRev = "r1", device = "phone-a"))
    }

    @Test
    fun restoredPhoneMayWriteToTheBackupItRestored() {
        // Phone B restored phone A's backup: same rev, different device.
        assertEquals(UploadDecision.Overwrite(cloud()), decide(cloud(), lastRev = "r1", device = "phone-b"))
    }

    @Test
    fun lostUploadReplyStillCountsAsOwnBackup() {
        // The upload reached Drive but the reply didn't reach the phone, so the rev wasn't saved.
        val written = cloud(rev = "r9", device = "phone-a")
        assertEquals(UploadDecision.Overwrite(written), decide(written, lastRev = "r1"))
    }

    @Test
    fun emptyPhoneNeverReplacesABackupWithMeals() {
        assertEquals(UploadDecision.Conflict(cloud()), decide(cloud(), empty = true))
        assertEquals(UploadDecision.Overwrite(cloud(meals = 0)), decide(cloud(meals = 0), empty = true))
    }

    @Test
    fun keepsTheNewestSevenDailyCopies() {
        val days = (1..10).map { day ->
            CloudFile("d$day", CloudFile.snapshotName(java.time.LocalDate.of(2026, 10, day)), null, null, null, null, null)
        }
        val latest = CloudFile("l", CloudFile.LATEST, null, null, null, null, null)
        val stale = staleSnapshots(days.shuffled() + latest)
        assertEquals(listOf("d3", "d2", "d1"), stale.map { it.id })
    }
}
