package io.github.fowles.stochastic_strength.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration15To16Test {

    private val dbName = "migration-15-16-test-db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setup() { context.deleteDatabase(dbName) }
    @After fun teardown() { context.deleteDatabase(dbName) }

    private fun createV15DbAndMigrate(
        seed: (SupportSQLiteDatabase) -> Unit = {},
    ): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `baseline_override` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `sessionId` INTEGER,
                                `muscleGroup` TEXT NOT NULL,
                                `baselineWeight` REAL NOT NULL,
                                `asOf` INTEGER NOT NULL)
                        """.trimIndent())
                        seed(db)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_15_16.migrate(db)
        return db
    }

    @Test fun migrate15to16_defaultsExistingRowsToOverride() {
        createV15DbAndMigrate { db ->
            db.execSQL(
                "INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf) " +
                    "VALUES (7, 'CHEST', 100.0, 1000)"
            )
        }.use { migrated ->
            migrated.query("SELECT reason FROM baseline_override WHERE sessionId = 7").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("OVERRIDE", c.getString(0))
            }
        }
    }

    @Test fun migrate15to16_allowsInsertingDetrainRows() {
        createV15DbAndMigrate().use { migrated ->
            migrated.execSQL(
                "INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf, reason) " +
                    "VALUES (8, 'BACK', 80.0, 2000, 'DETRAIN')"
            )
            migrated.query("SELECT reason FROM baseline_override WHERE sessionId = 8").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("DETRAIN", c.getString(0))
            }
        }
    }
}
