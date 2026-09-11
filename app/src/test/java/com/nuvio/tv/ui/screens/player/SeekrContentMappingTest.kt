package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.seekr.previews.core.SeekrContent

class SeekrContentMappingTest {
    @Test
    fun mapsMovieTmdbId() {
        val content = seekrContentFor("tmdb:603", "movie", null, null)
        require(content is SeekrContent.Movie)
        assertEquals(603, content.tmdbId)
        assertNull(content.imdbId)
    }

    @Test
    fun mapsEpisodeWithSeasonAndEpisode() {
        val content = seekrContentFor("tmdb:1399", "series", 2, 4)
        require(content is SeekrContent.Episode)
        assertEquals(1399, content.showTmdbId)
        assertEquals(2, content.season)
        assertEquals(4, content.episode)
    }

    @Test
    fun mapsImdbEpisode() {
        val content = seekrContentFor("tt0944947", "tv", 1, 1)
        require(content is SeekrContent.Episode)
        assertEquals("tt0944947", content.showImdbId)
    }

    @Test
    fun rejectsUnknownId() {
        assertNull(seekrContentFor("stremio:custom", "movie", null, null))
    }
}
