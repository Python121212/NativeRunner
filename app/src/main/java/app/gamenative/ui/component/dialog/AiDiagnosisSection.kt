package app.gamenative.ui.component.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsMenuLink
import app.gamenative.ui.component.settings.SettingsSwitch
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.GameLaunchLog
import app.gamenative.utils.LaunchConfigAdvisor
import com.alorma.compose.settings.ui.SettingsGroup
import kotlinx.coroutines.launch

/**
 * The "game settings → AI check" entry, next to the rest of the container tabs.
 *
 * [appId] is the game whose container this dialog edits. The settings screen opens the same dialog
 * for the app-wide defaults, where there is no game yet: then the game-specific items are hidden
 * and only the AI switches remain, which is what belongs to a default.
 */
@Composable
fun AiDiagnosisSection(appId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var useLlm by remember { mutableStateOf(PrefManager.aiConfigUseLlm) }
    var showSettings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var advice by remember { mutableStateOf<LaunchConfigAdvisor.Advice?>(null) }
    var adviceVisible by remember { mutableStateOf(false) }
    // The dialog edits a copy of the config while this section writes the live container, so the
    // picked entries are handed back here instead of being written under the user's dialog.
    var pendingEntries by remember { mutableStateOf<List<LaunchConfigAdvisor.Entry>?>(null) }
    LaunchedEffect(pendingEntries) {
        val entries = pendingEntries ?: return@LaunchedEffect
        pendingEntries = null
        if (appId == null || entries.isEmpty()) return@LaunchedEffect
        val applied = runCatching { LaunchConfigAdvisor.apply(context, appId, entries) }.getOrDefault(0)
        SnackbarManager.show(
            context.getString(
                if (applied > 0) R.string.ai_advice_applied else R.string.ai_advice_apply_failed,
                applied,
            ),
        )
    }

    AiAdviceSettingsDialog(visible = showSettings, onDismiss = { showSettings = false })

    if (appId != null) {
        AiLaunchAdviceDialog(
            visible = adviceVisible,
            loading = busy,
            advice = advice,
            gameName = remember(appId) { runCatching { ContainerUtils.resolveGameName(appId) }.getOrDefault(appId) },
            onDismiss = { adviceVisible = false },
            onApply = { entries ->
                adviceVisible = false
                pendingEntries = entries
            },
        )
    }

    SettingsGroup {
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.ai_advice_settings_title)) },
            subtitle = { Text(stringResource(R.string.ai_advice_settings_subtitle)) },
            onClick = { showSettings = true },
        )
        SettingsSwitch(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.ai_advice_use_llm_title)) },
            subtitle = { Text(stringResource(R.string.ai_advice_use_llm_subtitle)) },
            state = useLlm,
            onCheckedChange = {
                useLlm = it
                PrefManager.aiConfigUseLlm = it
            },
        )
        if (appId != null) {
            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(stringResource(R.string.option_ai_config_fix)) },
                subtitle = { Text(stringResource(R.string.ai_diagnosis_run_hint)) },
                enabled = !busy,
                onClick = {
                    adviceVisible = true
                    busy = true
                    advice = null
                    scope.launch {
                        advice = runCatching { LaunchConfigAdvisor.advise(context, appId) }.getOrNull()
                        busy = false
                    }
                },
            )
            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(stringResource(R.string.option_export_launch_log)) },
                subtitle = { Text(stringResource(R.string.ai_diagnosis_log_hint)) },
                onClick = {
                    scope.launch {
                        val file = GameLaunchLog.export(context, appId)
                        if (file == null) {
                            SnackbarManager.show(context.getString(R.string.game_launch_log_missing))
                        } else {
                            GameLaunchLog.share(context, file)
                        }
                    }
                },
            )
        }
    }
}
