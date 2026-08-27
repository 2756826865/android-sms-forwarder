@file:Suppress("MagicNumber")
package org.fossify.messages.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.fossify.messages.helpers.Converters
import org.fossify.messages.interfaces.AttachmentsDao
import org.fossify.messages.interfaces.ConversationsDao
import org.fossify.messages.interfaces.DraftsDao
import org.fossify.messages.interfaces.MessageAttachmentsDao
import org.fossify.messages.interfaces.MessagesDao
import org.fossify.messages.interfaces.ShadowDaos
import org.fossify.messages.interfaces.SmsSendDao
import org.fossify.messages.interfaces.RemoteCommandDao
import org.fossify.messages.interfaces.OutboxTaskDao
import org.fossify.messages.interfaces.RecoveryRecordDao
import org.fossify.messages.models.Attachment
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Draft
import org.fossify.messages.models.Message
import org.fossify.messages.models.MessageAttachment
import org.fossify.messages.models.RecycleBinMessage
import org.fossify.messages.models.MessageOperation
import org.fossify.messages.models.MessageOperationStep
import org.fossify.messages.models.ForwardingShadowDelivery
import org.fossify.messages.models.ForwardingShadowAttempt
import org.fossify.messages.models.DiagnosticCounter
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendPartEntity
import org.fossify.messages.models.RemoteCommandExecutionEntity
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.RecoveryRecordEntity

@Database(
    entities = [
        Conversation::class,
        Attachment::class,
        MessageAttachment::class,
        Message::class,
        RecycleBinMessage::class,
        Draft::class,
        MessageOperation::class,
        MessageOperationStep::class,
        ForwardingShadowDelivery::class,
        ForwardingShadowAttempt::class,
        DiagnosticCounter::class,
        SmsSendOperationEntity::class,
        SmsSendPartEntity::class,
        RemoteCommandExecutionEntity::class,
        OutboxTaskEntity::class,
        RecoveryRecordEntity::class
    ],
    version = 16
)
@TypeConverters(Converters::class)
abstract class MessagesDatabase : RoomDatabase() {

    abstract fun ConversationsDao(): ConversationsDao

    abstract fun AttachmentsDao(): AttachmentsDao

    abstract fun MessageAttachmentsDao(): MessageAttachmentsDao

    abstract fun MessagesDao(): MessagesDao

    abstract fun DraftsDao(): DraftsDao
    
    abstract fun ShadowDaos(): ShadowDaos

    abstract fun SmsSendDao(): SmsSendDao

    abstract fun RemoteCommandDao(): RemoteCommandDao

    abstract fun OutboxTaskDao(): OutboxTaskDao

    abstract fun RecoveryRecordDao(): RecoveryRecordDao

    companion object {
        private var db: MessagesDatabase? = null

        fun getInstance(context: Context): MessagesDatabase {
            if (db == null) {
                synchronized(MessagesDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(
                            context = context.applicationContext,
                            klass = MessagesDatabase::class.java,
                            name = "conversations.db"
                        )
                            .fallbackToDestructiveMigration()
                            .addMigrations(MIGRATION_1_2)
                            .addMigrations(MIGRATION_2_3)
                            .addMigrations(MIGRATION_3_4)
                            .addMigrations(MIGRATION_4_5)
                            .addMigrations(MIGRATION_5_6)
                            .addMigrations(MIGRATION_6_7)
                            .addMigrations(MIGRATION_7_8)
                            .addMigrations(MIGRATION_8_9)
                            .addMigrations(MIGRATION_9_10)
                            .addMigrations(MIGRATION_10_11)
                            .addMigrations(MIGRATION_11_12)
                            .addMigrations(MIGRATION_12_13)
                            .addMigrations(MIGRATION_13_14)
                            .addMigrations(MIGRATION_14_15)
                            .addMigrations(MIGRATION_15_16)
                            .build()
                    }
                }
            }
            return db!!
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY NOT NULL, `body` TEXT NOT NULL, `type` INTEGER NOT NULL, `participants` TEXT NOT NULL, `date` INTEGER NOT NULL, `read` INTEGER NOT NULL, `thread_id` INTEGER NOT NULL, `is_mms` INTEGER NOT NULL, `attachment` TEXT, `sender_name` TEXT NOT NULL, `sender_photo_uri` TEXT NOT NULL, `subscription_id` INTEGER NOT NULL)")

                    execSQL("CREATE TABLE IF NOT EXISTS `message_attachments` (`id` INTEGER PRIMARY KEY NOT NULL, `text` TEXT NOT NULL, `attachments` TEXT NOT NULL)")

                    execSQL("CREATE TABLE IF NOT EXISTS `attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `message_id` INTEGER NOT NULL, `uri_string` TEXT NOT NULL, `mimetype` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `filename` TEXT NOT NULL)")
                    execSQL("CREATE UNIQUE INDEX `index_attachments_message_id` ON `attachments` (`message_id`)")
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("CREATE TABLE conversations_new (`thread_id` INTEGER NOT NULL PRIMARY KEY, `snippet` TEXT NOT NULL, `date` INTEGER NOT NULL, `read` INTEGER NOT NULL, `title` TEXT NOT NULL, `photo_uri` TEXT NOT NULL, `is_group_conversation` INTEGER NOT NULL, `phone_number` TEXT NOT NULL)")

                    execSQL(
                        "INSERT OR IGNORE INTO conversations_new (thread_id, snippet, date, read, title, photo_uri, is_group_conversation, phone_number) " +
                                "SELECT thread_id, snippet, date, read, title, photo_uri, is_group_conversation, phone_number FROM conversations"
                    )

                    execSQL("DROP TABLE conversations")

                    execSQL("ALTER TABLE conversations_new RENAME TO conversations")

                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_conversations_id` ON `conversations` (`thread_id`)")
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE messages ADD COLUMN status INTEGER NOT NULL DEFAULT -1")
                }
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE messages ADD COLUMN is_scheduled INTEGER NOT NULL DEFAULT 0")
                    execSQL("ALTER TABLE conversations ADD COLUMN is_scheduled INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE conversations ADD COLUMN uses_custom_title INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE messages ADD COLUMN sender_phone_number TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                    execSQL("CREATE TABLE IF NOT EXISTS `recycle_bin_messages` (`id` INTEGER NOT NULL PRIMARY KEY, `deleted_ts` INTEGER NOT NULL)")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recycle_bin_messages_id` ON `recycle_bin_messages` (`id`)")
                }
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("CREATE TABLE IF NOT EXISTS `drafts` (`thread_id` INTEGER NOT NULL PRIMARY KEY, `body` TEXT NOT NULL, `date` INTEGER NOT NULL)")
                }
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE conversations ADD COLUMN unread_count INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_thread_id_date` " +
                        "ON `messages` (`thread_id`, `date`)"
                )
            }
        }
        
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    // message_operations
                    execSQL("CREATE TABLE IF NOT EXISTS `message_operations` (`operation_id` TEXT NOT NULL, `direction` TEXT NOT NULL, `source` TEXT NOT NULL, `provider_message_id` INTEGER, `thread_id` INTEGER, `address_hmac` TEXT, `body_hmac` TEXT, `body_length` INTEGER, `subscription_id` INTEGER, `pdu_count` INTEGER, `format` TEXT, `message_timestamp` INTEGER, `received_at` INTEGER NOT NULL, `provider_inserted_at` INTEGER, `match_confidence` REAL, `diagnostic_state` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `expires_at` INTEGER, PRIMARY KEY(`operation_id`))")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_message_operations_direction_created_at` ON `message_operations` (`direction`, `created_at`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_message_operations_provider_message_id` ON `message_operations` (`provider_message_id`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_message_operations_address_hmac` ON `message_operations` (`address_hmac`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_message_operations_body_hmac` ON `message_operations` (`body_hmac`)")
                    
                    // message_operation_steps
                    execSQL("CREATE TABLE IF NOT EXISTS `message_operation_steps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `operation_id` TEXT NOT NULL, `step_type` TEXT NOT NULL, `status` TEXT NOT NULL, `detail` TEXT, `timestamp` INTEGER NOT NULL)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_message_operation_steps_operation_id` ON `message_operation_steps` (`operation_id`)")
                    
                    // forwarding_deliveries
                    execSQL("CREATE TABLE IF NOT EXISTS `forwarding_deliveries` (`delivery_id` TEXT NOT NULL, `operation_id` TEXT NOT NULL, `channel` TEXT NOT NULL, `state` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`delivery_id`))")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_forwarding_deliveries_operation_id` ON `forwarding_deliveries` (`operation_id`)")
                    
                    // forwarding_attempts
                    execSQL("CREATE TABLE IF NOT EXISTS `forwarding_attempts` (`attempt_id` TEXT NOT NULL, `delivery_id` TEXT NOT NULL, `attempt_number` INTEGER NOT NULL, `legacy_worker_id` TEXT, `state` TEXT NOT NULL, `request_started_at` INTEGER, `request_finished_at` INTEGER, `response_received_at` INTEGER, `http_status` INTEGER, `error_class` TEXT, `error_hmac` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`attempt_id`))")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_forwarding_attempts_delivery_id` ON `forwarding_attempts` (`delivery_id`)")
                    
                    // operation_diagnostic_counters
                    execSQL("CREATE TABLE IF NOT EXISTS `operation_diagnostic_counters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bucket_key` TEXT NOT NULL, `event_type` TEXT NOT NULL, `count` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_operation_diagnostic_counters_bucket_key_event_type` ON `operation_diagnostic_counters` (`bucket_key`, `event_type`)")
                }
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    // sms_send_operations
                    execSQL("CREATE TABLE IF NOT EXISTS `sms_send_operations` (`send_operation_id` TEXT NOT NULL, `trigger_type` TEXT NOT NULL, `address_hmac` TEXT, `body_hmac` TEXT, `body_length` INTEGER, `subscription_id` INTEGER, `thread_id` INTEGER, `require_delivery_report` INTEGER NOT NULL, `message_uri` TEXT, `provider_message_id` INTEGER, `part_count` INTEGER, `state` TEXT NOT NULL, `error_class` TEXT, `created_at` INTEGER NOT NULL, `submitting_at` INTEGER, `submitted_at` INTEGER, `sent_at` INTEGER, `delivered_at` INTEGER, `failed_at` INTEGER, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`send_operation_id`))")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_sms_send_operations_state_created_at` ON `sms_send_operations` (`state`, `created_at`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_sms_send_operations_provider_message_id` ON `sms_send_operations` (`provider_message_id`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_sms_send_operations_address_hmac` ON `sms_send_operations` (`address_hmac`)")

                    // sms_send_parts
                    execSQL("CREATE TABLE IF NOT EXISTS `sms_send_parts` (`part_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `send_operation_id` TEXT NOT NULL, `part_index` INTEGER NOT NULL, `part_count` INTEGER NOT NULL, `sent_request_identity` INTEGER, `delivered_request_identity` INTEGER, `sent_state` TEXT, `sent_result_code` INTEGER, `delivered_state` TEXT, `delivered_result_code` INTEGER, `created_at` INTEGER NOT NULL, `sent_at` INTEGER, `delivered_at` INTEGER, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`send_operation_id`) REFERENCES `sms_send_operations`(`send_operation_id`) ON DELETE CASCADE ON UPDATE NO ACTION)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_sms_send_parts_send_operation_id` ON `sms_send_parts` (`send_operation_id`)")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sms_send_parts_send_operation_id_part_index` ON `sms_send_parts` (`send_operation_id`, `part_index`)")
                }
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    // remote_command_executions
                    execSQL("CREATE TABLE IF NOT EXISTS `remote_command_executions` (`command_id` TEXT NOT NULL, `source_type` TEXT NOT NULL, `source_message_key` TEXT NOT NULL, `command_type` TEXT NOT NULL, `target_hmac` TEXT, `payload_hmac` TEXT NOT NULL, `payload_length` INTEGER NOT NULL, `requested_sim_mode` INTEGER NOT NULL, `requester_hmac` TEXT NOT NULL, `received_at` INTEGER NOT NULL, `authorized` INTEGER NOT NULL, `authorization_reason` TEXT NOT NULL, `execution_state` TEXT NOT NULL, `outbox_task_id` TEXT, `send_operation_id` TEXT, `started_at` INTEGER, `completed_at` INTEGER, `error_class` TEXT, `error_hmac` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`command_id`))")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_commands_idempotency` ON `remote_command_executions` (`source_type`, `source_message_key`, `payload_hmac`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_remote_commands_state_created_at` ON `remote_command_executions` (`execution_state`, `created_at`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_remote_commands_source` ON `remote_command_executions` (`source_type`, `source_message_key`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_remote_commands_requester_hmac` ON `remote_command_executions` (`requester_hmac`)")
                }
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    // outbox_tasks
                    execSQL("CREATE TABLE IF NOT EXISTS `outbox_tasks` (`task_id` TEXT NOT NULL, `task_type` TEXT NOT NULL, `source_type` TEXT NOT NULL, `source_id` TEXT NOT NULL, `payload_hmac` TEXT NOT NULL, `payload_payload` TEXT, `state` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL DEFAULT 0, `max_attempts` INTEGER NOT NULL DEFAULT 3, `next_retry_at` INTEGER NOT NULL DEFAULT 0, `last_error_class` TEXT, `last_error_hmac` TEXT, `locked_by` TEXT, `lock_expires_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`task_id`))")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_tasks_state_next_retry` ON `outbox_tasks` (`state`, `next_retry_at`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_tasks_source` ON `outbox_tasks` (`source_type`, `source_id`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_tasks_locked` ON `outbox_tasks` (`locked_by`, `lock_expires_at`)")
                }
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    // recovery_records
                    execSQL("CREATE TABLE IF NOT EXISTS `recovery_records` (`record_id` TEXT NOT NULL, `scan_time` INTEGER NOT NULL, `trigger_source` TEXT NOT NULL, `object_type` TEXT NOT NULL, `object_id` TEXT NOT NULL, `initial_status` TEXT NOT NULL, `recovered_status` TEXT NOT NULL, `action_taken` TEXT NOT NULL, `detail_message` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`record_id`))")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_recovery_records_scan_time` ON `recovery_records` (`scan_time`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_recovery_records_object` ON `recovery_records` (`object_type`, `object_id`)")
                }
            }
        }
    }
}
