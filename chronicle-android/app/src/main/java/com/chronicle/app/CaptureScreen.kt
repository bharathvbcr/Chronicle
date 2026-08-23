package com.chronicle.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.chronicle.app.ai.AiAvailability
import com.chronicle.app.ai.AiFeature
import com.chronicle.app.ai.RewriteTone
import com.chronicle.app.ui.markdown.MarkdownBody
import com.chronicle.app.ui.markdown.MarkdownToolbar
import com.chronicle.app.ui.theme.EntryTypeColors
import com.chronicle.app.ui.theme.GlassTokens
import com.chronicle.app.ui.theme.JournalSerif
import com.chronicle.app.ui.theme.MotionSpec
import com.chronicle.app.ui.theme.ThemeMode
import com.chronicle.app.ui.theme.entryTypeIcon
import com.chronicle.app.ui.theme.isChronicleDark
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val ENTRY_TYPES = listOf("log", "idea", "dream", "reflection")

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val text by viewModel.text.collectAsState()
    val entryType by viewModel.entryType.collectAsState()
    val mood by viewModel.mood.collectAsState()
    val nanoMood by viewModel.nanoMoodSuggestion.collectAsState()
    val attachedImages by viewModel.attachedImages.collectAsState()
    val pendingAudio by viewModel.pendingAudioPaths.collectAsState()
    val audioDurationsMs by viewModel.audioDurationsMs.collectAsState()
    val selectedTags by viewModel.selectedTags.collectAsState()
    val recentTags by viewModel.recentTags.collectAsState()
    val newTagText by viewModel.newTagText.collectAsState()
    val isNewTagInputActive by viewModel.isNewTagInputActive.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val showCheckmark by viewModel.showCheckmark.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val quickCapture by viewModel.quickCaptureSession.collectAsState()
    val todayInsight by viewModel.todayInsight.collectAsState()
    val todayDismissed by viewModel.todayCardDismissed.collectAsState()
    val activePrompt by viewModel.activePrompt.collectAsState()
    val brainFreshness by viewModel.brainFreshness.collectAsState()
    val editingId by viewModel.editingEntryId.collectAsState()
    val enrichment by viewModel.enrichment.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val tagsTaxonomy by viewModel.tagsTaxonomy.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val nanoTags by viewModel.nanoTagSuggestions.collectAsState()
    val textAiSuggestion by viewModel.textAiSuggestion.collectAsState()
    val textAiBusy by viewModel.textAiBusy.collectAsState()
    val imageDescriptionGhost by viewModel.imageDescriptionGhost.collectAsState()
    val onDeviceAiEnabled by viewModel.onDeviceAiEnabled.collectAsState()
    val aiAvailability by viewModel.aiFeatureAvailability.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var recordingElapsedSec by remember { mutableLongStateOf(0L) }
    var recordingAmplitude by remember { mutableIntStateOf(0) }
    var fieldValue by remember { mutableStateOf(TextFieldValue(text)) }
    var markdownPreview by remember { mutableStateOf(false) }
    var aiMenuExpanded by remember { mutableStateOf(false) }
    var attachMenuExpanded by remember { mutableStateOf(false) }
    var previewImageUri by remember { mutableStateOf<Uri?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var inlinePreviewCollapsed by rememberSaveable { mutableStateOf(false) }
    val editorFocusRequester = remember { FocusRequester() }
    val tagFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val words = remember(fieldValue.text) { wordCount(fieldValue.text) }
    val hasMarkdown = remember(fieldValue.text) { hasMarkdownSyntax(fieldValue.text) }
    // Inline preview earns its space only when markdown syntax is present.
    val showInlinePreview = !markdownPreview && fieldValue.text.isNotBlank() &&
        hasMarkdown && !inlinePreviewCollapsed
    val canSave = fieldValue.text.isNotBlank() || attachedImages.isNotEmpty() || pendingAudio.isNotEmpty()

    val displayTags = remember(selectedTags, recentTags) {
        pinSelectedTagsFront(selectedTags, recentTags)
    }
    val autocompleteSuggestions = remember(newTagText, allTags, tagsTaxonomy, selectedTags, isNewTagInputActive) {
        if (isNewTagInputActive && newTagText.isNotBlank()) {
            matchTagAutocomplete(
                query = newTagText,
                allTags = allTags,
                taxonomy = tagsTaxonomy,
                exclude = selectedTags,
                limit = 6,
            )
        } else {
            emptyList()
        }
    }
    val ghostTags = remember(
        text, selectedTags, recentTags, enrichment, todayInsight, allTags, tagsTaxonomy, nanoTags,
    ) {
        viewModel.liveGhostTagSuggestions(limit = 4)
    }
    val showAiActions = onDeviceAiEnabled && (
        aiAvailability[AiFeature.PROOFREAD].isUsable() ||
            aiAvailability[AiFeature.REWRITE].isUsable()
        )

    LaunchedEffect(text) {
        if (text != fieldValue.text) {
            fieldValue = TextFieldValue(text, TextRange(text.length))
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingStartedAt = System.currentTimeMillis()
            while (isActive) {
                recordingElapsedSec = (System.currentTimeMillis() - recordingStartedAt) / 1000
                recordingAmplitude = viewModel.currentRecordingAmplitude()
                delay(120)
            }
        } else {
            recordingElapsedSec = 0
            recordingAmplitude = 0
        }
    }

    // Widget quick-capture lands here expecting to type immediately.
    LaunchedEffect(quickCapture) {
        if (quickCapture) {
            editorFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(showCheckmark) {
        if (showCheckmark) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.attachImages(context, uris) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) viewModel.tempCameraUri?.let { viewModel.attachImages(context, listOf(it)) }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) cameraLauncher.launch(viewModel.createTempCameraUri(context))
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (!viewModel.startVoiceRecording(context)) {
                scope.launch { snackbarHostState.showSnackbar("Could not start recording") }
            }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Microphone permission required") }
        }
    }

    val now = remember { ZonedDateTime.now() }
    val weekdayOverline = remember(now) {
        now.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())).uppercase(Locale.getDefault())
    }
    val dateHeadline = remember(now) {
        now.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refreshAll(context) },
        modifier = Modifier
            .fillMaxSize()
            .testTag("capture_pull_refresh"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        ) {
        // Body shrinks first so the mood/tag footer always stays above the
        // floating glass bottom bar (non-weighted chrome used to overflow under it).
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = weekdayOverline,
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.6.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                    ),
                    modifier = Modifier.testTag("capture_weekday"),
                )
                Text(
                    text = dateHeadline,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = JournalSerif,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .testTag("capture_date"),
                )
                Text(
                    text = brainFreshness
                        ?: "Your brain is still forming — process on your Mac.",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                    ),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row {
                IconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        viewModel.cycleThemeMode(context)
                    },
                    modifier = Modifier.testTag("theme_cycle_button"),
                ) {
                    Icon(
                        imageVector = when (themeMode) {
                            ThemeMode.LIGHT -> Icons.Default.LightMode
                            ThemeMode.DARK -> Icons.Default.DarkMode
                            ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                        },
                        contentDescription = "Theme ${themeMode.name.lowercase()}",
                    )
                }
                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        viewModel.navigateTo(Screen.SETTINGS)
                    },
                    modifier = Modifier.testTag("settings_button"),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        AnimatedVisibility(
            visible = todayInsight != null && !todayDismissed,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            todayInsight?.let { insight ->
                TodayCard(
                    insight = insight,
                    onDismiss = { viewModel.dismissTodayCard(context) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ENTRY_TYPES.forEach { type ->
                val selected = entryType == type
                val typeColor = EntryTypeColors.of(type)
                val bg by animateColorAsState(
                    targetValue = if (selected) {
                        typeColor.copy(alpha = 0.18f)
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f)
                    },
                    label = "type_bg_$type",
                )
                val borderColor by animateColorAsState(
                    targetValue = if (selected) {
                        typeColor.copy(alpha = 0.45f)
                    } else {
                        Color.Transparent
                    },
                    label = "type_border_$type",
                )
                val labelColor by animateColorAsState(
                    targetValue = if (selected) {
                        typeColor
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                    },
                    label = "type_label_$type",
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(bg)
                        .border(1.dp, borderColor, RoundedCornerShape(20.dp))
                        .semantics(mergeDescendants = true) {
                            this.selected = selected
                            role = Role.RadioButton
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true),
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.setEntryType(type)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("type_$type"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            entryTypeIcon(type),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = labelColor,
                        )
                        Text(
                            text = type.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = labelColor,
                            ),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = editingId != null,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut(),
        ) {
            Text(
                text = "Editing · unlocked until processed",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        val journalStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = JournalSerif,
            fontSize = 18.sp,
            lineHeight = 30.sp,
        )
        // Live markdown: render an inline preview only when the text actually uses
        // markdown (collapsible). The eye toggle still expands to a full-height
        // preview (hides the TextField).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (!markdownPreview) {
                TextField(
                    value = fieldValue,
                    onValueChange = { next ->
                        fieldValue = next
                        viewModel.updateText(next.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusRequester(editorFocusRequester)
                        .testTag("capture_text_field"),
                    placeholder = {
                        Text(
                            typePlaceholder(entryType),
                            style = journalStyle.copy(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                            ),
                        )
                    },
                    textStyle = journalStyle,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
            if (!markdownPreview && fieldValue.text.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (showInlinePreview) "Preview" else "",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        ),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "$words ${if (words == 1) "word" else "words"}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JournalSerif,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        ),
                        modifier = Modifier.testTag("capture_word_count"),
                    )
                    if (hasMarkdown) {
                        IconButton(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                inlinePreviewCollapsed = !inlinePreviewCollapsed
                            },
                            modifier = Modifier
                                .size(24.dp)
                                .testTag("preview_collapse_toggle"),
                        ) {
                            Icon(
                                imageVector = if (showInlinePreview) {
                                    Icons.Default.KeyboardArrowUp
                                } else {
                                    Icons.Default.KeyboardArrowDown
                                },
                                contentDescription = if (showInlinePreview) {
                                    "Hide preview"
                                } else {
                                    "Show preview"
                                },
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
            if (markdownPreview || showInlinePreview) {
                MarkdownBody(
                    content = fieldValue.text,
                    style = journalStyle,
                    maxCollapsedHeight = if (markdownPreview) null else 120.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (markdownPreview) Modifier.weight(1f) else Modifier),
                    testTag = "capture_markdown_preview",
                )
            }
        }

        AnimatedVisibility(
            visible = textAiBusy || textAiSuggestion != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            TextAiSuggestionCard(
                busy = textAiBusy,
                suggestion = textAiSuggestion,
                onApply = {
                    viewModel.applyTextAiSuggestion()
                    fieldValue = TextFieldValue(
                        viewModel.text.value,
                        TextRange(viewModel.text.value.length),
                    )
                },
                onDismiss = { viewModel.dismissTextAiSuggestion() },
            )
        }

        AnimatedVisibility(
            visible = !imageDescriptionGhost.isNullOrBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ImageDescriptionGhostCard(
                description = imageDescriptionGhost.orEmpty(),
                onAdd = {
                    viewModel.acceptImageDescription()
                    fieldValue = TextFieldValue(
                        viewModel.text.value,
                        TextRange(viewModel.text.value.length),
                    )
                },
                onDismiss = { viewModel.dismissImageDescription() },
            )
        }

        activePrompt?.let { prompt ->
            Row(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .combinedClickable(
                        onClickLabel = "Insert prompt into entry",
                        onLongClickLabel = "Show another prompt",
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.applyPrompt(prompt)
                        },
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            viewModel.shufflePrompt()
                        },
                    )
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    .testTag("prompt_chip"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "✦ ${prompt.text}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.primary,
                    ),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        viewModel.shufflePrompt()
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("prompt_shuffle"),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Next prompt",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isRecording,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val pulse = rememberInfiniteTransition(label = "rec_pulse")
            val pulseAlpha by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "rec_alpha",
            )
            val mins = recordingElapsedSec / 60
            val secs = recordingElapsedSec % 60
            // Perceptual mic level: sqrt compresses the 0..32767 amplitude range.
            val micLevel = sqrt(recordingAmplitude.toFloat() / 32767f).coerceIn(0f, 1f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .background(
                        MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(pulseAlpha)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Recording  %d:%02d".format(mins, secs),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                MicLevelMeter(
                    level = micLevel,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .testTag("mic_level_meter"),
                )
                TextButton(onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    viewModel.cancelVoiceRecording()
                }) { Text("Cancel") }
                TextButton(onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    viewModel.stopVoiceRecording()
                }) { Text("Done") }
            }
        }

        AnimatedVisibility(visible = pendingAudio.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(pendingAudio, key = { _, path -> path }) { index, path ->
                    val clipLabel = buildString {
                        append("Voice ${index + 1}")
                        audioDurationsMs[path]?.let { append(" · ${formatClipDuration(it)}") }
                    }
                    Row(
                        modifier = Modifier
                            .animateItem()
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                RoundedCornerShape(16.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(clipLabel, style = MaterialTheme.typography.labelMedium)
                        IconButton(
                            onClick = { viewModel.removePendingAudio(path) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove $clipLabel",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = attachedImages.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                items(attachedImages, key = { it.toString() }) { uri ->
                    Box(
                        modifier = Modifier
                            .animateItem()
                            .size(72.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { previewImageUri = uri }
                            .testTag("image_thumbnail"),
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = "Attached photo. Tap to preview.",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true),
                                    onClickLabel = "Remove photo",
                                ) { viewModel.removeAttachedImage(uri) }
                                .testTag("remove_image_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove photo",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        } // end body (weight)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarkdownToolbar(
                    value = fieldValue,
                    onValueChange = { next ->
                        fieldValue = next
                        viewModel.updateText(next.text)
                    },
                    preview = markdownPreview,
                    onPreviewToggle = { markdownPreview = !markdownPreview },
                )
                Box {
                    IconButton(
                        onClick = { attachMenuExpanded = true },
                        modifier = Modifier.testTag("attach_button"),
                    ) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = "Attach photo",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenuExpanded,
                        onDismissRequest = { attachMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Take photo") },
                            leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                            onClick = {
                                attachMenuExpanded = false
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                val perm = Manifest.permission.CAMERA
                                if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                                    cameraLauncher.launch(viewModel.createTempCameraUri(context))
                                } else {
                                    cameraPermissionLauncher.launch(perm)
                                }
                            },
                            modifier = Modifier.testTag("attach_camera"),
                        )
                        DropdownMenuItem(
                            text = { Text("Choose photos") },
                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                            onClick = {
                                attachMenuExpanded = false
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            modifier = Modifier.testTag("attach_gallery"),
                        )
                    }
                }
                val micPulse = rememberInfiniteTransition(label = "mic_pulse")
                val micPulseScale by micPulse.animateFloat(
                    initialValue = 0.92f,
                    targetValue = 1.18f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(MotionSpec.SlowMs * 2, easing = MotionSpec.Ease),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "mic_scale",
                )
                val micGlow by micPulse.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 0.65f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(MotionSpec.SlowMs * 2, easing = MotionSpec.Ease),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "mic_glow",
                )
                IconButton(
                    onClick = {
                        if (isRecording) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            viewModel.stopVoiceRecording()
                        } else {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            val perm = Manifest.permission.RECORD_AUDIO
                            if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                                viewModel.startVoiceRecording(context)
                            } else {
                                micPermissionLauncher.launch(perm)
                            }
                        }
                    },
                    modifier = Modifier
                        .scale(if (isRecording) micPulseScale else 1f)
                        .testTag("mic_button"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isRecording) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .scale(micPulseScale)
                                    .alpha(micGlow)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.28f)),
                            )
                        }
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isRecording) "Stop recording" else "Voice",
                            tint = if (isRecording) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                            },
                        )
                    }
                }
                if (showAiActions) {
                    Box {
                        IconButton(
                            onClick = { aiMenuExpanded = true },
                            enabled = !textAiBusy && text.isNotBlank(),
                            modifier = Modifier.testTag("ai_sparkle_button"),
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "On-device AI",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            )
                        }
                        DropdownMenu(
                            expanded = aiMenuExpanded,
                            onDismissRequest = { aiMenuExpanded = false },
                        ) {
                            if (aiAvailability[AiFeature.PROOFREAD].isUsable()) {
                                DropdownMenuItem(
                                    text = { Text("Proofread") },
                                    onClick = {
                                        aiMenuExpanded = false
                                        viewModel.runProofread(context)
                                    },
                                    modifier = Modifier.testTag("ai_proofread"),
                                )
                            }
                            if (aiAvailability[AiFeature.REWRITE].isUsable()) {
                                DropdownMenuItem(
                                    text = { Text("Elaborate") },
                                    onClick = {
                                        aiMenuExpanded = false
                                        viewModel.runRewrite(context, RewriteTone.ELABORATE)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Friendly") },
                                    onClick = {
                                        aiMenuExpanded = false
                                        viewModel.runRewrite(context, RewriteTone.FRIENDLY)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Professional") },
                                    onClick = {
                                        aiMenuExpanded = false
                                        viewModel.runRewrite(context, RewriteTone.PROFESSIONAL)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Shorten") },
                                    onClick = {
                                        aiMenuExpanded = false
                                        viewModel.runRewrite(context, RewriteTone.SHORTEN)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            val saveDescription = when {
                isSaving -> "Saving entry"
                showCheckmark -> "Entry saved"
                canSave -> "Save entry. Long-press to discard draft."
                else -> "Save entry, unavailable until there is something to save"
            }
            val saveContainer by animateColorAsState(
                targetValue = if (canSave || isSaving) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                },
                animationSpec = MotionSpec.base(),
                label = "save_container",
            )
            // Custom Surface (not FAB) so tap and long-press share one target:
            // tap saves, long-press offers discard-draft.
            Surface(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        role = Role.Button,
                        onClickLabel = "Save entry",
                        onLongClickLabel = "Discard draft",
                        onClick = {
                            if (isSaving) return@combinedClickable
                            if (!canSave) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Write, record, or attach something first",
                                    )
                                }
                                return@combinedClickable
                            }
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            viewModel.saveEntry(context, viaWorkManager = quickCapture)
                        },
                        onLongClick = {
                            if (isSaving || !canSave) return@combinedClickable
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showDiscardDialog = true
                        },
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = saveDescription
                    }
                    .testTag("save_button"),
                shape = RoundedCornerShape(16.dp),
                color = saveContainer,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = when {
                            isSaving && !showCheckmark -> "saving"
                            showCheckmark -> "done"
                            else -> "idle"
                        },
                        transitionSpec = {
                            (fadeIn() + scaleIn(initialScale = 0.7f)) togetherWith
                                (fadeOut() + scaleOut(targetScale = 0.7f))
                        },
                        label = "save_fab",
                    ) { state ->
                        when (state) {
                            "saving" -> CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            "done" -> Icon(
                                Icons.Default.Check,
                                contentDescription = "Saved",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                            else -> Text(
                                "Save",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MOOD_FACES.forEachIndexed { idx, face ->
                val faceVal = idx + 1
                val selected = mood == faceVal
                val suggested = nanoMood == faceVal && mood == null
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.28f else if (suggested) 1.12f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                    label = "mood_$faceVal",
                )
                val opacity = when {
                    mood == null && suggested -> 1f
                    mood == null || selected -> 1f
                    else -> 0.4f
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .alpha(opacity)
                        .scale(scale)
                        .semantics {
                            contentDescription = MOOD_FACE_DESCRIPTIONS.getOrElse(idx) { "Mood $faceVal" }
                        }
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.setMood(if (selected) null else faceVal)
                        }
                        .testTag("mood_$faceVal"),
                ) {
                    Text(face, fontSize = if (selected) 34.sp else 26.sp)
                }
            }
        }
        AnimatedVisibility(
            visible = nanoMood != null && mood == null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 6.dp)
                    .testTag("nano_mood_suggestion"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Suggested mood",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.acceptNanoMoodSuggestion() }) {
                    Text("Use ${MOOD_FACES.getOrNull((nanoMood ?: 1) - 1).orEmpty()}")
                }
                TextButton(onClick = { viewModel.dismissNanoMoodSuggestion() }) {
                    Text("Dismiss")
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            AnimatedVisibility(
                visible = autocompleteSuggestions.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .testTag("tag_autocomplete_row"),
                ) {
                    items(autocompleteSuggestions, key = { it.lowercase() }) { tag ->
                        TagChip(tag = tag, selected = false, ghost = true) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.addNewTag(tag)
                        }
                    }
                }
            }
            // Full-width tag input: replaces the cramped inline field. Stays open
            // across adds (comma/IME Done) for rapid multi-tag entry.
            AnimatedVisibility(
                visible = isNewTagInputActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = newTagText,
                        onValueChange = { viewModel.updateNewTagText(it) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(tagFocusRequester)
                            .testTag("new_tag_input"),
                        placeholder = {
                            Text("New tag… (comma adds)", style = MaterialTheme.typography.labelMedium)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { viewModel.addNewTag(newTagText) },
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                    TextButton(
                        onClick = { viewModel.addNewTag(newTagText) },
                        modifier = Modifier.testTag("add_tag_confirm"),
                    ) { Text("Add") }
                    IconButton(
                        onClick = { viewModel.setNewTagInputActive(false) },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("close_tag_input"),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close tag input",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            LaunchedEffect(isNewTagInputActive) {
                if (isNewTagInputActive) {
                    tagFocusRequester.requestFocus()
                    keyboardController?.show()
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(displayTags, key = { it.lowercase() }) { tag ->
                    val selected = tagSetContains(selectedTags, tag)
                    TagChip(tag = tag, selected = selected, ghost = false) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        viewModel.toggleTag(tag)
                    }
                }
                items(ghostTags, key = { "ghost_${it.lowercase()}" }) { tag ->
                    TagChip(tag = tag, selected = false, ghost = true) {
                        viewModel.acceptGhostTag(tag)
                    }
                }
                if (!isNewTagInputActive) {
                    item {
                        Box(
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                                    RoundedCornerShape(18.dp),
                                )
                                .clickable { viewModel.setNewTagInputActive(true) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("add_tag_button"),
                        ) { Text("+ tag", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }

        if (showDiscardDialog) {
            AlertDialog(
                onDismissRequest = { showDiscardDialog = false },
                title = { Text("Discard draft?") },
                text = { Text("Clears the text, mood, tags, and any attachments.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDiscardDialog = false
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.clearCapture()
                        },
                        modifier = Modifier.testTag("discard_confirm"),
                    ) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDiscardDialog = false },
                        modifier = Modifier.testTag("discard_cancel"),
                    ) { Text("Keep") }
                },
                modifier = Modifier.testTag("discard_dialog"),
            )
        }

        previewImageUri?.let { uri ->
            Dialog(
                onDismissRequest = { previewImageUri = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.92f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "Close preview",
                        ) { previewImageUri = null }
                        .testTag("image_preview_dialog"),
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Attached photo, full preview",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentScale = ContentScale.Fit,
                    )
                    IconButton(
                        onClick = { previewImageUri = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(8.dp)
                            .testTag("close_image_preview"),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close preview",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun TodayCard(insight: com.chronicle.app.brain.DayInsight, onDismiss: () -> Unit) {
    val isDark = isChronicleDark()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f))
            .border(1.dp, GlassTokens.hairlineBrush(isDark), RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("today_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
            }
        }
        if (insight.summary.isNotBlank()) {
            Text(insight.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        }
        insight.moodAvg?.let {
            Text(
                "Mood · ${"%.1f".format(it)}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        insight.connections.firstOrNull()?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (insight.onThisDay.isNotEmpty()) {
            Text(
                "On this day · ${insight.onThisDay.size} memories",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (insight.timeCapsules.isNotEmpty()) {
            Text(
                "Time capsules due · ${insight.timeCapsules.size}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TagChip(tag: String, selected: Boolean, ghost: Boolean, onClick: () -> Unit) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        ghost -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)
    }
    val border = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        ghost -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .background(bg, RoundedCornerShape(18.dp))
            .then(if (ghost) Modifier.alpha(0.55f) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (ghost) "+ $tag" else tag,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                    },
                ),
            )
            if (selected) {
                // Visual cue that tapping a selected chip removes it.
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** Five animated bars visualizing the live mic [level] (0..1) while recording. */
@Composable
private fun MicLevelMeter(level: Float, modifier: Modifier = Modifier) {
    val barWeights = remember { listOf(0.55f, 1f, 0.75f, 0.9f, 0.6f) }
    Row(
        modifier = modifier.height(18.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        barWeights.forEachIndexed { index, weight ->
            val barHeight by animateFloatAsState(
                targetValue = 4f + 14f * level * weight,
                animationSpec = tween(MotionSpec.FastMs),
                label = "mic_bar_$index",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.75f)),
            )
        }
    }
}

@Composable
private fun TextAiSuggestionCard(
    busy: Boolean,
    suggestion: TextAiSuggestion?,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                RoundedCornerShape(14.dp),
            )
            .padding(12.dp)
            .testTag("text_ai_suggestion_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (busy) "Thinking…" else suggestion?.label ?: "Suggestion",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            if (!busy) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                }
            }
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(22.dp),
                strokeWidth = 2.dp,
            )
        } else if (suggestion != null) {
            Text(
                text = suggestion.suggestedText,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 8,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("ai_suggestion_dismiss")) {
                    Text("Dismiss")
                }
                TextButton(onClick = onApply, modifier = Modifier.testTag("ai_suggestion_apply")) {
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun ImageDescriptionGhostCard(
    description: String,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                RoundedCornerShape(14.dp),
            )
            .padding(12.dp)
            .testTag("image_description_ghost"),
    ) {
        Text(
            text = "Add description",
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = JournalSerif,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            ),
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 4,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            TextButton(onClick = onAdd, modifier = Modifier.testTag("image_description_add")) {
                Text("Add")
            }
        }
    }
}

private fun AiAvailability?.isUsable(): Boolean =
    this is AiAvailability.Available ||
        this is AiAvailability.Downloadable ||
        this is AiAvailability.Downloading
