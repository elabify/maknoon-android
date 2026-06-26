// The retail passport detail (Android mirror of iOS PassportCardDetailView,
// ADR-0039): one navy hero card merging the scanned chip with its pinned-network
// anchors. Replaces IDDocumentDetailScreen as the primary passport screen; the
// technical table + management actions move behind "Advanced options" (the
// existing IDDocumentDetailScreen, reached via onAdvanced).
//
// Pure display + callbacks: the caller (IdentityScreen) wires Show QR to the
// existing present flow, Share to a share intent, and Advanced to the detail
// screen. Dates render ISO 8601 (YYYY-MM-DD); the genuine seal shows green
// "Verified" for a full CSCA-verified chip and blue "Genuine" for a chip whose
// signer just isn't in the on-device trust list.

package com.elabify.app.maknoon.ui.iddocument

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.iddocument.IDDocument
import com.elabify.app.maknoon.iddocument.PassiveAuthResult
import com.elabify.app.maknoon.ui.components.CardPalette
import com.elabify.musnad.present.AnchorEntry
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportCardDetailScreen(
    document: IDDocument,
    photo: ImageBitmap?,
    anchors: List<AnchorEntry>,
    passiveAuth: PassiveAuthResult?,
    canShowQr: Boolean,
    onShowQr: () -> Unit,
    onShare: () -> Unit,
    onAdvanced: () -> Unit,
    onBack: () -> Unit,
    passiveAuthRunning: Boolean = false,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.passport_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroCard(document, photo, anchors, passiveAuth, passiveAuthRunning)

            Button(
                onClick = onShowQr,
                enabled = canShowQr,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.passport_share_qr))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.passport_action_share))
                }
                OutlinedButton(onClick = onAdvanced, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.passport_action_advanced))
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    doc: IDDocument,
    photo: ImageBitmap?,
    anchors: List<AnchorEntry>,
    passiveAuth: PassiveAuthResult?,
    passiveAuthRunning: Boolean = false,
) {
    val palette = CardPalette.forSchema("elabify://schema/global/passport/v1")
    val fg = palette.foreground
    val shape = RoundedCornerShape(22.dp)
    // The launcher icon is an adaptive-icon XML, which painterResource cannot
    // load (it supports vectors / raster only). Render it to a bitmap instead.
    val context = LocalContext.current
    val maknoonLogo = remember {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()?.asImageBitmap()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.brush)
            .border(0.5.dp, fg.copy(alpha = 0.08f), shape)
            .padding(15.dp),
    ) {
        // header: passport icon + "Passport" + version chip ... Maknoon logo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ContactPage, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.passport_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = fg)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, fg.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text("v${doc.schemaVersion}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
            }
            Spacer(Modifier.weight(1f))
            maknoonLogo?.let { logo ->
                Image(
                    bitmap = logo,
                    contentDescription = "Maknoon",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(0.5.dp, fg.copy(alpha = 0.25f), RoundedCornerShape(6.dp)),
                )
            }
        }

        // issuer + passport number
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                flagEmoji(doc.issuingAuthority).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 13.sp) }
                Text(stringResource(R.string.passport_issued_by, issuerCode(doc.issuingAuthority)), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.passport_number_label), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = 0.7f))
                Text(doc.documentNumber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = fg)
            }
        }

        // photo + fields
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .width(88.dp)
                    .height(108.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(fg.copy(alpha = 0.12f))
                    .border(0.5.dp, fg.copy(alpha = 0.3f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (photo != null) {
                    Image(bitmap = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(108.dp))
                } else {
                    Text(monogram(doc), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = fg.copy(alpha = 0.8f))
                }
            }
            // SelectionContainer so the holder can long-press to select + copy
            // any attribute value (iOS textSelection parity).
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Field(stringResource(R.string.passport_field_surname), cleanName(doc.latinSurname ?: doc.surname), fg)
                    Field(stringResource(R.string.passport_field_given_names), cleanName(doc.latinGivenNames ?: doc.givenNames), fg)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Field(stringResource(R.string.passport_field_nationality), issuerCode(doc.nationality), fg)
                        doc.sex?.takeIf { it.isNotEmpty() }?.let { Field(stringResource(R.string.passport_field_sex), it.uppercase(), fg) }
                        Field(stringResource(R.string.passport_field_dob), isoDate(doc.dateOfBirth, DateKind.BIRTH), fg)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Field(stringResource(R.string.passport_field_issued), issueDate(doc.dg12) ?: "—", fg)
                        Field(stringResource(R.string.passport_field_expires), isoDate(doc.dateOfExpiry, DateKind.EXPIRY), fg)
                    }
                    doc.formattedPlaceOfBirth?.takeIf { it.isNotEmpty() }?.let { Field(stringResource(R.string.passport_field_place_of_birth), it, fg) }
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 9.dp).height(1.dp).background(fg.copy(alpha = 0.16f)))

        GenuineSeal(passiveAuth, fg, passiveAuthRunning)

        // Production chains always; testnet pins (Sepolia, Base Sepolia) only when
        // the holder opted in (Settings, Identity, Advanced, "Show testnet
        // anchors"). The credential itself always shows (ADR-0040 / ADR-0043).
        val showTestnet = com.elabify.app.maknoon.ui.settings.TestnetAnchorSettings.showTestnetAnchors
        val shownAnchors = anchors.filter { isProductionChain(it.chain) || (showTestnet && chainIsTestnet(it.chain)) }
        if (shownAnchors.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            PinnedStrip(shownAnchors, fg)
        }
    }
}

// iOS Text packs label + value tightly (no extra leading); Android Text adds
// includeFontPadding + loose line height by default, which inflates the gap.
// Trim both so the label sits right above the value like iOS.
private val TightText = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

@Composable
private fun Field(label: String, value: String, fg: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            label,
            fontSize = 9.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg.copy(alpha = 0.7f),
            style = TightText,
        )
        Text(
            value,
            fontSize = 15.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            style = TightText,
        )
    }
}

@Composable
private fun GenuineSeal(r: PassiveAuthResult?, fg: Color, running: Boolean = false) {
    // While the check is in flight on first open and no result exists yet, show
    // a neutral "Checking…" state instead of a premature "Not verified".
    if (running && r == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = fg)
            Text(stringResource(R.string.passport_seal_checking), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg)
        }
        return
    }
    // Green "Verified" for a full CSCA-verified chip; blue "Genuine" for a
    // genuine chip whose signer isn't in the on-device trust list; red on a
    // real failure; grey when not yet run. Mirrors iOS genuineState.
    val (label, color, verified) = when (r?.status) {
        PassiveAuthResult.Status.VERIFIED -> Triple(stringResource(R.string.passport_seal_verified), Color(0xFF34D399), true)
        PassiveAuthResult.Status.INTEGRITY_ONLY -> Triple(stringResource(R.string.passport_seal_genuine), Color(0xFF3B82F6), true)
        PassiveAuthResult.Status.FAILED -> Triple(stringResource(R.string.passport_seal_failed), Color(0xFFF87171), false)
        else -> Triple(stringResource(R.string.passport_seal_unverified), Color.Gray, false)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            modifier = Modifier.size(21.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (verified) Icons.Filled.Check else if (r?.status == PassiveAuthResult.Status.FAILED) Icons.Filled.Close else Icons.Filled.QuestionMark,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun PinnedStrip(anchors: List<AnchorEntry>, fg: Color) {
    val primary = anchors.firstOrNull()
    val explorer = primary?.let { explorerUrl(it.chain, it.registry) }
    val uriHandler = LocalUriHandler.current
    val linkColor = Color(0xFF9DC0FF)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(fg.copy(alpha = 0.07f))
            .border(1.dp, fg.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (explorer != null) {
            // A real hyperlink: accent colour + underline, opening the registry
            // contract on the chain's block explorer.
            Row(
                modifier = Modifier.clickable { uriHandler.openUri(explorer) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.Link, contentDescription = null, tint = linkColor, modifier = Modifier.size(13.dp))
                Text(
                    stringResource(R.string.passport_pinned_registry),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                )
                Text("↗", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = linkColor)
            }
        } else {
            Text(stringResource(R.string.passport_pinned_registry), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            anchors.take(3).forEachIndexed { idx, a ->
                ChainChip(a.chain, pinned = idx == 0, fg = fg)
            }
            if (anchors.size > 3) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(fg.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+${anchors.size - 3}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg)
                }
            }
        }
    }
}

@Composable
private fun ChainChip(chain: String, pinned: Boolean, fg: Color) {
    val res = chainDrawable(chain)
    val ring = if (pinned) Color(0xFFF5C542) else Color.White.copy(alpha = 0.25f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(if (pinned) 2.dp else 1.dp, ring, CircleShape)
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (res != null) {
                Image(painter = painterResource(res), contentDescription = chain, modifier = Modifier.fillMaxWidth())
            } else {
                Text(chain.take(1).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
        if (chainIsTestnet(chain)) {
            // A small red rounded-rect label naming the testnet (e.g. "Sepolia"),
            // mirroring the iOS pill so a testnet anchor never reads as production
            // trust.
            Text(
                chainTestnetLabel(chain),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                lineHeight = 9.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFE5484D))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

// ── helpers ────────────────────────────────────────────────────────────────

private enum class DateKind { BIRTH, EXPIRY }

/** MRZ YYMMDD -> ISO 8601 "YYYY-MM-DD" with century inference (birth: sliding
 *  window vs current year; expiry: always 2000s). "—" on malformed input. */
private fun isoDate(yymmdd: String, kind: DateKind): String {
    if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return "—"
    val yy = yymmdd.substring(0, 2).toInt()
    val century = when (kind) {
        DateKind.BIRTH -> {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val currentYY = cal.get(Calendar.YEAR) % 100
            if (yy <= currentYY) 2000 else 1900
        }
        DateKind.EXPIRY -> 2000
    }
    return "${century + yy}-${yymmdd.substring(2, 4)}-${yymmdd.substring(4, 6)}"
}

/** Date of issue from DG12 tag 0x5F26 (ASCII "YYYYMMDD") -> "YYYY-MM-DD".
 *  null when DG12 is absent or doesn't carry it (the field then shows "—").
 *  We do NOT substitute the estimated issue date here. */
private fun issueDate(dg12: ByteArray?): String? {
    val b = dg12 ?: return null
    var i = 0
    while (i + 2 < b.size) {
        if (b[i] == 0x5F.toByte() && b[i + 1] == 0x26.toByte()) {
            // BER-TLV length: short form (0x08) OR long form one-byte (0x81 0x08).
            // Some issuers encode DG12 0x5F26 long-form, which the old short-form
            // -only scan missed (the date then never appeared).
            var p = i + 2
            var len = b[p].toInt() and 0xFF
            if (len == 0x81 && p + 1 < b.size) { p += 1; len = b[p].toInt() and 0xFF }
            val start = p + 1
            if (len == 8 && start + len <= b.size) {
                val s = String(b, start, len, Charsets.US_ASCII)
                if (s.length == 8 && s.all { it.isDigit() }) {
                    return "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}"
                }
            }
        }
        i++
    }
    return null
}

private fun cleanName(s: String): String {
    val cleaned = s.replace("<", " ").split(" ").filter { it.isNotEmpty() }.joinToString(" ")
    return cleaned.ifEmpty { "—" }
}

/** alpha-3 -> alpha-2 (ISO 3166) for the 2-letter codes; falls back to the
 *  uppercased input. */
private fun issuerCode(alpha3: String): String =
    com.elabify.app.maknoon.iddocument.ISO3166.alpha2(alpha3) ?: alpha3.uppercase()

/** alpha-3 -> flag emoji via alpha-2 regional indicators. Empty when unknown.
 *  Mirrors iOS PassportCardDetailView.flagEmoji(forAlpha3:). */
internal fun flagEmoji(alpha3: String): String {
    val a2 = com.elabify.app.maknoon.iddocument.ISO3166.alpha2(alpha3) ?: return ""
    if (a2.length != 2) return ""
    val base = 0x1F1E6
    val sb = StringBuilder()
    for (ch in a2.uppercase()) {
        if (ch !in 'A'..'Z') return ""
        sb.appendCodePoint(base + (ch.code - 'A'.code))
    }
    return sb.toString()
}

private fun monogram(doc: IDDocument): String {
    val given = (doc.latinGivenNames ?: doc.givenNames).trim()
    val family = (doc.latinSurname ?: doc.surname).trim()
    val a = given.firstOrNull()?.toString() ?: ""
    val f = family.firstOrNull()?.toString() ?: ""
    val m = (a + f).uppercase()
    return m.ifEmpty { "ID" }
}

/** Chains shown to end users. Testnets (Sepolia eip155:11155111, Base Sepolia
 *  eip155:84532, anvil, devnets) are anchored for testing but never rendered in
 *  the clients - they are admin-only in the issuer console (ADR-0040). Explicit
 *  production allowlist, not a testnet heuristic (CAIP-2 84532 has no "sepolia"
 *  substring). Mirrors iOS ChainMark.isProduction. */
private val PRODUCTION_CHAINS = setOf("eip155:1", "eip155:8453")

fun isProductionChain(chain: String): Boolean = chain.lowercase() in PRODUCTION_CHAINS

/** True for a testnet anchor (drives the red testnet pill). */
private fun chainIsTestnet(chain: String): Boolean {
    val c = chain.lowercase()
    return c == "eip155:11155111" || c == "eip155:84532" || c.contains("sepolia") ||
        c.contains("testnet") || c.contains("devnet") || c.contains("goerli")
}

/** Block-explorer URL for the registry contract address on a chain; null when
 *  the chain has no contract-address explorer. Mirrors iOS ChainMark. */
private fun explorerUrl(chain: String, address: String): String? {
    val id = chain.lowercase()
    val base = when {
        id == "eip155:1" -> "https://etherscan.io/address/"
        id == "eip155:11155111" -> "https://sepolia.etherscan.io/address/"
        id == "eip155:8453" -> "https://basescan.org/address/"
        id == "eip155:84532" -> "https://sepolia.basescan.org/address/"
        id == "eip155:42161" -> "https://arbiscan.io/address/"
        id == "eip155:137" -> "https://polygonscan.com/address/"
        id.startsWith("solana:") -> "https://explorer.solana.com/address/"
        id.startsWith("tron:") || id.contains("tron") -> "https://tronscan.org/#/contract/"
        else -> null
    } ?: return null
    var s = base + address
    if (id.startsWith("solana:") && (id.contains("devnet") || id.contains("testnet"))) s += "?cluster=devnet"
    return s
}

/** Brand-logo drawable for an anchor chain (name or CAIP-2). null -> glyph. */
private fun chainDrawable(chain: String): Int? {
    val c = chain.lowercase()
    return when {
        // Base mainnet (8453) + Base Sepolia (84532) carry the Base mark.
        c == "eip155:8453" || c == "eip155:84532" || c.contains("base") -> R.drawable.ic_chain_base
        c.contains("eip155") || c.contains("eth") -> R.drawable.ic_chain_ethereum
        c.contains("solana") || c.contains("sol") -> R.drawable.ic_chain_solana
        c.contains("bip122") || c.contains("bitcoin") || c.contains("btc") -> R.drawable.ic_chain_bitcoin
        c.contains("tron") || c.contains("trx") -> R.drawable.ic_chain_tron
        c.contains("light") -> R.drawable.ic_chain_lightning
        else -> null
    }
}

/** Short red caption for a testnet anchor chip (e.g. "Sepolia", "Devnet"). */
private fun chainTestnetLabel(chain: String): String {
    val c = chain.lowercase()
    return when {
        c.contains("sepolia") || c == "eip155:11155111" || c == "eip155:84532" -> "Sepolia"
        c.contains("devnet") -> "Devnet"
        c.contains("goerli") -> "Goerli"
        else -> "TEST"
    }
}
