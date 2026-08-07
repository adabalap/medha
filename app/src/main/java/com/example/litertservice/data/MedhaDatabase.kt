package com.example.litertservice.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        DocumentEntity::class,
        ChunkEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MedhaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun documentDao(): DocumentDao

    companion object {
        @Volatile private var INSTANCE: MedhaDatabase? = null

        fun get(context: Context): MedhaDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MedhaDatabase::class.java,
                    "medha.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
