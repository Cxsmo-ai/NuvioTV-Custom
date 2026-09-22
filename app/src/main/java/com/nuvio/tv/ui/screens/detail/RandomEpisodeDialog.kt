package com.nuvio.tv.ui.screens.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.random.Random

@Composable
fun RandomEpisodeDialog(
    meta: Meta,
    selectedSeason: Int,
    watchedEpisodes: Set<Pair<Int, Int>>,
    mysteryMode: Boolean,
    onMysteryModeChange: (Boolean) -> Unit,
    unwatchedOnly: Boolean,
    onUnwatchedOnlyChange: (Boolean) -> Unit,
    onPlayEpisode: (video: Video, isMystery: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val episodes = meta.videos.filter { it.season != null && it.episode != null }

    val playRandom: (Int?) -> Unit = { seasonFilter ->
        val candidatePool = if (seasonFilter != null) {
            episodes.filter { it.season == seasonFilter }
        } else {
            val nonZero = episodes.filter { (it.season ?: 0) > 0 }
            if (nonZero.isNotEmpty()) nonZero else episodes
        }

        val finalPool = if (unwatchedOnly) {
            val unwatched = candidatePool.filterNot { ep ->
                val s = ep.season ?: return@filterNot false
                val e = ep.episode ?: return@filterNot false
                watchedEpisodes.contains(s to e)
            }
            if (unwatched.isNotEmpty()) unwatched else candidatePool
        } else {
            candidatePool
        }

        if (finalPool.isEmpty()) {
            Toast.makeText(context, R.string.random_episode_none_available, Toast.LENGTH_SHORT).show()
        } else {
            val chosen = finalPool[Random.nextInt(finalPool.size)]
            onPlayEpisode(chosen, mysteryMode)
            onDismiss()
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.random_episode_title),
        subtitle = stringResource(R.string.random_episode_subtitle, meta.name),
        width = 680.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            SettingsActionRow(
                title = stringResource(R.string.random_episode_all_seasons),
                subtitle = stringResource(R.string.random_episode_all_seasons_sub),
                trailingIcon = Icons.Default.Shuffle,
                onClick = { playRandom(null) }
            )

            if (selectedSeason > 0) {
                SettingsActionRow(
                    title = stringResource(R.string.random_episode_current_season, selectedSeason),
                    subtitle = stringResource(R.string.random_episode_current_season_sub, selectedSeason),
                    trailingIcon = Icons.Default.PlayArrow,
                    onClick = { playRandom(selectedSeason) }
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

            SettingsToggleRow(
                title = stringResource(R.string.random_episode_mystery_mode),
                subtitle = stringResource(R.string.random_episode_mystery_mode_subtitle),
                checked = mysteryMode,
                onToggle = { onMysteryModeChange(!mysteryMode) }
            )

            SettingsToggleRow(
                title = stringResource(R.string.random_episode_unwatched_only),
                subtitle = stringResource(R.string.random_episode_unwatched_only_subtitle),
                checked = unwatchedOnly,
                onToggle = { onUnwatchedOnlyChange(!unwatchedOnly) }
            )
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
        }
    }
}
