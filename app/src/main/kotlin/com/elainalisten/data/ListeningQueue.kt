package com.elainalisten.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.elainalisten.article.ReadableArticle
import com.elainalisten.tts.Voice
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "listening_queue")
internal data class QueueEntry(
    @PrimaryKey val id: String,
    val position: Long,
    val title: String,
    val sourceUrl: String?,
    val snapshotTitle: String?,
    val snapshotUrl: String?,
    val snapshot: String?,
    val chapter: Int = 0,
    val paragraph: Int = 0,
    val part: Int = 0,
    val offsetMs: Long = 0,
    val positionMs: Long = 0,
    val voiceId: String? = null,
    val speed: Float = 1f,
    val segmentLength: Int = 80,
    val completed: Boolean = false,
) {
    fun resumeBookmark(voice: Voice, currentSpeed: Float, length: Int): Bookmark? {
        val encoded = snapshot ?: return null
        val resume = !completed && voiceId != null
        return Bookmark(id, snapshotTitle ?: title, snapshotUrl, encoded,
            if (resume) chapter else 0, if (resume) paragraph else 0, if (resume) part else 0,
            if (resume) offsetMs else 0, if (resume) positionMs else 0,
            if (resume) checkNotNull(voiceId) else voice.id, if (resume) speed else currentSpeed, 0L,
            segmentLength = if (resume) segmentLength else length)
    }

    companion object {
        fun fromArticle(article: ReadableArticle) = QueueEntry(
            Bookmark.idFor(article), 0, article.title, article.sourceUrl,
            article.title, article.sourceUrl, Bookmark.encodeArticle(article),
        )

        fun fromLink(title: String, url: String) = QueueEntry(
            Bookmark.idFor(ReadableArticle(title, url, emptyList())), 0, title, url, null, null, null,
        )
    }
}

internal data class QueueRow(
    val id: String,
    val title: String,
    val position: Long,
    val chapter: Int,
    val paragraph: Int,
    val positionMs: Long,
    val completed: Boolean,
)

@Entity(tableName = "listening_queue_cursor")
internal data class QueueCursor(@PrimaryKey val slot: Int = 0, val entryId: String?)

@Dao
internal interface ListeningQueueDao {
    @Query("SELECT id, title, position, chapter, paragraph, positionMs, completed FROM listening_queue ORDER BY position, id")
    fun observe(): Flow<List<QueueRow>>

    @Query("SELECT entryId FROM listening_queue_cursor WHERE slot = 0")
    fun observeCursor(): Flow<String?>

    @Query("SELECT entryId FROM listening_queue_cursor WHERE slot = 0")
    suspend fun cursor(): String?

    @Query("SELECT * FROM listening_queue WHERE id = :id")
    suspend fun find(id: String): QueueEntry?

    @Query("SELECT EXISTS(SELECT 1 FROM listening_queue WHERE id = :id)")
    suspend fun contains(id: String): Boolean

    @Query("SELECT id FROM listening_queue WHERE completed = 0 ORDER BY position, id LIMIT 1")
    suspend fun firstPending(): String?

    @Query("""SELECT id FROM listening_queue WHERE completed = 0 AND position >
        (SELECT position FROM listening_queue WHERE id = :id) ORDER BY position, id LIMIT 1""")
    suspend fun nextPending(id: String): String?

    @Query("SELECT COALESCE(MAX(position), 0) FROM listening_queue")
    suspend fun lastPosition(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: QueueEntry): Long

    @Transaction
    suspend fun append(entries: List<QueueEntry>): Int {
        var position = lastPosition()
        return entries.count { entry ->
            val inserted = insert(entry.copy(position = position + 1)) != -1L
            if (inserted) position++ else if (entry.snapshot != null) {
                fillSnapshot(entry.id, entry.snapshotTitle, entry.snapshotUrl, entry.snapshot)
            }
            inserted
        }
    }

    @Query("""UPDATE listening_queue SET snapshotTitle = :title, snapshotUrl = :url, snapshot = :encoded
        WHERE id = :id AND snapshot IS NULL""")
    suspend fun fillSnapshot(id: String, title: String?, url: String?, encoded: String)

    @Query("""UPDATE listening_queue SET snapshotTitle = :title, snapshotUrl = :url, snapshot = :encoded,
        chapter = 0, paragraph = 0, part = 0, offsetMs = 0, positionMs = 0
        WHERE id = :id""")
    suspend fun writeSnapshot(id: String, title: String, url: String?, encoded: String)

    @Query("""UPDATE listening_queue SET chapter = :chapter, paragraph = :paragraph, part = :part,
        offsetMs = :offsetMs, positionMs = :positionMs, voiceId = :voiceId, speed = :speed, segmentLength = :length
        WHERE id = :id""")
    suspend fun checkpoint(id: String, chapter: Int, paragraph: Int, part: Int, offsetMs: Long,
        positionMs: Long, voiceId: String, speed: Float, length: Int)

    @Query("UPDATE listening_queue SET completed = 1 WHERE id = :id")
    suspend fun complete(id: String)

    @Query("""UPDATE listening_queue SET completed = 0, chapter = 0, paragraph = 0, part = 0,
        offsetMs = 0, positionMs = 0, voiceId = NULL WHERE id = :id AND completed = 1""")
    suspend fun restartCompleted(id: String)

    @Upsert
    suspend fun putCursor(cursor: QueueCursor)

    @Transaction
    suspend fun select(id: String): Boolean {
        if (!contains(id)) return false
        restartCompleted(id)
        putCursor(QueueCursor(entryId = id))
        return true
    }

    @Query("SELECT id, title, position, chapter, paragraph, positionMs, completed FROM listening_queue ORDER BY position, id")
    suspend fun ordered(): List<QueueRow>

    @Query("UPDATE listening_queue SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Long)

    @Transaction
    suspend fun move(id: String, offset: Int) {
        val items = ordered()
        val index = items.indexOfFirst { it.id == id }
        val other = items.getOrNull(index + offset)
        if (index < 0 || other == null) return
        setPosition(id, other.position)
        setPosition(other.id, items[index].position)
    }

    @Query("DELETE FROM listening_queue WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE reading_resume SET queueEntryId = NULL WHERE queueEntryId = :id")
    suspend fun detachResume(id: String)

    @Transaction
    suspend fun remove(id: String) {
        if (cursor() == id) putCursor(QueueCursor(entryId = nextPending(id)))
        detachResume(id)
        delete(id)
    }
}
