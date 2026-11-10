package io.github.mangi.eta.fork.projects

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, ProjectSubjectBindingEntity::class, io.github.mangi.eta.data.db.BranchSetupReceiptEntity::class],
    version = 2,
    exportSchema = false,
)
internal abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile private var instance: ProjectDatabase? = null

        fun get(context: Context): ProjectDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ProjectDatabase::class.java,
                "eta_projects.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        internal val MIGRATION_1_2 = androidx.room.migration.Migration(1, 2) {
            io.github.mangi.eta.data.db.createBranchSetupReceipts(it)
        }
    }
}
