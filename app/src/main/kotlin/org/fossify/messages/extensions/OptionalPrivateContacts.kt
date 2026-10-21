package org.fossify.messages.extensions

import android.content.Context
import android.database.Cursor
import org.fossify.commons.extensions.getMyContactsCursor

fun Context.getOptionalPrivateContactsCursor(favoritesOnly: Boolean = false, withPhoneNumbersOnly: Boolean = false): Cursor? {
    if (packageManager.resolveContentProvider("org.fossify.commons.contactsprovider", 0) == null) return null
    return runCatching { getMyContactsCursor(favoritesOnly, withPhoneNumbersOnly) }.getOrNull()
}
