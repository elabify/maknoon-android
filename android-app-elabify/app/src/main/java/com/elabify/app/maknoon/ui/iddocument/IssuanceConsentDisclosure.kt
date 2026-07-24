package com.elabify.app.maknoon.ui.iddocument

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R

/**
 * Plain-language "what leaves your phone" disclosure shown before a passport is
 * submitted to an issuer. Shared by the post-scan minted step
 * (TapIDDocumentScreen) and the document detail / Advanced screen
 * (IDDocumentDetailScreen) so the two consent surfaces never drift. ADR-0069.
 */
@Composable
fun IssuanceConsentDisclosure(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val caption = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.id_uploaded_fields_note), style = caption, color = color)
        Text(stringResource(R.string.id_issue_purpose), style = caption, color = color)
        Text(stringResource(R.string.id_issue_no_retention), style = caption, color = color)
        Text(
            stringResource(R.string.settings_privacy_policy_link),
            style = caption,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                uriHandler.openUri("https://elabify.com/support/compliance/privacy-policy/")
            },
        )
    }
}
