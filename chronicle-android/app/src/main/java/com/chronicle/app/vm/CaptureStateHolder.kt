package com.chronicle.app.vm

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Capture-draft UI state (text, type, mood, tags, media). */
class CaptureStateHolder {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _entryType = MutableStateFlow("log")
    val entryType: StateFlow<String> = _entryType.asStateFlow()

    private val _mood = MutableStateFlow<Int?>(null)
    val mood: StateFlow<Int?> = _mood.asStateFlow()

    private val _attachedImages = MutableStateFlow<List<Uri>>(emptyList())
    val attachedImages: StateFlow<List<Uri>> = _attachedImages.asStateFlow()

    private val _pendingAudioPaths = MutableStateFlow<List<String>>(emptyList())
    val pendingAudioPaths: StateFlow<List<String>> = _pendingAudioPaths.asStateFlow()

    /** Clip duration in milliseconds keyed by pending-audio cache path. */
    private val _audioDurationsMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val audioDurationsMs: StateFlow<Map<String, Long>> = _audioDurationsMs.asStateFlow()

    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags: StateFlow<Set<String>> = _selectedTags.asStateFlow()

    private val _newTagText = MutableStateFlow("")
    val newTagText: StateFlow<String> = _newTagText.asStateFlow()

    private val _isNewTagInputActive = MutableStateFlow(false)
    val isNewTagInputActive: StateFlow<Boolean> = _isNewTagInputActive.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _quickCaptureSession = MutableStateFlow(false)
    val quickCaptureSession: StateFlow<Boolean> = _quickCaptureSession.asStateFlow()

    private val _showCheckmark = MutableStateFlow(false)
    val showCheckmark: StateFlow<Boolean> = _showCheckmark.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _editingEntryId = MutableStateFlow<String?>(null)
    val editingEntryId: StateFlow<String?> = _editingEntryId.asStateFlow()

    internal val textMutable get() = _text
    internal val entryTypeMutable get() = _entryType
    internal val moodMutable get() = _mood
    internal val attachedImagesMutable get() = _attachedImages
    internal val pendingAudioPathsMutable get() = _pendingAudioPaths
    internal val audioDurationsMsMutable get() = _audioDurationsMs
    internal val selectedTagsMutable get() = _selectedTags
    internal val newTagTextMutable get() = _newTagText
    internal val isNewTagInputActiveMutable get() = _isNewTagInputActive
    internal val isSavingMutable get() = _isSaving
    internal val quickCaptureSessionMutable get() = _quickCaptureSession
    internal val showCheckmarkMutable get() = _showCheckmark
    internal val isRecordingMutable get() = _isRecording
    internal val editingEntryIdMutable get() = _editingEntryId

    fun clearDraft() {
        _text.value = ""
        _entryType.value = "log"
        _mood.value = null
        _attachedImages.value = emptyList()
        _pendingAudioPaths.value = emptyList()
        _audioDurationsMs.value = emptyMap()
        _selectedTags.value = emptySet()
        _newTagText.value = ""
        _isNewTagInputActive.value = false
        _editingEntryId.value = null
    }
}
