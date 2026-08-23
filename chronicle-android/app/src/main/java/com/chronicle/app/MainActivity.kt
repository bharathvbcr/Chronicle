package com.chronicle.app

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chronicle.app.ui.theme.GlassTokens
import com.chronicle.app.ui.theme.MotionSpec
import com.chronicle.app.ui.theme.MyApplicationTheme
import com.chronicle.app.ui.theme.isChronicleDark
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CancellationException

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Journal privacy: keep content out of the recents preview and
        // screenshots (no-op on surfaces that don't support the flag).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val quickCapture = intent?.getBooleanExtra(EXTRA_QUICK_CAPTURE, false) == true
        // Restore remembered vault before compose lifecycle ON_RESUME can race.
        val earlyViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        earlyViewModel.initFolder(this)
        setContent {
            val viewModel: MainViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            val dynamicColor by viewModel.dynamicColorEnabled.collectAsState()

            MyApplicationTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
            ) {
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    if (quickCapture) {
                        viewModel.beginQuickCaptureSession()
                        viewModel.navigateTo(Screen.CAPTURE)
                    }
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                viewModel.checkFolderPermission(context)
                                if (viewModel.folderUri.value != null) {
                                    viewModel.refreshAll(context)
                                    viewModel.startVaultWatch(context)
                                }
                                viewModel.checkLanHealth(context)
                                if (viewModel.biometricEnabled.value) {
                                    viewModel.setAuthenticated(false)
                                    BiometricHelper.showBiometricPrompt(
                                        activity = context as FragmentActivity,
                                        onSuccess = { viewModel.setAuthenticated(true) },
                                        onError = { viewModel.reportBiometricError(it) },
                                    )
                                } else {
                                    viewModel.setAuthenticated(true)
                                }
                            }
                            Lifecycle.Event.ON_PAUSE -> {
                                viewModel.stopVaultWatch()
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        viewModel.stopVaultWatch()
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                val currentScreen by viewModel.currentScreen.collectAsState()
                val notesSection by viewModel.notesSection.collectAsState()
                val isAuthenticated by viewModel.isAuthenticated.collectAsState()
                val biometricEnabled by viewModel.biometricEnabled.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(Unit) {
                    viewModel.userMessages.collect { message ->
                        snackbarHostState.showSnackbar(message)
                    }
                }
                val hazeState = rememberHazeState()
                val density = LocalDensity.current
                val imeVisible = WindowInsets.ime.getBottom(density) > 0
                val showBottomBar = currentScreen != Screen.FIRST_RUN &&
                    currentScreen != Screen.SETTINGS &&
                    !(currentScreen == Screen.CAPTURE && imeVisible)

                val folderUri by viewModel.folderUri.collectAsState()
                val quickCaptureSession by viewModel.quickCaptureSession.collectAsState()
                val backEnabled = when (currentScreen) {
                    Screen.SETTINGS -> true
                    Screen.NOTES,
                    Screen.PORTFOLIO,
                    Screen.BRAIN,
                    -> true
                    Screen.CAPTURE -> folderUri != null && !quickCaptureSession
                    else -> false
                }

                var backProgress by remember { mutableFloatStateOf(0f) }

                if (backEnabled) {
                    PredictiveBackHandler { progress ->
                        try {
                            progress.collect { event ->
                                backProgress = event.progress
                            }
                            viewModel.navigateBack()
                        } catch (_: CancellationException) {
                            // Gesture cancelled
                        } finally {
                            backProgress = 0f
                        }
                    }
                }

                if (biometricEnabled && !isAuthenticated) {
                    LockScreen(
                        onUnlock = {
                            BiometricHelper.showBiometricPrompt(
                                activity = context as FragmentActivity,
                                onSuccess = { viewModel.setAuthenticated(true) },
                                onError = { viewModel.reportBiometricError(it) },
                            )
                        },
                    )
                    return@MyApplicationTheme
                }

                // Floating GlassBottomBar is drawn above content; every main
                // screen must clear BottomBarClearance + navBars so docked UI
                // (Capture tags, Brain recall) isn't clipped underneath.
                val navBarInset = with(density) {
                    WindowInsets.navigationBars.getBottom(this).toDp()
                }
                val contentBottomPad = when {
                    showBottomBar -> GlassTokens.BottomBarClearance + navBarInset
                    // Capture hides the bar while typing; imePadding owns insets.
                    currentScreen == Screen.CAPTURE && imeVisible -> 0.dp
                    else -> navBarInset
                }

                Scaffold(
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(
                                    bottom = if (showBottomBar) {
                                        GlassTokens.BottomBarClearance
                                    } else {
                                        8.dp
                                    },
                                ),
                        )
                    },
                    bottomBar = {},
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                ) { scaffoldPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(scaffoldPadding),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(state = hazeState)
                                .graphicsLayer {
                                    val p = backProgress
                                    scaleX = 1f - (0.05f * p)
                                    scaleY = 1f - (0.05f * p)
                                    translationX = 48f * p
                                    alpha = 1f - (0.15f * p)
                                    shape = RoundedCornerShape((16f * p).dp)
                                    clip = p > 0.01f
                                },
                        ) {
                            AnimatedContent(
                                targetState = currentScreen,
                                transitionSpec = {
                                    val forward = targetState.ordinal > initialState.ordinal
                                    val enterSlide = if (forward) {
                                        slideInHorizontally(
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        ) { it / 3 } + fadeIn() + scaleIn(initialScale = 0.96f)
                                    } else {
                                        slideInHorizontally(
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        ) { -it / 3 } + fadeIn() + scaleIn(initialScale = 0.96f)
                                    }
                                    val exitSlide = if (forward) {
                                        slideOutHorizontally(
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        ) { -it / 4 } + fadeOut() + scaleOut(targetScale = 0.96f)
                                    } else {
                                        slideOutHorizontally(
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        ) { it / 4 } + fadeOut() + scaleOut(targetScale = 0.96f)
                                    }
                                    enterSlide togetherWith exitSlide
                                },
                                label = "screen_transitions",
                            ) { screen ->
                                when (screen) {
                                    Screen.FIRST_RUN -> FirstRunScreen(viewModel)
                                    Screen.CAPTURE -> CaptureScreen(
                                        viewModel,
                                        snackbarHostState,
                                        contentPadding = PaddingValues(bottom = contentBottomPad),
                                    )
                                    Screen.TIMELINE -> TimelineScreen(
                                        viewModel,
                                        snackbarHostState = snackbarHostState,
                                        contentPadding = PaddingValues(bottom = contentBottomPad),
                                    )
                                    Screen.NOTES -> NotesScreen(
                                        viewModel,
                                        snackbarHostState = snackbarHostState,
                                        contentPadding = PaddingValues(bottom = contentBottomPad),
                                    )
                                    Screen.BRAIN -> BrainScreen(
                                        viewModel,
                                        snackbarHostState = snackbarHostState,
                                        contentPadding = PaddingValues(bottom = contentBottomPad),
                                    )
                                    Screen.PORTFOLIO -> PortfolioScreen(
                                        viewModel,
                                        snackbarHostState = snackbarHostState,
                                        contentPadding = PaddingValues(bottom = contentBottomPad),
                                    )
                                    Screen.SETTINGS -> SettingsScreen(
                                        viewModel,
                                        contentPadding = PaddingValues(bottom = navBarInset + 16.dp),
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = showBottomBar,
                            enter = fadeIn(MotionSpec.base()) +
                                slideInVertically(MotionSpec.enter()) { it / 3 } +
                                scaleIn(initialScale = 0.94f),
                            exit = fadeOut(MotionSpec.fast()) +
                                slideOutVertically(MotionSpec.base()) { it / 3 } +
                                scaleOut(targetScale = 0.94f),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            GlassBottomBar(
                                hazeState = hazeState,
                                currentScreen = currentScreen,
                                notesSection = notesSection,
                                onNavigate = { viewModel.navigateTo(it) },
                                onNewNote = { viewModel.requestNewNote() },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_QUICK_CAPTURE = "extra_quick_capture"
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            pulse.animateTo(1.08f, spring(stiffness = Spring.StiffnessVeryLow))
            pulse.animateTo(1f, spring(stiffness = Spring.StiffnessVeryLow))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f))
                .border(
                    1.dp,
                    GlassTokens.hairlineBrush(dark = isChronicleDark()),
                    RoundedCornerShape(28.dp),
                )
                .padding(32.dp),
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = "Locked",
                modifier = Modifier
                    .size(64.dp)
                    .scale(pulse.value),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("App Locked", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onUnlock) { Text("Unlock with Biometrics") }
        }
    }
}

private data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
    val testTag: String,
)

private val NAV_ITEMS = listOf(
    NavItem(Screen.TIMELINE, "Timeline", Icons.Default.AutoStories, "nav_item_timeline"),
    NavItem(Screen.NOTES, "Notes", Icons.Default.Description, "nav_item_notes"),
    NavItem(Screen.BRAIN, "Brain", Icons.Default.Psychology, "nav_item_brain"),
)

private val PillHeight = 52.dp
private val CaptureWidth = 96.dp

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun GlassBottomBar(
    hazeState: HazeState,
    currentScreen: Screen,
    notesSection: String,
    onNavigate: (Screen) -> Unit,
    onNewNote: () -> Unit,
) {
    val view = LocalView.current
    val isDark = isChronicleDark()
    // On Notes (knowledge sections) the action pill becomes a new-note plus;
    // everywhere else (Timeline / Journal / Brain / Capture) it stays Capture.
    val noteMode = currentScreen == Screen.NOTES &&
        (notesSection == KnowledgePathMap.SECTION_KB || notesSection == KnowledgePathMap.SECTION_NOTES)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.Transparent,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .hazeEffect(state = hazeState, style = HazeMaterials.thin())
                .border(1.dp, GlassTokens.hairlineBrush(isDark), RoundedCornerShape(28.dp)),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
            ) {
                val gap = 6.dp
                val pillsWidth = maxWidth - CaptureWidth - gap
                val segmentWidth = pillsWidth / NAV_ITEMS.size
                val selectedIndex = NAV_ITEMS.indexOfFirst { it.screen == currentScreen }
                val indicatorOffset by animateDpAsState(
                    targetValue = segmentWidth * selectedIndex.coerceAtLeast(0),
                    animationSpec = spring(
                        dampingRatio = MotionSpec.SpringDamping,
                        stiffness = MotionSpec.SpringStiffness,
                    ),
                    label = "nav_indicator_offset",
                )
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (selectedIndex >= 0) 1f else 0f,
                    animationSpec = MotionSpec.fast(),
                    label = "nav_indicator_alpha",
                )

                // Sliding selection capsule behind the three nav pills.
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(segmentWidth)
                        .height(PillHeight)
                        .graphicsLayer { alpha = indicatorAlpha }
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.26f else 0.15f),
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            RoundedCornerShape(percent = 50),
                        ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NAV_ITEMS.forEach { item ->
                        NavPill(
                            item = item,
                            selected = currentScreen == item.screen,
                            modifier = Modifier.width(segmentWidth),
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onNavigate(item.screen)
                        }
                    }
                    Spacer(modifier = Modifier.width(gap))
                    CapturePill(
                        noteMode = noteMode,
                        active = !noteMode && currentScreen == Screen.CAPTURE,
                        modifier = Modifier.width(CaptureWidth),
                    ) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (noteMode) onNewNote() else onNavigate(Screen.CAPTURE)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavPill(
    item: NavItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MotionSpec.base(),
        label = "nav_pill_fg",
    )
    Column(
        modifier = modifier
            .height(PillHeight)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                role = Role.Button,
                onClick = onClick,
            )
            .testTag(item.testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            item.icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = contentColor,
        )
        Text(
            item.label,
            color = contentColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CapturePill(
    noteMode: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = MotionSpec.SpringDamping,
            stiffness = MotionSpec.SpringStiffness,
        ),
        label = "capture_pill_scale",
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = MotionSpec.base(),
        label = "capture_pill_halo",
    )
    val shape = RoundedCornerShape(percent = 50)
    val contentColor = MaterialTheme.colorScheme.onPrimary

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(PillHeight)
            .scale(scale)
            .testTag(if (noteMode) "nav_item_new_note" else "nav_item_capture"),
        shape = shape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Active-state inner ring when Capture is the current screen.
            if (haloAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.5.dp)
                        .border(
                            1.dp,
                            contentColor.copy(alpha = 0.4f * haloAlpha),
                            shape,
                        ),
                )
            }
            Crossfade(
                targetState = noteMode,
                animationSpec = MotionSpec.base(),
                label = "capture_pill_mode",
            ) { isNote ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (isNote) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = contentColor,
                        )
                        Text(
                            "New note",
                            color = contentColor,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    } else {
                        Icon(
                            Icons.Default.Create,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = contentColor,
                        )
                        Text(
                            "Capture",
                            color = contentColor,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
