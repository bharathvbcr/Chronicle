package com.chronicle.app.vm

import com.chronicle.app.KbNoteRef
import com.chronicle.app.KnowledgePathMap
import com.chronicle.app.NoteRef
import com.chronicle.app.ResumePointRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Notes, KB notes, and resume-point browse state. */
class NotesStateHolder {
    private val _notes = MutableStateFlow<List<NoteRef>>(emptyList())
    val notes: StateFlow<List<NoteRef>> = _notes.asStateFlow()

    private val _isLoadingNotes = MutableStateFlow(false)
    val isLoadingNotes: StateFlow<Boolean> = _isLoadingNotes.asStateFlow()

    private val _selectedNotePath = MutableStateFlow<String?>(null)
    val selectedNotePath: StateFlow<String?> = _selectedNotePath.asStateFlow()

    private val _kbNotes = MutableStateFlow<List<KbNoteRef>>(emptyList())
    val kbNotes: StateFlow<List<KbNoteRef>> = _kbNotes.asStateFlow()

    private val _isLoadingKbNotes = MutableStateFlow(false)
    val isLoadingKbNotes: StateFlow<Boolean> = _isLoadingKbNotes.asStateFlow()

    private val _selectedKbNotePath = MutableStateFlow<String?>(null)
    val selectedKbNotePath: StateFlow<String?> = _selectedKbNotePath.asStateFlow()

    private val _isSavingKbNote = MutableStateFlow(false)
    val isSavingKbNote: StateFlow<Boolean> = _isSavingKbNote.asStateFlow()

    /** UI section: [KnowledgePathMap.SECTION_KB], [SECTION_NOTES], or [SECTION_JOURNAL]. */
    private val _notesSection = MutableStateFlow(KnowledgePathMap.SECTION_NOTES)
    val notesSection: StateFlow<String> = _notesSection.asStateFlow()

    private val _resumePoints = MutableStateFlow<List<ResumePointRef>>(emptyList())
    val resumePoints: StateFlow<List<ResumePointRef>> = _resumePoints.asStateFlow()

    private val _isLoadingResumePoints = MutableStateFlow(false)
    val isLoadingResumePoints: StateFlow<Boolean> = _isLoadingResumePoints.asStateFlow()

    private val _selectedResumePath = MutableStateFlow<String?>(null)
    val selectedResumePath: StateFlow<String?> = _selectedResumePath.asStateFlow()

    internal val notesMutable get() = _notes
    internal val isLoadingNotesMutable get() = _isLoadingNotes
    internal val selectedNotePathMutable get() = _selectedNotePath
    internal val kbNotesMutable get() = _kbNotes
    internal val isLoadingKbNotesMutable get() = _isLoadingKbNotes
    internal val selectedKbNotePathMutable get() = _selectedKbNotePath
    internal val isSavingKbNoteMutable get() = _isSavingKbNote
    internal val notesSectionMutable get() = _notesSection
    internal val resumePointsMutable get() = _resumePoints
    internal val isLoadingResumePointsMutable get() = _isLoadingResumePoints
    internal val selectedResumePathMutable get() = _selectedResumePath

    fun clear() {
        _notes.value = emptyList()
        _kbNotes.value = emptyList()
        _resumePoints.value = emptyList()
        _selectedNotePath.value = null
        _selectedKbNotePath.value = null
        _selectedResumePath.value = null
        _notesSection.value = KnowledgePathMap.SECTION_NOTES
    }

    fun clearSelection() {
        _selectedNotePath.value = null
        _selectedKbNotePath.value = null
        _selectedResumePath.value = null
    }
}
