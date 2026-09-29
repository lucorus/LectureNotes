package com.example.lecturenotes.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lecturenotes.audio.AudioSplitter
import com.example.lecturenotes.data.AppDatabase
import com.example.lecturenotes.data.Note
import com.example.lecturenotes.data.NoteRepository
import com.example.lecturenotes.databinding.ActivityMainBinding
import com.example.lecturenotes.databinding.DialogNewNoteBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.content.ContextCompat


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: NoteRepository
    private lateinit var adapter: NoteAdapter
    private lateinit var categoryAdapter: ArrayAdapter<String>

    private var allNotes: List<Note> = emptyList()
    private var selectedCategory: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        selectedCategory = getString(com.example.lecturenotes.R.string.all_categories)
        repository = NoteRepository(AppDatabase.getInstance(this).noteDao())

        setupRecyclerView()
        setupCategoryFilter()
        setupSwipeToDelete()

        binding.fabAdd.setOnClickListener { showNewNoteDialog() }

        // Показываем результат очистки старых файлов
        showCleanupResult()

        // Наблюдаем за изменениями в базе данных
        lifecycleScope.launch {
            repository.getAll().collectLatest { notes ->
                allNotes = notes
                updateCategoryDropdownItems()
                applyFilter()
                updateNotesCount(notes.size)
                updateEmptyState(notes.size)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = NoteAdapter { note ->
            val intent = Intent(this, NoteDetailActivity::class.java)
            intent.putExtra(NoteDetailActivity.EXTRA_NOTE_ID, note.id)
            startActivity(intent)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    /**
     * Обновляет счётчик конспектов в тулбаре
     */
    private fun updateNotesCount(count: Int) {
        binding.textNotesCountNumber.text = count.toString()
    }

    /**
     * Показывает или скрывает пустое состояние
     */
    private fun updateEmptyState(notesCount: Int) {
        if (notesCount == 0) {
            binding.emptyState.visibility = android.view.View.VISIBLE
            binding.recyclerView.visibility = android.view.View.GONE
        } else {
            binding.emptyState.visibility = android.view.View.GONE
            binding.recyclerView.visibility = android.view.View.VISIBLE
        }
    }

    /**
     * Показывает пользователю информацию об очищенных файлах
     */
    private fun showCleanupResult() {
        lifecycleScope.launch {
            // Небольшая задержка, чтобы Activity успела загрузиться
            delay(500)

            val result = withContext(Dispatchers.IO) {
                AudioSplitter.cleanupOldChunksWithResult(this@MainActivity)
            }

            if (result.deletedFolders >= 0) {  // Показываем даже если ничего не удалено
                val message = when {
                    result.freedBytes > 1024 * 1024 -> {
                        "Очищено ${result.deletedFolders} временных файлов (${"%.1f".format(result.freedBytes / (1024.0 * 1024.0))} MB)"
                    }
                    result.freedBytes > 1024 -> {
                        "Очищено ${result.deletedFolders} временных файлов (${result.freedBytes / 1024} KB)"
                    }
                    else -> {
                        "Очищено ${result.deletedFolders} временных файлов"
                    }
                }

                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- Фильтр по категории ----------

    private fun setupCategoryFilter() {
        categoryAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.dropdownCategoryFilter.setAdapter(categoryAdapter)
        binding.dropdownCategoryFilter.setText(selectedCategory, false)

        binding.dropdownCategoryFilter.setOnItemClickListener { _, _, position, _ ->
            selectedCategory = categoryAdapter.getItem(position)
                ?: getString(com.example.lecturenotes.R.string.all_categories)
            applyFilter()
        }
    }

    private fun updateCategoryDropdownItems() {
        val allLabel = getString(com.example.lecturenotes.R.string.all_categories)
        val categories = allNotes.map { it.category }.distinct().sorted()
        val items = listOf(allLabel) + categories

        categoryAdapter.clear()
        categoryAdapter.addAll(items)
        categoryAdapter.notifyDataSetChanged()

        // Если выбранной ранее категории больше не существует — сбрасываем на "Все категории"
        if (selectedCategory != allLabel && categories.none { it.equals(selectedCategory, ignoreCase = true) }) {
            selectedCategory = allLabel
            binding.dropdownCategoryFilter.setText(allLabel, false)
        }
    }

    private fun applyFilter() {
        val allLabel = getString(com.example.lecturenotes.R.string.all_categories)
        val filtered = if (selectedCategory == allLabel) {
            allNotes
        } else {
            allNotes.filter { it.category.equals(selectedCategory, ignoreCase = true) }
        }
        adapter.submitList(filtered)

        // Обновляем пустое состояние в зависимости от фильтра
        if (filtered.isEmpty()) {
            binding.emptyState.visibility = android.view.View.VISIBLE
            binding.recyclerView.visibility = android.view.View.GONE
        } else {
            binding.emptyState.visibility = android.view.View.GONE
            binding.recyclerView.visibility = android.view.View.VISIBLE
        }
    }

    // ---------- Свайп для удаления лекции ----------

    private fun setupSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val note = adapter.getNoteAt(position)
                deleteNoteWithUndo(note)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }

    private fun deleteNoteWithUndo(note: Note) {
        lifecycleScope.launch {
            repository.delete(note)
        }

        Snackbar.make(binding.root, com.example.lecturenotes.R.string.delete_note_confirm, Snackbar.LENGTH_LONG)
            .setAction(com.example.lecturenotes.R.string.undo) {
                lifecycleScope.launch {
                    repository.insert(note.copy(id = 0))
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    // Если пользователь НЕ нажал "Отменить" — подчищаем файлы на диске
                    if (event != DISMISS_EVENT_ACTION) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            note.audioFilePath?.let { path ->
                                File(path).delete()
                            }
                            val chunkDir = File(filesDir, "audio_chunks/note_${note.id}")
                            if (chunkDir.exists()) {
                                chunkDir.deleteRecursively()
                            }
                        }
                    }
                }
            })
            .show()
    }

    private fun showNewNoteDialog() {
        val dialogBinding = DialogNewNoteBinding.inflate(LayoutInflater.from(this))

        val dialog = androidx.appcompat.app.AlertDialog.Builder(
            this,
            com.example.lecturenotes.R.style.CustomDialogTheme
        )
            .setView(dialogBinding.root)
            .setPositiveButton("Создать") { _, _ ->
                val title = dialogBinding.editTitle.text?.toString()?.trim().orEmpty()
                val category = dialogBinding.editCategory.text?.toString()?.trim().orEmpty()

                if (title.isEmpty()) {
                    Toast.makeText(this, "Введите название конспекта", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val note = Note(
                        title = title,
                        date = System.currentTimeMillis(),
                        category = category.ifEmpty { "Без категории" }
                    )
                    val id = repository.insert(note)
                    val intent = Intent(this@MainActivity, NoteDetailActivity::class.java)
                    intent.putExtra(NoteDetailActivity.EXTRA_NOTE_ID, id)
                    startActivity(intent)
                }
            }
            .setNegativeButton("Отмена", null)
            .create()

        // Убираем белый фон диалога
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialog.show()

        // Стилизуем кнопки
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(android.graphics.Color.parseColor("#6A1B9A"))
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(android.graphics.Color.parseColor("#757575"))
        }
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(com.example.lecturenotes.R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == com.example.lecturenotes.R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}