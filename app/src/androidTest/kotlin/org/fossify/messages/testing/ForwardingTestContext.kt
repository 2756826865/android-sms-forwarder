package org.fossify.messages.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID

/** Keep regression databases and preferences away from the installed app's actual records. */
object ForwardingTestContext {
    val context: Context by lazy {
        val prefix = "forwarding-regression-${UUID.randomUUID()}-"
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            private val directory = File(baseContext.cacheDir, prefix).apply { check(mkdirs() || isDirectory) }
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(directory, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(prefix + name, mode)
        }
    }
}
