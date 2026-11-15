package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.powercontrol.PowerManager
import app.gamenative.ui.theme.ThemedAlertDialog
import app.gamenative.utils.GameStorageOptimizer
import app.gamenative.utils.PersistentLog
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.StorageUtils
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.launch
import java.io.File

/**
 * Entry point for the storage shrinker: a row in the storage screen that confirms, runs and then
 * reports what the filesystem actually gave back.
 */
@Composable
fun StorageOptimizerLauncher() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(Step.IDLE) }
    var progress by remember { mutableStateOf<GameStorageOptimizer.OptimizerProgress?>(null) }
    var result by remember { mutableStateOf<GameStorageOptimizer.OptimizerResult?>(null) }

    fun start() {
        if (PowerManager.isGameStarted) {
            SnackbarManager.show(context.getString(R.string.storage_optimize_running_game))
            step = Step.IDLE
            return
        }
        step = Step.RUNNING
        progress = null
        result = null
        scope.launch {
            val roots = gameRoots(context)
            val outcome = GameStorageOptimizer.optimize(roots) { progress = it }
            result = outcome
            step = Step.DONE
        }
    }

    when (step) {
        Step.IDLE -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { step = Step.CONFIRM }) {
                    Text(text = stringResource(R.string.storage_optimize_action))
                }
            }
        }

        Step.CONFIRM -> ThemedAlertDialog(
            onDismissRequest = { step = Step.IDLE },
            title = { Text(text = stringResource(R.string.storage_optimize_confirm_title)) },
            text = { Text(text = stringResource(R.string.storage_optimize_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { start() }) {
                    Text(text = stringResource(R.string.storage_optimize_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { step = Step.IDLE }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )

        Step.RUNNING -> ThemedAlertDialog(
            onDismissRequest = {},
            title = { Text(text = stringResource(R.string.storage_optimize_running_title)) },
            text = {
                Column {
                    val current = progress
                    Text(
                        text = when (current?.phase) {
                            GameStorageOptimizer.Phase.JUNK -> stringResource(R.string.storage_optimize_phase_junk)
                            GameStorageOptimizer.Phase.DEDUPE -> stringResource(R.string.storage_optimize_phase_dedupe)
                            GameStorageOptimizer.Phase.HOLES -> stringResource(R.string.storage_optimize_phase_holes)
                            else -> stringResource(R.string.storage_optimize_phase_scan)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val fraction = current?.let {
                        if (it.total <= 0) 0f else (it.processed.toFloat() / it.total).coerceIn(0f, 1f)
                    } ?: 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = current?.current.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    Text(
                        text = stringResource(
                            R.string.storage_optimize_progress_bytes,
                            StorageUtils.formatBinarySize(current?.reclaimedBytes ?: 0L),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { GameStorageOptimizer.cancel() }) {
                    Text(text = stringResource(R.string.storage_optimize_stop))
                }
            },
        )

        Step.DONE -> {
            val outcome = result
            ThemedAlertDialog(
                onDismissRequest = { step = Step.IDLE },
                title = { Text(text = stringResource(R.string.storage_optimize_done_title)) },
                text = {
                    Column {
                        if (outcome == null) {
                            Text(text = stringResource(R.string.storage_optimize_failed))
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.storage_optimize_result_measured,
                                    StorageUtils.formatBinarySize(outcome.measuredFreeDelta),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.storage_optimize_result_detail,
                                    StorageUtils.formatBinarySize(outcome.junkBytes),
                                    StorageUtils.formatBinarySize(outcome.dedupeBytes),
                                    StorageUtils.formatBinarySize(outcome.holeBytes),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(
                                    R.string.storage_optimize_result_counts,
                                    outcome.scannedFiles,
                                    outcome.droppedFiles,
                                    outcome.linkedFiles,
                                    outcome.elapsedMs / 1000,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (outcome.unsupported.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(
                                        R.string.storage_optimize_unsupported,
                                        outcome.unsupported.joinToString(", "),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (!outcome.anyChange) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.storage_optimize_nothing_to_do),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { step = Step.IDLE }) {
                        Text(text = stringResource(R.string.ok))
                    }
                },
            )
        }
    }

    // Log where the numbers came from once per run, so a report can explain them later.
    LaunchedEffect(result) {
        result?.let {
            PersistentLog.write(
                android.util.Log.INFO,
                "StorageOptimizer",
                "result measured=${it.measuredFreeDelta} junk=${it.junkBytes} " +
                    "dedupe=${it.dedupeBytes} holes=${it.holeBytes} files=${it.scannedFiles}",
            )
        }
    }
}

private enum class Step { IDLE, CONFIRM, RUNNING, DONE }

/**
 * The game trees inside the image: `home` holds the wine prefixes and the Steam library,
 * `storage` the volumes the container can see. `usr` is the runtime itself and is left alone.
 */
private fun gameRoots(context: android.content.Context): List<File> {
    val root = runCatching { ImageFs.find(context).rootDir }.getOrNull() ?: return emptyList()
    return listOf(File(root, "home"), File(root, "storage")).filter { it.isDirectory }
}
