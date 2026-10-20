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
        job = viewModelScope.launch {
            analyzing = true
            error = null
            try {
                val images = photos.map { app.photos.prepareForAi(it) }
                var result = app.ai.analyze(images, note.ifBlank { null })
                if (app.settings.useFoodFacts.first()) result = withFoodFacts(result)
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
