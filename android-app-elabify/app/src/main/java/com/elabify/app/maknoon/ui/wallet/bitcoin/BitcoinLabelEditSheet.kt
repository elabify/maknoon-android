// Edit-label dialog, ported from iOS BitcoinLabelEditSheet. Reads + writes
// BitcoinLabelStore. Scope is either an address or a (txid, vout) output;
// the scope picks which setter we call. Labels are device-local.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.bitcoin.BitcoinLabelStore

/** Which thing the label applies to. */
sealed class LabelScope {
    data class Address(val address: String) : LabelScope()
    data class Output(val txid: String, val vout: Long) : LabelScope()

    val displayName: String
        get() = when (this) {
            is Address -> shortMiddle(address, 6, 6)
            is Output -> "${shortMiddle(txid, 6, 6)}:$vout"
        }
}

@Composable
internal fun BitcoinLabelEditSheet(
    scope: LabelScope,
    labelStore: BitcoinLabelStore,
    onDismiss: () -> Unit,
) {
    val current = remember(scope) {
        when (scope) {
            is LabelScope.Address -> labelStore.labelForAddress(scope.address)
            is LabelScope.Output -> labelStore.labelForOutput(scope.txid, scope.vout)
        } ?: ""
    }
    var text by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.btc_edit_label)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.btc_label_example)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.btc_label_applies_to, scope.displayName),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = text.trim()
                when (scope) {
                    is LabelScope.Address -> labelStore.setLabelForAddress(trimmed, scope.address)
                    is LabelScope.Output -> labelStore.setLabelForOutput(trimmed, scope.txid, scope.vout)
                }
                onDismiss()
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
