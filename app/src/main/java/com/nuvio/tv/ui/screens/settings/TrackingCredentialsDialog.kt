@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun TrackingCredentialsDialog(
    state: TrackingCredentialsUiState,
    onSaveTrakt: (String, String) -> Unit,
    onSaveSimkl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var traktId by remember(state.traktClientId) { mutableStateOf(state.traktClientId) }
    var traktSecret by remember { mutableStateOf("") }
    var simklId by remember(state.simklClientId) { mutableStateOf(state.simklClientId) }
    val firstFocusRequester = remember { FocusRequester() }
    val secretFocusRequester = remember { FocusRequester() }
    val simklFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        firstFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tracking_credentials_title),
        subtitle = stringResource(R.string.tracking_credentials_dialog_subtitle),
        width = 720.dp,
        suppressFirstKeyUp = false
    ) {
        CredentialInput(
            label = stringResource(R.string.tracking_credentials_trakt_id),
            value = traktId,
            onValueChange = { traktId = it },
            focusRequester = firstFocusRequester,
            keyboardController = keyboardController
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        CredentialInput(
            label = stringResource(R.string.tracking_credentials_trakt_secret),
            value = traktSecret,
            onValueChange = { traktSecret = it },
            focusRequester = secretFocusRequester,
            placeholder = if (state.traktSecretConfigured) {
                stringResource(R.string.tracking_credentials_saved_secret)
            } else null,
            secret = true,
            keyboardController = keyboardController
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        CredentialInput(
            label = stringResource(R.string.tracking_credentials_simkl_id),
            value = simklId,
            onValueChange = { simklId = it },
            focusRequester = simklFocusRequester,
            keyboardController = keyboardController
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
            SettingsDialogActionButton(
                text = stringResource(R.string.action_save),
                primary = true,
                onClick = {
                    onSaveTrakt(traktId, traktSecret)
                    onSaveSimkl(simklId)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun CredentialInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester? = null,
    placeholder: String? = null,
    secret: Boolean = false,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = { focusRequester?.requestFocus() },
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        colors = CardDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.85f),
            focusedContainerColor = Color.Black.copy(alpha = 0.85f)
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
            )
        ),
        shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.sm)) {
            Text(label, color = NuvioTheme.colors.TextSecondary)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused || it.hasFocus },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                    color = NuvioTheme.colors.TextPrimary
                ),
                cursorBrush = SolidColor(if (focused) Color.White else Color.Transparent),
                decorationBox = { inner ->
                    if (value.isBlank() && !placeholder.isNullOrBlank()) {
                        Text(placeholder, color = NuvioTheme.colors.TextTertiary)
                    }
                    inner()
                }
            )
        }
    }
}
