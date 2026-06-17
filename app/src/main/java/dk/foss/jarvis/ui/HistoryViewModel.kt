package dk.foss.jarvis.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationMeta
import dk.foss.jarvis.data.ConversationRepository
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConversationRepository.get(app)
    val items = mutableStateOf<List<ConversationMeta>>(emptyList())

    fun refresh() {
        viewModelScope.launch { items.value = repo.list() }
    }

    /** Persist the current conversation, load the chosen one, then continue. */
    fun open(id: String, onReady: () -> Unit) {
        viewModelScope.launch {
            repo.persist()
            repo.open(id)
            onReady()
        }
    }

    fun startNew(onReady: () -> Unit) {
        viewModelScope.launch {
            repo.persist()
            repo.startNew()
            onReady()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            items.value = repo.list()
        }
    }
}
