package com.nuvio.tv.core.profile

import com.nuvio.tv.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileAvatarResolverTest {
    private val profile = UserProfile(
        id = 1,
        name = "Test",
        avatarColorHex = "#1E88E5"
    )

    @Test
    fun catalogAvatarIdWinsOverStaleDirectUrl() {
        val result = resolveProfileAvatarImageUrl(
            profile.copy(avatarId = "avatar-1", avatarUrl = "https://old.invalid/avatar.png")
        ) { "https://cdn.example/avatar-1.png" }

        assertEquals("https://cdn.example/avatar-1.png", result)
    }

    @Test
    fun directUrlRemainsFallbackWhenCatalogIsTemporarilyUnavailable() {
        val result = resolveProfileAvatarImageUrl(
            profile.copy(avatarId = "avatar-1", avatarUrl = "https://legacy.example/avatar.png")
        ) { null }

        assertEquals("https://legacy.example/avatar.png", result)
    }

    @Test
    fun urlOnlyLegacyProfileStillWorks() {
        val result = resolveProfileAvatarImageUrl(
            profile.copy(avatarUrl = " https://legacy.example/avatar.png ")
        ) { error("catalog lookup should not be used") }

        assertEquals("https://legacy.example/avatar.png", result)
    }

    @Test
    fun blankValuesResolveToInitialsFallback() {
        val result = resolveProfileAvatarImageUrl(
            profile.copy(avatarId = " ", avatarUrl = " ")
        ) { error("blank ID should not be looked up") }

        assertNull(result)
    }

    @Test
    fun catalogUrlIsTrimmed() {
        val result = resolveProfileAvatarImageUrl(profile.copy(avatarId = "avatar-1")) {
            "  https://cdn.example/avatar-1.png  "
        }

        assertEquals("https://cdn.example/avatar-1.png", result)
    }
}
