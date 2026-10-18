package app.morsel.data

import androidx.room.withTransaction
import app.morsel.data.db.FoodItemEntity
import app.morsel.data.db.MealEntity
import app.morsel.data.db.MorselDatabase
import app.morsel.data.db.WaterEntity
import app.morsel.data.db.WeightEntity
import app.morsel.domain.UserProfile
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

/** Everything the app stores, minus API keys. Used for export and import. */
@Serializable
data class Backup(
    val version: Int = 1,
    val profile: UserProfile? = null,
    val meals: List<MealEntity> = emptyList(),
    val items: List<FoodItemEntity> = emptyList(),
    val water: List<WaterEntity> = emptyList(),
    val weights: List<WeightEntity> = emptyList(),
)

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
    }

    suspend fun addWater(date: LocalDate, ml: Int) =
        db.water().insert(WaterEntity(date = date.toString(), timestamp = System.currentTimeMillis(), ml = ml))

    suspend fun undoWater(date: LocalDate) = db.water().deleteLast(date.toString())

    suspend fun logWeight(kg: Double) = db.weights().insert(
        WeightEntity(date = LocalDate.now().toString(), timestamp = System.currentTimeMillis(), kg = kg),
    )

    suspend fun export(profile: UserProfile?) = Backup(
        profile = profile,
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
    }
}
