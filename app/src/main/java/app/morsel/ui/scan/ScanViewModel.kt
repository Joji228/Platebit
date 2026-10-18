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
                val result = app.ai.analyze(images, note.ifBlank { null })
                app.draft = MealDraft(photos.toList(), note.ifBlank { null }, result)
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

    fun cancelAnalysis() {
        job?.cancel()
        analyzing = false
    }

    /** Skip the AI and fill the meal in by hand. */
    fun manual(onReady: () -> Unit) {
        app.draft = MealDraft(photos.toList(), note.ifBlank { null }, null)
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
