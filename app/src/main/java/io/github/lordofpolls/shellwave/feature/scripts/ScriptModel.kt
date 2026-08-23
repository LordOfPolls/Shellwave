package io.github.lordofpolls.shellwave.feature.scripts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import org.json.JSONArray
import org.json.JSONObject

/** Stored as ScriptEntity.mode's `.name`, same plain-string convention as CredentialType. */
enum class ScriptMode {
    /** Connect, send the snippet, stay in the session. */
    ATTACH,

    /** Headless `exec`, collect stdout/stderr/exit status, result sheet + ScriptRunEntity history. */
    CAPTURE,

    /** Write into the already-focused session - see SessionsScreen. */
    SEND_TO_CURRENT,
}

enum class ParamType { TEXT, CHOICE, SECRET }

/**
 * One `{{name}}` placeholder's editor-defined metadata; what kind of prompt field it gets and, for
 * [ParamType.CHOICE], the fixed set of values to offer. A list of these encodes to
 * `ScriptEntity.paramsJson`; see [encodeParams]/[decodeParams]. Never carries a run's actual value:
 * see [ScriptEntity]'s class doc for why that distinction matters for `SECRET`.
 */
data class ScriptParam(
    val name: String,
    val type: ParamType = ParamType.TEXT,
    val label: String = name,
    val choices: List<String> = emptyList(),
)

/** `org.json`, an Android platform API: a handful of small objects doesn't earn a serialization dependency. */
fun encodeParams(params: List<ScriptParam>): String {
    val array = JSONArray()
    params.forEach { param ->
        val obj = JSONObject()
        obj.put("name", param.name)
        obj.put("type", param.type.name)
        obj.put("label", param.label)
        if (param.choices.isNotEmpty()) obj.put("choices", JSONArray(param.choices))
        array.put(obj)
    }
    return array.toString()
}

/** Tolerant of blank or malformed input, so a hand-edited or corrupt row degrades to "no params" and not crashing the script list. */
fun decodeParams(json: String): List<ScriptParam> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val choices = obj.optJSONArray("choices")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            val type =
                runCatching { ParamType.valueOf(obj.getString("type")) }.getOrDefault(ParamType.TEXT)
            ScriptParam(
                name = obj.getString("name"),
                type = type,
                label = obj.optString("label", obj.getString("name")),
                choices = choices
            )
        }
    }.getOrDefault(emptyList())
}

/** A small fixed accent palette for [ScriptEntity.color], and not a full colour picker. */
object ScriptColor {
    val PRESETS =
        listOf(
            Color(0xFF3C6E48).toArgb(),
            Color(0xFF396569).toArgb(),
            Color(0xFF6E3C3C).toArgb(),
            Color(0xFF6E5C3C).toArgb(),
            Color(0xFF4A3C6E).toArgb(),
            Color(0xFF3C526E).toArgb(),
        )
    val DEFAULT = PRESETS.first()
}
