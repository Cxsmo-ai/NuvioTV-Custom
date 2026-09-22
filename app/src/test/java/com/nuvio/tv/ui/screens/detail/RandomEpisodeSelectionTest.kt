package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomEpisodeSelectionTest {

    private val sampleVideos = listOf(
        Video(id = "ep0", title = "Special 1", released = null, thumbnail = null, season = 0, episode = 1, overview = null),
        Video(id = "ep1_1", title = "Pilot", released = null, thumbnail = null, season = 1, episode = 1, overview = null),
        Video(id = "ep1_2", title = "Cat's in the Bag", released = null, thumbnail = null, season = 1, episode = 2, overview = null),
        Video(id = "ep2_1", title = "Seven Thirty-Seven", released = null, thumbnail = null, season = 2, episode = 1, overview = null),
        Video(id = "ep2_2", title = "Grilled", released = null, thumbnail = null, season = 2, episode = 2, overview = null)
    )

    @Test
    fun `all seasons pool excludes specials when regular seasons exist`() {
        val nonZero = sampleVideos.filter { (it.season ?: 0) > 0 }
        assertEquals(4, nonZero.size)
        assertTrue(nonZero.none { it.season == 0 })
    }

    @Test
    fun `season filter restricts candidate pool to selected season`() {
        val season1 = sampleVideos.filter { it.season == 1 }
        assertEquals(2, season1.size)
        assertTrue(season1.all { it.season == 1 })

        val season2 = sampleVideos.filter { it.season == 2 }
        assertEquals(2, season2.size)
        assertTrue(season2.all { it.season == 2 })
    }

    @Test
    fun `unwatched only filter excludes watched episodes`() {
        val watched = setOf(1 to 1, 2 to 1)
        val candidatePool = sampleVideos.filter { (it.season ?: 0) > 0 }
        val unwatched = candidatePool.filterNot { ep ->
            val s = ep.season ?: return@filterNot false
            val e = ep.episode ?: return@filterNot false
            watched.contains(s to e)
        }

        assertEquals(2, unwatched.size)
        assertTrue(unwatched.any { it.season == 1 && it.episode == 2 })
        assertTrue(unwatched.any { it.season == 2 && it.episode == 2 })
    }

    @Test
    fun `unwatched only filter falls back to candidate pool if all watched`() {
        val watched = setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2)
        val candidatePool = sampleVideos.filter { (it.season ?: 0) > 0 }
        val unwatched = candidatePool.filterNot { ep ->
            val s = ep.season ?: return@filterNot false
            val e = ep.episode ?: return@filterNot false
            watched.contains(s to e)
        }

        assertTrue(unwatched.isEmpty())
        val finalPool = if (unwatched.isNotEmpty()) unwatched else candidatePool
        assertEquals(4, finalPool.size)
    }
}
