package io.github.lordofpolls.shellwave.feature.session

private const val SHELL = "source.shell"
private const val PYTHON = "source.python"

private val scopeByExtension = mapOf(
    "sh" to SHELL, "bash" to SHELL, "zsh" to SHELL,
    "py" to PYTHON,
    "yml" to "source.yaml", "yaml" to "source.yaml",
    "json" to "source.json",
    "toml" to "source.toml",
    "ini" to "source.ini", "cfg" to "source.ini", "conf" to "source.ini",
    "dockerfile" to "source.dockerfile",
)

private val shellDotfiles = setOf(".bashrc", ".zshrc", ".profile", ".bash_profile", ".zprofile", ".bash_aliases")

private val shellInterpreter = Regex("""[/\s](sh|bash|zsh|dash)(\s|$)""")

internal fun textMateScopeFor(fileName: String, firstLine: String): String? {
    if (fileName == "Dockerfile") return "source.dockerfile"
    if (fileName in shellDotfiles) return SHELL
    scopeByExtension[fileName.substringAfterLast('.', "").lowercase()]?.let { return it }
    if (!firstLine.startsWith("#!")) return null
    return when {
        "python" in firstLine -> PYTHON
        shellInterpreter.containsMatchIn(firstLine) -> SHELL
        else -> null
    }
}
