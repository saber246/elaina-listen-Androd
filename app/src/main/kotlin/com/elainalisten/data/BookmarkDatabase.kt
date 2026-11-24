package com.elainalisten.data

import android.content.Context
import androidx.room.Dao
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
        fun idFor(article: ReadableArticle): String {
            val identity = article.sourceUrl?.let { "web:$it" } ?: "text:${encodeArticle(article)}"
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        fun encodeArticle(article: ReadableArticle): String {
            val chapters = JSONArray()
            article.chapters.forEach { chapter ->
                chapters.put(JSONObject().put("title", chapter.title).put("paragraphs", JSONArray(chapter.paragraphs)))
            }
            return JSONObject().put("chapters", chapters).put("next", article.nextPageUrl.orEmpty()).toString()
        }
    }
}

@Dao
internal interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY updatedAt DESC")
    fun observe(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun find(id: String): Bookmark?

    @Upsert
    suspend fun insert(bookmark: Bookmark)

    @Update
    suspend fun checkpoint(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [Bookmark::class], version = 1, exportSchema = true)
internal abstract class BookmarkDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao

    companion object {
        fun open(context: Context): BookmarkDatabase =
            Room.databaseBuilder(context, BookmarkDatabase::class.java, "reading-bookmarks.db").build()
    }
}
