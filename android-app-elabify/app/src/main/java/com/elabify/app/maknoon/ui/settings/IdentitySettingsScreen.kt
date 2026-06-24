// Settings > Identity. Ported 1:1 from the iOS IdentitySettingsView.swift.
//
// iOS IdentitySettingsView is a grouped Form whose body lists five sections, in
// this exact order:
//   1. biometricSection    ("On-device authorization")  - always-on Secure
//      Enclave signing row + "Always on. Cannot be disabled." footer.
//   2. registeredSection   ("Hardware second factor")    - identity-capable
//      registered devices (or an empty-state explainer), with the wrapping
//      footer.
//   3. knownIssuersSection ("Known issuers")             - the issuer allow-list
//      with a live health shield per host, swipe-to-remove, and an add field.
//   4. cscaSection         ("Passport trust list (CSCA)")- the on-device CSCA
//      bundle state + "Update now".
//   5. footerSection       (footer only)                 - the FIDO2 / entitlement
//      note.
//
// On Android we render the same five sections, in the same order, as
// section-grouped rows inside a scrollable Column under a Scaffold titled
// "Identity". The grouped iOS Form Sections become rounded "section card" groups
// with a SectionHeader above and the iOS section footer as a small caption
// below.
//
// Backing logic, all reused / preserved:
//   * Hardware second factor: DeviceRegistry(context).devices filtered to the
//     identity-promoted devices (the same filter DevicesScreen uses,
//     promotions.identity != null). iOS uses store.devices.devicesSupporting(
//     .identity); the Android SDK exposes no devicesSupporting(...) capability
//     accessor, so this lists the devices actually enrolled into Identity.
//   * Known issuers: a small persisted allow-list (KnownIssuersStore, in this
//     package) implementing the existing KnownIssuersProvider interface, seeded
//     with the default issuer host. iOS uses store.knownIssuers; the Android SDK
//     ships only the read-only KnownIssuersProvider interface (no concrete
//     persisted store), so this provides the add/remove/persist the iOS section
//     needs against that same interface.
//   * Health shield: IssuerHealthCheck (this file) re-runs GET {host}/v1/issuer/
//     info over the SDK MaknoonHttp client, exactly the iOS IssuerHealthCheck.
//   * Passport trust list: the existing CSCATrustStore (iddocument package).

package com.elabify.app.maknoon.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.iddocument.CSCATrustStore
import com.elabify.app.maknoon.iddocument.KnownIssuersProvider
import com.elabify.app.maknoon.ui.components.AdvancedSection
import com.elabify.app.maknoon.ui.components.SectionHeader
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val registry = remember { DeviceRegistry(context) }
    val knownIssuers = remember { KnownIssuersStore(context) }
    val csca = remember { CSCATrustStore(context) }

    // identity-capable / identity-promoted registered devices (see file note).
    val identityDevices = remember { registry.devices.filter { it.promotions.identity != null } }

    // Issuer allow-list, observed locally so add/remove re-render.
    val hosts = remember { mutableStateListOf<String>().apply { addAll(knownIssuers.hosts) } }
    var newIssuerDraft by remember { mutableStateOf("") }

    // Live health per known-issuer host (drives the shield).
    val health = remember { mutableStateMapOf<String, IssuerHealth>() }

    // CSCA trust-list state.
    var cscaVersion by remember { mutableStateOf<String?>(null) }
    var cscaCount by remember { mutableStateOf<Int?>(null) }
    var cscaRefreshedAt by remember { mutableStateOf<Long?>(null) }
    var cscaUpdating by remember { mutableStateOf(false) }

    fun loadCscaState() {
        cscaVersion = csca.version
        cscaCount = csca.certCount
        cscaRefreshedAt = csca.lastRefreshedAt
    }

    // Re-check every known issuer whenever the host list changes (add/remove).
    LaunchedEffect(hosts.toList()) {
        for (host in hosts) {
            val base = knownIssuers.outboundBaseUrl(host)
            if (base == null) {
                health[host] = IssuerHealth.Invalid("bad host")
                continue
            }
            health[host] = IssuerHealth.Checking
            health[host] = IssuerHealthCheck.check(base)
        }
    }
    LaunchedEffect(Unit) { loadCscaState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_identity)) },
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
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            BiometricSection()
            // Advanced: the technical controls a normal user rarely touches live
            // here (0.6.1 friendliness pass), collapsed by default.
            AdvancedSection {
                ShowTestnetAnchorsSection()
                RegisteredSection(identityDevices)
                KnownIssuersSection(
                    hosts = hosts,
                    health = health,
                    newIssuerDraft = newIssuerDraft,
                    onDraftChange = { newIssuerDraft = it },
                    onAdd = {
                        val raw = newIssuerDraft
                        newIssuerDraft = ""
                        knownIssuers.add(raw)
                        hosts.clear()
                        hosts.addAll(knownIssuers.hosts)
                    },
                    onRemove = { host ->
                        knownIssuers.remove(host)
                        health.remove(host)
                        hosts.clear()
                        hosts.addAll(knownIssuers.hosts)
                    },
                )
                CscaSection(
                    count = cscaCount,
                    version = cscaVersion,
                    refreshedAt = cscaRefreshedAt,
                    updating = cscaUpdating,
                    firstHost = hosts.firstOrNull(),
                    onUpdate = {
                        cscaUpdating = true
                        csca.refresh(force = true)
                        loadCscaState()
                        cscaUpdating = false
                    },
                )
                PresentationRelaySection()
            }
        }
    }
}

// MARK: -- always-on biometric / passcode

@Composable
private fun BiometricSection() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(title = stringResource(R.string.settings_on_device_authorization))
        SectionCardGroup {
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Filled.Memory, contentDescription = null, tint = MaknoonColors.info, modifier = Modifier.size(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(R.string.settings_secure_enclave_signing),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.settings_secure_enclave_signing_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// MARK: -- show testnet anchors (advanced opt-in)

@Composable
private fun ShowTestnetAnchorsSection() {
    var enabled by remember { mutableStateOf(TestnetAnchorSettings.showTestnetAnchors) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionCardGroup {
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_show_testnet_anchors), modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; TestnetAnchorSettings.showTestnetAnchors = it },
                )
            }
        }
        FooterCaption(stringResource(R.string.settings_show_testnet_anchors_footer))
    }
}

// MARK: -- registered devices that can protect Identity

@Composable
private fun RegisteredSection(identityDevices: List<RegisteredDevice>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(title = stringResource(R.string.settings_hardware_second_factor))
        SectionCardGroup {
            if (identityDevices.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        stringResource(R.string.settings_no_identity_devices),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.settings_register_device_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                identityDevices.forEach { dev -> DeviceRow(dev) }
            }
        }
        FooterCaption(
            stringResource(R.string.settings_hardware_second_factor_footer),
        )
    }
}

@Composable
private fun DeviceRow(dev: RegisteredDevice) {
    // On Android, a device only appears here once it is identity-promoted, so it
    // is always "active" (the iOS view also lists not-yet-enabled capable
    // devices; the Android SDK has no capability accessor, so only enrolled
    // devices show). The iOS "Configure" NavigationLink targets DeviceDetailView,
    // which has no Android equivalent yet, so the per-device drill-in is omitted.
    Row(
        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = if (dev.kind == DeviceKind.LEDGER) Icons.Filled.Memory else Icons.Filled.Key,
            contentDescription = null,
            tint = MaknoonColors.success,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(dev.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.settings_device_kind_serial, dev.kind.displayName, dev.serialDisplay),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Icon(Icons.Filled.GppGood, contentDescription = null, tint = MaknoonColors.success, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.settings_enabled), style = MaterialTheme.typography.labelSmall, color = MaknoonColors.success)
            }
        }
    }
}

// MARK: -- known issuers allow-list

@Composable
private fun KnownIssuersSection(
    hosts: List<String>,
    health: Map<String, IssuerHealth>,
    newIssuerDraft: String,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(title = stringResource(R.string.settings_known_issuers))
        SectionCardGroup {
            hosts.forEach { host ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        StatusIcon(health[host])
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            host,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        statusSubtitle(health[host])?.let { sub ->
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    IconButton(onClick = { onRemove(host) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_remove), tint = MaknoonColors.error, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = newIssuerDraft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.settings_issuer_host_placeholder), fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                IconButton(
                    onClick = onAdd,
                    enabled = newIssuerDraft.trim().isNotEmpty(),
                ) {
                    Icon(Icons.Filled.AddCircle, contentDescription = stringResource(R.string.settings_add_issuer), tint = MaknoonBrand.accent)
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: IssuerHealth?) {
    when (status ?: IssuerHealth.Checking) {
        IssuerHealth.Checking ->
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        is IssuerHealth.Healthy ->
            Icon(Icons.Filled.GppGood, contentDescription = null, tint = MaknoonColors.success, modifier = Modifier.size(18.dp))
        is IssuerHealth.Unreachable ->
            Icon(Icons.Filled.GppMaybe, contentDescription = null, tint = MaknoonColors.warning, modifier = Modifier.size(18.dp))
        is IssuerHealth.Invalid ->
            Icon(Icons.Filled.GppBad, contentDescription = null, tint = MaknoonColors.error, modifier = Modifier.size(18.dp))
    }
}

private fun statusSubtitle(status: IssuerHealth?): String? = when (status) {
    null -> null
    IssuerHealth.Checking -> "Checking..."
    is IssuerHealth.Healthy -> "Verified - ${status.did}"
    is IssuerHealth.Unreachable -> "Unreachable - ${status.reason}"
    is IssuerHealth.Invalid -> "Not a valid issuer - ${status.reason}"
}

// MARK: -- passport trust list (CSCA)

@Composable
private fun CscaSection(
    count: Int?,
    version: String?,
    refreshedAt: Long?,
    updating: Boolean,
    firstHost: String?,
    onUpdate: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(title = stringResource(R.string.settings_passport_trust_list))
        SectionCardGroup {
            KeyValueRow(
                stringResource(R.string.settings_trust_list),
                count?.let { stringResource(R.string.settings_certificates_count, it.toString()) }
                    ?: stringResource(R.string.settings_not_downloaded),
            )
            if (version != null) KeyValueRow(stringResource(R.string.settings_version), version, mono = true)
            if (refreshedAt != null) {
                KeyValueRow(stringResource(R.string.settings_updated), relativeTime(refreshedAt))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !updating && firstHost != null) {
                        scope.launch { onUpdate() }
                    }
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (updating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = MaknoonBrand.accent, modifier = Modifier.size(18.dp))
                }
                Text(
                    if (updating) stringResource(R.string.settings_updating) else stringResource(R.string.settings_update_now),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (firstHost != null) MaknoonBrand.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

// MARK: -- shared bits

@Composable
private fun FooterCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xs),
    )
}

// MARK: -- presentation relay (#61: overridable + disable-able)

@Composable
private fun PresentationRelaySection() {
    var enabled by remember { mutableStateOf(RelaySettings.enabled) }
    var host by remember { mutableStateOf(RelaySettings.host) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(title = stringResource(R.string.settings_presentation_relay))
        SectionCardGroup {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_use_network_relay), modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it; RelaySettings.enabled = it },
                    )
                }
                if (enabled) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it; RelaySettings.host = it },
                        label = { Text(stringResource(R.string.settings_relay_host_url)) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { host = RelaySettings.DEFAULT_HOST; RelaySettings.host = RelaySettings.DEFAULT_HOST }) {
                        Text(stringResource(R.string.settings_reset_to_default))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.settings_presentation_relay_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCardGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

private fun relativeTime(epochMillis: Long): String {
    val deltaSec = (System.currentTimeMillis() - epochMillis) / 1000L
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60} min ago"
        deltaSec < 86_400 -> "${deltaSec / 3600} hr ago"
        else -> "${deltaSec / 86_400} d ago"
    }
}

// ---------------------------------------------------------------------------
// Known-issuer allow-list (persisted) + live health check.
// ---------------------------------------------------------------------------

/**
 * Persisted issuer allow-list, implementing the existing [KnownIssuersProvider]
 * so it can also feed the issuance flow (IssuerSelection). Each stored entry is
 * a bare `host` or `host:port`; only the host is kept (a full URL is reduced).
 * The local-dev scheme heuristic (http for localhost / RFC 1918 / link-local,
 * https otherwise) mirrors the iOS KnownIssuersStore.outboundBaseURL behaviour.
 *
 * Seeded once with the default issuer host so the section is never empty before
 * the user adds their own.
 */
class KnownIssuersStore(context: Context) : KnownIssuersProvider {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val hosts: List<String>
        get() {
            val stored = prefs.getString(KEY, null)
            if (stored == null) {
                // Seed with the default issuer host on first read.
                val seed = hostOf(DEFAULT_ISSUER_BASE_URL)
                if (seed != null) prefs.edit().putString(KEY, seed).apply()
                return listOfNotNull(seed)
            }
            return stored.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }

    fun add(raw: String) {
        val host = hostOf(raw) ?: return
        val current = hosts.toMutableList()
        if (current.none { it.equals(host, ignoreCase = true) }) {
            current.add(host)
            persist(current)
        }
    }

    fun remove(host: String) {
        persist(hosts.filterNot { it.equals(host, ignoreCase = true) })
    }

    override fun outboundBaseUrl(entry: String): String? {
        val host = entry.trim()
        if (host.isEmpty()) return null
        val bareHost = host.substringBefore(':')
        val scheme = if (isLocalLike(bareHost)) "http" else "https"
        return "$scheme://$host"
    }

    private fun persist(list: List<String>) {
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()
    }

    /** Reduce a full URL or bare host[:port] to the host[:port] string we store. */
    private fun hostOf(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (s.contains("://")) s = s.substringAfter("://")
        s = s.takeWhile { it != '/' && it != '?' && it != '#' }
        return s.ifEmpty { null }
    }

    private fun isLocalLike(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" ||
            h == "127.0.0.1" ||
            h.startsWith("10.") ||
            h.startsWith("192.168.") ||
            h.startsWith("169.254.") ||
            (h.startsWith("172.") && (h.substringAfter("172.").substringBefore('.').toIntOrNull() ?: 0) in 16..31)
    }

    private companion object {
        const val PREFS = "settings.known_issuers.v1"
        const val KEY = "hosts"
        const val DEFAULT_ISSUER_BASE_URL = CSCATrustStore.DEFAULT_ISSUER_BASE_URL
    }
}

/**
 * Live health/validity of a known issuer, surfaced in Settings > Identity. A
 * host is only [Healthy] (green shield) when its /v1/issuer/info is reachable
 * over a valid TLS connection and returns a well-formed issuer document.
 * Mirrors the iOS IssuerHealth enum.
 */
sealed interface IssuerHealth {
    data object Checking : IssuerHealth
    data class Healthy(val did: String) : IssuerHealth
    data class Unreachable(val reason: String) : IssuerHealth
    data class Invalid(val reason: String) : IssuerHealth
}

/**
 * GET {baseUrl}/v1/issuer/info over the SDK HTTP client. Network errors map to
 * [IssuerHealth.Unreachable]; a non-2xx or undecodable body maps to
 * [IssuerHealth.Invalid]. Mirrors the iOS IssuerHealthCheck.
 */
object IssuerHealthCheck {
    private val http = MaknoonHttp()

    suspend fun check(baseUrl: String): IssuerHealth = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/v1/issuer/info"
        val body = try {
            http.getJson(url)
        } catch (e: NetworkException) {
            return@withContext IssuerHealth.Invalid("HTTP ${e.status}")
        } catch (e: Exception) {
            return@withContext IssuerHealth.Unreachable(e.message ?: "connection failed")
        }
        try {
            val did = JSONObject(body).optString("did")
            if (did.isEmpty()) IssuerHealth.Invalid("not an Elabify issuer")
            else IssuerHealth.Healthy(did)
        } catch (_: Exception) {
            IssuerHealth.Invalid("not an Elabify issuer")
        }
    }
}
