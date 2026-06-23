// Dashboard row for one installed ERC-20 token. Ported from iOS
// EthereumTokenRow: monogram badge (chain-tinted), symbol + name on the
// left, formatted balance on the right, a "Custom" capsule when the entry
// was user-added rather than catalog-curated.
//
// The iOS row uses a remote logo (TrustWallet asset URL) with a monogram
// fallback; Android keeps it to the monogram badge to stay GMS/network
// light here (the logo URL is still available via EthereumSettings.
// tokenLogoURL for a later image-loader pass).

package com.elabify.app.maknoon.ui.wallet.ethereum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.ethereum.EthereumToken

@Composable
internal fun EthereumTokenRow(
    token: EthereumToken,
    rawBalanceHex: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(EthBlue.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                token.symbol.take(4).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = EthBlue,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(token.symbol, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (!token.curated) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFF29900).copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(stringResource(R.string.walletc_custom), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(token.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                rawBalanceHex?.let { formatTokenBalanceHex(it, token.decimals) } ?: "-",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(token.symbol, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Format a raw (hex) token balance into human units, trimming zeros. */
internal fun formatTokenBalanceHex(hex: String, decimals: Int): String {
    val wei = runCatching { com.elabify.musnad.wallet.ethereum.EthereumWeiValue.fromHex(hex) }.getOrNull() ?: return "0"
    return wei.units(decimals).setScale(6, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()
}
