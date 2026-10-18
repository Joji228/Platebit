package app.morsel.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import app.morsel.domain.Macros
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "meals", indices = [Index("date")])
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO date (yyyy-MM-dd) the meal counts towards. */
    val date: String,
    val timestamp: Long,
    val mealType: String,
    val name: String,
    val photoPath: String? = null,
    val source: String? = null,
    val note: String? = null,
)

@Serializable
@Entity(
    tableName = "food_items",
    foreignKeys = [
        ForeignKey(
            entity = MealEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mealId")],
)
data class FoodItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val name: String,
    val grams: Double,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val carbsPer100: Double,
    val fatPer100: Double,
)

data class MealWithItems(
    @Embedded val meal: MealEntity,
    @Relation(parentColumn = "id", entityColumn = "mealId") val items: List<FoodItemEntity>,
)

@Serializable
@Entity(tableName = "water", indices = [Index("date")])
data class WaterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val timestamp: Long,
    val ml: Int,
)

@Serializable
@Entity(tableName = "weights")
data class WeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val timestamp: Long,
    val kg: Double,
)

fun FoodItemEntity.macros() =
    Macros(kcalPer100, proteinPer100, carbsPer100, fatPer100) * (grams / 100.0)

fun MealWithItems.macros() = items.fold(Macros()) { acc, item -> acc + item.macros() }

@Dao
interface MealDao {
    @Transaction
    @Query("SELECT * FROM meals WHERE date = :date ORDER BY timestamp")
    fun observeDay(date: String): Flow<List<MealWithItems>>

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :id")
    suspend fun get(id: Long): MealWithItems?

    @Insert
    suspend fun insert(meal: MealEntity): Long

    @Update
    suspend fun update(meal: MealEntity)

    @Insert
    suspend fun insertItems(items: List<FoodItemEntity>)

    @Query("DELETE FROM food_items WHERE mealId = :mealId")
    suspend fun deleteItems(mealId: Long)

    @Query("DELETE FROM meals WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM meals")
    suspend fun allMeals(): List<MealEntity>

    @Query("SELECT * FROM food_items")
    suspend fun allItems(): List<FoodItemEntity>

    @Insert
    suspend fun insertMeals(meals: List<MealEntity>)

    @Query("DELETE FROM meals")
    suspend fun clear()

    @Query("SELECT * FROM meals WHERE photoPath IS NOT NULL AND timestamp < :cutoff")
    suspend fun withPhotosBefore(cutoff: Long): List<MealEntity>

    @Query("UPDATE meals SET photoPath = NULL WHERE id IN (:ids)")
    suspend fun clearPhotos(ids: List<Long>)

    @Query("SELECT photoPath FROM meals WHERE photoPath IS NOT NULL")
    suspend fun photoPaths(): List<String>

    /** Total calories per logged day, for the adaptive target. */
    @Query(
        """
        SELECT m.date AS date, SUM(f.grams * f.kcalPer100 / 100.0) AS kcal
        FROM meals m JOIN food_items f ON f.mealId = m.id
        WHERE m.date BETWEEN :from AND :to
        GROUP BY m.date
        """,
    )
    fun observeDailyKcal(from: String, to: String): Flow<List<DailyKcal>>
}

data class DailyKcal(val date: String, val kcal: Double)

@Dao
interface WaterDao {
    @Query("SELECT COALESCE(SUM(ml), 0) FROM water WHERE date = :date")
    fun observeTotal(date: String): Flow<Int>

    @Insert
    suspend fun insert(entry: WaterEntity)

    @Query("DELETE FROM water WHERE id = (SELECT id FROM water WHERE date = :date ORDER BY timestamp DESC LIMIT 1)")
    suspend fun deleteLast(date: String)

    @Query("SELECT * FROM water")
    suspend fun all(): List<WaterEntity>

    @Insert
    suspend fun insertAll(entries: List<WaterEntity>)

    @Query("DELETE FROM water")
    suspend fun clear()
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights ORDER BY timestamp")
    fun observeAll(): Flow<List<WeightEntity>>

    @Insert
    suspend fun insert(entry: WeightEntity)

    @Query("DELETE FROM weights WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM weights")
    suspend fun all(): List<WeightEntity>

    @Insert
    suspend fun insertAll(entries: List<WeightEntity>)

    @Query("DELETE FROM weights")
    suspend fun clear()
}

@Database(
    entities = [MealEntity::class, FoodItemEntity::class, WaterEntity::class, WeightEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MorselDatabase : RoomDatabase() {
    abstract fun meals(): MealDao
    abstract fun water(): WaterDao
    abstract fun weights(): WeightDao
}
