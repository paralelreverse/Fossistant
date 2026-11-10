package org.fossistant.assistant.ui.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossistant.assistant.FossistantApp
import org.fossistant.assistant.ui.MainActivity
import java.util.Locale

/**
 * "Share to Fossistant" and the text-selection menu entry. Shows what was shared and lets the user pick
 * what to do with it; nothing is sent to the assistant until they tap an option, and the prompt is handed
 * over in-process (never through intent extras, which other apps could forge).
 */
class ShareToAssistantActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = extractSharedText(intent)?.take(MAX_SHARED_CHARS)
        if (shared.isNullOrBlank()) {
            finish()
            return
        }
        val translateTarget = Locale.getDefault().displayLanguage.ifBlank { "English" }
        // Selected text in an editable field can be replaced with a writing-tool result.
        val canReplace = intent?.action == Intent.ACTION_PROCESS_TEXT &&
            !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        setContent {
            var question by remember { mutableStateOf("") }
            var result by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { finish() },
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xF00F172A))
                        .clickable(enabled = false) {}
                        .padding(18.dp)
                ) {
                    Text("Fossistant", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = shared,
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Writing tools", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    org.fossistant.assistant.ui.features.WritingActionButtons(enabled = !busy) { action ->
                        busy = true
                        result = "Working on it…"
                        scope.launch {
                            result = org.fossistant.assistant.features.WritingTools.run(action, shared)
                                ?: "Couldn't do that: ${FossistantApp.instance?.gemmaEngine?.lastEngineError ?: "the model isn't available"}"
                            busy = false
                        }
                    }
                    if (result.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = result,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canReplace) {
                                ActionButton("Replace") {
                                    setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result))
                                    finish()
                                }
                            }
                            ActionButton("Copy") {
                                getSystemService(android.content.ClipboardManager::class.java)
                                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Fossistant", result))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("Explain") { send("Explain this simply:\n\n$shared") }
                        ActionButton("Translate") { send("Translate this into $translateTarget:\n\n$shared") }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        placeholder = { Text("Ask about it…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { send("${question.trim()}\n\n$shared") },
                        enabled = question.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Ask") }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ActionButton(label: String, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF312E81))
        ) { Text(label, fontSize = 13.sp) }
    }

    private fun send(prompt: String) {
        FossistantApp.pendingSharedPrompt = prompt
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun extractSharedText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        ).joinToString("\n").trim()
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
        else -> null
    }

    private companion object {
        // Roughly 1,500 tokens: leaves room in the 4096-token context for the answer.
        const val MAX_SHARED_CHARS = 6000
    }
}
