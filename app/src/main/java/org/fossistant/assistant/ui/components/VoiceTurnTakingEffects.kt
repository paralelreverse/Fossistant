package org.fossistant.assistant.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.fossistant.assistant.ui.AssistantUiState
import org.fossistant.assistant.ui.AssistantViewModel
import org.fossistant.assistant.util.AppLogger

/**
 * Shared hands-free turn-taking and idle-microphone recovery effects used across
 * MainActivity (in VOICE_MODE), AssistOverlayActivity, and FossistantVoiceInteractionSession.
 */
@Composable
fun VoiceTurnTakingEffects(
    context: Context,
    viewModel: AssistantViewModel,
    uiState: AssistantUiState,
    isActiveSurface: Boolean,
    watchdogIntervalMs: Long = 1200L,
    logTag: String = "VoiceTurnTaking",
    onStartListening: () -> Unit
) {
    LaunchedEffect(
        isActiveSurface,
        uiState.isListeningVoice,
        uiState.isVoiceMutedByUser,
        uiState.isGenerating,
        uiState.isTtsSpeaking,
        uiState.isReasoning
    ) {
        if (isActiveSurface &&
            !uiState.isListeningVoice &&
            !uiState.isVoiceMutedByUser &&
            !uiState.isGenerating &&
            !uiState.isTtsSpeaking &&
            !uiState.isReasoning
        ) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                onStartListening()
            }
        }
    }

    LaunchedEffect(isActiveSurface) {
        while (isActiveSurface) {
            delay(watchdogIntervalMs)
            val s = viewModel.uiState.value
            val idle = !s.isVoiceMutedByUser && !s.isGenerating && !s.isTtsSpeaking && !s.isReasoning
            if (idle && !viewModel.sttEngine.isListening.value &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                AppLogger.w(logTag, "Voice surface was idle with the microphone off: listening again")
                onStartListening()
            }
        }
    }
}
