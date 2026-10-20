package app.morsel.ui.scan

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.MealDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import app.morsel.ai.AnalysisResult
import app.morsel.data.BarcodeReader
import app.morsel.ai.AiMeal
import app.morsel.ai.AiItem
import java.io.File

class ScanViewModel(private val app: AppContainer) : ViewModel() {
    val photos = mutableStateListOf<File>()
    var note by mutableStateOf("")
    var torch by mutableStateOf(false)
    var analyzing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    private var job: Job? = null

    val canAnalyze get() = photos.isNotEmpty() || note.isNotBlank()

    fun newCaptureFile() = app.photos.newCaptureFile()

    fun addPhoto(file: File) {
        if (photos.size < MAX_PHOTOS) photos += file
    }

    fun removePhoto(file: File) {
        photos.remove(file)
        file.delete()
    }

    fun importUris(uris: List<Uri>) {
        viewModelScope.launch {
            uris.take(MAX_PHOTOS - photos.size).forEach { uri ->
                runCatching { app.photos.importUri(uri) }
                    .onSuccess { photos += it }
                    .onFailure { error = it.message }
            }
        }
    }

    fun analyze(onReady: () -> Unit) {
        if (!canAnalyze || analyzing) return
        // The photos are taken; no reason to keep the flash burning while the meal is analyzed.
        torch = false
        job = viewModelScope.launch {
            analyzing = true
            error = null
            try {
                val useFoodFacts = app.settings.useFoodFacts.first()
                // A product barcode in the photos goes straight to Open Food Facts: exact label
                // values in a second or two, and no AI call at all.
                val fromBarcode = if (useFoodFacts) fromBarcodes() else null
                val result = fromBarcode ?: run {
                    val images = photos.map { app.photos.prepareForAi(it) }
                    val analyzed = app.ai.analyze(images, note.ifBlank { null })
                    if (useFoodFacts) withFoodFacts(analyzed) else analyzed
                }
                app.draft = MealDraft(photos.toList(), note.ifBlank { null }, result, app.clock.today.value)
                reset()
                onReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong"
            } finally {
                analyzing = false
            }
        }
    }

    /**
     * Reads barcodes in the photos on the phone and looks each one up on Open Food Facts. Returns
     * a ready result when at least one product is found (one item each, a serving or 100 g),
     * or null to let the AI handle the photos as usual.
     */
    private suspend fun fromBarcodes(): AnalysisResult? {
        val codes = photos.mapNotNull { BarcodeReader.read(it) }.distinct().take(3)
        if (codes.isEmpty()) return null
        val facts = withTimeoutOrNull(6_000) {
            coroutineScope { codes.map { code -> async { runCatching { app.foodFacts.product(code) }.getOrNull() } }.awaitAll() }
        }?.filterNotNull().orEmpty()
        if (facts.isEmpty()) return null
        val items = facts.map {
            AiItem(
                name = it.label,
                grams = it.servingGrams ?: 100.0,
                kcalPer100 = it.kcalPer100,
                proteinPer100 = it.proteinPer100,
                carbsPer100 = it.carbsPer100,
                fatPer100 = it.fatPer100,
                foodFacts = it.label,
            )
        }
        val ai = app.settings.ai.first()
        return AnalysisResult(
            meal = AiMeal(
                mealName = facts.joinToString(" + ") { it.label }.take(60),
                items = items,
                confidence = "high",
                notes = "Read from the barcode on Open Food Facts. Amount is " +
                    (if (facts.all { it.servingGrams != null }) "one serving" else "100 g or one serving") + "; adjust if you had more or less.",
            ),
            provider = ai.primary,
            model = "",
            sourceLabel = "Open Food Facts · barcode",
        )
    }

    /**
     * Checks the packaged products the AI recognized against Open Food Facts and uses the
     * product's own label values when a close match is found. Lookups run side by side, at most
     * three, and the whole step gives up after a few seconds so a slow network can't hold a scan.
     */
    private suspend fun withFoodFacts(result: AnalysisResult): AnalysisResult {
        val products = result.meal.items.map { it.product.trim() }.filter { it.isNotEmpty() }.distinct().take(3)
        if (products.isEmpty()) return result
        val matches = withTimeoutOrNull(6_000) {
            coroutineScope {
                products.map { product -> async { product to runCatching { app.foodFacts.match(product) }.getOrNull() } }.awaitAll()
            }
        }?.toMap().orEmpty()
        val items = result.meal.items.map { item ->
            val fact = matches[item.product.trim()] ?: return@map item
            item.copy(
                kcalPer100 = fact.kcalPer100,
                proteinPer100 = fact.proteinPer100,
                carbsPer100 = fact.carbsPer100,
                fatPer100 = fact.fatPer100,
                foodFacts = fact.label,
            )
        }
        return result.copy(meal = result.meal.copy(items = items))
    }

    fun cancelAnalysis() {
        job?.cancel()
        analyzing = false
    }

    /** Skip the AI and fill the meal in by hand. */
    fun manual(onReady: () -> Unit) {
        app.draft = MealDraft(photos.toList(), note.ifBlank { null }, null, app.clock.today.value)
        reset()
        onReady()
    }

    private fun reset() {
        photos.clear()
        note = ""
        torch = false
    }

    companion object {
        const val MAX_PHOTOS = 4
    }
}
