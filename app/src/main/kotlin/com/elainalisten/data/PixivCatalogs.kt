package com.elainalisten.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.elainalisten.article.PixivEntry
import com.elainalisten.article.PixivNovel
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "pixiv_catalogs")
internal data class StoredPixivCatalog(@PrimaryKey val id: String, val title: String, val entries: String, val failed: String) {
    fun decode() = PixivCatalog(id, title, PixivNovel.catalog(JSONArray(entries), includeUnavailable = true),
        failed.split(',').filter(String::isNotEmpty).toSet())
}

internal data class PixivCatalog(val id: String, val title: String, val entries: List<PixivEntry>, val failed: Set<String> = emptySet()) {
    fun encode() = StoredPixivCatalog(id, title, JSONArray(entries.map { entry ->
        JSONObject().put("id", entry.id).put("title", entry.title).put("order", entry.number).put("available", entry.available)
    }).toString(), failed.joinToString(","))
}

@Dao
internal interface PixivCatalogDao {
    @Query("SELECT * FROM pixiv_catalogs")
    fun observe(): Flow<List<StoredPixivCatalog>>

    @Query("SELECT * FROM pixiv_catalogs WHERE id = :id")
    suspend fun find(id: String): StoredPixivCatalog?

    @Upsert
    suspend fun save(catalog: StoredPixivCatalog)

    @Query("UPDATE pixiv_catalogs SET failed = :failed WHERE id = :id")
    suspend fun failures(id: String, failed: String)
}
