package com.example.lecturenotes

import android.app.Application
import android.util.Log
import com.example.lecturenotes.audio.AudioSplitter

class LectureNotesApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Запускаем очистку в фоновом потоке
        Thread {
            try {
                val result = AudioSplitter.cleanupOldChunksWithResult(this)
                if (result.deletedFolders > 0) {
                    Log.d("LectureNotesApp", "Очищено ${result.deletedFolders} папок, освобождено ${result.freedBytes} байт")
                }
            } catch (e: Exception) {
                Log.e("LectureNotesApp", "Ошибка очистки", e)
            }
        }.start()
    }
}