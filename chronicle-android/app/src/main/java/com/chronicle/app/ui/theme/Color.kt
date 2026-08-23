package com.chronicle.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.chronicle.app.brain.GraphGroup

/**
 * Chronicle design tokens — rose / glass theme.
 * Mirrors chronicle-pc/frontend/src/styles/tokens.css (light + dark).
 */

/* ── Light (:root) ─────────────────────────────────────────────── */

val LightBg = Color(0xFFF6E8EB)
val LightBgWashTop = Color(0xFFF0D4DA)
val LightBgWashBottom = Color(0xFFEAD0D6)
val LightSurface = Color(0x9EFFF8F9) // rgba(255,248,249,0.62) ≈ solid for M3
val LightSurfaceSolid = Color(0xFFFFF8F9)
val LightChrome = Color(0x8CFFF5F7)
val LightChromeSolid = Color(0xC7FFF2F4)

val LightInk = Color(0xFF2A0E12)
val LightMuted = Color(0xFF8A5560)
val LightLine = Color(0x2E8C2837)

val LightAccent = Color(0xFFB91C3A)
val LightAccentSoft = Color(0x1FB91C3A)
val LightAccentGlow = Color(0x2EB91C3A)

val LightIdea = Color(0xFFC45C12)
val LightDream = Color(0xFF8B2E4A)
val LightReflection = Color(0xFF2F7A4E)
val LightLog = Color(0xFF8A6A6E)
val LightDanger = Color(0xFF7A0010)

val LightSuccess = Color(0xFF2F7A4E)
val LightSuccessSoft = Color(0x242F7A4E)
val LightWarning = Color(0xFFC45C12)
val LightWarningSoft = Color(0x24C45C12)
val LightInfo = Color(0xFF5B6B8A)
val LightInfoSoft = Color(0x245B6B8A)

val LightHover = Color(0x14B91C3A)
val LightShadow = Color(0x1A500A14)
val LightOverlay = Color(0x6628080E)
val LightSkeletonBase = Color(0x148C2837)
val LightSkeletonShine = Color(0x8CFFFFFF)

val LightGlassShine = Color(0xB3FFFFFF)
val LightGlassEdge = Color(0xCCFFFFFF)
val LightGlassInset = Color(0x73FFFFFF)

val LightMmLabel = Color(0xFF3A1820)
val LightMmEdge = Color(0x6B7A4A52)
val LightMmEdgeStrong = Color(0x9EB91C3A)
val LightMmNodeTopic = Color(0xFFB91C3A)
val LightMmNodeConcept = Color(0xFFC45C12)
val LightMmNodeEntry = Color(0xFF2F7A4E)
val LightMmNodePerson = Color(0xFF8B2E4A)
val LightMmNodePlace = Color(0xFF5B6B8A)
val LightMmNodeProject = Color(0xFF7A5C2E)
val LightMmHighlight = Color(0xFFF43F5E)
val LightMmPulse = Color(0x8CF43F5E)

/* Surface containers derived from wash / chrome */
val LightSurfaceContainerLowest = Color(0xFFFFFCFD)
val LightSurfaceContainerLow = Color(0xFFFBF0F2)
val LightSurfaceContainer = Color(0xFFF5E4E8)
val LightSurfaceContainerHigh = Color(0xFFEFD8DE)
val LightSurfaceContainerHighest = Color(0xFFE8CCD4)
val LightOutline = Color(0xFFC9A0A8)
val LightOutlineVariant = Color(0xFFE4CCD2)

/* ── Dark ([data-theme=dark]) ──────────────────────────────────── */

val DarkBg = Color(0xFF0A0406)
val DarkBgWashTop = Color(0xFF1C060A)
val DarkBgWashBottom = Color(0xFF080203)
val DarkSurface = Color(0x6B48121A)
val DarkSurfaceSolid = Color(0xFF1A0A0E)
val DarkChrome = Color(0x7A12060A)
val DarkChromeSolid = Color(0xB816080C)

val DarkInk = Color(0xFFF3E4E7)
val DarkMuted = Color(0xFFB08A90)
val DarkLine = Color(0x29FF788C)

val DarkAccent = Color(0xFFE11D48)
val DarkAccentSoft = Color(0x38E11D48)
val DarkAccentGlow = Color(0x59BE1432)

val DarkIdea = Color(0xFFF0A04B)
val DarkDream = Color(0xFFE879A8)
val DarkReflection = Color(0xFF4ADE80)
val DarkLog = Color(0xFFA8989A)
val DarkDanger = Color(0xFFFB7185)

val DarkSuccess = Color(0xFF4ADE80)
val DarkSuccessSoft = Color(0x2E4ADE80)
val DarkWarning = Color(0xFFF0A04B)
val DarkWarningSoft = Color(0x2EF0A04B)
val DarkInfo = Color(0xFF93A4C8)
val DarkInfoSoft = Color(0x2E93A4C8)

val DarkHover = Color(0x14FF5064)
val DarkShadow = Color(0x73000000)
val DarkOverlay = Color(0x9E000000)
val DarkSkeletonBase = Color(0x1AFF788C)
val DarkSkeletonShine = Color(0x1FFFC8D2)

val DarkGlassShine = Color(0x1FFFB4BE)
val DarkGlassEdge = Color(0x38FFC8D2)
val DarkGlassInset = Color(0x0FFFFFFF)

val DarkMmLabel = Color(0xFFF3E4E7)
val DarkMmEdge = Color(0x73B45A69)
val DarkMmEdgeStrong = Color(0xB3E11D48)
val DarkMmNodeTopic = Color(0xFFE11D48)
val DarkMmNodeConcept = Color(0xFFF0A04B)
val DarkMmNodeEntry = Color(0xFF4ADE80)
val DarkMmNodePerson = Color(0xFFE879A8)
val DarkMmNodePlace = Color(0xFF93A4C8)
val DarkMmNodeProject = Color(0xFFD4B483)
val DarkMmHighlight = Color(0xFFFB7185)
val DarkMmPulse = Color(0x80FB7185)

val DarkSurfaceContainerLowest = Color(0xFF080203)
val DarkSurfaceContainerLow = Color(0xFF12060A)
val DarkSurfaceContainer = Color(0xFF1C0A0E)
val DarkSurfaceContainerHigh = Color(0xFF2A1016)
val DarkSurfaceContainerHighest = Color(0xFF3A1820)
val DarkOutline = Color(0xFF5A3038)
val DarkOutlineVariant = Color(0xFF3A1820)

/** Mindmap / Brain node fill by kind (theme-aware). */
object MindMapColors {
    fun forKind(kind: String, dark: Boolean): Color = when (kind) {
        "topic" -> if (dark) DarkMmNodeTopic else LightMmNodeTopic
        "concept" -> if (dark) DarkMmNodeConcept else LightMmNodeConcept
        "entry" -> if (dark) DarkMmNodeEntry else LightMmNodeEntry
        "person" -> if (dark) DarkMmNodePerson else LightMmNodePerson
        "place" -> if (dark) DarkMmNodePlace else LightMmNodePlace
        "project" -> if (dark) DarkMmNodeProject else LightMmNodeProject
        else -> if (dark) DarkLog else LightLog
    }

    /** Prefer category (group) hex from graph.json; fall back to kind tokens. */
    fun forNode(
        kind: String,
        group: String?,
        groups: Map<String, GraphGroup>,
        dark: Boolean,
    ): Color {
        val key = group?.trim().orEmpty()
        if (key.isNotEmpty()) {
            parseHexColor(groups[key]?.color)?.let { return it }
        }
        return forKind(kind, dark)
    }

    fun parseHexColor(raw: String?): Color? {
        val s = raw?.trim()?.removePrefix("#") ?: return null
        val hex = when (s.length) {
            6 -> s
            8 -> s.take(6) // ignore alpha channel in #RRGGBBAA
            3 -> s.map { "$it$it" }.joinToString("")
            else -> return null
        }
        return try {
            Color(android.graphics.Color.parseColor("#$hex"))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun highlight(dark: Boolean): Color = if (dark) DarkMmHighlight else LightMmHighlight
    fun pulse(dark: Boolean): Color = if (dark) DarkMmPulse else LightMmPulse
    fun edge(dark: Boolean): Color = if (dark) DarkMmEdge else LightMmEdge
    fun edgeStrong(dark: Boolean): Color = if (dark) DarkMmEdgeStrong else LightMmEdgeStrong
    fun label(dark: Boolean): Color = if (dark) DarkMmLabel else LightMmLabel
}

/** Shared glass tokens for bars, cards, and lock screen. */
object GlassTokens {
    val LightTint = LightChromeSolid
    val DarkTint = DarkChromeSolid
    val HairlineLight = LightGlassEdge
    val HairlineDark = DarkGlassEdge
    val RadiusDp = 14

    /**
     * Content height of the floating glass bottom bar above system nav insets.
     * Matches MainActivity GlassBottomBar: 8+6+52+6+8 chrome + 4dp gap.
     * Screens should pad by `BottomBarClearance + navigationBars` when the bar is shown.
     */
    val BottomBarClearance = 84.dp

    fun hairlineBrush(dark: Boolean): Brush = Brush.linearGradient(
        colors = if (dark) {
            listOf(HairlineDark, Color.Transparent, HairlineDark.copy(alpha = 0.4f))
        } else {
            listOf(HairlineLight, Color.Transparent, HairlineLight.copy(alpha = 0.5f))
        },
    )
}

/** Elevation helpers mirroring --shadow-sm/md/lg/lift in tokens.css. */
object ElevationTokens {
    fun shadowColor(dark: Boolean): Color = if (dark) DarkShadow else LightShadow

    fun sm(dark: Boolean): Color = shadowColor(dark).copy(alpha = if (dark) 0.35f else 0.08f)
    fun md(dark: Boolean): Color = shadowColor(dark).copy(alpha = if (dark) 0.45f else 0.10f)
    fun lg(dark: Boolean): Color = shadowColor(dark).copy(alpha = if (dark) 0.55f else 0.22f)
    fun lift(dark: Boolean): Color = shadowColor(dark).copy(alpha = if (dark) 0.55f else 0.16f)
}

/** Theme-aware semantic feedback colors. */
object SemanticColors {
    fun success(dark: Boolean): Color = if (dark) DarkSuccess else LightSuccess
    fun warning(dark: Boolean): Color = if (dark) DarkWarning else LightWarning
    fun info(dark: Boolean): Color = if (dark) DarkInfo else LightInfo
    fun skeletonBase(dark: Boolean): Color = if (dark) DarkSkeletonBase else LightSkeletonBase
    fun skeletonShine(dark: Boolean): Color = if (dark) DarkSkeletonShine else LightSkeletonShine
}

/** Shared dark-theme detector for glass hairlines (replaces duplicated luminance hacks). */
fun Color.isChronicleDark(): Boolean = luminance() < 0.5f

@Composable
fun isChronicleDark(): Boolean = MaterialTheme.colorScheme.surface.isChronicleDark()

/** Entry type accents mirroring tokens.css --idea/--dream/--reflection/--log. */
object EntryTypeColors {
    fun of(type: String, dark: Boolean): Color = when (type.lowercase()) {
        "idea" -> if (dark) DarkIdea else LightIdea
        "dream" -> if (dark) DarkDream else LightDream
        "reflection" -> if (dark) DarkReflection else LightReflection
        else -> if (dark) DarkLog else LightLog
    }

    @Composable
    fun of(type: String): Color = of(type, isChronicleDark())
}

/** Shared glass TopAppBar container colors. */
object ChronicleChrome {
    @Composable
    fun topBarContainer(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)

    @Composable
    fun topBarScrolled(): Color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
}

/** Accent CTA tokens — mirror tokens.css --accent-gradient (165deg stops). */
val AccentBright = Color(0xFFF43F5E)
val AccentDeep = Color(0xFF9F1239)

object AccentTokens {
    /** Rose CTA gradient: #f43f5e → accent → #9f1239 (mirrors --accent-gradient). */
    fun gradient(dark: Boolean): Brush = Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to AccentBright,
            0.55f to if (dark) DarkAccent else LightAccent,
            1.0f to AccentDeep,
        ),
    )
}

/* Legacy aliases kept so older call sites compile during migration */
@Deprecated("Use LightAccent", ReplaceWith("LightAccent"))
val AccentViolet = LightAccent
@Deprecated("Use DarkAccent", ReplaceWith("DarkAccent"))
val AccentVioletLight = DarkAccent
@Deprecated("Use LightAccent", ReplaceWith("LightAccent"))
val AccentVioletDark = LightAccent
@Deprecated("Use LightReflection", ReplaceWith("LightReflection"))
val AccentTertiary = LightReflection
@Deprecated("Use LightSurfaceContainer", ReplaceWith("LightSurfaceContainer"))
val AccentTertiaryContainer = LightSurfaceContainer

@Deprecated("Use LightBg", ReplaceWith("LightBg"))
val LightBackground = LightBg
@Deprecated("Use LightInk", ReplaceWith("LightInk"))
val LightText = LightInk
@Deprecated("Use LightMuted", ReplaceWith("LightMuted"))
val LightMutedText = LightMuted

@Deprecated("Use DarkBg", ReplaceWith("DarkBg"))
val DarkBackground = DarkBg
@Deprecated("Use DarkInk", ReplaceWith("DarkInk"))
val DarkText = DarkInk
@Deprecated("Use DarkMuted", ReplaceWith("DarkMuted"))
val DarkMutedText = DarkMuted
