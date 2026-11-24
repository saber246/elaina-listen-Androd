package com.elainalisten.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Transaction
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import com.elainalisten.article.Chapter
import com.elainalisten.article.ReadableArticle
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "bookmarks")
internal data class Bookmark(
    @PrimaryKey val id: String,
    val title: String,
    val url: String?,
    val article: String,
    val chapter: Int,
    val paragraph: Int,
    val part: Int,
    val offsetMs: Long,
    val positionMs: Long,
    val voiceId: String,
    val speed: Float,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "80") val segmentLength: Int = 80,
    @ColumnInfo(defaultValue = "0") val isWebsite: Boolean = false,
    val folderId: String? = null,
) {
    fun readArticle(): ReadableArticle {
        val saved = JSONObject(article)
        val items = saved.getJSONArray("chapters")
        val chapters = List(items.length()) { i ->
            val chapter = items.getJSONObject(i)
            val paragraphs = chapter.getJSONArray("paragraphs")
            Chapter(chapter.getString("title"), List(paragraphs.length(), paragraphs::getString))
        }
        return ReadableArticle(title, url, chapters, saved.optString("next").takeIf(String::isNotBlank))
    }

    companion object {
        fun websiteId(url: String): String = digest("website:$url")

        fun idFor(article: ReadableArticle): String {
            val identity = article.sourceUrl?.let { "web:$it" } ?: "text:${encodeArticle(article)}"
            return digest(identity)
        }

        private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun encodeArticle(article: ReadableArticle): String {
            val chapters = JSONArray()
            article.chapters.forEach { chapter ->
                chapters.put(JSONObject().put("title", chapter.title).put("paragraphs", JSONArray(chapter.paragraphs)))
            }
            return JSONObject().put("chapters", chapters).put("next", article.nextPageUrl.orEmpty()).toString()
        }
    }
}

@Entity(tableName = "bookmark_folders", indices = [Index(value = ["name"], unique = true)])
internal data class BookmarkFolder(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "reading_resume")
internal data class ReadingResume(@PrimaryKey val slot: Int = 0, @Embedded val bookmark: Bookmark)

@Dao
internal interface BookmarkDao {
    @Query("SELECT * FROM reading_resume WHERE slot = 0")
    fun observeResume(): Flow<ReadingResume?>

    @Upsert
    suspend fun putResume(resume: ReadingResume)

    @Query("SELECT * FROM bookmarks ORDER BY updatedAt DESC")
    fun observe(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE isWebsite = 0 ORDER BY updatedAt DESC, id LIMIT :limit OFFSET :offset")
    suspend fun list(limit: Int, offset: Int): List<Bookmark>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun find(id: String): Bookmark?

    @Upsert
    suspend fun insert(bookmark: Bookmark)

    @Update
    suspend fun checkpoint(bookmark: Bookmark)

    @Query("SELECT folderId FROM bookmarks WHERE id = :id")
    suspend fun folderOf(id: String): String?

    @Query("SELECT * FROM bookmark_folders ORDER BY name COLLATE NOCASE, id")
    fun observeFolders(): Flow<List<BookmarkFolder>>

    @Query("SELECT * FROM bookmark_folders WHERE id = :id")
    suspend fun findFolder(id: String): BookmarkFolder?

    @Query("SELECT COUNT(*) FROM bookmark_folders WHERE name = :name COLLATE NOCASE AND id != :except")
    suspend fun folderNameInUse(name: String, except: String): Int

    @Insert
    suspend fun insertFolder(folder: BookmarkFolder)

    @Update
    suspend fun updateFolder(folder: BookmarkFolder)

    @Query("UPDATE bookmarks SET folderId = :folderId WHERE id = :id")
    suspend fun move(id: String, folderId: String?)

    @Query("UPDATE bookmarks SET folderId = NULL WHERE folderId = :id")
    suspend fun unfile(id: String)

    @Query("DELETE FROM bookmark_folders WHERE id = :id")
    suspend fun deleteFolder(id: String)

    @Transaction
    suspend fun removeFolder(id: String) {
        unfile(id)
        deleteFolder(id)
    }

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Database(entities = [Bookmark::class, BookmarkFolder::class, ReadingResume::class], version = 3,
    exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)])
internal abstract class BookmarkDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao

    companion object {
        fun open(context: Context): BookmarkDatabase =
            Room.databaseBuilder(context, BookmarkDatabase::class.java, "reading-bookmarks.db").build()
    }
}
