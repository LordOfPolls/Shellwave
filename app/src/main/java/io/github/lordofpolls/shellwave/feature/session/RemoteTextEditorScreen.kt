package io.github.lordofpolls.shellwave.feature.session

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import io.github.lordofpolls.shellwave.R
import io.github.lordofpolls.shellwave.ssh.RemoteFileChanged
import io.github.lordofpolls.shellwave.ssh.RemoteFileNotText
import io.github.lordofpolls.shellwave.ssh.RemoteText
import io.github.lordofpolls.shellwave.ssh.RemoteTextCodec
import io.github.lordofpolls.shellwave.ssh.SftpOp
import io.github.lordofpolls.shellwave.ssh.SshConnection
import io.github.lordofpolls.shellwave.ssh.describeSftpFailure
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.MachineText
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IThemeSource

internal object TextMateAssets {
    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val assets = context.applicationContext.assets
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            for (name in listOf("dark", "light")) {
                val path = "textmate/themes/$name.json"
                val model = assets.open(path).use { ThemeModel(IThemeSource.fromInputStream(it, path, null), name) }
                model.isDark = name == "dark"
                ThemeRegistry.getInstance().loadTheme(model)
            }
            loaded = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteTextEditorScreen(
    connection: SshConnection?,
    sessionId: Long,
    path: String,
    onBack: () -> Unit,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = remember(path) { path.substringAfterLast('/').ifEmpty { path } }
    val currentConnection by rememberUpdatedState(connection)
    val currentDarkTheme by rememberUpdatedState(darkTheme)

    var loaded by remember { mutableStateOf<RemoteText?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailure by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var opError by remember { mutableStateOf<String?>(null) }

    val editor = remember {
        CodeEditor(context).apply {
            typefaceText = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
            isLineNumberEnabled = true
            isWordwrap = true
            // ponytail: full toString compare per keystroke; compare on undo/redo only if 2 MiB files lag.
            subscribeAlways(ContentChangeEvent::class.java) {
                if (it.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) dirty = text.toString() != loaded?.text
            }
        }
    }

    fun applyTheme(dark: Boolean) {
        ThemeRegistry.getInstance().setTheme(if (dark) "dark" else "light")
        if (editor.colorScheme !is TextMateColorScheme) {
            editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        }
    }

    LaunchedEffect(darkTheme) {
        if (editor.colorScheme is TextMateColorScheme) applyTheme(darkTheme)
    }

    fun show(text: RemoteText, buffer: String) {
        applyTheme(currentDarkTheme)
        textMateScopeFor(name, text.text.substringBefore('\n'))?.let {
            editor.setEditorLanguage(TextMateLanguage.create(it, false))
        }
        editor.setText(buffer)
        loaded = text
        loadFailure = null
    }

    LaunchedEffect(reloadToken) {
        val draft = if (reloadToken == 0) RemoteDrafts.take(sessionId, path) else null
        if (draft != null) {
            // Synchronous on purpose: a draft only exists after a load in this process, so the
            // assets are already there, and suspending here would leave a gap in which a second
            // relaunch finds nothing to retain.
            TextMateAssets.ensureLoaded(context)
            show(draft.loaded, draft.text)
            dirty = true
            loading = false
            return@LaunchedEffect
        }
        val conn = currentConnection
        if (conn == null) {
            if (loaded == null) loadFailure = "The session ended before \"$name\" loaded."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        withContext(Dispatchers.IO) { TextMateAssets.ensureLoaded(context) }
        conn.readRemoteText(path).fold(
            onSuccess = { text ->
                show(text, text.text)
                dirty = false
            },
            onFailure = { e ->
                val message =
                    if (e is RemoteFileNotText) "Can't edit \"$name\": ${e.reason}. Download it instead."
                    else "Couldn't open \"$name\": ${describeSftpFailure(e, SftpOp.Download, path)}"
                if (loaded == null) loadFailure = message else opError = message
            },
        )
        loading = false
    }

    fun save(expectedMtime: Long) {
        val conn = connection ?: return
        val base = loaded ?: return
        val snapshot = editor.text.toString()
        val updated = base.copy(text = snapshot)
        saving = true
        scope.launch {
            conn.writeRemoteText(path, RemoteTextCodec.encode(updated), expectedMtime).fold(
                onSuccess = { mtime ->
                    loaded = updated.copy(mtime = mtime)
                    dirty = editor.text.toString() != snapshot
                },
                onFailure = { e ->
                    if (e is RemoteFileChanged) {
                        conflict = true
                    } else {
                        opError = "Couldn't save \"$name\": ${describeSftpFailure(e, SftpOp.Write, path)}"
                    }
                },
            )
            saving = false
        }
    }

    fun requestReload() {
        if (dirty) confirmReload = true else reloadToken++
    }

    BackHandler(enabled = dirty) { confirmDiscard = true }

    DisposableEffect(editor) {
        onDispose {
            if (dirty && (context as? Activity)?.isFinishing != true) {
                loaded?.let { RemoteDrafts.retain(sessionId, path, RemoteDrafts.Draft(it, editor.text.toString())) }
            }
            editor.release()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                MachineText(
                    if (dirty) "● $name" else name,
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = path },
                )
            },
            navigationIcon = {
                IconButton(onClick = { if (dirty) confirmDiscard = true else onBack() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(
                    onClick = { loaded?.let { save(it.mtime) } },
                    enabled = dirty && connection != null && loaded != null && !saving,
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = "Save")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Reload") },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            enabled = connection != null && !loading && !saving,
                            onClick = {
                                menuOpen = false
                                requestReload()
                            },
                        )
                    }
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )

        // Unlike SftpBrowserScreen, this screen stays up when the session goes: it holds the
        // user's unsaved work, which is still selectable and copyable.
        if (connection == null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    "Session ended. Changes can't be saved, but you can still copy the text.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (saving || (loading && loaded != null)) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        when {
            loaded == null && loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            loaded == null -> EmptyState(message = loadFailure.orEmpty(), modifier = Modifier.fillMaxSize())

            // This screen sits on the sub-stack, so the Scaffold hands it the whole window below
            // the status bar: the editor has to pad for the keyboard and nav bar itself.
            else -> AndroidView(
                factory = { editor },
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            )
        }
    }

    if (conflict) {
        AlertDialog(
            onDismissRequest = { conflict = false },
            title = { Text("\"$name\" changed on the server since you opened it") },
            text = { Text("Overwrite it with your version, or reload and lose your changes?") },
            confirmButton = {
                TextButton(onClick = {
                    conflict = false
                    save(-1)
                }) { Text("Overwrite") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { conflict = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        conflict = false
                        confirmReload = true
                    }) { Text("Reload") }
                }
            },
        )
    }

    if (confirmReload) {
        AlertDialog(
            onDismissRequest = { confirmReload = false },
            title = { Text("Discard changes and reload \"$name\"?") },
            text = { Text("Your edits will be replaced with the file as it is on the server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReload = false
                    reloadToken++
                }) { Text("Reload") }
            },
            dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes to \"$name\"?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    dirty = false
                    onBack()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    opError?.let { message ->
        AlertDialog(
            onDismissRequest = { opError = null },
            title = { Text("Couldn't complete that") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { opError = null }) { Text("OK") } },
        )
    }
}
