// The language picker. Mirror of iOS LanguagePickerView; iOS is the reference.
//
// A dropdown over 32 options is unusable, so this is a searchable screen.
//
// Ordered by ENGLISH name so the order does not rearrange itself when the UI
// language changes, which would be disorienting in the one screen a user needs
// to operate while unable to read the current language. Each row leads with the
// self-name, because a person hunting for 简体中文 must see 简体中文 and not a
// translation of "Chinese (Simplified)".

package com.elabify.app.maknoon.ui.settings

import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.theme.AppLanguage
import com.elabify.app.maknoon.ui.theme.AppLanguageCatalog
import com.elabify.app.maknoon.ui.theme.DisplayPreferences
import com.elabify.app.maknoon.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerScreen(onPicked: () -> Unit, onBack: () -> Unit = onPicked) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val current = DisplayPreferences.language

    val rows = remember(query) {
        val q = query.trim().lowercase()
        val all = AppLanguage.all
        if (q.isEmpty()) all
        else all.filter {
            it.key.lowercase().contains(q) ||
                it.englishName?.lowercase()?.contains(q) == true ||
                it.selfName?.lowercase()?.contains(q) == true ||
                (it.key.isEmpty() && "phone".contains(q))
        }
    }

    // A Scaffold, not a bare Column. Rendered bare, the search field sat at y=0
    // UNDER the status bar (reported as "above the top of the screen"), and with
    // no top bar there was no way back either: the only exit was picking a
    // language, on rows that were not clickable. The one screen a user opens
    // when they cannot read the current language has to be escapable.
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_language)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.settings_language_search)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.key }) { lang ->
                LanguageRow(
                    lang = lang,
                    selected = lang.key == current.key,
                    onClick = {
                        if (lang.key == current.key) {
                            onPicked()
                        } else {
                            DisplayPreferences.language = lang
                            // Recreate the Activity, which is the whole point.
                            //
                            // The locale reaches the app through
                            // LocaleSupport.wrap() in attachBaseContext, and
                            // attachBaseContext runs ONLY when the Activity is
                            // created. Writing the preference updates a state
                            // flow but not the Configuration, so every
                            // stringResource() kept resolving against the old
                            // values-XX/ and the change appeared to need a
                            // restart. recreate() re-runs attachBaseContext, so
                            // the new locale (and its layout direction, which
                            // wrap() also sets) applies immediately. This is the
                            // Android counterpart of the iOS soft restart, the
                            // `.id(prefs.language)` re-root.
                            //
                            // Not a system config change: `locale` is absent
                            // from the Activity's configChanges, but an in-app
                            // preference never raises one, so nothing recreates
                            // on our behalf.
                            (context as? Activity)?.recreate()
                            onPicked()
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    }
}

@Composable
private fun LanguageRow(lang: AppLanguage, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // The row took an onClick and never wired it, so no language could
            // be selected at all. clickable BEFORE padding, so the whole row is
            // the target rather than just the text.
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            // Self-name, or the one translatable label in this list.
            Text(
                lang.selfName ?: stringResource(R.string.settings_language_use_phone),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            val english = lang.englishName
            if (english != null && english != lang.selfName) {
                Text(
                    english,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.common_selected),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Kept so the row can render the count without importing the catalog directly. */
internal val shippedLocaleCount: Int get() = AppLanguageCatalog.all.size
