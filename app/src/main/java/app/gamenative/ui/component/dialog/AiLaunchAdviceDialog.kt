package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedDialogSurface
import app.gamenative.utils.LaunchConfigAdvisor

/**
 * Shows what the AI check wants to change, one row per key, with the current value on the left and
 * the proposed value on the right. Nothing is written until the user confirms, and a row that is
 * not ticked is not applied.
 */
@Composable
fun AiLaunchAdviceDialog(
    visible: Boolean,
    loading: Boolean,
    advice: LaunchConfigAdvisor.Advice?,
    gameName: String,
    onDismiss: () -> Unit,
    onApply: (List<LaunchConfigAdvisor.Entry>) -> Unit,
    /** When set, the dialog offers a free-text question about this launch. */
    onAsk: ((String) -> Unit)? = null,
) {
    if (!visible) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ThemedDialogSurface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = PluviaTheme.colors.surfaceElevated,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            val selected = remember(advice) {
                mutableStateListOf<LaunchConfigAdvisor.Entry>().apply {
                    advice?.entries?.forEach { add(it) }
                }
            }
            var showFacts by remember { mutableStateOf(false) }
            var question by remember { mutableStateOf("") }

            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.ai_advice_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (gameName.isNotBlank()) {
                    Text(
                        text = gameName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PluviaTheme.progressColor)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.ai_advice_analysing))
                    }
                    return@Column
                }

                if (advice != null) {
                    Text(
                        text = when (advice.source) {
                            LaunchConfigAdvisor.Advice.Source.LLM -> stringResource(R.string.ai_advice_source_llm)
                            LaunchConfigAdvisor.Advice.Source.RULES -> stringResource(R.string.ai_advice_source_rules)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    advice.llmError?.let { error ->
                        Text(
                            text = stringResource(R.string.ai_advice_llm_failed, error),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    advice.answer?.let { answer ->
                        Text(
                            text = stringResource(R.string.ai_advice_answer),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                ) {
                    if (advice == null || advice.entries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ai_advice_nothing_found),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        advice.entries.forEach { entry ->
                            val checked = selected.contains(entry)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        if (on) {
                                            if (!selected.contains(entry)) selected.add(entry)
                                        } else {
                                            selected.remove(entry)
                                        }
                                    },
                                )
                                Column(modifier = Modifier.padding(top = 12.dp)) {
                                    Text(
                                        text = entryLabel(entry),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "${entry.from ?: stringResource(R.string.ai_advice_unset)}" +
                                            "  →  ${entry.to ?: stringResource(R.string.ai_advice_unset)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = entry.why,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    if (advice != null && advice.facts.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = { showFacts = !showFacts }) {
                            Text(
                                stringResource(
                                    if (showFacts) R.string.ai_advice_hide_facts else R.string.ai_advice_show_facts,
                                    advice.facts.size,
                                ),
                            )
                        }
                        if (showFacts) {
                            advice.facts.forEach { fact ->
                                Text(
                                    text = "• $fact",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (onAsk != null && !loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NoExtractOutlinedTextField(
                            value = question,
                            onValueChange = { question = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.launch_log_ask_ai_title)) },
                            placeholder = { Text(stringResource(R.string.launch_log_ask_ai_hint)) },
                            singleLine = true,
                        )
                        TextButton(
                            enabled = question.isNotBlank(),
                            onClick = {
                                val asked = question.trim()
                                question = ""
                                onAsk.invoke(asked)
                            },
                        ) { Text(stringResource(R.string.launch_log_ask_ai_send)) }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = selected.isNotEmpty(),
                        onClick = { onApply(selected.toList()) },
                    ) {
                        Text(stringResource(R.string.ai_advice_apply, selected.size))
                    }
                }
            }
        }
    }
}

private fun entryLabel(entry: LaunchConfigAdvisor.Entry): String =
    if (entry.name != null) "${entry.key} · ${entry.name}" else entry.key
