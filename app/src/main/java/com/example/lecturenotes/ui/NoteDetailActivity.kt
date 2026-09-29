package com.example.lecturenotes.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.lecturenotes.audio.AudioSplitter
import com.example.lecturenotes.data.AppDatabase
import com.example.lecturenotes.data.Note
import com.example.lecturenotes.data.NoteRepository
import com.example.lecturenotes.databinding.ActivityNoteDetailBinding
import com.example.lecturenotes.network.ApiKeyStore
import com.example.lecturenotes.network.LectureAiHelper
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.lecturenotes.network.SummaryService
import android.util.Log

class NoteDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteDetailBinding
    private lateinit var repository: NoteRepository
    private lateinit var markwon: Markwon

    private var noteId: Long = -1
    private var currentNote: Note? = null

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false
    private var isAudioPlaying = false
    private var isTranscriptExpanded = true

    // Флаг, чтобы не пересохранять текст, пока мы сами его программно устанавливаем
    private var suppressTextWatcher = false

    private val requestRecordPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else
                Toast.makeText(this, "Нужно разрешение на запись звука", Toast.LENGTH_SHORT).show()
        }

    private val importAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val file = copyUriToAppStorage(uri)
                if (file != null) {
                    saveAudioPath(file.absolutePath)
                }
            }
        }

    private val createMdDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            uri ?: return@registerForActivityResult
            saveSummaryAsMarkdown(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        markwon = Markwon.create(this)
        repository = NoteRepository(AppDatabase.getInstance(this).noteDao())
        noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1)
        if (noteId == -1L) {
            finish()
            return
        }

        loadNote()
        setupListeners()
        setupAutoSaveTextFields()
        setupTranscriptCollapse()
        setupSummaryModeToggle()
    }

    private fun loadNote() {
        lifecycleScope.launch {
            val note = repository.getById(noteId) ?: run { finish(); return@launch }
            currentNote = note
            supportActionBar?.title = note.title

            val dateFormat = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
            binding.textMeta.text = "${note.category} · ${dateFormat.format(note.date)}"

            suppressTextWatcher = true
            binding.editTranscript.setText(note.transcriptText)
            binding.editSummary.setText(note.summaryText)
            suppressTextWatcher = false

            updateAudioUi(note.audioFilePath)
        }
    }

    private fun setupListeners() {
        binding.buttonRecord.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                requestRecordPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.buttonImport.setOnClickListener {
            importAudioLauncher.launch(arrayOf("audio/*"))
        }

        binding.buttonReplaceAudio.setOnClickListener {
            stopPlaybackIfNeeded()
            // Новое аудио — старые части/кэш распознавания от предыдущего файла больше не актуальны
            clearChunkCache()
            binding.layoutHasAudioButtons.visibility = android.view.View.GONE
            binding.layoutNoAudioButtons.visibility = android.view.View.VISIBLE
        }

        binding.buttonPlayPause.setOnClickListener { togglePlayback() }

        binding.buttonDeleteAudio.setOnClickListener { confirmDeleteAudio() }

        binding.buttonTranscribe.setOnClickListener { runTranscription() }
        binding.buttonSummarize.setOnClickListener { runSummarization() }

        binding.buttonCopyTranscript.setOnClickListener { copyTranscriptToClipboard() }
        binding.buttonDownloadMd.setOnClickListener { startDownloadMd() }
    }

    // ---------- Копирование текста лекции в буфер обмена ----------

    private fun copyTranscriptToClipboard() {
        val text = binding.editTranscript.text?.toString().orEmpty()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Текст лекции", text))
        Toast.makeText(this, com.example.lecturenotes.R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    // ---------- Скачивание конспекта в формате .md ----------

    private fun startDownloadMd() {
        val note = currentNote ?: return
        val fileName = "${sanitizeFileName(note.title)}.md"
        createMdDocumentLauncher.launch(fileName)
    }

    private fun saveSummaryAsMarkdown(uri: android.net.Uri) {
        val note = currentNote ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                val content = buildString {
                    append("# ${note.title}\n\n")
                    append("_Категория: ${note.category}_\n\n")
                    append(binding.editSummary.text?.toString().orEmpty())
                }
                output.write(content.toByteArray())
            }
            Toast.makeText(this, com.example.lecturenotes.R.string.md_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось сохранить файл: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifEmpty { "конспект" }
    }

    private fun confirmDeleteAudio() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(com.example.lecturenotes.R.string.delete_audio_confirm)
            .setPositiveButton(com.example.lecturenotes.R.string.delete) { _, _ -> deleteAudio() }
            .setNegativeButton(com.example.lecturenotes.R.string.cancel_dialog, null)
            .show()
    }

    private fun deleteAudio() {
        stopPlaybackIfNeeded()
        clearChunkCache()
        val path = currentNote?.audioFilePath
        if (!path.isNullOrEmpty()) {
            File(path).delete()
        }
        lifecycleScope.launch {
            val note = currentNote ?: return@launch
            val updated = note.copy(audioFilePath = null)
            repository.update(updated)
            currentNote = updated
            updateAudioUi(null)
        }
    }

    // ---------- Сворачивание секции "Текст лекции" ----------

    private fun setupTranscriptCollapse() {
        binding.rowTranscriptHeader.setOnClickListener {
            isTranscriptExpanded = !isTranscriptExpanded
            binding.layoutTranscriptContent.visibility =
                if (isTranscriptExpanded) android.view.View.VISIBLE else android.view.View.GONE
            binding.imageTranscriptChevron.animate()
                .rotation(if (isTranscriptExpanded) 0f else 180f)
                .setDuration(200)
                .start()
        }
    }

    // ---------- Переключение режима "Редактировать / Просмотр" для конспекта ----------

    private fun setupSummaryModeToggle() {
        binding.toggleSummaryMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == binding.buttonSummaryPreviewMode.id) {
                val markdownText = binding.editSummary.text?.toString().orEmpty()
                markwon.setMarkdown(binding.textSummaryPreview, markdownText)
                binding.layoutSummaryEdit.visibility = android.view.View.GONE
                binding.textSummaryPreview.visibility = android.view.View.VISIBLE
            } else {
                binding.textSummaryPreview.visibility = android.view.View.GONE
                binding.layoutSummaryEdit.visibility = android.view.View.VISIBLE
            }
        }
    }

    // ---------- Запись аудио ----------

    private fun startRecording() {
        if (isRecording) {
            stopRecording()
            return
        }
        val audioDir = File(filesDir, "audio").apply { mkdirs() }
        val outputFile = File(audioDir, "lecture_${noteId}_${System.currentTimeMillis()}.m4a")

        @Suppress("DEPRECATION")
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile.absolutePath)
            try {
                prepare()
                start()
            } catch (e: Exception) {
                Toast.makeText(this@NoteDetailActivity, "Не удалось начать запись: ${e.message}", Toast.LENGTH_SHORT).show()
                return
            }
        }

        isRecording = true
        binding.buttonRecord.text = getString(com.example.lecturenotes.R.string.stop_recording)
        binding.buttonImport.isEnabled = false
        currentRecordingFile = outputFile
    }

    private var currentRecordingFile: File? = null

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Запись была слишком короткой или произошла ошибка — файл может быть пустым
        }
        mediaRecorder = null
        isRecording = false
        binding.buttonRecord.text = getString(com.example.lecturenotes.R.string.record_audio)
        binding.buttonImport.isEnabled = true

        currentRecordingFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                clearChunkCache()
                saveAudioPath(file.absolutePath)
            } else {
                Toast.makeText(this, "Запись не удалась, попробуйте снова", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Импорт аудиофайла ----------

    private fun copyUriToAppStorage(uri: android.net.Uri): File? {
        return try {
            val audioDir = File(filesDir, "audio").apply { mkdirs() }
            val extension = getFileExtension(uri)
            val outFile = File(audioDir, "lecture_${noteId}_${System.currentTimeMillis()}.$extension")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось загрузить файл: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    // Определяет расширение файла (mp3, m4a, wav...), чтобы Groq API правильно понял формат аудио
    private fun getFileExtension(uri: android.net.Uri): String {
        // Сначала пробуем достать оригинальное имя файла через ContentResolver
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                val displayName = cursor.getString(nameIndex)
                val dotIndex = displayName?.lastIndexOf('.') ?: -1
                if (dotIndex != -1 && dotIndex < displayName!!.length - 1) {
                    return displayName.substring(dotIndex + 1).lowercase()
                }
            }
        }
        // Если не получилось — пробуем определить по MIME-типу
        val mimeType = contentResolver.getType(uri)
        return when (mimeType) {
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/m4a" -> "m4a"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg" -> "ogg"
            "audio/webm" -> "webm"
            else -> "mp3" // разумное значение по умолчанию
        }
    }

    // ---------- Сохранение и отображение аудио ----------

    private fun saveAudioPath(path: String) {
        lifecycleScope.launch {
            val note = currentNote ?: return@launch
            val updated = note.copy(audioFilePath = path)
            repository.update(updated)
            currentNote = updated
            updateAudioUi(path)
        }
    }

    private fun updateAudioUi(audioPath: String?) {
        if (audioPath.isNullOrEmpty()) {
            binding.textAudioStatus.text = getString(com.example.lecturenotes.R.string.no_audio)
            binding.layoutNoAudioButtons.visibility = android.view.View.VISIBLE
            binding.layoutHasAudioButtons.visibility = android.view.View.GONE
        } else {
            binding.textAudioStatus.text = File(audioPath).name
            binding.layoutNoAudioButtons.visibility = android.view.View.GONE
            binding.layoutHasAudioButtons.visibility = android.view.View.VISIBLE
        }
    }

    private fun togglePlayback() {
        val path = currentNote?.audioFilePath ?: return
        if (isAudioPlaying) {
            mediaPlayer?.pause()
            isAudioPlaying = false
            binding.buttonPlayPause.text = getString(com.example.lecturenotes.R.string.play)
        } else {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    setOnCompletionListener {
                        isAudioPlaying = false
                        binding.buttonPlayPause.text = getString(com.example.lecturenotes.R.string.play)
                    }
                }
            }
            mediaPlayer?.start()
            isAudioPlaying = true
            binding.buttonPlayPause.text = getString(com.example.lecturenotes.R.string.pause)
        }
    }

    private fun stopPlaybackIfNeeded() {
        mediaPlayer?.release()
        mediaPlayer = null
        isAudioPlaying = false
    }

    // ---------- Распознавание речи (с разбивкой длинного аудио на части) ----------

    private fun chunkDirForCurrentNote(): File = File(filesDir, "audio_chunks/note_$noteId")

    private fun clearChunkCache() {
        val dir = chunkDirForCurrentNote()
        if (dir.exists()) dir.deleteRecursively()
    }

    private fun runTranscription() {
        val audioPath = currentNote?.audioFilePath
        if (audioPath.isNullOrEmpty()) {
            Toast.makeText(this, com.example.lecturenotes.R.string.error_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        if (ApiKeyStore.getGroqKey(this).isEmpty()) {
            Toast.makeText(this, com.example.lecturenotes.R.string.error_no_groq_key, Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressTranscribe.visibility = android.view.View.VISIBLE
        binding.textTranscribeProgress.visibility = android.view.View.VISIBLE
        binding.buttonTranscribe.isEnabled = false
        binding.textTranscribeProgress.text = "Подготовка аудио..."

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val audioFile = File(audioPath)
                val chunkDir = chunkDirForCurrentNote()

                // Тяжелые операции выносим в IO-поток
                val chunks = withContext(Dispatchers.IO) {
                    withContext(Dispatchers.Main) {
                        binding.textTranscribeProgress.text = "Разбивка аудио на части..."
                    }
                    AudioSplitter.splitIfNeeded(this@NoteDetailActivity, audioFile, chunkDir)
                }

                val fullText = StringBuilder()

                for ((index, chunkFile) in chunks.withIndex()) {
                    // Обновляем UI на главном потоке
                    if (chunks.size > 1) {
                        binding.textTranscribeProgress.text =
                            "Распознаётся часть ${index + 1} из ${chunks.size}..."
                    }

                    // Сетевой запрос и работа с файлами в IO-потоке
                    val partText = withContext(Dispatchers.IO) {
                        val cacheFile = File(chunkFile.parentFile, "${chunkFile.nameWithoutExtension}.txt")
                        if (cacheFile.exists()) {
                            cacheFile.readText()
                        } else {
                            val text = LectureAiHelper.transcribeAudio(this@NoteDetailActivity, chunkFile)
                            if (chunks.size > 1) {
                                cacheFile.writeText(text)
                            }
                            text
                        }
                    }

                    if (fullText.isNotEmpty()) fullText.append("\n\n")
                    fullText.append(partText)

                    // Обновляем UI и сохраняем в БД
                    suppressTextWatcher = true
                    binding.editTranscript.setText(fullText.toString())
                    suppressTextWatcher = false
                    persistTranscript(fullText.toString())
                }

                // Очистка кэша в IO-потоке
                withContext(Dispatchers.IO) {
                    clearChunkCache()
                }

                binding.textTranscribeProgress.text = "Готово!"

            } catch (e: Exception) {
                Toast.makeText(
                    this@NoteDetailActivity,
                    "Ошибка распознавания: ${e.message}. Нажмите «Распознать текст» ещё раз — уже готовые части не будут отправлены повторно.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressTranscribe.visibility = android.view.View.GONE
                binding.textTranscribeProgress.visibility = android.view.View.GONE
                binding.buttonTranscribe.isEnabled = true
            }
        }
    }
    private fun runSummarization() {
        val text = binding.editTranscript.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, com.example.lecturenotes.R.string.error_no_transcript, Toast.LENGTH_SHORT).show()
            return
        }
        if (ApiKeyStore.getOpenRouterKey(this).isEmpty()) {
            Toast.makeText(this, com.example.lecturenotes.R.string.error_no_gemini_key, Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressSummarize.visibility = android.view.View.VISIBLE
        binding.buttonSummarize.isEnabled = false

        val textLength = text.length
        if (textLength > 200_000) {
            val parts = (textLength / 200_000) + 1
            Toast.makeText(
                this,
                "Текст большой (~${textLength} символов), будет обработан в $parts этапов. Это может занять некоторое время.",
                Toast.LENGTH_LONG
            ).show()
        }

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val summary = withContext(Dispatchers.IO) {
                    SummaryService.createSummary(this@NoteDetailActivity, text)
                }

                suppressTextWatcher = true
                binding.editSummary.setText(summary)
                suppressTextWatcher = false
                persistSummary(summary)

                if (binding.textSummaryPreview.visibility == android.view.View.VISIBLE) {
                    markwon.setMarkdown(binding.textSummaryPreview, summary)
                }

                Toast.makeText(
                    this@NoteDetailActivity,
                    "Конспект готов!",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Log.e("NoteDetail", "Ошибка создания конспекта", e)
                Toast.makeText(
                    this@NoteDetailActivity,
                    "Ошибка создания конспекта: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressSummarize.visibility = android.view.View.GONE
                binding.buttonSummarize.isEnabled = true
            }
        }
    }
    // ---------- Автосохранение при ручном редактировании текста ----------

    private fun setupAutoSaveTextFields() {
        binding.editTranscript.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return
                persistTranscript(s?.toString().orEmpty())
            }
        })

        binding.editSummary.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return
                persistSummary(s?.toString().orEmpty())
            }
        })
    }

    private fun persistTranscript(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val note = currentNote ?: return@launch
            val updated = note.copy(transcriptText = text)
            repository.update(updated)
            currentNote = updated
        }
    }

    private fun persistSummary(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val note = currentNote ?: return@launch
            val updated = note.copy(summaryText = text)
            repository.update(updated)
            currentNote = updated
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlaybackIfNeeded()
        if (isRecording) stopRecording()
    }

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
    }
}
