package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.TrackingClientCredentialsStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingCredentialsViewModelTest {

    @Test
    fun `state reflects credentials in store`() {
        val store = mockk<TrackingClientCredentialsStore>(relaxed = true) {
            every { traktClientId() } returns "trakt-id-123"
            every { traktClientSecret() } returns "trakt-secret-456"
            every { simklClientId() } returns "simkl-id-789"
        }

        val viewModel = TrackingCredentialsViewModel(store)
        val state = viewModel.uiState.value

        assertEquals("trakt-id-123", state.traktClientId)
        assertTrue(state.traktSecretConfigured)
        assertEquals("simkl-id-789", state.simklClientId)
    }

    @Test
    fun `saveTrakt delegates to store keeping secret when blank`() {
        val store = mockk<TrackingClientCredentialsStore>(relaxed = true)
        val viewModel = TrackingCredentialsViewModel(store)

        viewModel.saveTrakt("new-id", "")
        verify(exactly = 1) { store.saveTrakt("new-id", null) }

        viewModel.saveTrakt("new-id-2", "new-secret")
        verify(exactly = 1) { store.saveTrakt("new-id-2", "new-secret") }
    }

    @Test
    fun `saveSimkl delegates to store`() {
        val store = mockk<TrackingClientCredentialsStore>(relaxed = true)
        val viewModel = TrackingCredentialsViewModel(store)

        viewModel.saveSimkl("simkl-abc")
        verify(exactly = 1) { store.saveSimkl("simkl-abc") }
    }
}
