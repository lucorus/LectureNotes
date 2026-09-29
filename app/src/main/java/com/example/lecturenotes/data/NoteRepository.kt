package com.example.lecturenotes.data

import kotlinx.coroutines.flow.Flow

class NoteRepository(private val dao: NoteDao) {

    fun getAll(): Flow<List<Note>> = dao.getAll()

    fun getByIdFlow(id: Long): Flow<Note?> = dao.getByIdFlow(id)

    suspend fun getById(id: Long): Note? = dao.getById(id)

    suspend fun insert(note: Note): Long = dao.insert(note)

    suspend fun update(note: Note) = dao.update(note)

    suspend fun delete(note: Note) = dao.delete(note)

    suspend fun getCount(): Int {
        return dao.getCount()
    }
}
