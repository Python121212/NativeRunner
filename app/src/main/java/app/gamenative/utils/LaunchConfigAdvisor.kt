package app.gamenative.utils

import android.content.Context
import android.os.Build
import app.gamenative.PrefManager
import app.gamenative.api.SupportSuggestion
import app.gamenative.api.SuggestionConfigKeys
import app.gamenative.ui.screen.support.SupportSuggestionApplier
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.Locale

/**
 * Reads a failed launch (its own log, the config it ran with, the device) and proposes concrete
 * setting changes.
 *
 * Two brains, in this order:
 *   1. rules that need nothing but the log text and the hardware strings - always available, free,
 *      offline, deterministic;
 *   2. optionally, an OpenAI-compatible endpoint the user configures with their own key. It sees
 *      the same condensed facts and returns changes in the same shape, so the confirm screen and
 *      the applier do not care which one produced them.
 *
 * Nothing is applied here: [Advice.entries] is what the confirmation dialog shows and
 * [apply] applies exactly the entries the user kept ticked.
 */
object LaunchConfigAdvisor {

    /** One proposed change, keyed the same way the support suggestion applier keys them. */
    data class Entry(
        /** Container key ("dxwrapper"), "key.subKey" for a kv set, or [SupportSuggestion.ENV_KEY]. */
        val key: String,
        /** Env var name, only for [SupportSuggestion.ENV_KEY]. */
        val name: String?,
        /** Value to write; null means "remove this key". */
        val to: String?,
        val from: String?,
        val why: String,
        /** True for changes the rules consider safe to apply without looking at the log closely. */
        val confident: Boolean = true,
    ) {
        /** Stable identity for the checkbox list. */
        val id: String get() = "$key\u0000${name.orEmpty()}"
    }

    data class Advice(
        val entries: List<Entry>,
        /** Short human summary, shown at the top of the dialog. */
        val summary: String,
        /** Which brain answered. */
        val source: Source,
        /** Facts that were used, shown so the user can see what the advice is based on. */
        val facts: List<String> = emptyList(),
    ) {
        enum class Source { RULES, LLM }
    }

    private const val TAG = "LaunchConfigAdvisor"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val LLM_TIMEOUT_SECONDS = 60L

    /* ---------------------------------------------------------------- entry point */

    /**
     * Analyses [appId]'s most recent launch. [logText] may be supplied when the caller already has
     * it (the report dialog does); otherwise the newest launch log is read.
     */
    suspend fun advise(context: Context, appId: String, logText: String? = null): Advice =
        withContext(Dispatchers.IO) {
            val live = runCatching { ContainerUtils.toContainerData(ContainerUtils.getContainer(context, appId)) }.getOrNull()
            val log = logText?.takeIf { it.isNotBlank() } ?: GameLaunchLog.tail(context, appId)
            val facts = facts(context, appId, live, log)
            val ruleEntries = rules(context, live, log)

            if (!PrefManager.aiConfigUseLlm || PrefManager.aiConfigApiKey.isBlank()) {
                return@withContext Advice(
                    entries = ruleEntries,
                    summary = summaryFor(ruleEntries, facts),
                    source = Advice.Source.RULES,
                    facts = facts,
                )
            }

            val llmEntries = runCatching { askLlm(context, live, log, facts, ruleEntries) }
                .onFailure { Timber.tag(TAG).w(it, "LLM advice failed, keeping the rules") }
                .getOrNull()

            if (llmEntries.isNullOrEmpty()) {
                Advice(
                    entries = ruleEntries,
                    summary = summaryFor(ruleEntries, facts),
                    source = Advice.Source.RULES,
                    facts = facts,
                )
            } else {
                Advice(
                    entries = merge(ruleEntries, llmEntries),
                    summary = summaryFor(llmEntries, facts),
                    source = Advice.Source.LLM,
                    facts = facts,
                )
            }
        }

    /** Applies exactly [entries] to the game's container. Returns how many values were written. */
    suspend fun apply(context: Context, appId: String, entries: List<Entry>): Int =
        withContext(Dispatchers.IO) {
            if (entries.isEmpty()) return@withContext 0
            val container = ContainerUtils.getContainer(context, appId)
            val live = ContainerUtils.toContainerData(container)
            val edits = entries.mapNotNull { editFor(it) }
            if (edits.isEmpty()) return@withContext 0
            val updates = SupportSuggestionApplier.updatesFor(live, edits)
            if (updates.isEmpty()) return@withContext 0
            val updated = ContainerUtils.applyBestConfigMapToContainerData(live, updates)
            ContainerUtils.applyToContainer(context, container, updated)
            SessionReport.markConfigApplied(container, "ai_launch_diagnosis")
            updates.size
        }

    /** The same Edit shape the support suggestion and optimize appliers write through. */
    private fun editFor(entry: Entry): SupportSuggestionApplier.Edit? = when {
        entry.key == SupportSuggestion.ENV_KEY -> entry.name?.let { name ->
            SupportSuggestionApplier.Edit(SupportSuggestion.ENV_KEY, name, entry.to)
        }
        entry.key.contains('.') && entry.key.substringBefore('.') in SupportSuggestion.KV_PARENTS ->
            SupportSuggestionApplier.Edit(entry.key, null, entry.to)
        // A plain key can only be set to a value; there is nothing to remove.
        entry.to == null -> null
        entry.key in SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS && entry.key != SupportSuggestion.ENV_KEY ->
            SupportSuggestionApplier.Edit(entry.key, null, entry.to)
        else -> null
    }

    /* ---------------------------------------------------------------- facts */

    /** Everything both brains get to see, as "label: value" lines. */
    private fun facts(context: Context, appId: String, data: ContainerData?, log: String): List<String> = buildList {
        add("game: ${runCatching { ContainerUtils.resolveGameName(appId) }.getOrDefault(appId)} ($appId)")
        add("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        add("soc: ${HardwareUtils.getSOCName() ?: "unknown"}")
        add("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        add("abi: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        add("gpu: ${runCatching { GPUInformation.getRenderer(context) }.getOrNull().orEmpty().ifEmpty { "unknown" }}")
        add("gpu flags: ${gpuFlags(context)}")
        if (data != null) {
            add("variant: ${data.containerVariant}")
            add("wine: ${data.wineVersion}")
            add("emulator: ${data.emulator} ${data.fexcoreVersion}/${data.fexcorePreset}")
            add("driver: ${data.graphicsDriver} ${data.graphicsDriverVersion}")
            add("dxwrapper: ${data.dxwrapper} (${data.dxwrapperConfig})")
            add("box64: ${data.box64Version}/${data.box64Preset}")
            add("memory: videoMemorySize=${data.videoMemorySize}")
            add("startup/suspend: ${data.startupSelection}/${data.suspendPolicy}")
            if (data.envVars.isNotBlank()) add("env: ${data.envVars}")
            if (data.execArgs.isNotBlank()) add("execArgs: ${data.execArgs}")
        }
        val previous = PersistentLog.lastSession
        if (previous != null) add("previous app session: ${previous.end}")
        val exits = runCatching {
            AppExitRecorder.recentExits(context, System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000, limit = 3)
        }.getOrDefault(emptyList())
        exits.forEach { record ->
            add(
                "android exit: ${record.optString("reason")} " +
                    "status=${record.optInt("status")} pss=${record.optInt("pssKb")}kB " +
                    "${record.optString("description").takeIf { it != "null" && it.isNotBlank() }?.let { "($it)" } ?: ""}",
            )
        }
        add("log lines: ${log.lines().size}")
    }

    private fun gpuFlags(context: Context): String = runCatching {
        listOfNotNull(
            "turnipCapable".takeIf { GPUInformation.isTurnipCapable(context) },
            "adreno".takeIf { GPUInformation.isAdrenoGPU(context) },
            "adreno6xx".takeIf { GPUInformation.isAdreno6xx(context) },
            "adreno8elite".takeIf { GPUInformation.isAdreno8Elite(context) },
            "adreno8eliteGen5".takeIf { GPUInformation.isAdreno8EliteGen5(context) },
            "adrenoA12".takeIf { GPUInformation.isAdrenoA12(context) },
            "adreno710_720_732".takeIf { GPUInformation.isAdreno710_720_732(context) },
            "adreno740".takeIf { GPUInformation.isAdreno740(context) },
        ).joinToString(",").ifEmpty { "none" }
    }.getOrDefault("unknown")

    /* ---------------------------------------------------------------- rules */

    /**
     * Log signatures that have one known cause. Each one only proposes a value that is different
     * from the live one, so a rule never repeats a setting the user already has.
     */
    private fun rules(context: Context, data: ContainerData?, log: String): List<Entry> {
        if (data == null) return emptyList()
        val text = log.lowercase(Locale.ENGLISH)
        val entries = mutableListOf<Entry>()

        fun add(key: String, to: String?, why: String, confident: Boolean = true) {
            val from = currentValue(data, key)
            if (from == to) return
            if (entries.any { it.key == key }) return
            entries += Entry(key = key, name = null, to = to, from = from, why = why, confident = confident)
        }

        fun addEnv(name: String, to: String?, why: String) {
            val from = EnvVars(data.envVars).let { if (it.has(name)) it.get(name) else null }
            if (from == to) return
            if (entries.any { it.key == SupportSuggestion.ENV_KEY && it.name == name }) return
            entries += Entry(
                key = SupportSuggestion.ENV_KEY,
                name = name,
                to = to,
                from = from,
                why = why,
                confident = true,
            )
        }

        fun dxwrapperVersion(): String = KeyValueSet(data.dxwrapperConfig).get("version")
        fun graphicsVersion(): String = KeyValueSet(data.graphicsDriverConfig).get("version")
            .ifEmpty { data.graphicsDriverVersion }

        /* Vulkan present support missing: the wrapper has no usable swapchain path. */
        if (text.contains("vulkan") && (text.contains("swapchain") || text.contains("vkCreateSwapchain")) &&
            (text.contains("error") || text.contains("failed"))
        ) {
            if (data.dxwrapper != "vkd3d") {
                add(
                    "dxwrapperConfig.version",
                    if (GPUInformation.isAdreno6xx(context)) "1.11.1-sarek" else "2.4.1-gplasync",
                    "The Vulkan device rejected the swapchain. A newer DXVK keeps a fallback present path " +
                        "(Adreno 6xx needs the 1.11.1 line).",
                )
                addEnv("DXVK_WSI_PRESENT_MODE", "fifo", "Force a present mode that every Vulkan driver supports.")
            }
        }

        /* The dxvk/vkd3d package itself failed to load. */
        if (text.contains("err:module:") && text.contains("dxvk") && text.contains("not found") ||
            text.contains("failed to load dxvk") || text.contains("dxvk: could not load")
        ) {
            if (dxwrapperVersion().isEmpty()) {
                add("dxwrapperConfig.version", "2.4.1-gplasync", "DXVK could not be loaded at all; pick an explicit version.")
            }
            addEnv("DXVK_DEBUG", "none", "Stops DXVK spewing a warning per frame while the real cause is elsewhere.")
        }

        /* 32-bit process that cannot start: the WoW64 emulator is the usual reason. */
        if (text.contains("wow64") || (text.contains("box64") && text.contains("error")) ||
            text.contains("unimplemented function") || text.contains("fex: unimplemented")
        ) {
            if (!data.emulator.equals("FEXCore", ignoreCase = true)) {
                add("emulator", "FEXCore", "FEXCore implements more of the 32-bit instruction set than Box64 here.")
                add("fexcorePreset", "COMPATIBILITY", "Compatibility preset trades a little speed for fewer unimplemented paths.")
            } else if (data.fexcorePreset != "STABILITY" && data.fexcorePreset != "COMPATIBILITY") {
                add("fexcorePreset", "COMPATIBILITY", "An unimplemented instruction was hit; a calmer FEXCore preset avoids it.")
            }
        }

        /* Wine could not create the window / device: often the X server vs gamescope mismatch. */
        if (text.contains("failed to create device") || text.contains("vkCreateDevice failed") ||
            text.contains("err:dxgi") || text.contains("d3d11 device creation failed")
        ) {
            add(
                "graphicsDriverConfig.version",
                when {
                    GPUInformation.isAdreno8EliteGen5(context) -> ContainerUtils.WRAPPER_ADRENO_8ELITE_GEN5
                    GPUInformation.isAdreno8Elite(context) -> ContainerUtils.WRAPPER_ADRENO_8ELITE
                    GPUInformation.isAdrenoA12(context) -> ContainerUtils.WRAPPER_ADRENO_A12
                    else -> ContainerUtils.WRAPPER_TURNIP_CAPABLE
                },
                "The Vulkan device could not be created. The bundled Turnip build matches this GPU family " +
                    "better than the system driver.",
            )
            add("graphicsDriver", "Wrapper", "Route rendering through the wrapper instead of the system GL driver.")
        }

        /* Out of memory in the guest: the VRAM ceiling is the value to lower. */
        if (text.contains("out of memory") || text.contains("vkAllocateMemory failed") ||
            text.contains("err:virtual:out_of_memory") || text.contains("status: 137")
        ) {
            val current = data.videoMemorySize.toIntOrNull() ?: 2048
            val next = if (current > 2048) 2048 else 1024
            add("videoMemorySize", next.toString(), "The guest ran out of memory; a smaller reported VRAM budget leaves room for the rest.")
            add("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL.toString(), "Essential-only startup loads fewer Windows services into the same memory.")
        }

        /* Direct3D 12 title falling back to nothing. */
        if ((text.contains("d3d12") || text.contains("vkd3d")) && (text.contains("unsupported") || text.contains("failed"))) {
            val vkd3d = KeyValueSet(data.dxwrapperConfig).get("vkd3dVersion")
            if (vkd3d.isEmpty()) {
                add("dxwrapperConfig.vkd3dVersion", "2.14.1", "D3D12 needs VKD3D-Proton; pin a known version instead of the default.")
            }
            add("dxwrapper", "vkd3d", "The game asks for D3D12, which DXVK does not provide.")
        }

        /* Audio device never opened: a silent game with a clean log looks like a hang. */
        if (text.contains("alsa") && (text.contains("cannot open") || text.contains("failed to open"))) {
            add("audioDriver", "pulseaudio", "ALSA could not open the device; PulseAudio is routed through the system mixer.")
        }

        /* Steam client could not be reached in a title that needs it. */
        if (text.contains("steam_api") || text.contains("steamclient")) {
            if (text.contains("failed") || text.contains("not found")) {
                add("steamOfflineMode", "true", "The Steam client could not be initialised in the guest; start the game offline.")
            }
        }

        /* Nothing in the log: the launch itself probably died before the guest ran. */
        if (log.isBlank()) {
            add("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL.toString(), "No guest output was produced; load the essential Windows components only.")
            addEnv("WINEDEBUG", "fixme-all", "Log wine output next time so the next run can be diagnosed.")
        }

        return entries
    }

    /** The value this key has in [data] right now, for the "from" column of the diff. */
    fun currentValue(data: ContainerData, key: String): String? {
        if (key.contains('.')) {
            val parent = key.substringBefore('.')
            val sub = key.substringAfter('.')
            val source = SuggestionConfigKeys.configValueOf(data, parent).orEmpty()
            return KeyValueSet(source).get(sub).takeIf { it.isNotEmpty() }
        }
        return SuggestionConfigKeys.configValueOf(data, key)
    }

    private fun summaryFor(entries: List<Entry>, facts: List<String>): String =
        if (entries.isEmpty()) {
            "No known cause was found in the log. The items below are the facts that were used."
        } else {
            "${entries.size} suggested change(s) from ${facts.size} facts."
        }

    /* ---------------------------------------------------------------- applying */

    /** Rule entries win over LLM entries for the same key: they are derived from the log itself. */
    private fun merge(ruleEntries: List<Entry>, llmEntries: List<Entry>): List<Entry> {
        val seen = ruleEntries.map { it.id }.toSet()
        return ruleEntries + llmEntries.filterNot { it.id in seen }
    }

    /* ---------------------------------------------------------------- LLM */

    private fun prompt(facts: List<String>, log: String, rules: List<Entry>): String = buildString {
        appendLine("You are diagnosing a Windows game that fails to start under Wine on Android.")
        appendLine("Facts:")
        facts.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Allowed container keys: ${SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS.keys.joinToString(", ")}")
        appendLine("- For dxwrapperConfig / graphicsDriverConfig / wincomponents use \"<key>.<subKey>\".")
        appendLine("- For environment variables use {\"key\":\"envVars\",\"name\":\"NAME\",\"to\":\"value\"}.")
        appendLine("- \"to\": null removes the value. Only propose changes worth trying; at most 6.")
        appendLine("- Values must already be plausible for this device and these installed components.")
        appendLine()
        if (rules.isNotEmpty()) {
            appendLine("Other analysers already proposed:")
            rules.forEach { appendLine("- ${it.key}${it.name?.let { n -> " ($n)" } ?: ""} -> ${it.to}: ${it.why}") }
            appendLine()
        }
        appendLine("Log tail (the game's own launch log):")
        appendLine("-----")
        appendLine(log)
        appendLine("-----")
        appendLine("Answer with JSON only: {\"summary\":\"...\",\"changes\":[{\"key\":\"...\",\"name\":null,\"to\":\"...\",\"why\":\"...\"}]}")
    }

    private suspend fun askLlm(
        context: Context,
        live: ContainerData?,
        log: String,
        facts: List<String>,
        rules: List<Entry>,
    ): List<Entry>? {
        val key = PrefManager.aiConfigApiKey
        if (key.isBlank() || live == null) return null

        val body = JSONObject().apply {
            put("model", PrefManager.aiConfigModel)
            put("temperature", 0.2)
            put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", prompt(facts, log.takeLast(12000), rules)))
                },
            )
        }

        val request = okhttp3.Request.Builder()
            .url(PrefManager.aiConfigApiUrl)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val client = Net.http.newBuilder()
            .callTimeout(LLM_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("endpoint HTTP ${response.code}: ${text.take(300)}")
                return null
            }
            val content = JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                .orEmpty()
            if (content.isBlank()) return null
            return parseChanges(content, live)
        }
    }

    /** Tolerant parser: the model is asked for JSON, but a stray fence or nesting must not crash. */
    internal fun parseChanges(content: String, live: ContainerData): List<Entry> {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = runCatching { JSONObject(content.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
        val changes = root.optJSONArray("changes") ?: return emptyList()
        val out = mutableListOf<Entry>()
        for (i in 0 until changes.length()) {
            val item = changes.optJSONObject(i) ?: continue
            val key = item.optString("key").trim()
            if (key.isEmpty()) continue
            val name = if (item.isNull("name")) null else item.optString("name").trim().ifEmpty { null }
            val to = if (item.isNull("to")) null else item.optString("to")
            val why = item.optString("why").ifBlank { "Suggested by the model." }
            val valid = when {
                key == SupportSuggestion.ENV_KEY -> name != null && name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
                key.contains('.') -> key.substringBefore('.') in
                    setOf("dxwrapperConfig", "graphicsDriverConfig", "wincomponents") &&
                    key.substringAfter('.').isNotBlank()
                else -> key in SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS
            }
            if (!valid) continue
            out += Entry(
                key = key,
                name = name,
                to = to,
                from = currentValue(live, key),
                why = why,
                confident = false,
            )
            if (out.size >= 6) break
        }
        return out
    }

    private const val SYSTEM_PROMPT =
        "You fix Windows-on-Wine-on-Android launch failures. You answer with JSON only, never prose. " +
            "You prefer the smallest change that addresses the evidence in the log, and you never " +
            "invent components that a stock GameNative install does not ship."


    /** Used by the settings screen to check a key without running a diagnosis. */
    suspend fun testEndpoint(context: Context): Boolean = withContext(Dispatchers.IO) {
        val key = PrefManager.aiConfigApiKey
        if (key.isBlank()) return@withContext false
        runCatching {
            val body = JSONObject().apply {
                put("model", PrefManager.aiConfigModel)
                put("max_tokens", 8)
                put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with the word ok.")),
                )
            }
            val request = okhttp3.Request.Builder()
                .url(PrefManager.aiConfigApiUrl)
                .header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            Net.http.newBuilder()
                .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
