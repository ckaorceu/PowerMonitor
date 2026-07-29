package com.powermonitor.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.powermonitor.data.dao.PowerDao
import com.powermonitor.data.entity.MinuteAggregate
import com.powermonitor.data.entity.RawSample

@Database(
    entities = [RawSample::class, MinuteAggregate::class],
    version = 1,
    exportSchema = false
)
abstract class PowerDatabase : RoomDatabase() {

    abstract fun powerDao(): PowerDao

    companion object {
        @Volatile
        private var INSTANCE: PowerDatabase? = null

        fun getDatabase(context: Context): PowerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PowerDatabase::class.java,
                    "power_monitor_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
