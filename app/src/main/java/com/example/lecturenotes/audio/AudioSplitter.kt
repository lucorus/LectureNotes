package com.example.lecturenotes.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object AudioSplitter {

    private const val HARD_LIMIT_BYTES = 19L * 1024 * 1024
    private const val TARGET_CHUNK_BYTES = 14L * 1024 * 1024
    private const val MIN_SEGMENT_US = 5_000_000L

    // 48 часов в миллисекундах
    private const val MAX_FILE_AGE_MS = 48 * 60 * 60 * 1000L

    /**
     * Очищает старые временные файлы (старше 48 часов).
     * Рекомендуется вызывать при запуске приложения.
     */
    fun cleanupOldChunks(context: Context) {
        try {
            val chunksRootDir = File(context.filesDir, "audio_chunks")
            if (!chunksRootDir.exists()) return

            val cutoffTime = System.currentTimeMillis() - MAX_FILE_AGE_MS
            var deletedCount = 0
            var freedBytes = 0L

            chunksRootDir.listFiles()?.forEach { chunkDir ->
                if (chunkDir.isDirectory && chunkDir.lastModified() < cutoffTime) {
                    val size = chunkDir.walkTopDown().sumOf { it.length() }
                    if (chunkDir.deleteRecursively()) {
                        deletedCount++
                        freedBytes += size
                        Log.d("AudioSplitter", "Удалена старая папка: ${chunkDir.name} (${formatSize(size)})")
                    }
                }
            }

            if (deletedCount > 0) {
                Log.d("AudioSplitter", "Очистка завершена: удалено $deletedCount папок, освобождено ${formatSize(freedBytes)}")
            }

        } catch (e: Exception) {
            Log.e("AudioSplitter", "Ошибка при очистке старых файлов", e)
        }
    }

    /**
     * Форматирует размер в читаемый вид
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    fun splitIfNeeded(
        context: Context,
        sourceFile: File,
        chunkDir: File,
        targetChunkBytes: Long = TARGET_CHUNK_BYTES
    ): List<File> {

        if (sourceFile.length() <= HARD_LIMIT_BYTES) {
            Log.d("AudioSplitter", "Файл ${sourceFile.length()} байт, не требует разбиения")
            return listOf(sourceFile)
        }

        val existingParts = chunkDir.listFiles { f -> f.name.matches(Regex("part_\\d+\\.m4a")) }
            ?.sortedBy { partNumber(it) }
            ?: emptyList()
        if (existingParts.isNotEmpty()) {
            chunkDir.listFiles { f -> f.name.startsWith("tmp_") }?.forEach { it.delete() }
            Log.d("AudioSplitter", "Найдено ${existingParts.size} существующих частей")
            return existingParts
        }

        chunkDir.listFiles()?.forEach { it.delete() }
        chunkDir.mkdirs()

        val durationUs = getDurationUs(sourceFile)
        Log.d("AudioSplitter", "Общая длительность: ${durationUs / 1_000_000} сек")

        // Основное изменение: считаем количество частей более точно
        val totalSize = sourceFile.length()
        val estimatedChunkCount = Math.max(1, Math.ceil(totalSize.toDouble() / targetChunkBytes).toInt())

        // Убедимся, что длительность каждой части положительная
        val chunkDurationUs = if (durationUs > 0) durationUs / estimatedChunkCount else 1_000_000L

        Log.d("AudioSplitter", "Планируется $estimatedChunkCount частей по ${chunkDurationUs / 1_000_000} сек каждая")

        val result = mutableListOf<File>()
        val counter = intArrayOf(0)

        // Создаем список всех сегментов, которые нужно обработать
        val segments = mutableListOf<Pair<Long, Long>>()
        for (chunkIndex in 0 until estimatedChunkCount) {
            val startUs = chunkIndex * chunkDurationUs
            val endUs = if (chunkIndex == estimatedChunkCount - 1) {
                durationUs  // Последний сегмент идет до конца файла
            } else {
                (chunkIndex + 1) * chunkDurationUs
            }
            segments.add(Pair(startUs, endUs))
        }

        // Обрабатываем все сегменты
        for ((startUs, endUs) in segments) {
            Log.d("AudioSplitter", "Обработка сегмента: ${startUs / 1_000_000}-${endUs / 1_000_000} сек")
            extractChunkRecursive(sourceFile, chunkDir, startUs, endUs, counter, result)
        }

        Log.d("AudioSplitter", "Создано ${result.size} частей")
        result.forEachIndexed { index, file ->
            Log.d("AudioSplitter", "Часть ${index + 1}: ${file.name}, размер: ${file.length()} байт")
        }

        return result
    }

    private fun extractChunkRecursive(
        sourceFile: File,
        chunkDir: File,
        startUs: Long,
        endUs: Long,
        counter: IntArray,
        result: MutableList<File>
    ) {
        // Добавляем небольшую защиту от пустых сегментов
        if (endUs <= startUs) {
            Log.w("AudioSplitter", "Пропуск пустого сегмента: $startUs-$endUs")
            return
        }

        val tempFile = File(chunkDir, "tmp_${System.nanoTime()}.m4a")

        try {
            extractChunk(sourceFile, tempFile, startUs, endUs)

            // Проверяем, что файл не пустой
            if (tempFile.length() == 0L) {
                Log.w("AudioSplitter", "Создан пустой файл для сегмента $startUs-$endUs, пропускаем")
                tempFile.delete()
                return
            }

            val tooLarge = tempFile.length() > HARD_LIMIT_BYTES
            val tooShortToSplitFurther = (endUs - startUs) < MIN_SEGMENT_US

            if (!tooLarge || tooShortToSplitFurther) {
                counter[0]++
                val finalFile = File(chunkDir, "part_${counter[0]}.m4a")
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
                result.add(finalFile)
                Log.d("AudioSplitter", "Создана часть ${counter[0]}: ${finalFile.length()} байт")
            } else {
                tempFile.delete()
                val midUs = startUs + (endUs - startUs) / 2
                Log.d("AudioSplitter", "Дробление сегмента $startUs-$endUs на $startUs-$midUs и $midUs-$endUs")
                extractChunkRecursive(sourceFile, chunkDir, startUs, midUs, counter, result)
                extractChunkRecursive(sourceFile, chunkDir, midUs, endUs, counter, result)
            }
        } catch (e: Exception) {
            Log.e("AudioSplitter", "Ошибка при обработке сегмента $startUs-$endUs", e)
            tempFile.delete()
        }
    }

    private fun partNumber(file: File): Int {
        val match = Regex("part_(\\d+)\\.m4a").find(file.name)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun getDurationUs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            durationMs * 1000
        } catch (e: Exception) {
            Log.e("AudioSplitter", "Ошибка получения длительности", e)
            0L
        } finally {
            retriever.release()
        }
    }

    private fun extractChunk(sourceFile: File, outFile: File, startUs: Long, endUs: Long) {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(sourceFile.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            checkNotNull(format) { "В файле не найдена звуковая дорожка" }
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            try {
                val muxerTrackIndex = muxer.addTrack(format)
                muxer.start()

                val buffer = ByteBuffer.allocate(1 * 1024 * 1024)
                val bufferInfo = MediaCodec.BufferInfo()
                var baseTimeUs = -1L
                var sampleCount = 0

                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val sampleTime = extractor.sampleTime

                    // Важное изменение: обрабатываем все сэмплы до конца файла,
                    // даже если они выходят за пределы endUs для последнего сегмента
                    if (sampleTime >= endUs && endUs < getDurationUs(sourceFile)) {
                        break
                    }

                    if (baseTimeUs == -1L) baseTimeUs = sampleTime

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTime - baseTimeUs
                    bufferInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_SYNC_FRAME
                    } else {
                        0
                    }

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    sampleCount++
                    extractor.advance()
                }

                Log.d("AudioSplitter", "Извлечено $sampleCount сэмплов для сегмента $startUs-$endUs")
            } finally {
                muxer.stop()
                muxer.release()
            }
        } finally {
            extractor.release()
        }
    }



    data class CleanupResult(
        val deletedFolders: Int,
        val freedBytes: Long
    )

    fun cleanupOldChunksWithResult(context: Context): CleanupResult {
        var deletedCount = 0
        var freedBytes = 0L

        try {
            val chunksRootDir = File(context.filesDir, "audio_chunks")
            if (!chunksRootDir.exists()) return CleanupResult(0, 0)

            val cutoffTime = System.currentTimeMillis() - MAX_FILE_AGE_MS

            chunksRootDir.listFiles()?.forEach { chunkDir ->
                if (chunkDir.isDirectory && chunkDir.lastModified() < cutoffTime) {
                    val size = chunkDir.walkTopDown().sumOf { it.length() }
                    if (chunkDir.deleteRecursively()) {
                        deletedCount++
                        freedBytes += size
                        Log.d("AudioSplitter", "Удалена папка: ${chunkDir.name} (${formatSize(size)})")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("AudioSplitter", "Ошибка при очистке", e)
        }

        return CleanupResult(deletedCount, freedBytes)
    }

}
