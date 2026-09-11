@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image

@Composable
internal fun SeekPreviewSettingsItem(
    apiKey: String,
    onSetApiKey: (String) -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var showDialog by remember { mutableStateOf(false) }
    val notSet = stringResource(R.string.sub_not_set)
    SettingsActionRow(
        title = stringResource(R.string.seek_preview_title),
        subtitle = stringResource(R.string.seek_preview_sub),
        value = maskSeekrKey(apiKey, notSet),
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
        onFocused = onFocused,
        enabled = enabled,
        leadingIcon = Icons.Default.Image
    )
    if (showDialog) {
        SeekrApiKeyDialog(
            currentValue = apiKey,
            onSave = { onSetApiKey(it); showDialog = false },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun SeekrApiKeyDialog(
    currentValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.seek_preview_dialog_title),
        subtitle = stringResource(R.string.seek_preview_dialog_subtitle),
        width = 700.dp
    ) {
        Card(
            onClick = { requester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary
                    ),
                    cursorBrush = SolidColor(
                        if (focused) NuvioTheme.colors.Primary else Color.Transparent
                    ),
                    decorationBox = { inner ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.seek_preview_dialog_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        inner()
                    }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_cancel)) }
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { onSave("") },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_clear)) }
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { onSave(value) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

private fun maskSeekrKey(key: String, notSet: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSet
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
