package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.settings.SettingsMenuLink
import app.gamenative.ui.component.settings.SettingsSwitch
import app.gamenative.ui.theme.ThemedAlertDialog
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.LaunchConfigAdvisor
import com.alorma.compose.settings.ui.SettingsGroup
import kotlinx.coroutines.launch

/**
 * The AI check's own settings: whether to send anything off the device, and to which endpoint.
 *
 * The key is optional and off by default. Without it the check still works from the on-device
 * rules, which is why the switch and the key are the only two things this dialog asks about.
 */
@Composable
fun AiAdviceSettingsDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var useLlm by remember { mutableStateOf(PrefManager.aiConfigUseLlm) }
    var apiKey by remember { mutableStateOf(PrefManager.aiConfigApiKey) }
    var apiUrl by remember { mutableStateOf(PrefManager.aiConfigApiUrl) }
    var model by remember { mutableStateOf(PrefManager.aiConfigModel) }
    var testing by remember { mutableStateOf(false) }

    ThemedAlertDialog(
        onDismissRequest = {
            PrefManager.aiConfigApiUrl = apiUrl
            PrefManager.aiConfigModel = model
            onDismiss()
        },
        title = { Text(stringResource(R.string.ai_advice_settings_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsGroup {
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
                    SettingsMenuLink(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.ai_advice_api_key_title)) },
                        subtitle = {
                            Text(
                                if (apiKey.isBlank()) {
                                    stringResource(R.string.ai_advice_api_key_empty)
                                } else {
                                    stringResource(R.string.ai_advice_api_key_subtitle)
                                },
                            )
                        },
                        onClick = { },
                    )
                }
                NoExtractOutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        PrefManager.aiConfigApiKey = it.trim()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    label = { Text(stringResource(R.string.ai_advice_api_key_title)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                NoExtractOutlinedTextField(
                    value = apiUrl,
                    onValueChange = { apiUrl = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    label = { Text(stringResource(R.string.ai_advice_api_url_title)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                NoExtractOutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    label = { Text(stringResource(R.string.ai_advice_model_title)) },
                    singleLine = true,
                )
                SettingsGroup {
                    SettingsMenuLink(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.ai_advice_test_title)) },
                        enabled = !testing,
                        onClick = {
                            if (PrefManager.aiConfigApiKey.isBlank()) {
                                SnackbarManager.show(context.getString(R.string.ai_advice_test_need_key))
                            } else {
                                testing = true
                                scope.launch {
                                    val ok = LaunchConfigAdvisor.testEndpoint(context)
                                    testing = false
                                    SnackbarManager.show(
                                        context.getString(
                                            if (ok) R.string.ai_advice_test_ok else R.string.ai_advice_test_failed,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    SettingsMenuLink(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.ai_advice_clear_key)) },
                        enabled = apiKey.isNotBlank(),
                        onClick = {
                            apiKey = ""
                            PrefManager.aiConfigApiKey = ""
                        },
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    PrefManager.aiConfigApiUrl = apiUrl
                    PrefManager.aiConfigModel = model
                    onDismiss()
                },
            ) { Text(stringResource(R.string.close)) }
        },
    )
}
