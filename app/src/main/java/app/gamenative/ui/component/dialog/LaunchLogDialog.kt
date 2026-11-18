package app.gamenative.ui.component.dialog

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedDialogSurface
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.GameLaunchLog
import app.gamenative.utils.LaunchLogExcerpt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The launch logs this game kept, readable on the phone.
 *
 * Sharing a log was the only way to look at one, which meant sending it out before knowing whether
 * it was even the right run. This lists the kept launches newest first (with when it ran, how big it
 * is and how it ended), shows the condensed form of the selected one - problem lines first, then
 * the last lines - and offers the whole file when it has to leave the device.
 *
 * The AI check is one tap away from here because the two answer the same question: what happened on
 * this launch.
 */
@Composable
fun LaunchLogDialog(
    visible: Boolean,
    appId: String,
    gameName: String,
    onDismiss: () -> Unit,
    onShare: (File) -> Unit,
    onAiCheck: (() -> Unit)? = null,
) {
    if (!visible) return
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var sessions by remember(appId) { mutableStateOf<List<GameLaunchLog.Session>>(emptyList()) }
    var selected by remember(appId) { mutableIntStateOf(0) }
    var text by remember(appId) { mutableStateOf("") }
    var loading by remember(appId) { mutableStateOf(true) }

    LaunchedEffect(appId) {
        sessions = withContext(Dispatchers.IO) { GameLaunchLog.sessionList(context, appId) }
        loading = sessions.isEmpty()
    }

    LaunchedEffect(sessions, selected) {
        val file = sessions.getOrNull(selected)?.file ?: return@LaunchedEffect
        loading = true
        text = withContext(Dispatchers.IO) {
            val raw = GameLaunchLog.tail(file, GameLaunchLog.ANALYSIS_BYTES)
            LaunchLogExcerpt.build(raw)
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ThemedDialogSurface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .padding(vertical = 20.dp),
            shape = RoundedCornerShape(20.dp),
            color = PluviaTheme.colors.surfaceElevated,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.launch_log_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = gameName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )

                if (sessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.game_launch_log_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sessions.forEachIndexed { index, session ->
                            FilterChip(
                                selected = index == selected,
                                onClick = { selected = index },
                                label = { Text(sessionLabel(session)) },
                            )
                        }
                    }

                    Text(
                        text = sessionSummary(sessions[selected]),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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
                            Text(stringResource(R.string.launch_log_reading))
                        }
                    } else {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                    Spacer(Modifier.width(4.dp))
                    if (text.isNotBlank()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            android.content.ClipData.newPlainText(
                                                context.getString(R.string.launch_log_title),
                                                text,
                                            ),
                                        ),
                                    )
                                    SnackbarManager.show(context.getString(R.string.launch_log_copied))
                                }
                            },
                        ) { Text(stringResource(R.string.launch_log_copy)) }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            enabled = sessions.isNotEmpty(),
                            onClick = { sessions.getOrNull(selected)?.file?.let(onShare) },
                        ) { Text(stringResource(R.string.launch_log_share)) }
                    }
                    if (onAiCheck != null) {
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                onDismiss()
                                onAiCheck()
                            },
                        ) { Text(stringResource(R.string.launch_log_ask_ai)) }
                    }
                }
            }
        }
    }
}

/** `20260718-193001` -> `18 Jul 19:30`, which is what a user compares across runs. */
private fun sessionLabel(session: GameLaunchLog.Session): String {
    val parsed = runCatching {
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(session.label)
    }.getOrNull() ?: return session.label
    return SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(parsed.time))
}

private fun sessionSummary(session: GameLaunchLog.Session): String {
    val kb = session.sizeBytes / 1024
    val ending = session.ending
        .removePrefix("=== session end:")
        .removeSuffix("===")
        .trim()
    return if (ending.isEmpty()) "$kb kB · ended abruptly" else "$kb kB · $ending"
}
