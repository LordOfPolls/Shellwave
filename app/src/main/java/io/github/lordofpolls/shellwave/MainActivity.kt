package io.github.lordofpolls.shellwave

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import io.github.lordofpolls.shellwave.core.billing.SupporterBilling
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.crypto.isBiometricCancellation
import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.KeyBarLayoutDao
import io.github.lordofpolls.shellwave.core.db.dao.PortForwardDao
import io.github.lordofpolls.shellwave.core.db.dao.ScriptDao
import io.github.lordofpolls.shellwave.core.db.dao.ScriptRunDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.net.HostReachabilityProbe
import io.github.lordofpolls.shellwave.core.net.sendMagicPacket
import io.github.lordofpolls.shellwave.core.prefs.AppearancePreferences
import io.github.lordofpolls.shellwave.core.prefs.AutomationPreferences
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityPreferences
import io.github.lordofpolls.shellwave.core.prefs.ThemeMode
import io.github.lordofpolls.shellwave.core.ui.HostVerificationDialog
import io.github.lordofpolls.shellwave.core.ui.KeyboardInteractiveDialog
import io.github.lordofpolls.shellwave.core.ui.QuickConnectPasswordDialog
import io.github.lordofpolls.shellwave.core.ui.QuickConnectSavedHostDialog
import io.github.lordofpolls.shellwave.feature.glance.updateDynamicShortcuts
import io.github.lordofpolls.shellwave.feature.home.HomeScreen
import io.github.lordofpolls.shellwave.feature.home.QuickConnectTarget
import io.github.lordofpolls.shellwave.feature.home.savedHostsMatching
import io.github.lordofpolls.shellwave.feature.host.AddEditHostScreen
import io.github.lordofpolls.shellwave.feature.host.ImportSshConfigScreen
import io.github.lordofpolls.shellwave.feature.host.hostDeleteBlockReason
import io.github.lordofpolls.shellwave.feature.nav.AppDestination
import io.github.lordofpolls.shellwave.feature.nav.isNavAtRoot
import io.github.lordofpolls.shellwave.feature.nav.popNav
import io.github.lordofpolls.shellwave.feature.nav.useNavigationRail
import io.github.lordofpolls.shellwave.feature.scripts.RunHistoryScreen
import io.github.lordofpolls.shellwave.feature.scripts.ScriptEditorScreen
import io.github.lordofpolls.shellwave.feature.scripts.ScriptMode
import io.github.lordofpolls.shellwave.feature.scripts.ScriptRunDialogs
import io.github.lordofpolls.shellwave.feature.scripts.ScriptTemplates
import io.github.lordofpolls.shellwave.feature.scripts.ScriptsScreen
import io.github.lordofpolls.shellwave.feature.scripts.rememberScriptRunController
import io.github.lordofpolls.shellwave.feature.session.SessionsListScreen
import io.github.lordofpolls.shellwave.feature.session.SessionsScreen
import io.github.lordofpolls.shellwave.feature.settings.ConfigExporter
import io.github.lordofpolls.shellwave.feature.settings.KeyBarLayoutsScreen
import io.github.lordofpolls.shellwave.feature.settings.LicenseScreen
import io.github.lordofpolls.shellwave.feature.settings.SettingsScreen
import io.github.lordofpolls.shellwave.ssh.AuthMethod
import io.github.lordofpolls.shellwave.ssh.ConnectionSpec
import io.github.lordofpolls.shellwave.ssh.HostVerificationGate
import io.github.lordofpolls.shellwave.ssh.KeyEnrolment
import io.github.lordofpolls.shellwave.ssh.KeyboardInteractiveGate
import io.github.lordofpolls.shellwave.ssh.ScriptRunner
import io.github.lordofpolls.shellwave.ssh.SessionManager
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.resolveProxyHops
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
import io.github.lordofpolls.shellwave.ui.design.ShellwaveTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screens pushed on top of whichever [AppDestination] tab is selected, except
 * [Hosts]/[Sessions]/[Scripts]/[Settings], which are also a tab's own root content. They stay
 * [Screen] cases and don't fold into [AppDestination] because rendering needs per-instance data an
 * enum cannot carry.
 */
private sealed class Screen {
    data object Hosts : Screen()

    data class AddEditHost(val hostId: Long?) : Screen()

    /** The overview list. The terminal itself is [Terminal]. */
    data object Sessions : Screen()

    /**
     * Pushed above the nav bar onto whichever destination opened the session, which makes "back returns
     * to where the session was opened from" fall out of [popNav] with no rule of its own.
     *
     * Carries no session id: `targetSessionId` and SessionsScreen's own selection decide which session
     * shows, so the terminal can switch sessions without navigating.
     */
    data object Terminal : Screen()

    data object Scripts : Screen()

    data class AddEditScript(val scriptId: Long?) : Screen()

    data class ScriptHistory(val scriptId: Long) : Screen()

    data object Settings : Screen()

    data object KeyBarLayouts : Screen()

    data object License : Screen()

    data object ImportSshConfig : Screen()
}

/**
 * A quick-connect target that matched something already saved, waiting on the user to choose.
 *
 * The target is kept beside the matches because "Enter password" has to fall through to the
 * connection the user typed, and reparsing the box would reread text they may have edited since.
 */
private data class SavedHostOffer(
    val target: QuickConnectTarget,
    val matches: List<HostEntity>,
)

/**
 * A quick-connect password the user asked to keep, held until its session authenticates or fails.
 *
 * A type instead of three loose `var`s so a save cannot be attributed to the wrong session id and
 * write one host's password against another host's row. [password] lives here in memory and nowhere
 * else until storePassword takes it; never in saved instance state.
 */
private data class PendingCredentialSave(
    val sessionId: Long,
    val target: QuickConnectTarget,
    val password: String,
)

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    companion object {
        /** Set on the notification's PendingIntent by buildNotification - see [handleIntent]. */
        const val EXTRA_OPEN_SESSIONS = "io.github.lordofpolls.shellwave.extra.OPEN_SESSIONS"

        /** Set by a script's dynamic App Shortcut (updateDynamicShortcuts) - see [handleIntent]. */
        const val EXTRA_RUN_SCRIPT_ID = "io.github.lordofpolls.shellwave.extra.RUN_SCRIPT_ID"

        /** Set by a host's dynamic App Shortcut (updateDynamicShortcuts) - see [handleIntent]. */
        const val EXTRA_OPEN_HOST_ID = "io.github.lordofpolls.shellwave.extra.OPEN_HOST_ID"
    }

    @Inject
    lateinit var hostDao: HostDao

    @Inject
    lateinit var credentialDao: CredentialDao

    @Inject
    lateinit var credentialVault: CredentialVault

    @Inject
    lateinit var hostVerificationGate: HostVerificationGate

    @Inject
    lateinit var keyboardInteractiveGate: KeyboardInteractiveGate

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var scriptDao: ScriptDao

    @Inject
    lateinit var scriptRunDao: ScriptRunDao

    @Inject
    lateinit var terminalProfileDao: TerminalProfileDao

    @Inject
    lateinit var colorSchemeDao: ColorSchemeDao

    @Inject
    lateinit var keyBarLayoutDao: KeyBarLayoutDao

    @Inject
    lateinit var portForwardDao: PortForwardDao

    @Inject
    lateinit var scriptRunner: ScriptRunner

    @Inject
    lateinit var configExporter: ConfigExporter

    @Inject
    lateinit var keyEnrolment: KeyEnrolment

    @Inject
    lateinit var reachabilityProbe: HostReachabilityProbe

    @Inject lateinit var supporterBilling: SupporterBilling

    private val requestLocalNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private var notificationsGranted = mutableStateOf(true)
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsGranted.value = granted
        }

    // Consumed by a LaunchedEffect in setContent, the only scope screenKind lives in.
    private val openSessionsRequested = mutableStateOf(false)

    // -1L means "nothing pending". A shortcut brings the app to the foreground, so unlike the widget
    // and QS-tile paths these run through ScriptRunController with real dialogs.
    private val runScriptRequested = mutableStateOf(-1L)
    private val openHostRequested = mutableStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        // API 36+ Local Network Protection: without this runtime grant, connects to
        // RFC1918/link-local addresses - every host this app talks to - silently time out instead
        // of failing fast. Every host here is user-chosen, so it's safe to request unconditionally.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocalNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }

        // Sessions work either way; a denial only means less-visible background ones, surfaced by
        // the quiet banner below.
        notificationsGranted.value =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
        if (!notificationsGranted.value) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            // SharedPreferences has no change notification, so anything SettingsScreen can edit
            // that also feeds ShellwaveTheme is mirrored into Compose state here. Disk stays the
            // source of truth.
            var dynamicColorEnabled by remember {
                mutableStateOf(
                    AppearancePreferences.getDynamicColor(
                        this@MainActivity
                    )
                )
            }
            var themeMode by remember { mutableStateOf(AppearancePreferences.getThemeMode(this@MainActivity)) }
            var exactSchemeColours by remember {
                mutableStateOf(
                    AppearancePreferences.getExactSchemeColours(
                        this@MainActivity
                    )
                )
            }

            // These three exist only to render the Settings controls and gate the indicator: each
            // setter writes the pref and calls start(), and the probe re-reads all of them on
            // restart.
            var reachabilityEnabled by remember {
                mutableStateOf(
                    ReachabilityPreferences.isEnabled(
                        this@MainActivity
                    )
                )
            }
            var reachabilityInterval by remember {
                mutableStateOf(
                    ReachabilityPreferences.interval(
                        this@MainActivity
                    )
                )
            }
            var reachabilityMetered by remember {
                mutableStateOf(
                    ReachabilityPreferences.allowsMetered(
                        this@MainActivity
                    )
                )
            }
            val reachability by reachabilityProbe.states.collectAsState()

            var automationEnabled by remember { mutableStateOf(AutomationPreferences.isEnabled(this@MainActivity)) }
            var automationToken by remember { mutableStateOf(AutomationPreferences.token(this@MainActivity)) }

            val supporterState by supporterBilling.state.collectAsState()

            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme =
                when (themeMode) {
                    ThemeMode.SYSTEM -> systemDarkTheme
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }

            ShellwaveTheme(darkTheme = darkTheme, dynamicColor = dynamicColorEnabled) {
                // A pair instead of one flat back stack, so a tab switch and a push-within-a-tab
                // stay distinguishable; see [popNav] and [isNavAtRoot].
                var destination by rememberSaveable { mutableStateOf(AppDestination.HOSTS) }
                var subStack by rememberSaveable { mutableStateOf(emptyList<String>()) }

                fun push(kind: String) {
                    subStack = subStack + kind
                }

                // False at the true root, where the BackHandler below is disabled so the system
                // finishes the Activity. Back from Home has to exit the app rather than trap the
                // user.
                fun popBack(): Boolean {
                    val popped = popNav(destination, subStack) ?: return false
                    destination = popped.first
                    subStack = popped.second
                    return true
                }

                // A tab switch is never a push, so any screen mid-flow inside the previous tab is
                // dropped.
                fun selectDestination(dest: AppDestination) {
                    destination = dest
                    subStack = emptyList()
                }

                // Idempotent on the top of the stack: a second open while the terminal is already
                // showing - tapping another session in the wide-window list pane, a script run -
                // must not stack a second entry that back would then have to be pressed through
                // twice.
                fun openTerminal() {
                    if (subStack.lastOrNull() != "terminal") push("terminal")
                }

                // Nested screens pop this same state through their own back affordance, so system back is
                // wired in once here instead of per screen.
                //
                // Deliberately ignores SessionsScreen's ListDetailPaneScaffoldNavigator: that
                // navigator's "list" destination is unreachable here, since SessionsScreen jumps
                // straight to Detail and the tab row already switches sessions. Popping to it would
                // insert a screen the user has never seen.
                BackHandler(enabled = !isNavAtRoot(destination, subStack)) { popBack() }

                var editingHostId by rememberSaveable { mutableStateOf(-1L) }
                var editingScriptId by rememberSaveable { mutableStateOf(-1L) }
                // By name: a ScriptTemplate is a plain constant, so this stays `rememberSaveable`
                // without the type having to be Parcelable. Null for an ordinary "add script".
                var prefillTemplateName by rememberSaveable { mutableStateOf<String?>(null) }
                var historyScriptId by rememberSaveable { mutableStateOf(-1L) }
                var pendingQuickConnect by remember { mutableStateOf<QuickConnectTarget?>(null) }
                // Plain `remember`: a question that outlived the process that asked it would be
                // answered blind.
                var pendingSavedHostOffer by remember { mutableStateOf<SavedHostOffer?>(null) }
                // Plain `remember`, never `rememberSaveable`: it holds a password for as long as
                // the attempt it belongs to, and saved instance state would put a plaintext
                // credential somewhere the vault does not control.
                var pendingCredentialSave by remember { mutableStateOf<PendingCredentialSave?>(null) }
                // Set when a specific session is tapped, rather than always landing on the first
                // one.
                var targetSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
                val scope = rememberCoroutineScope()

                val summaries by sessionManager.summaries.collectAsState()
                val screen =
                    when (subStack.lastOrNull()) {
                        "addEdit" -> Screen.AddEditHost(editingHostId.takeIf { it >= 0 })
                        "addEditScript" -> Screen.AddEditScript(editingScriptId.takeIf { it >= 0 })
                        "scriptHistory" -> Screen.ScriptHistory(historyScriptId)
                        "keyBarLayouts" -> Screen.KeyBarLayouts
                        "license" -> Screen.License
                        "importSshConfig" -> Screen.ImportSshConfig
                        "terminal" -> Screen.Terminal
                        else ->
                            when (destination) {
                                AppDestination.HOSTS -> Screen.Hosts
                                AppDestination.SESSIONS -> Screen.Sessions
                                AppDestination.SCRIPTS -> Screen.Scripts
                                AppDestination.SETTINGS -> Screen.Settings
                            }
                    }

                // Recording the id matters: leaving `targetSessionId` on whatever was last tapped
                // means connecting to a second host opens the first, with the second running unseen
                // behind it.
                fun openSession(spec: ConnectionSpec) {
                    targetSessionId = sessionManager.openSession(spec)
                    openTerminal()
                }

                val scriptRunController =
                    rememberScriptRunController(
                        hostDao = hostDao,
                        credentialVault = credentialVault,
                        scriptRunner = scriptRunner,
                        scriptRunDao = scriptRunDao,
                        sessionManager = sessionManager,
                        activity = this@MainActivity,
                        onSessionOpened = { id ->
                            targetSessionId = id
                            openTerminal()
                        },
                    )

                /**
                 * Connect to a host exactly as saved - vault credential, resilient-session and proxy-jump settings,
                 * and its id on the session.
                 *
                 * Hoisted out of `HomeScreen`'s `onConnectSaved` so the quick-connect offer runs the same path. A
                 * near-copy would drift - quietly losing proxy hops, say - and a host that connects from one button
                 * and fails from the other is the result.
                 */
                fun connectSaved(host: HostEntity) {
                    scope.launch {
                        try {
                            val authMethod =
                                credentialVault.resolve(host.credentialId, this@MainActivity)
                            val hops =
                                resolveProxyHops(host, hostDao, credentialVault, this@MainActivity)
                            openSession(
                                ConnectionSpec(
                                    host.hostname,
                                    host.port,
                                    host.username,
                                    authMethod,
                                    host.id,
                                    host.resilientSession,
                                    hops
                                )
                            )
                        } catch (e: Exception) {
                            // Same rationale as the openHostRequested LaunchedEffect above.
                            if (!e.isBiometricCancellation()) scriptRunController.reportError(
                                e.message ?: "Could not unlock this host's saved credential"
                            )
                        }
                    }
                }

                val openSessionsRequestedState by openSessionsRequested
                LaunchedEffect(openSessionsRequestedState) {
                    if (openSessionsRequestedState) {
                        openTerminal()
                        openSessionsRequested.value = false
                    }
                }

                // A shortcut is launcher-level state; no one screen owns it.
                val scriptsForShortcuts by scriptDao.observeAll()
                    .collectAsState(initial = emptyList())
                val hostsForShortcuts by hostDao.observeRecents()
                    .collectAsState(initial = emptyList())

                // Every saved host, for ScriptRunDialogs' "ask each run" picker. The dialogs are
                // rendered once at the top of the composition, so a run started anywhere resolves
                // wherever the user ends up.
                val hostsForScriptPicker by hostDao.observeAll()
                    .collectAsState(initial = emptyList())

                // Null until Settings' first edit inserts a row, in which case SessionsScreen falls
                // back to DEFAULT_TERMINAL_PROFILE.
                val terminalProfile by terminalProfileDao.observeDefault()
                    .collectAsState(initial = null)

                // SessionManager stays Compose-free, so this is the one dynamicLightColorScheme
                // call. Light rather than dark is immaterial: harmonization reads only the hue,
                // which both variants of a dynamic scheme share.
                LaunchedEffect(Unit) {
                    sessionManager.setDynamicAccent(dynamicLightColorScheme(this@MainActivity).primary.toArgb())
                }

                // Unlike terminalProfile this is also applied as it changes:
                // SessionManager.attemptConnect only covers new and reconnecting sessions, so a
                // Settings edit would otherwise never reach one already running.
                val colorScheme by colorSchemeDao.observeDefault().collectAsState(initial = null)
                // Keyed on exactSchemeColours too, since SessionManager.harmonized reads that
                // preference every time it resolves a scheme.
                LaunchedEffect(colorScheme, exactSchemeColours) {
                    sessionManager.applyDefaultColorScheme(colorScheme ?: DEFAULT_COLOR_SCHEME)
                }
                // The other half of the opt-in save: wait for the session the user ticked the box for to
                // settle, and write the host and credential only if it reached CONNECTED.
                //
                // Waits on `sessionManager.summaries` rather than the `summaries` collected above.
                // Keying on the collected list ran the effect against whatever snapshot composition
                // held when the save was recorded - for the composition that sets it, the list from
                // before `openSession` registered anything - so the session looked absent, the effect
                // concluded it had gone, and the save was dropped. Ticked box, successful connect,
                // nothing saved.
                //
                // `seen` separates "hasn't appeared yet" from "appeared and then vanished". Without
                // it the first looks like the second and the save is dropped again.
                val saveRequest = pendingCredentialSave
                LaunchedEffect(saveRequest) {
                    if (saveRequest == null) return@LaunchedEffect
                    var seen = false
                    val settled =
                        sessionManager.summaries.first { list ->
                            val summary = list.firstOrNull { it.id == saveRequest.sessionId }
                            if (summary != null) seen = true
                            when (summary?.status) {
                                SessionStatus.CONNECTED, SessionStatus.FAILED, SessionStatus.DISCONNECTED -> true
                                SessionStatus.CONNECTING, SessionStatus.RECONNECTING -> false
                                // Absent: not registered yet, or closed after we saw it.
                                null -> seen
                            }
                        }

                    // Clearing `pendingCredentialSave` last is load-bearing. It is this effect's
                    // own key, so writing to it recomposes and cancels the coroutine currently
                    // running; clearing before the vault write cancelled `storePassword` mid-flight
                    // and nothing was persisted. Traced on device: the effect reached CONNECTED and
                    // then stopped. Every exit path below falls through to the single clear at the
                    // end.
                    val connected =
                        settled.firstOrNull { it.id == saveRequest.sessionId }?.status == SessionStatus.CONNECTED
                    if (connected) {

                        // `requireBiometric = false`: a host being created right now is not
                        // configured for biometric gating, and defaulting it on would impose a
                        // prompt nobody asked for.
                        val target = saveRequest.target
                        val credentialId =
                            credentialVault.storePassword(
                                password = saveRequest.password,
                                label = "${target.username}@${target.host}",
                                requireBiometric = false,
                                activity = this@MainActivity,
                            )
                        hostDao.insert(
                            HostEntity(
                                label = null,
                                hostname = target.host,
                                port = target.port,
                                username = target.username,
                                credentialId = credentialId,
                                lastConnectedAt = System.currentTimeMillis(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    pendingCredentialSave = null
                }

                LaunchedEffect(scriptsForShortcuts, hostsForShortcuts) {
                    updateDynamicShortcuts(
                        this@MainActivity,
                        scriptsForShortcuts,
                        hostsForShortcuts
                    )
                }

                val runScriptRequestedState by runScriptRequested
                LaunchedEffect(runScriptRequestedState, scriptsForShortcuts) {
                    if (runScriptRequestedState >= 0) {
                        scriptsForShortcuts.firstOrNull { it.id == runScriptRequestedState }
                            ?.let { scriptRunController.request(it) }
                        runScriptRequested.value = -1L
                    }
                }

                val openHostRequestedState by openHostRequested
                LaunchedEffect(openHostRequestedState) {
                    if (openHostRequestedState >= 0) {
                        val host = hostDao.getById(openHostRequestedState)
                        if (host != null) {
                            try {
                                val authMethod =
                                    credentialVault.resolve(host.credentialId, this@MainActivity)
                                val hops = resolveProxyHops(
                                    host,
                                    hostDao,
                                    credentialVault,
                                    this@MainActivity
                                )
                                openSession(
                                    ConnectionSpec(
                                        host.hostname,
                                        host.port,
                                        host.username,
                                        authMethod,
                                        host.id,
                                        host.resilientSession,
                                        hops
                                    )
                                )
                            } catch (e: Exception) {
                                // A cancelled biometric prompt is the user changing their mind;
                                // only a genuine failure (lockout, no credential) gets surfaced.
                                if (!e.isBiometricCancellation()) scriptRunController.reportError(
                                    e.message ?: "Could not unlock this host's saved credential"
                                )
                            }
                        }
                        openHostRequested.value = -1L
                    }
                }

                // The chrome decorates a destination's own root only; anything pushed on top reads
                // as a focused flow. That is also what hoists the terminal above the nav bar: with
                // no bottomBar, Scaffold's contentWindowInsets shrink to the status bar and it gets
                // the whole window.
                val navChromeVisible = subStack.isEmpty()

                val useRail = useNavigationRail(currentWindowAdaptiveInfoV2().windowSizeClass)

                // Identical either way; only the surrounding chrome differs by width class.
                val screenContent: @Composable (PaddingValues) -> Unit = { innerPadding ->
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            val notificationsBannerVisible by notificationsGranted
                            // Never over the terminal, whose chrome is fixed at two strips - this
                            // made a third, pushing the grid down. It loses nothing by being
                            // absent: it is about sessions running in the background, which is not
                            // what you are looking at.
                            if (!notificationsBannerVisible && summaries.isNotEmpty() && screen !is Screen.Terminal) {
                                NotificationsDeniedBanner()
                            }
                            when (val current = screen) {
                                is Screen.Hosts -> {
                                    val hosts by hostDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    val recents by hostDao.observeRecents()
                                        .collectAsState(initial = emptyList())
                                    val scripts by scriptDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    HomeScreen(
                                        hosts = hosts,
                                        recents = recents,
                                        scripts = scripts,
                                        liveSessions = summaries,
                                        onQuickConnect = { target: QuickConnectTarget ->
                                            // Offer the saved credential instead of asking for a
                                            // password the vault already holds. `hosts` is the same
                                            // observeAll() the cards are drawn from, so the offer
                                            // can never rest on a staler list than the screen.
                                            val matches = savedHostsMatching(target, hosts)
                                            if (matches.isEmpty()) {
                                                pendingQuickConnect = target
                                            } else {
                                                pendingSavedHostOffer =
                                                    SavedHostOffer(target, matches)
                                            }
                                        },
                                        onOpenSession = { id ->
                                            targetSessionId = id
                                            openTerminal()
                                        },
                                        onOpenSessionList = { selectDestination(AppDestination.SESSIONS) },
                                        onConnectSaved = { host -> connectSaved(host) },
                                        onAddHost = {
                                            editingHostId = -1L
                                            push("addEdit")
                                        },
                                        onEditHost = {
                                            editingHostId = it.id
                                            push("addEdit")
                                        },
                                        onDeleteHost = { host ->
                                            scope.launch {
                                                // Pre-check the RESTRICT FK; see
                                                // hostDeleteBlockReason for why this is a block and
                                                // not a repair.
                                                val blockReason = hostDeleteBlockReason(
                                                    host,
                                                    hostDao.getProxyJumpDependents(host.id)
                                                )
                                                if (blockReason != null) {
                                                    scriptRunController.reportError(blockReason)
                                                    return@launch
                                                }
                                                hostDao.delete(host)
                                                // ~/.ssh/config import can attach one credential to
                                                // several hosts, so this row is not always this
                                                // host's alone to delete.
                                                if (hostDao.countOtherHostsUsingCredential(
                                                        host.credentialId,
                                                        host.id
                                                    ) == 0
                                                ) {
                                                    credentialDao.getById(host.credentialId)
                                                        ?.let { credentialDao.delete(it) }
                                                }
                                            }
                                        },
                                        // terminalProfileId/colorSchemeId are left null instead of
                                        // copied: those rows are created for one host, so copying
                                        // an id would leave two hosts sharing one override that
                                        // editing either would mutate.
                                        onDuplicateHost = { host ->
                                            scope.launch {
                                                hostDao.insert(
                                                    host.copy(
                                                        id = 0,
                                                        label = "${host.label ?: host.hostname} (copy)",
                                                        lastConnectedAt = null,
                                                        createdAt = System.currentTimeMillis(),
                                                        terminalProfileId = null,
                                                        colorSchemeId = null,
                                                    ),
                                                )
                                            }
                                        },
                                        onWakeHost = { host ->
                                            scope.launch {
                                                val message =
                                                    runCatching { sendMagicPacket(host.macAddress.orEmpty()) }
                                                        .fold(
                                                            { "Wake packet sent to ${host.label ?: host.hostname}" },
                                                            { it.message ?: "Couldn't send the wake packet" },
                                                        )
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    message,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        reachability = reachability,
                                        reachabilityEnabled = reachabilityEnabled,
                                        onRunScript = { script, host ->
                                            scriptRunController.request(
                                                script,
                                                hostId = host.id
                                            )
                                        },
                                        onImportSshConfig = { push("importSshConfig") },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                is Screen.AddEditHost -> {
                                    val hosts by hostDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    AddEditHostScreen(
                                        existing = hosts.firstOrNull { it.id == current.hostId },
                                        hostDao = hostDao,
                                        credentialVault = credentialVault,
                                        keyEnrolment = keyEnrolment,
                                        terminalProfileDao = terminalProfileDao,
                                        colorSchemeDao = colorSchemeDao,
                                        keyBarLayoutDao = keyBarLayoutDao,
                                        portForwardDao = portForwardDao,
                                        sessionManager = sessionManager,
                                        onDone = { popBack() },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                is Screen.Sessions -> {
                                    // Only for resolving each session's display name:
                                    // SessionSummary carries a hostId; the row itself never travels
                                    // with it.
                                    val hosts by hostDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    SessionsListScreen(
                                        summaries = summaries,
                                        hosts = hosts,
                                        onOpenSession = { id ->
                                            targetSessionId = id
                                            openTerminal()
                                        },
                                        onReconnect = sessionManager::reconnectNow,
                                        onClose = sessionManager::closeSession,
                                        onOpenHosts = { selectDestination(AppDestination.HOSTS) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                is Screen.Terminal -> {
                                    val scripts by scriptDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    val sendToCurrentScripts =
                                        scripts.filter { runCatching { ScriptMode.valueOf(it.mode) }.getOrNull() == ScriptMode.SEND_TO_CURRENT }
                                    // Unfiltered by host: which ones suit the selected session is a
                                    // question only SessionsScreen can answer.
                                    val captureScripts =
                                        scripts.filter { runCatching { ScriptMode.valueOf(it.mode) }.getOrNull() == ScriptMode.CAPTURE }
                                    val hosts by hostDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    SessionsScreen(
                                        sessionManager = sessionManager,
                                        hosts = hosts,
                                        onNewSession = { selectDestination(AppDestination.HOSTS) },
                                        onEmpty = { popBack() },
                                        initialSessionId = targetSessionId,
                                        sendToCurrentScripts = sendToCurrentScripts,
                                        captureScripts = captureScripts,
                                        onRunScript = { script, connection ->
                                            scriptRunController.request(
                                                script,
                                                connection
                                            )
                                        },
                                        terminalProfile = terminalProfile,
                                        terminalProfileDao = terminalProfileDao,
                                        keyBarLayoutDao = keyBarLayoutDao,
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                is Screen.Scripts -> {
                                    val scripts by scriptDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    val hosts by hostDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    ScriptsScreen(
                                        scripts = scripts,
                                        hosts = hosts,
                                        onRun = { scriptRunController.request(it) },
                                        onAdd = {
                                            editingScriptId = -1L
                                            prefillTemplateName = null
                                            push("addEditScript")
                                        },
                                        onEdit = {
                                            editingScriptId = it.id
                                            prefillTemplateName = null
                                            push("addEditScript")
                                        },
                                        onDelete = { script ->
                                            scope.launch {
                                                scriptDao.delete(
                                                    script
                                                )
                                            }
                                        },
                                        onHistory = {
                                            historyScriptId = it.id
                                            push("scriptHistory")
                                        },
                                        modifier = Modifier.weight(1f),
                                        onAddFromTemplate = { template ->
                                            editingScriptId = -1L
                                            prefillTemplateName = template.name
                                            push("addEditScript")
                                        },
                                    )
                                }

                                is Screen.AddEditScript -> {
                                    val scripts by scriptDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    val hosts by hostDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    // Once per template rather than per composition: the editor
                                    // keys its field state on this value, and toEntity() stamps
                                    // createdAt with the current time, so a fresh entity each
                                    // recomposition would reset the form under the user's fingers.
                                    val prefill = remember(prefillTemplateName) {
                                        ScriptTemplates.ALL.firstOrNull { it.name == prefillTemplateName }
                                            ?.toEntity()
                                    }
                                    ScriptEditorScreen(
                                        existing = scripts.firstOrNull { it.id == current.scriptId },
                                        hosts = hosts,
                                        scriptDao = scriptDao,
                                        onDone = { popBack() },
                                        modifier = Modifier.weight(1f),
                                        prefill = prefill,
                                    )
                                }

                                is Screen.ScriptHistory -> {
                                    val scripts by scriptDao.observeAll()
                                        .collectAsState(initial = emptyList())
                                    val runs by scriptRunDao.observeForScript(current.scriptId)
                                        .collectAsState(initial = emptyList())
                                    RunHistoryScreen(
                                        scriptName = scripts.firstOrNull { it.id == current.scriptId }?.name
                                            ?: "Script",
                                        runs = runs,
                                        onBack = { popBack() },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                is Screen.Settings -> {
                                    SettingsScreen(
                                        terminalProfileDao = terminalProfileDao,
                                        colorSchemeDao = colorSchemeDao,
                                        dynamicColorEnabled = dynamicColorEnabled,
                                        onDynamicColorChange = {
                                            dynamicColorEnabled = it
                                            AppearancePreferences.setDynamicColor(
                                                this@MainActivity,
                                                it
                                            )
                                        },
                                        themeMode = themeMode,
                                        onThemeModeChange = {
                                            themeMode = it
                                            AppearancePreferences.setThemeMode(
                                                this@MainActivity,
                                                it
                                            )
                                        },
                                        exactSchemeColours = exactSchemeColours,
                                        onExactSchemeColoursChange = {
                                            exactSchemeColours = it
                                            AppearancePreferences.setExactSchemeColours(
                                                this@MainActivity,
                                                it
                                            )
                                        },
                                        // Each writes its pref and restarts the probe, which
                                        // re-reads all three.
                                        reachabilityEnabled = reachabilityEnabled,
                                        onReachabilityEnabledChange = {
                                            reachabilityEnabled = it
                                            ReachabilityPreferences.setEnabled(
                                                this@MainActivity,
                                                it
                                            )
                                            reachabilityProbe.start()
                                        },
                                        reachabilityInterval = reachabilityInterval,
                                        onReachabilityIntervalChange = {
                                            reachabilityInterval = it
                                            ReachabilityPreferences.setInterval(
                                                this@MainActivity,
                                                it
                                            )
                                            reachabilityProbe.start()
                                        },
                                        reachabilityMetered = reachabilityMetered,
                                        onReachabilityMeteredChange = {
                                            reachabilityMetered = it
                                            ReachabilityPreferences.setAllowsMetered(
                                                this@MainActivity,
                                                it
                                            )
                                            reachabilityProbe.start()
                                        },
                                        automationEnabled = automationEnabled,
                                        onAutomationEnabledChange = {
                                            automationEnabled = it
                                            AutomationPreferences.setEnabled(this@MainActivity, it)
                                            // Enabling mints the first token, so re-read rather
                                            // than assume unchanged, or the switch turns on and the
                                            // token area stays empty.
                                            automationToken =
                                                AutomationPreferences.token(this@MainActivity)
                                        },
                                        automationToken = automationToken,
                                        onRegenerateAutomationToken = {
                                            automationToken =
                                                AutomationPreferences.regenerateToken(this@MainActivity)
                                        },
                                        onOpenKeyBarLayouts = { push("keyBarLayouts") },
                                        onExportConfig = configExporter::writeTo,
                                        onOpenLicenses = { push("license") },
                                        supporterState = supporterState,
                                        onBecomeSupporter = { supporterBilling.launchPurchase(this@MainActivity) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                is Screen.KeyBarLayouts -> {
                                    KeyBarLayoutsScreen(
                                        keyBarLayoutDao = keyBarLayoutDao,
                                        onBack = { popBack() },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                is Screen.License -> {
                                    LicenseScreen(
                                        onBack = { popBack() },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                is Screen.ImportSshConfig -> {
                                    ImportSshConfigScreen(
                                        hostDao = hostDao,
                                        credentialDao = credentialDao,
                                        credentialVault = credentialVault,
                                        onDone = { popBack() },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        val pendingHostVerification by hostVerificationGate.pending.collectAsState()
                        pendingHostVerification.values.firstOrNull()
                            ?.let { HostVerificationDialog(it) }

                        val pendingKeyboardInteractive by keyboardInteractiveGate.pending.collectAsState()
                        pendingKeyboardInteractive.values.firstOrNull()
                            ?.let { KeyboardInteractiveDialog(it) }

                        ScriptRunDialogs(scriptRunController, hostsForScriptPicker)

                        // Never shown alongside the password prompt: "Enter password" hands the
                        // same target to it.
                        pendingSavedHostOffer?.let { offer ->
                            QuickConnectSavedHostDialog(
                                target = offer.target,
                                matches = offer.matches,
                                onUseSaved = { host ->
                                    pendingSavedHostOffer = null
                                    connectSaved(host)
                                },
                                onEnterPassword = {
                                    pendingSavedHostOffer = null
                                    pendingQuickConnect = offer.target
                                },
                                onCancel = { pendingSavedHostOffer = null },
                            )
                        }

                        pendingQuickConnect?.let { target ->
                            QuickConnectPasswordDialog(
                                target = target,
                                onCancel = { pendingQuickConnect = null },
                                onConnect = { password, save ->
                                    pendingQuickConnect = null
                                    val sessionId =
                                        sessionManager.openSession(
                                            ConnectionSpec(
                                                target.host,
                                                target.port,
                                                target.username,
                                                AuthMethod.Password(password),
                                                hostId = null
                                            ),
                                        )
                                    // Recorded here, acted on by the LaunchedEffect below once the
                                    // session is actually CONNECTED. Saving on the tap would
                                    // happily persist a typo.
                                    if (save) pendingCredentialSave =
                                        PendingCredentialSave(sessionId, target, password)
                                    // Quick connect goes through sessionManager directly, having a
                                    // password to hand off, so it needs its own copy of
                                    // openSession's landing behaviour.
                                    targetSessionId = sessionId
                                    openTerminal()
                                },
                            )
                        }
                    }
                }

                if (useRail) {
                    // Medium/expanded width: a side rail beside the content, both in one Row.
                    // Scaffold has no side-rail slot, so the rail sits outside it and the inner
                    // Scaffold still only handles the status/nav bar insets; the rail's width is
                    // reserved by the Row's weight.
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (navChromeVisible) {
                            ShellwaveNavigationRail(
                                selected = destination,
                                onSelect = ::selectDestination
                            )
                        }
                        Scaffold(modifier = Modifier.weight(1f)) { innerPadding ->
                            screenContent(
                                innerPadding
                            )
                        }
                    }
                } else {
                    // Compact width: Scaffold's own bottomBar slot, so its insets fold into
                    // innerPadding.
                    Scaffold(
                        bottomBar = {
                            if (navChromeVisible) ShellwaveNavigationBar(
                                selected = destination,
                                onSelect = ::selectDestination
                            )
                        },
                    ) { innerPadding -> screenContent(innerPadding) }
                }
            }
        }
    }

    // MainActivity is launchMode="singleTop" (see the manifest), so tapping the notification while
    // the app is already running redelivers here instead of creating a second instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Cold start (via [onCreate]) and warm relaunch (via [onNewIntent]) both funnel through here. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SESSIONS, false) == true) {
            openSessionsRequested.value = true
        }
        intent?.getLongExtra(EXTRA_RUN_SCRIPT_ID, -1L)?.takeIf { it >= 0 }
            ?.let { runScriptRequested.value = it }
        intent?.getLongExtra(EXTRA_OPEN_HOST_ID, -1L)?.takeIf { it >= 0 }
            ?.let { openHostRequested.value = it }
    }
}

/**
 * One item per [AppDestination], in enum declaration order.
 *
 * [alwaysShowLabel] stays `true`, so the visible label is also the accessible name and
 * `contentDescription = null` on the [Icon] is correct: a description would make TalkBack read
 * every tab twice.
 */
@Composable
private fun ShellwaveNavigationBar(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        AppDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

/** [ShellwaveNavigationBar] for medium/expanded widths - rail components, same reasoning. */
@Composable
private fun ShellwaveNavigationRail(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(modifier = modifier) {
        AppDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

/** A slim card - it never blocks anything, so a dialog would overstate it. */
@Composable
private fun NotificationsDeniedBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            "Notifications are off - running sessions won't show a status. Enable them in system settings to see it.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
