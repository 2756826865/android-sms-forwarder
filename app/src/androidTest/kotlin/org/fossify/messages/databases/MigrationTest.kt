package org.fossify.messages.databases

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MessagesDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun migrate11To12() {
        // Create earliest version of the database to test entities from v11
        var db = helper.createDatabase(TEST_DB, 11)

        // Insert some data that should be preserved
        db.execSQL("INSERT INTO conversations (thread_id, snippet, date, read, title, photo_uri, is_group_conversation, phone_number) " +
                "VALUES (1, 'test snippet', 123456789, 0, 'test title', '', 0, '123456')")
        
        db.execSQL("INSERT INTO messages (id, body, type, participants, date, read, thread_id, is_mms, attachment, sender_name, sender_photo_uri, subscription_id) " +
                "VALUES (100, 'test message', 1, '[]', 123456789, 0, 1, 0, NULL, 'sender', '', -1)")

        // Prepare for the next version
        db.close()

        // Re-open the database with version 12 and provide MIGRATION_11_12
        db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MessagesDatabase.MIGRATION_11_12)

        // Verify that the data was preserved
        val cursor = db.query("SELECT * FROM conversations WHERE thread_id = 1")
        assert(cursor.moveToFirst())
        assert(cursor.getString(cursor.getColumnIndex("snippet")) == "test snippet")
        cursor.close()

        val msgCursor = db.query("SELECT * FROM messages WHERE id = 100")
        assert(msgCursor.moveToFirst())
        assert(msgCursor.getString(msgCursor.getColumnIndex("body")) == "test message")
        msgCursor.close()

        // Verify that new tables exist
        db.query("SELECT * FROM message_operations").close()
        db.query("SELECT * FROM message_operation_steps").close()
        db.query("SELECT * FROM forwarding_deliveries").close()
        db.query("SELECT * FROM forwarding_attempts").close()
        db.query("SELECT * FROM operation_diagnostic_counters").close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate12To13() {
        // Create v12 database with existing data
        var db = helper.createDatabase(TEST_DB, 12)

        // Insert conversation data that must survive the migration
        db.execSQL("INSERT INTO conversations (thread_id, snippet, date, read, title, photo_uri, is_group_conversation, phone_number) " +
                "VALUES (2, 'pre-migration snippet', 987654321, 0, 'test title 2', '', 0, '987654')")

        db.execSQL("INSERT INTO message_operations (operation_id, direction, source, received_at, created_at, updated_at) " +
                "VALUES ('op-test-1', 'INCOMING', 'BROADCAST', 111111111, 111111112, 111111113)")

        db.close()

        // Run migration 12 -> 13 and validate schema
        db = helper.runMigrationsAndValidate(TEST_DB, 13, true, MessagesDatabase.MIGRATION_12_13)

        // Verify existing data was preserved
        val convCursor = db.query("SELECT * FROM conversations WHERE thread_id = 2")
        assert(convCursor.moveToFirst())
        assert(convCursor.getString(convCursor.getColumnIndex("snippet")) == "pre-migration snippet")
        convCursor.close()

        val opCursor = db.query("SELECT * FROM message_operations WHERE operation_id = 'op-test-1'")
        assert(opCursor.moveToFirst())
        assert(opCursor.getString(opCursor.getColumnIndex("direction")) == "INCOMING")
        opCursor.close()

        // Verify new tables exist
        db.query("SELECT * FROM sms_send_operations").close()
        db.query("SELECT * FROM sms_send_parts").close()

        // Verify foreign key and indices
        val indexCursor = db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='sms_send_operations'")
        val indexNames = mutableListOf<String>()
        while (indexCursor.moveToNext()) {
            indexNames.add(indexCursor.getString(0))
        }
        indexCursor.close()
        assert(indexNames.contains("index_sms_send_operations_state_created_at"))
        assert(indexNames.contains("index_sms_send_operations_provider_message_id"))
        assert(indexNames.contains("index_sms_send_operations_address_hmac"))

        val partIndexCursor = db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='sms_send_parts'")
        val partIndexNames = mutableListOf<String>()
        while (partIndexCursor.moveToNext()) {
            partIndexNames.add(partIndexCursor.getString(0))
        }
        partIndexCursor.close()
        assert(partIndexNames.contains("index_sms_send_parts_send_operation_id"))
        assert(partIndexNames.contains("index_sms_send_parts_send_operation_id_part_index"))
    }

    @Test
    @Throws(IOException::class)
    fun migrate11To13() {
        // Chain migration 11 -> 12 -> 13
        var db = helper.createDatabase(TEST_DB, 11)

        db.execSQL("INSERT INTO conversations (thread_id, snippet, date, read, title, photo_uri, is_group_conversation, phone_number) " +
                "VALUES (3, 'chained snippet', 555555555, 0, 'chained title', '', 0, '555555')")

        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 13, true,
            MessagesDatabase.MIGRATION_11_12, MessagesDatabase.MIGRATION_12_13)

        val cursor = db.query("SELECT * FROM conversations WHERE thread_id = 3")
        assert(cursor.moveToFirst())
        assert(cursor.getString(cursor.getColumnIndex("snippet")) == "chained snippet")
        cursor.close()

        // All tables from both migrations should exist
        db.query("SELECT * FROM message_operations").close()
        db.query("SELECT * FROM sms_send_operations").close()
        db.query("SELECT * FROM sms_send_parts").close()
    }
}
