package app.morsel.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real upgrade path of every phone that had Platebit before 1.0: a version 1 database with
 * data, upgraded with the app's own migration. Nothing may be lost, and the new body-composition
 * columns start empty.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MorselDatabase::class.java)

    @Test
    fun version1To2KeepsEveryRow() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO meals (id, date, timestamp, mealType, name, photoPath, source, note) VALUES (1, '2026-10-01', 1000, 'LUNCH', 'Soup', NULL, 'DeepSeek', NULL)")
            execSQL("INSERT INTO food_items (id, mealId, name, grams, kcalPer100, proteinPer100, carbsPer100, fatPer100) VALUES (1, 1, 'Soup', 300, 50, 2, 6, 1.5)")
            execSQL("INSERT INTO water (id, date, timestamp, ml) VALUES (1, '2026-10-01', 1000, 500)")
            execSQL("INSERT INTO weights (id, date, timestamp, kg) VALUES (1, '2026-10-01', 1000, 83.4)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, MorselDatabase.MIGRATION_1_2)

        fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count("meals"))
        assertEquals(1, count("food_items"))
        assertEquals(1, count("water"))
        assertEquals(1, count("weights"))
        db.query("SELECT kg, impedance, fatPct, waterPct, muscleKg, boneKg, leanKg FROM weights").use { c ->
            c.moveToFirst()
            assertEquals(83.4, c.getDouble(0), 0.0)
            for (column in 1..6) assertTrue("column $column should start empty", c.isNull(column))
        }
        db.query("SELECT name, source FROM meals").use { c ->
            c.moveToFirst()
            assertEquals("Soup", c.getString(0))
            assertEquals("DeepSeek", c.getString(1))
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}