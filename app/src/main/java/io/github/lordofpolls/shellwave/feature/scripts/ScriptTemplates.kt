package io.github.lordofpolls.shellwave.feature.scripts

import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.feature.scripts.ScriptTemplates.MEMORY
import io.github.lordofpolls.shellwave.feature.scripts.ScriptTemplates.TOP_PROCESSES

/**
 * Constants, not seeded database rows. A seeded row is uninvited, indistinguishable from something
 * the user wrote, and gone for good once deleted; a catalogue can be re-consulted after a mistaken
 * delete and needs no migration for existing installs.
 *
 * [description] is the catalogue's one-liner and is not carried into `ScriptEntity` - there is no
 * column for it, and a template stops being a template the moment it is saved.
 */
data class ScriptTemplate(
    val name: String,
    val description: String,
    val snippet: String,
    val mode: ScriptMode = ScriptMode.CAPTURE,
    val params: List<ScriptParam> = emptyList(),
    val confirmBeforeRun: Boolean = false,
) {
    /**
     * `id = 0`, and never inserted here: [ScriptEditorScreen] takes this as `prefill`, not `existing`,
     * so nothing reaches the database until the user saves.
     *
     * `targetHostId = null` throughout. A starter script cannot know which host it is for, and "no
     * fixed host" means "ask each run", so it is useful the moment it is saved.
     */
    fun toEntity(): ScriptEntity =
        ScriptEntity(
            id = 0,
            name = name,
            icon = null,
            color = ScriptColor.DEFAULT,
            targetHostId = null,
            snippet = snippet,
            mode = mode.name,
            disconnectAfter = false,
            paramsJson = encodeParams(params),
            confirmBeforeRun = confirmBeforeRun,
            createdAt = System.currentTimeMillis(),
        )
}

/**
 * POSIX scope, Linux and macOS. Every snippet has to run unmodified on both, which rules out the
 * obvious spelling of [MEMORY] and [TOP_PROCESSES]. Where that is impossible the platform goes in
 * the name, since a script that quietly does nothing on a Mac is worse than one that admits it is
 * for Linux. PowerShell hosts get nothing: `HostEntity` has no OS field, so a second catalogue
 * could not be filtered and both halves would show to everyone.
 *
 * These are the first snippets most users read, so each stays a legible one-liner.
 */
object ScriptTemplates {
    private val DISK_SPACE =
        ScriptTemplate(
            name = "Disk space",
            description = "Free and used space on every mounted filesystem.",
            snippet = "df -h",
        )

    private val UPTIME =
        ScriptTemplate(
            name = "Uptime and load",
            description = "How long the server has been up, and its load averages.",
            snippet = "uptime",
        )

    /**
     * `free` is procps, so Linux only, and macOS ships nothing equivalent - `vm_stat` reports the same
     * facts in Mach pages. Two templates named "(Linux)" and "(macOS)" would double the catalogue to
     * serve a user who already knows which one they are.
     *
     * `2>/dev/null` keeps the Linux-only "command not found" off stderr, where it would land beside a
     * perfectly good `vm_stat` result.
     */
    private val MEMORY =
        ScriptTemplate(
            name = "Memory usage",
            description = "Memory in use and available. Falls back to vm_stat on macOS.",
            snippet = "free -h 2>/dev/null || vm_stat",
        )

    /**
     * `--sort` is a GNU procps extension that BSD `ps` rejects, so the idiomatic Linux spelling fails
     * on macOS. `-A` and `-o` with `pid`/`pcpu`/`pmem`/`comm` are POSIX.
     *
     * Known wart: `sort` has no idea the first line is a header, so the `%CPU` heading sorts as 0 and
     * lands at the bottom. `tail -n +2` would drop the column labels entirely, which costs more.
     */
    private val TOP_PROCESSES =
        ScriptTemplate(
            name = "Top processes",
            description = "The ten processes using the most CPU.",
            snippet = "ps -Ao pid,pcpu,pmem,comm | sort -k2 -rn | head -10",
        )

    /**
     * The minute of delay is the point. Capture mode reads an `exec` channel to EOF, so an immediate
     * reboot tears the connection down mid-read and a reboot that worked perfectly reports as a
     * connection failure. Scheduling it returns cleanly, and leaves a minute to reconsider.
     *
     * [confirmBeforeRun] is set, but that is a foreground-dialog concept `ScriptTriggerService` never
     * consults. What actually stops a one-tap reboot from a widget is that background triggers refuse
     * hostless scripts, and this template is saved hostless.
     */
    private val REBOOT =
        ScriptTemplate(
            name = "Reboot the server",
            description = "Schedules a reboot one minute out. Cancel with: sudo shutdown -c",
            snippet = "sudo shutdown -r +1",
            confirmBeforeRun = true,
        )

    /**
     * systemd, so Linux only, and that goes in the name: unlike [MEMORY] there is no portable spelling
     * to fall back to, macOS being `launchctl` with a different service-naming scheme entirely.
     *
     * Doubles as the catalogue's one worked example of a `{{param}}`. Worth stating: any parameter
     * makes a script permanently ineligible for a widget or tile, a background trigger having nowhere
     * to prompt, so this one can never become a one-tap service restart however it is later edited.
     */
    private val RESTART_SERVICE =
        ScriptTemplate(
            name = "Restart a systemd service",
            description = "Restarts a named service, then reports whether it came back up.",
            snippet = "sudo systemctl restart {{service}} && systemctl status {{service}} --no-pager",
            params = listOf(
                ScriptParam(
                    name = "service",
                    type = ParamType.TEXT,
                    label = "Service name"
                )
            ),
            confirmBeforeRun = true,
        )

    /** Read-only first, then the two that change the server. A browse order in place of an alphabetical one. */
    val ALL = listOf(DISK_SPACE, UPTIME, MEMORY, TOP_PROCESSES, REBOOT, RESTART_SERVICE)
}
