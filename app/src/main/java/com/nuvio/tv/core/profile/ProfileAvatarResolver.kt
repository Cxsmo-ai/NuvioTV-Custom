package com.nuvio.tv.core.profile

import com.nuvio.tv.domain.model.UserProfile

/**
 * Resolves a profile avatar without allowing a stale direct URL to hide a
 * valid catalog avatar. Catalog IDs are durable and are therefore preferred;
 * the URL remains a compatibility fallback for legacy/custom profiles.
 */
internal fun resolveProfileAvatarImageUrl(
    profile: UserProfile?,
    resolveCatalogAvatar: (String) -> String?
): String? {
    if (profile == null) return null

    val avatarId = profile.avatarId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val catalogUrl = avatarId
        ?.let(resolveCatalogAvatar)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (catalogUrl != null) return catalogUrl

    return profile.avatarUrl
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
