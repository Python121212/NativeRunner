package app.gamenative.ui.component.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsMenuLink
import app.gamenative.ui.component.settings.SettingsSwitch
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup

/**
 * The AI check's switches, shown inside the container settings.
 *
 * The diagnosis itself sits on the dialog's top bar, so it is reachable from every tab and needs
 * no game id here. This section is what belongs to the settings: whether the log may leave the
 * device, and to which endpoint.
 */
@Composable
fun AiSettingsSection() {
    var useLlm by remember { mutableStateOf(PrefManager.aiConfigUseLlm) }
    var showSettings by remember { mutableStateOf(false) }

    AiAdviceSettingsDialog(visible = showSettings, onDismiss = { showSettings = false })

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
    }
}
