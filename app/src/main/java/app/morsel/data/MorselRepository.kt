package app.morsel.data

import androidx.room.withTransaction
import app.morsel.data.db.FoodItemEntity
import app.morsel.data.db.MealEntity
import app.morsel.data.db.MorselDatabase
import app.morsel.data.db.WaterEntity
import app.morsel.data.db.WeightEntity
import app.morsel.domain.BodyComposition
import app.morsel.domain.UserProfile
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

/** Everything the app stores, minus API keys and photos. Used for export/import and the Google Drive backup. */
@Serializable
data class Backup(
    val version: Int = 2,
    val profile: UserProfile? = null,
    val meals: List<MealEntity> = emptyList(),
    val items: List<FoodItemEntity> = emptyList(),
    val water: List<WaterEntity> = emptyList(),
    val weights: List<WeightEntity> = emptyList(),
    /** Added in version 2. Version 1 files have none, and restoring them leaves settings as they are. */
    val settings: SettingsBackup? = null,
) {
    /** Nothing logged at all: a fresh install, or a phone that was just erased. */
    val isEmpty get() = meals.isEmpty() && water.isEmpty() && weights.isEmpty()
}

const val PHOTO_DAYS = 30

class MorselRepository(private val db: MorselDatabase) {

    fun mealsFor(date: LocalDate) = db.meals().observeDay(date.toString())

    fun waterFor(date: LocalDate) = db.water().observeTotal(date.toString())

    fun weights() = db.weights().observeAll()

    fun dailyKcal(from: LocalDate, to: LocalDate) = db.meals().observeDailyKcal(from.toString(), to.toString())

    suspend fun deleteWeight(id: Long) = db.weights().delete(id)

    suspend fun meal(id: Long) = db.meals().get(id)

    suspend fun saveMeal(meal: MealEntity, items: List<FoodItemEntity>): Long = db.withTransaction {
        val id = if (meal.id == 0L) {
            db.meals().insert(meal)
        } else {
            db.meals().update(meal)
            db.meals().deleteItems(meal.id)
            meal.id
        }
        db.meals().insertItems(items.map { it.copy(id = 0, mealId = id) })
        id
    }

    suspend fun deleteMeal(id: Long) {
        val photo = db.meals().get(id)?.meal?.photoPath
        db.meals().delete(id)
        photo?.let { File(it).delete() }
    }

    /**
     * Rolling photo window: photos older than [days] are deleted and their meals keep only
     * the numbers. Files no meal points to (e.g. from deleted meals) are removed too.
     */
    suspend fun prunePhotos(photos: List<File>, days: Int = PHOTO_DAYS) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val old = db.meals().withPhotosBefore(cutoff)
        old.forEach { meal -> meal.photoPath?.let { File(it).delete() } }
        if (old.isNotEmpty()) db.meals().clearPhotos(old.map { it.id })
        val referenced = db.meals().photoPaths().toSet()
        photos.filter { it.absolutePath !in referenced }.forEach { it.delete() }
        clearMissingPhotos()
    }

    /**
     * Meals pointing at photos that no longer exist (for example after importing a backup made
     * on another phone; backups don't carry photos) drop the link instead of showing an empty box.
     */
    suspend fun clearMissingPhotos() {
        val missing = db.meals().withPhotosBefore(Long.MAX_VALUE).filter { m -> m.photoPath?.let { !File(it).exists() } == true }
        if (missing.isNotEmpty()) db.meals().clearPhotos(missing.map { it.id })
    }

    /** Deletes every saved meal photo; the meals stay logged with their numbers. */
    suspend fun deleteAllPhotos(photos: List<File>) {
        db.meals().clearAllPhotos()
        photos.forEach { it.delete() }
    }

    suspend fun addWater(date: LocalDate, ml: Int) =
        db.water().insert(WaterEntity(date = date.toString(), timestamp = System.currentTimeMillis(), ml = ml))

    suspend fun undoWater(date: LocalDate) = db.water().deleteLast(date.toString())

    suspend fun logWeight(kg: Double) = db.weights().insert(
        WeightEntity(date = LocalDate.now().toString(), timestamp = System.currentTimeMillis(), kg = kg),
    )

    /**
     * A weigh-in from the smart scale; [composition] is null when it was weighed without impedance.
     * Returns the new entry's id, so an automatic save can be undone.
     */
    suspend fun logScaleWeighIn(kg: Double, impedance: Int?, composition: BodyComposition?): Long = db.weights().insert(
        WeightEntity(
            date = LocalDate.now().toString(),
            timestamp = System.currentTimeMillis(),
            kg = kg,
            impedance = impedance,
            fatPct = composition?.fatPct,
            waterPct = composition?.waterPct,
            muscleKg = composition?.muscleKg,
            boneKg = composition?.boneKg,
            leanKg = composition?.leanKg,
        ),
    )

    suspend fun export(profile: UserProfile?, settings: SettingsBackup?) = Backup(
        profile = profile,
        settings = settings,
        meals = db.meals().allMeals(),
        items = db.meals().allItems(),
        water = db.water().all(),
        weights = db.weights().all(),
    )

    suspend fun restore(backup: Backup) = db.withTransaction {
        db.meals().clear()
        db.water().clear()
        db.weights().clear()
        db.meals().insertMeals(backup.meals)
        db.meals().insertItems(backup.items)
        db.water().insertAll(backup.water)
        db.weights().insertAll(backup.weights)
        clearMissingPhotos()
    }
}
