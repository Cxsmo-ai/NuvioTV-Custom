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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.SkipProviderCredentials
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun SkipProviderCredentialsDialog(
    current: SkipProviderCredentials,
    onSavePublicMetaDb: (String) -> Unit,
    onSaveIntroDbApp: (String) -> Unit,
    onSaveTheIntroDb: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var publicMetaDb by remember(current.publicMetaDbApiKey) { mutableStateOf(current.publicMetaDbApiKey) }
    var introDbApp by remember(current.introDbAppApiKey) { mutableStateOf(current.introDbAppApiKey) }
    var theIntroDb by remember(current.theIntroDbApiKey) { mutableStateOf(current.theIntroDbApiKey) }
    val firstFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "Skip provider credentials",
        subtitle = "Optional user keys. Reads work without keys; keys can include your own pending submissions where supported. Leave blank to clear.",
        width = 760.dp,
        suppressFirstKeyUp = false
    ) {
        CredentialInput(
            label = "PublicMetaDB API key",
            value = publicMetaDb,
            onValueChange = { publicMetaDb = it },
            focusRequester = firstFocus,
            keyboardController = keyboard
        )
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        CredentialInput(
            label = "IntroDB.app API key",
            value = introDbApp,
            onValueChange = { introDbApp = it },
            keyboardController = keyboard
        )
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        CredentialInput(
            label = "TheIntroDB API key",
            value = theIntroDb,
            onValueChange = { theIntroDb = it },
            keyboardController = keyboard
        )
        Spacer(Modifier.height(NuvioTheme.spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            SettingsDialogActionButton(text = "Cancel", onClick = onDismiss)
            Spacer(Modifier.width(NuvioTheme.spacing.xs))
            SettingsDialogActionButton(
                text = "Save",
                primary = true,
                onClick = {
                    onSavePublicMetaDb(publicMetaDb)
                    onSaveIntroDbApp(introDbApp)
                    onSaveTheIntroDb(theIntroDb)
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
        Column(Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.sm)) {
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
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                visualTransformation = PasswordVisualTransformation(),
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                    color = NuvioTheme.colors.TextPrimary
                ),
                cursorBrush = SolidColor(if (focused) Color.White else Color.Transparent)
            )
        }
    }
}
