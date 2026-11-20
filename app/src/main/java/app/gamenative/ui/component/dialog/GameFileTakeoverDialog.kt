package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.ui.component.RingTextButton
import app.gamenative.ui.component.ringToggleable
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedAlertDialog
import app.gamenative.utils.GameFileTakeover
import app.gamenative.utils.GameFileTakeoverController
import app.gamenative.utils.gameSourceLabel
import androidx.compose.ui.res.stringResource
import app.gamenative.utils.StorageUtils
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The takeover dialog. One dialog covers every phase so the user never loses the list they were
 * looking at: scanning → result list (with per-row checkboxes) → adopting → done.
 *
 * Rows are plain columns of text rather than a LazyColumn: the list is a handful of games and a
 * lazy list inside a dialog cannot be reached with a D-pad without extra focus plumbing.
 */
@Composable
fun GameFileTakeoverDialog(
    controller: GameFileTakeoverController = GameFileTakeoverController,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current

    val title = when (state.phase) {
        GameFileTakeoverController.Phase.SCANNING -> stringResource(R.string.takeover_scan_title)
        GameFileTakeoverController.Phase.ADOPTING -> stringResource(R.string.takeover_adopting_title)
        GameFileTakeoverController.Phase.DONE -> stringResource(R.string.takeover_done_title, state.adoptedCount)
        else -> {
            val adoptable = state.result?.adoptable.orEmpty()
            if (adoptable.isEmpty()) {
                stringResource(R.string.takeover_none_title)
            } else {
                stringResource(R.string.takeover_found_title, adoptable.size)
            }
        }
    }

    ThemedAlertDialog(
        onDismissRequest = {
            if (!state.isBusy) {
                PrefManager.gameFileTakeoverPromptDismissed = true
                controller.dismiss()
                onDismiss()
            }
        },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (state.phase) {
                    GameFileTakeoverController.Phase.SCANNING -> ScanProgress(state)
                    GameFileTakeoverController.Phase.ADOPTING -> AdoptProgress(state)
                    GameFileTakeoverController.Phase.DONE -> DoneBody(state)
                    else -> ResultBody(state, controller)
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                GameFileTakeoverController.Phase.READY -> {
                    val adoptable = state.result?.adoptable.orEmpty()
                    if (adoptable.isNotEmpty()) {
                        RingTextButton(
                            onClick = { controller.adopt(context) },
                            enabled = state.selected.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.takeover_adopt_action, state.selected.size))
                        }
                    }
                }
                GameFileTakeoverController.Phase.DONE -> {
                    RingTextButton(
                        onClick = {
                            controller.dismiss()
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.takeover_close))
                    }
                }
                else -> Unit
            }
        },
        dismissButton = {
            when (state.phase) {
                GameFileTakeoverController.Phase.READY -> {
                    RingTextButton(onClick = { controller.rescan(context) }) {
                        Text(stringResource(R.string.takeover_rescan))
                    }
                }
                GameFileTakeoverController.Phase.DONE, GameFileTakeoverController.Phase.SCANNING -> Unit
                else -> RingTextButton(
                    onClick = {
                        controller.dismiss()
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        containerColor = PluviaTheme.dialogContainerColor,
        titleContentColor = PluviaTheme.dialogTitleColor,
        textContentColor = PluviaTheme.dialogTextColor,
    )
}

@Composable
private fun ScanProgress(state: GameFileTakeoverController.UiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = PluviaTheme.progressColor)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            stringResource(
                R.string.takeover_scan_progress,
                state.progressDone,
                state.progressTotal,
                state.progressLabel,
            ),
        )
    }
}

@Composable
private fun AdoptProgress(state: GameFileTakeoverController.UiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = PluviaTheme.progressColor)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            stringResource(
                R.string.takeover_scan_progress,
                state.progressDone,
                state.progressTotal,
                state.progressLabel,
            ),
        )
    }
}

@Composable
private fun DoneBody(state: GameFileTakeoverController.UiState) {
    Column {
        Text(stringResource(R.string.takeover_done_message))
        state.error?.let {
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.takeover_scan_failed, it))
        }
    }
}

@Composable
private fun ResultBody(
    state: GameFileTakeoverController.UiState,
    controller: GameFileTakeoverController,
) {
    val result = state.result
    if (result == null) {
        state.error?.let { Text(stringResource(R.string.takeover_scan_failed, it)) }
        return
    }

    val adoptable = result.adoptable
    if (adoptable.isEmpty()) {
        Text(stringResource(R.string.takeover_none_message, result.scannedRoots.size))
        if (result.alreadyInstalled.isNotEmpty()) {
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.takeover_already_installed, result.alreadyInstalled.size))
        }
        return
    }

    Text(stringResource(R.string.takeover_found_message))
    Spacer(modifier = Modifier.padding(top = 8.dp))
    Text(
        text = stringResource(R.string.takeover_found_sources, controller.summary(result)),
        style = MaterialTheme.typography.bodySmall,
        color = PluviaTheme.colors.textMuted,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RingTextButton(onClick = { controller.toggleAll(true) }) {
            Text(stringResource(R.string.takeover_select_all))
        }
        RingTextButton(onClick = { controller.toggleAll(false) }) {
            Text(stringResource(R.string.takeover_select_none))
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    adoptable.forEach { candidate ->
        CandidateRow(
            candidate = candidate,
            checked = candidate.installPath in state.selected,
            onToggle = { controller.toggle(candidate.installPath) },
        )
    }

    if (result.relocations.isNotEmpty()) {
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Text(
            text = stringResource(R.string.takeover_relocations, result.relocations.size),
            style = MaterialTheme.typography.bodySmall,
            color = PluviaTheme.colors.textMuted,
        )
    }
    if (result.alreadyInstalled.isNotEmpty()) {
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Text(
            text = stringResource(R.string.takeover_already_installed, result.alreadyInstalled.size),
            style = MaterialTheme.typography.bodySmall,
            color = PluviaTheme.colors.textMuted,
        )
    }
    if (result.errors.isNotEmpty()) {
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Text(
            text = stringResource(R.string.takeover_errors, result.errors.size),
            style = MaterialTheme.typography.bodySmall,
            color = PluviaTheme.colors.textMuted,
        )
        result.errors.take(3).forEach { line ->
            Text(
                text = stringResource(R.string.takeover_error_detail, line),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: GameFileTakeover.Candidate,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val shape = PluviaTheme.shape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .ringToggleable(value = checked, role = Role.Checkbox, shape = shape, onValueChange = { onToggle() })
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                text = "${sourceLabel(candidate.source)} — ${candidate.title}",
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            Text(
                // The path and the evidence are both shown: the path is what gets registered,
                // and the evidence line says how confident the scan is.
                text = "${candidate.installPath}\n${StorageUtils.formatBinarySize(candidate.sizeBytes)} ・ ${evidenceLabel(candidate.evidence)}",
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                maxLines = 3,
            )
            if (candidate.staleLocation) {
                // Installed already, but the recorded folder is gone/unreadable (typically the
                // previous app's private data). Ticking this row repoints the record.
                Text(
                    text = stringResource(R.string.takeover_stale_location),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun sourceLabel(source: GameSource): String = gameSourceLabel(source)

@Composable
private fun evidenceLabel(evidence: GameFileTakeover.Evidence): String = when (evidence) {
    GameFileTakeover.Evidence.STORE_METADATA -> stringResource(R.string.takeover_evidence_metadata)
    GameFileTakeover.Evidence.NAME_MATCH -> stringResource(R.string.takeover_evidence_name)
}

/** Formats the "last checked" line the settings entry shows; null when it never ran. */
fun takeoverLastScanLabel(timestamp: Long): String? {
    if (timestamp <= 0L) return null
    return SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
