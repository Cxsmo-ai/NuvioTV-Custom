package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.nuvio.tv.data.local.TrackingClientCredentialsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class TrackingCredentialsUiState(
    val traktClientId: String = "",
    val simklClientId: String = "",
    val traktSecretConfigured: Boolean = false
)

@HiltViewModel
class TrackingCredentialsViewModel @Inject constructor(
    private val store: TrackingClientCredentialsStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(readState())
    val uiState: StateFlow<TrackingCredentialsUiState> = _uiState.asStateFlow()

    fun saveTrakt(clientId: String, clientSecret: String) {
        // A blank secret means “keep the saved secret” when the editor is used
        // only to correct the client ID. An explicitly supplied value replaces it.
        store.saveTrakt(clientId, clientSecret.takeIf { it.isNotBlank() })
        _uiState.value = readState()
    }

    fun saveSimkl(clientId: String) {
        store.saveSimkl(clientId)
        _uiState.update { readState() }
    }

    fun refresh() {
        _uiState.value = readState()
    }

    private fun readState() = TrackingCredentialsUiState(
        traktClientId = store.traktClientId(),
        simklClientId = store.simklClientId(),
        traktSecretConfigured = store.traktClientSecret().isNotBlank()
    )
}
