package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.theme.ThemedAlertDialog
import app.gamenative.utils.LaunchRisk
import app.gamenative.utils.LaunchRiskCode
import app.gamenative.utils.PreLaunchChoice

/**
 * Shown before a session starts when the configuration could end badly.
 *
 * Nothing is forbidden here - the user asked for the combination, so it stays launchable. The
 * dialog only makes the two dangerous outcomes (the device rebooting, the app being killed)
 * something the user chooses rather than something that happens to them.
 */
@Composable
fun PreLaunchRiskDialog(
    risks: List<LaunchRisk>,
    canRepair: Boolean,
    onChoice: (PreLaunchChoice) -> Unit,
) {
    if (risks.isEmpty()) return

    val rebootRisk = risks.any { it.isRebootRisk }

    ThemedAlertDialog(
        onDismissRequest = { onChoice(PreLaunchChoice.ABORT) },
        title = {
            Text(
                text = stringResource(
                    if (rebootRisk) R.string.launch_risk_title_reboot else R.string.launch_risk_title_crash,
                ),
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (rebootRisk) R.string.launch_risk_intro_reboot else R.string.launch_risk_intro_crash,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                risks.forEach { risk ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "・" + stringResource(labelFor(risk.code)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (risk.isRebootRisk) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = risk.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canRepair) {
                    TextButton(onClick = { onChoice(PreLaunchChoice.REPAIR) }) {
                        Text(text = stringResource(R.string.launch_risk_repair))
                    }
                }
                TextButton(onClick = { onChoice(PreLaunchChoice.PROCEED) }) {
                    Text(text = stringResource(R.string.launch_risk_proceed))
                }
                TextButton(onClick = { onChoice(PreLaunchChoice.ABORT) }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        },
    )
}

private fun labelFor(code: LaunchRiskCode): Int = when (code) {
    LaunchRiskCode.GPU_DRIVER_MISMATCH -> R.string.launch_risk_gpu_driver
    LaunchRiskCode.NO_DXVK -> R.string.launch_risk_no_dxvk
    LaunchRiskCode.DXVK_WRONG_ARCH -> R.string.launch_risk_dxvk_arch
    LaunchRiskCode.DXVK_INSTALL_FAILED -> R.string.launch_risk_dxvk_missing
    LaunchRiskCode.VRAM_OVER_COMMIT -> R.string.launch_risk_vram
    LaunchRiskCode.LOW_FREE_RAM -> R.string.launch_risk_low_ram
    LaunchRiskCode.LOW_FREE_STORAGE -> R.string.launch_risk_low_storage
    LaunchRiskCode.DEVICE_HOT -> R.string.launch_risk_device_hot
}
