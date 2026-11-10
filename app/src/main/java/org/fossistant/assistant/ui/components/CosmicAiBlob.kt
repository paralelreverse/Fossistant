package org.fossistant.assistant.ui.components

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import org.fossistant.assistant.model.BlobMoodTheme

/**
 * Activity state driving the blob's motion (see [BlobPhysics] for exactly how each one moves):
 *
 * - [IDLE]: breathes slowly and turns slowly.
 * - [AI_REASONING]: turns steadily at a fixed size while a light sweeps around its rim.
 * - [USER_TALKING]: stops turning and swells with your voice; ripples flow inward.
 * - [AI_TALKING]: stops turning and swells as it speaks; ripples flow outward.
 */
enum class BlobActivityState {
    IDLE,
    AI_REASONING,
    USER_TALKING,
    AI_TALKING
}

/**
 * The biomorphic cosmic blob: glossy jelly bubbles around a nebula core, with ripples in voice mode. Motion is
 * integrated once per display frame in [BlobPhysics] and painted by [BlobRenderer]; only the draw phase is
 * invalidated each frame, never the composition.
 */
@Composable
fun CosmicAiBlob(
    activityState: BlobActivityState,
    modifier: Modifier = Modifier,
    moodTheme: BlobMoodTheme = BlobMoodTheme.COSMIC_NEBULA,
    typingPulseTrigger: Int = 0,
    voiceRmsLevel: Float = 0f,
    ttsAudioLevel: Float = 0f,
    showVoiceConcentricRings: Boolean = false
) {
    val context = LocalContext.current
    val physics = remember { BlobPhysics() }
    val renderer = remember { BlobRenderer() }
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    // Inputs go straight into the physics object; no recomposition needed to apply them.
    SideEffect {
        physics.state = activityState
        // The mic level arrives either as 0..90 dB or already normalized to 0..1.
        physics.micLevel = if (voiceRmsLevel > 1.5f) ((voiceRmsLevel - 24f) / 46f).coerceIn(0f, 1f) else voiceRmsLevel.coerceIn(0f, 1f)
        physics.ttsLevel = ttsAudioLevel.coerceIn(0f, 1f)
        physics.showRings = showVoiceConcentricRings
        physics.reducedMotion = reducedMotion
        renderer.setPalette(
            BlobPalette(
                primary = moodTheme.primaryAccent.toArgb(),
                secondary = moodTheme.secondaryAccent.toArgb(),
                tertiary = moodTheme.tertiaryAccent.toArgb(),
                core = moodTheme.coreNebulaColor.toArgb()
            )
        )
    }
    LaunchedEffect(typingPulseTrigger) {
        if (typingPulseTrigger > 0) physics.kickTyping()
    }

    // The body is smaller in voice mode, leaving room for the ripples around it.
    val baseRatio by animateFloatAsState(
        targetValue = if (showVoiceConcentricRings) 0.25f else 0.36f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 200f),
        label = "blobBaseRatio"
    )

    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(physics) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                physics.step(if (last == 0L) 0.016f else (now - last) / 1_000_000_000f)
                last = now
                frameNanos = now
            }
        }
    }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_VARIABLE")
        val frame = frameNanos // subscribes this draw to the frame clock
        drawIntoCanvas { canvas ->
            renderer.draw(canvas.nativeCanvas, size.width, size.height, physics, baseRatio, density)
        }
    }
}
