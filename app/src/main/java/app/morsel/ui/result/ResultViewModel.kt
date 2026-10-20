package app.morsel.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.data.FoodFact
import app.morsel.data.db.FoodItemEntity
import app.morsel.data.db.MealEntity
import app.morsel.domain.Macros
import app.morsel.domain.MealType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class EditableItem(
    val key: Long,
    val name: String,
    val grams: Double,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val carbsPer100: Double,
    val fatPer100: Double,
    /** The Open Food Facts product these numbers came from, when they did. */
    val foodFacts: String? = null,
) {
    fun macros(multiplier: Double = 1.0) =
        Macros(kcalPer100, proteinPer100, carbsPer100, fatPer100) * (grams * multiplier / 100.0)
}

data class ResultState(
    val loaded: Boolean = false,
    val isEdit: Boolean = false,
    val name: String = "",
    val mealType: MealType = MealType.forTime(),
    val items: List<EditableItem> = emptyList(),
    val multiplier: Double = 1.0,
    val source: String? = null,
    val confidence: String? = null,
    val notes: String? = null,
    val fallbackReason: String? = null,
    val photos: List<File> = emptyList(),
    val savedPhotoPath: String? = null,
    val note: String? = null,
    val saving: Boolean = false,
    /** The day this meal counts towards. */
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
) {
    val total: Macros get() = items.fold(Macros()) { acc, item -> acc + item.macros(multiplier) }

    /** Weight of everything on the plate at the chosen portion. */
    val totalGrams: Double get() = items.sumOf { it.grams } * multiplier
}

/** Size of one +/− press on an item's grams. */
object GramsStep {
    fun forGrams(grams: Double) = if (grams < 50) 5.0 else 10.0
}

class ResultViewModel(private val app: AppContainer, private val mealId: Long) : ViewModel() {
    private val _state = MutableStateFlow(ResultState())
    val state: StateFlow<ResultState> = _state

    private var nextKey = 1L
    private var original: MealEntity? = null

    init {
        viewModelScope.launch {
            if (mealId > 0) {
                val saved = app.repository.meal(mealId) ?: return@launch
                original = saved.meal
                _state.value = ResultState(
                    loaded = true,
                    isEdit = true,
                    name = saved.meal.name,
                    mealType = MealType.parse(saved.meal.mealType),
                    items = saved.items.map {
                        EditableItem(nextKey++, it.name, it.grams, it.kcalPer100, it.proteinPer100, it.carbsPer100, it.fatPer100)
                    },
                    source = saved.meal.source,
                    savedPhotoPath = saved.meal.photoPath,
                    note = saved.meal.note,
                    date = LocalDate.parse(saved.meal.date),
                    today = app.clock.today.value,
                )
            } else {
                val draft = app.draft
                val meal = draft?.result?.meal
                _state.value = ResultState(
                    loaded = true,
                    name = meal?.mealName.orEmpty(),
                    // The AI's pick when it gave one (a chocolate bar is a snack whatever the time), else the time of day.
                    mealType = MealType.fromAi(meal?.mealType) ?: MealType.forTime(),
                    items = meal?.items.orEmpty().map {
                        EditableItem(nextKey++, it.name, it.grams, it.kcalPer100, it.proteinPer100, it.carbsPer100, it.fatPer100, it.foodFacts)
                    },
                    source = draft?.result?.let { "${it.provider.title} · ${it.model}" },
                    confidence = meal?.confidence,
                    notes = meal?.notes?.takeIf { it.isNotBlank() },
                    fallbackReason = draft?.result?.fallbackReason,
                    photos = draft?.photos.orEmpty(),
                    note = draft?.note,
                    date = draft?.date ?: app.clock.today.value,
                    today = app.clock.today.value,
                )
            }
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun setMealType(type: MealType) = _state.update { it.copy(mealType = type) }

    fun setMultiplier(value: Double) = _state.update { it.copy(multiplier = value) }

    fun setGrams(key: Long, grams: Double) = updateItem(key) { it.copy(grams = grams.coerceIn(0.0, 5000.0)) }

    /**
     * One press of the + or − next to an item's grams: 5 g steps under 50 g, 10 g above.
     * Applied to the current value inside the state update, so fast repeats while the button
     * is held can never read a stale amount and skip or double a step.
     */
    fun nudgeGrams(key: Long, direction: Int) = updateItem(key) {
        val step = GramsStep.forGrams(it.grams)
        it.copy(grams = (it.grams + direction * step).coerceIn(0.0, 5000.0))
    }

    fun rename(key: Long, name: String) = updateItem(key) { it.copy(name = name) }

    fun remove(key: Long) = _state.update { s -> s.copy(items = s.items.filterNot { it.key == key }) }

    fun add(name: String, grams: Double, kcal: Double, protein: Double, carbs: Double, fat: Double, foodFacts: String? = null) =
        _state.update { s -> s.copy(items = s.items + EditableItem(nextKey++, name, grams, kcal, protein, carbs, fat, foodFacts)) }

    /** Whether the add-item dialog offers Open Food Facts search (the user's choice in Settings). */
    val foodFactsEnabled = app.settings.useFoodFacts

    suspend fun searchFoods(query: String): List<FoodFact> = app.foodFacts.search(query)

    private fun updateItem(key: Long, transform: (EditableItem) -> EditableItem) =
        _state.update { s -> s.copy(items = s.items.map { if (it.key == key) transform(it) else it }) }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val photo = s.savedPhotoPath ?: s.photos.firstOrNull()?.let { runCatching { app.photos.saveThumbnail(it) }.getOrNull() }
            // A meal added to a past day is stamped with that day at the current time of day.
            val stamp = if (s.date == s.today) System.currentTimeMillis()
            else s.date.atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val meal = MealEntity(
                id = original?.id ?: 0,
                date = original?.date ?: s.date.toString(),
                timestamp = original?.timestamp ?: stamp,
                mealType = s.mealType.name,
                name = s.name.ifBlank { s.items.firstOrNull()?.name ?: "Meal" },
                photoPath = photo,
                source = s.source,
                note = s.note,
            )
            val items = s.items.filter { it.grams > 0 }.map {
                FoodItemEntity(
                    mealId = 0,
                    name = it.name.ifBlank { "Food" },
                    grams = it.grams * s.multiplier,
                    kcalPer100 = it.kcalPer100,
                    proteinPer100 = it.proteinPer100,
                    carbsPer100 = it.carbsPer100,
                    fatPer100 = it.fatPer100,
                )
            }
            app.repository.saveMeal(meal, items)
            if (!s.isEdit) {
                app.draft = null
                app.photos.clearCaptures()
                // New meals always count towards today, so show today instead of leaving you
                // on whichever past day you were looking at.
                app.viewedDay.value = app.clock.today.value
            }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            if (mealId > 0) app.repository.deleteMeal(mealId)
            onDone()
        }
    }

    fun discardDraft() {
        if (!_state.value.isEdit) {
            app.draft = null
            app.photos.clearCaptures()
        }
    }
}
