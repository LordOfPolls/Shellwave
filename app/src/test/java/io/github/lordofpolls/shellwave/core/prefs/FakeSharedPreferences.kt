package io.github.lordofpolls.shellwave.core.prefs

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

// A key mapped to this is deleted from `values` on commit, rather than simply absent from
// `pending` - otherwise remove() followed by commit() would be a no-op.
private object Removed

/** In-memory stand-in for the handful of [SharedPreferences] calls the prefs objects make. */
internal class FakeSharedPreferences : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = ConcurrentHashMap<String, Any?>()
        override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            apply { pending[key!!] = values }

        override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
        override fun remove(key: String?) = apply { pending[key!!] = Removed }
        override fun clear() = apply { values.clear() }
        override fun commit(): Boolean {
            pending.forEach { (key, value) ->
                if (value === Removed) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
}
