package app.morsel.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsel.AppContainer
import app.morsel.data.CoachContext
import app.morsel.data.CoachMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

class CoachViewModel(private val app: AppContainer) : ViewModel() {
    val messages = MutableStateFlow<List<CoachMessage>>(emptyList())

    /** True while the coach is working on an answer. */
    val thinking = MutableStateFlow(false)

    /** Why the last question got no answer; shown in the chat with a Retry button. */
    val error = MutableStateFlow<String?>(null)

    /** False until the saved conversation has been read, so the empty state doesn't flash first. */
    val loaded = MutableStateFlow(false)

    private var job: Job? = null

    init {
        // Turned off (or everything erased) while this chat is alive: its file is gone, so forget it here too.
        viewModelScope.launch {
            app.settings.coachEnabled.collect { on -> if (!on) newChat() }
        }
        viewModelScope.launch {
            messages.value = app.coach.load()
            loaded.value = true
            // Left mid-question last time (the app was closed before the answer came): ask again.
            if (messages.value.lastOrNull()?.fromUser == true) ask()
        }
    }

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || thinking.value || !loaded.value) return
        messages.value = messages.value + CoachMessage(fromUser = true, text = question)
        viewModelScope.launch { app.coach.save(messages.value) }
        ask()
    }

    fun retry() {
        if (!thinking.value && messages.value.lastOrNull()?.fromUser == true) ask()
    }

    fun newChat() {
        job?.cancel()
        thinking.value = false
        error.value = null
        messages.value = emptyList()
        viewModelScope.launch { app.coach.clear() }
    }

    private fun ask() {
        thinking.value = true
        error.value = null
        job = viewModelScope.launch {
            try {
                val context = withContext(Dispatchers.Default) {
                    val data = app.repository.export(null, null)
                    CoachContext.build(LocalDateTime.now(), app.plans.plan.first(), data.meals, data.items, data.water, data.weights)
                }
                val reply = app.ai.chat(context, messages.value)
                messages.value = messages.value + CoachMessage(fromUser = false, text = reply.text)
                app.coach.save(messages.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't reach the AI engine"
            } finally {
                thinking.value = false
            }
        }
    }
}
