package io.github.mangi.eta.fork.meeting

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MeetingRoomEntity::class,
        MeetingParticipantEntity::class,
        MeetingMessageEntity::class,
        MeetingTaskEntity::class,
        MeetingEventEntity::class,
        MeetingParticipantContextEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
internal abstract class MeetingDatabase : RoomDatabase() {
    abstract fun meetingDao(): MeetingDao

    companion object {
        @Volatile
        private var instance: MeetingDatabase? = null

        fun get(context: Context): MeetingDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeetingDatabase::class.java,
                    "eta_meeting.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS meeting_participant_contexts (
                        room_id TEXT NOT NULL,
                        profile_name TEXT NOT NULL,
                        snapshot_json TEXT NOT NULL,
                        last_message_sort_index INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(room_id, profile_name)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_meeting_participant_contexts_room_id " +
                        "ON meeting_participant_contexts(room_id)",
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meeting_rooms ADD COLUMN archived_at INTEGER")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meeting_rooms ADD COLUMN pinned_at INTEGER")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meeting_rooms ADD COLUMN context_window_override INTEGER")
            }
        }
    }
}
