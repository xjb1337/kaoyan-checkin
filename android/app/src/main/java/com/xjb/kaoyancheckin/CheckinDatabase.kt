package com.xjb.kaoyancheckin

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "document")
data class CheckinDocument(@PrimaryKey val id: Int = 1, val json: String)

@Dao
interface CheckinDao {
    @Query("SELECT json FROM document WHERE id = 1")
    fun readBlocking(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun writeBlocking(document: CheckinDocument)

    @Query("SELECT json FROM document WHERE id = 1")
    suspend fun read(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(document: CheckinDocument)
}

@Database(entities = [CheckinDocument::class], version = 1, exportSchema = false)
abstract class CheckinDatabase : RoomDatabase() {
    abstract fun document(): CheckinDao
}
