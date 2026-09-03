package org.fossify.messages.autofill

import android.content.Context

class AutofillConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var autoSubmit: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SUBMIT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SUBMIT, value).apply()

    var copyToClipboard: Boolean
        get() = prefs.getBoolean(KEY_COPY_TO_CLIPBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_COPY_TO_CLIPBOARD, value).apply()

    var enableFloatingPill: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_FLOATING_PILL, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_FLOATING_PILL, value).apply()

    var excludedPackages: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_PACKAGES, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, value).apply()

    fun isPackageExcluded(packageName: String): Boolean = excludedPackages.contains(packageName)

    companion object {
        private const val PREFS_NAME = "autofill_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTO_SUBMIT = "auto_submit"
        private const val KEY_COPY_TO_CLIPBOARD = "copy_to_clipboard"
        private const val KEY_ENABLE_FLOATING_PILL = "enable_floating_pill"
        private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
    }
}
