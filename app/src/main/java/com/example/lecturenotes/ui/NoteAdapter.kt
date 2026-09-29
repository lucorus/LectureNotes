package com.example.lecturenotes.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lecturenotes.R
import com.example.lecturenotes.data.Note
import com.example.lecturenotes.databinding.ItemNoteBinding
import java.text.SimpleDateFormat
import java.util.Locale

class NoteAdapter(
    private val onClick: (Note) -> Unit
) : ListAdapter<Note, NoteAdapter.NoteViewHolder>(DIFF_CALLBACK) {

    private val dateFormat = SimpleDateFormat("d MMMM yyyy", Locale("ru"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // Нужен для ItemTouchHelper — определить, какую заметку смахнули
    fun getNoteAt(position: Int): Note = getItem(position)

    inner class NoteViewHolder(private val binding: ItemNoteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(note: Note) {
            val context = binding.root.context

            binding.textTitle.text = note.title
            binding.textDate.text = dateFormat.format(note.date)
            binding.textCategoryChip.text = note.category

            val activeColor = ContextCompat.getColor(context, R.color.purple_500)
            val inactiveColor = ContextCompat.getColor(context, R.color.grey_medium)

            binding.textTranscriptStatus.text = "✓ Текст"
            binding.textTranscriptStatus.setTextColor(
                if (note.transcriptText.isNotBlank()) activeColor else inactiveColor
            )

            binding.textSummaryStatus.text = "✓ Конспект"
            binding.textSummaryStatus.setTextColor(
                if (note.summaryText.isNotBlank()) activeColor else inactiveColor
            )

            binding.root.setOnClickListener { onClick(note) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
        }
    }
}
