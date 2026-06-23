// The shared address book: the saved-contacts store + models (promoted out of
// SettingsMisc so the Settings screen AND the send recipient pickers use one
// store), plus a ContactsPickerSheet the send screens present so a saved address
// can be chosen instead of typed, matching the iOS send contacts picker.

package com.elabify.app.maknoon.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Spacing
import org.json.JSONArray
import org.json.JSONObject

enum class AddressBookNetwork(
    val key: String,
    val displayName: String,
    val icon: ImageVector,
    val tint: Color,
) {
    BITCOIN("bitcoin", "Bitcoin", Icons.Filled.CurrencyBitcoin, MaknoonColors.warning),
    ETHEREUM("ethereum", "Ethereum", Icons.Filled.Diamond, Color(0xFF5B5BD6)),
    LIGHTNING("lightning", "Lightning", Icons.Filled.Bolt, Color(0xFFE6A700)),
    SOLANA("solana", "Solana", Icons.Filled.Hexagon, Color(0xFF9945FF)),
    TRON("tron", "Tron", Icons.Filled.ChangeHistory, MaknoonColors.error);

    companion object {
        fun fromKey(k: String): AddressBookNetwork = entries.firstOrNull { it.key == k } ?: BITCOIN
    }
}

data class AddressBookEntry(
    val id: String,
    val name: String,
    val address: String,
    val network: AddressBookNetwork,
)

// One JSON-array document in a private prefs file. iOS persists this on
// HolderStore.addressBook; Android has no such SDK store. User entries only.
class AddressBookStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<AddressBookEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AddressBookEntry(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    address = o.optString("address"),
                    network = AddressBookNetwork.fromKey(o.optString("network")),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun entries(net: AddressBookNetwork): List<AddressBookEntry> = all().filter { it.network == net }

    fun upsert(entry: AddressBookEntry) {
        persist(all().filter { it.id != entry.id } + entry)
    }

    fun remove(id: String) = persist(all().filter { it.id != id })

    private fun persist(list: List<AddressBookEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id).put("name", e.name)
                    .put("address", e.address).put("network", e.network.key),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "maknoon.addressbook.v1"
        private const val KEY = "entries"
    }
}

/** A row in the "Your wallets" section of the picker: the user's own wallet,
 *  resolved to a receive address. Generic across chains (the caller resolves
 *  the addresses; for Bitcoin the list is already filtered to the active
 *  chain). */
data class OwnWalletEntry(val name: String, val address: String)

/**
 * Bottom-sheet recipient picker for a send screen. Lists the user's OWN wallets
 * for this network first ("Your wallets", from [ownWallets] resolved by the
 * caller) and then their saved contacts ("Contacts", from [AddressBookStore]),
 * mirroring iOS AddressBookPickerSheet. Both sections fill the recipient via
 * [onPick].
 *
 * [bitcoinNetwork] is the active Bitcoin chain (Mainnet / Testnet3 / Signet) on
 * a Bitcoin send: the caller filters [ownWallets] to it so a Testnet3 send never
 * lists Mainnet wallets. It is null for other networks (their addresses are
 * chain-agnostic, so no per-chain filter).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsPickerSheet(
    network: AddressBookNetwork,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    bitcoinNetwork: com.elabify.musnad.wallet.bitcoin.BitcoinNetwork? = null,
    ownWallets: List<OwnWalletEntry> = emptyList(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val entries = androidx.compose.runtime.remember { AddressBookStore(context).entries(network) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            val title = if (bitcoinNetwork != null) {
                "${network.displayName} · ${bitcoinNetwork.displayName}"
            } else {
                network.displayName
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )

            if (ownWallets.isEmpty() && entries.isEmpty()) {
                Text(
                    stringResource(R.string.settings_no_saved_contacts, network.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (ownWallets.isNotEmpty()) {
                Text(
                    stringResource(R.string.settings_your_wallets),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                ownWallets.forEach { w -> PickerRow(w.name, w.address, onPick, onDismiss) }
            }

            if (entries.isNotEmpty()) {
                Text(
                    stringResource(R.string.settings_contacts),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                entries.forEach { e -> PickerRow(e.name, e.address, onPick, onDismiss) }
            }
        }
    }
}

@Composable
private fun PickerRow(name: String, address: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(address); onDismiss() }
            .padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            name.ifEmpty { address },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            address,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
