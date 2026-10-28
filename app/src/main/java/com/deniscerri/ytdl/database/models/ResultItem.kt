package com.deniscerri.ytdl.database.models

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Entity(tableName = "results")
@Parcelize
data class ResultItem(
    @PrimaryKey(autoGenerate = true)
    var id: Long,
    var url: String,
    var title: String,
    var author: String,
    val duration: String,
    val thumb: String,
    val website: String,
    var playlistTitle: String,
    var formats: List<Format> = emptyList(),
    @ColumnInfo(defaultValue = "")
    var urls: String,
    var chapters: List<ChapterItem>?,
    @ColumnInfo(defaultValue = "")
    var playlistURL: String? = "",
    @ColumnInfo(defaultValue = "")
    var playlistIndex: Int? = null,
    var creationTime: Long = System.currentTimeMillis() / 1000,
    @ColumnInfo(defaultValue = "[]")
    var availableSubtitles: List<String> = listOf(),
    /** YTindir: shown in the results and used to sort them; null when the site doesn't say */
    var viewCount: Long? = null,
    /** YTindir: upload time in epoch seconds; null when unknown */
    var uploadDate: Long? = null
) : Parcelable