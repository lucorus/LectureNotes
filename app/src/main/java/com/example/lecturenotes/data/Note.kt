package com.example.lecturenotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Один конспект лекции.
 * audioFilePath — путь к аудиофайлу на устройстве (может быть null, пока аудио не добавлено).
 * transcriptText — распознанный текст лекции (по умолчанию пустой, редактируется вручную).
 * summaryText — итоговый конспект, сделанный нейросетью (по умолчанию пустой, редактируется вручную).
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val date: Long,
    val category: String,
    val audioFilePath: String? = null,
    val transcriptText: String = "",
    val summaryText: String = ""
)
