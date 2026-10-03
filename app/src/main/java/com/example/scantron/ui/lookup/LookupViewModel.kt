package com.example.scantron.ui.lookup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.scantron.data.Container
import com.example.scantron.data.ContainerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LookupViewModel(
    private val repository: ContainerRepository
) : ViewModel() {

    val inputId = MutableStateFlow("")
    val filterQuery = MutableStateFlow("")

    val containersList: StateFlow<List<Container>> = combine(
        repository.allContainers,
        filterQuery
    ) { containers, query ->
        if (query.isBlank()) {
            containers
        } else {
            containers.filter {
                it.id.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true) ||
                it.location.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onInputIdChanged(newText: String) {
        inputId.value = newText
    }

    fun onFilterQueryChanged(newText: String) {
        filterQuery.value = newText
    }

    fun openOrCreateContainer(id: String, onNavigate: (String) -> Unit) {
        val cleanId = id.trim().uppercase()
        if (cleanId.isNotBlank()) {
            viewModelScope.launch {
                val existing = repository.getContainerDirect(cleanId)
                if (existing == null) {
                    // Automatically create container shell if it doesn't exist
                    repository.createOrUpdateContainer(Container(id = cleanId))
                }
                onNavigate(cleanId)
            }
        }
    }

    fun createOrSaveContainer(container: Container, onNavigate: (String) -> Unit) {
        val cleanId = container.id.trim().uppercase()
        if (cleanId.isNotBlank()) {
            val updatedContainer = container.copy(
                id = cleanId,
                name = container.name.trim(),
                location = container.location.trim(),
                notes = container.notes.trim(),
                updatedAt = System.currentTimeMillis()
            )
            viewModelScope.launch {
                repository.createOrUpdateContainer(updatedContainer)
                onNavigate(cleanId)
            }
        }
    }

    fun seedSampleData() {
        viewModelScope.launch {
            repository.seedSampleDataIfEmpty()
        }
    }

    /** Total units held by a container, counting each item's quantity. */
    fun getTotalQuantityFlow(containerId: String) = repository.getTotalQuantity(containerId)

    class Factory(private val repository: ContainerRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LookupViewModel(repository) as T
        }
    }
}
