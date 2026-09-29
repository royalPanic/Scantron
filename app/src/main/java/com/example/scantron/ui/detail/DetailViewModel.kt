package com.example.scantron.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.data.ContainerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailViewModel(
    val containerId: String,
    private val repository: ContainerRepository
) : ViewModel() {

    val container: StateFlow<Container?> = repository.getContainer(containerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val items: StateFlow<List<ContainerItem>> = repository.getItemsForContainer(containerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filterQuery = MutableStateFlow("")

    fun saveContainer(container: Container) {
        viewModelScope.launch {
            repository.createOrUpdateContainer(container)
        }
    }

    fun deleteContainer(onDeleted: () -> Unit) {
        viewModelScope.launch {
            container.value?.let {
                repository.deleteContainer(it)
                onDeleted()
            }
        }
    }

    fun saveItem(item: ContainerItem) {
        viewModelScope.launch {
            repository.saveItem(item)
        }
    }

    fun updateItemQuantity(item: ContainerItem, newQty: Int) {
        if (newQty <= 0) {
            deleteItem(item)
        } else {
            viewModelScope.launch {
                repository.saveItem(item.copy(quantity = newQty, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun deleteItem(item: ContainerItem) {
        viewModelScope.launch {
            repository.deleteItem(item)
        }
    }

    class Factory(
        private val containerId: String,
        private val repository: ContainerRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DetailViewModel(containerId, repository) as T
        }
    }
}
