package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Drives [GameFileTakeover] for the UI: one scan at a time, cancellable, with the result kept so
 * the dialog survives a recomposition. Nothing is written until [adopt] is called, and [adopt]
 * only receives the rows the user ticked.
 */
object GameFileTakeoverController {

    enum class Phase { IDLE, SCANNING, READY, ADOPTING, DONE }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        val progressLabel: String = "",
        val result: GameFileTakeover.ScanResult? = null,
        /** installPath values the user ticked; defaults to everything adoptable. */
        val selected: Set<String> = emptySet(),
        val adoptedCount: Int = 0,
        val error: String? = null,
    ) {
        val isBusy: Boolean get() = phase == Phase.SCANNING || phase == Phase.ADOPTING
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Scans without asking anything; used on start-up, where a hit only raises a prompt. */
    fun scanInBackground(context: Context) {
        if (_state.value.isBusy) return
        start(context)
    }

    fun rescan(context: Context) {
        job?.cancel()
        _state.value = UiState()
        start(context)
    }

    private fun start(context: Context) {
        val appContext = context.applicationContext
        _state.value = UiState(phase = Phase.SCANNING)
        job = scope.launch {
            try {
                val result = GameFileTakeover.scan(appContext) { done, total, label ->
                    _state.value = _state.value.copy(
                        progressDone = done,
                        progressTotal = total,
                        progressLabel = label,
                    )
                }
                PrefManager.gameFileTakeoverLastScan = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    phase = Phase.READY,
                    result = result,
                    selected = result.adoptable.map { it.installPath }.toSet(),
                    error = null,
                )
                // Logged in full because "nothing found" is otherwise indistinguishable from
                // "nothing was looked at": the roots, the skipped ones and the store rows that
                // could not be reached are all part of the answer.
                Timber.i(
                    "Takeover scan: %d candidate(s), %d adoptable, %d unreachable, roots=%s",
                    result.candidates.size,
                    result.adoptable.size,
                    result.unreachable.size,
                    result.scannedRoots,
                )
                if (result.unreadableRoots.isNotEmpty()) {
                    Timber.i("Takeover scan skipped (unreadable): %s", result.unreadableRoots)
                }
                if (result.unreachable.isNotEmpty()) {
                    Timber.i(
                        "Takeover scan could not reach: %s",
                        result.unreachable.map { "${it.title}=${it.recordedPath}" },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Takeover scan failed")
                _state.value = _state.value.copy(phase = Phase.READY, error = e.message)
            }
        }
    }

    fun toggle(installPath: String) {
        val current = _state.value
        val selected = current.selected.toMutableSet()
        if (!selected.add(installPath)) selected.remove(installPath)
        _state.value = current.copy(selected = selected)
    }

    fun toggleAll(select: Boolean) {
        val current = _state.value
        val adoptable = current.result?.adoptable.orEmpty()
        _state.value = current.copy(
            selected = if (select) adoptable.map { it.installPath }.toSet() else emptySet(),
        )
    }

    fun adopt(context: Context) {
        val current = _state.value
        val chosen = current.result?.adoptable.orEmpty()
            .filter { it.installPath in current.selected }
        if (chosen.isEmpty() || current.isBusy) return
        val appContext = context.applicationContext
        _state.value = current.copy(phase = Phase.ADOPTING)
        job = scope.launch {
            val adopted = GameFileTakeover.adopt(appContext, chosen) { done, total, label ->
                _state.value = _state.value.copy(
                    progressDone = done,
                    progressTotal = total,
                    progressLabel = label,
                )
            }
            _state.value = _state.value.copy(
                phase = Phase.DONE,
                adoptedCount = adopted.size,
                selected = emptySet(),
            )
            Timber.i("Takeover: adopted %d of %d folder(s)", adopted.size, chosen.size)
        }
    }

    fun dismiss() {
        job?.cancel()
        _state.value = UiState()
    }

    /** Sources present in the current result, for the one-line summary the prompt shows. */
    fun summary(result: GameFileTakeover.ScanResult): String =
        result.adoptable
            .groupingBy { it.source }
            .eachCount()
            .entries
            .sortedBy { it.key.ordinal }
            .joinToString("、") { (source, count) -> "${gameSourceLabel(source)} $count" }

}

/** Store name as the user knows it; used by the dialog rows and the prompt summary. */
fun gameSourceLabel(source: GameSource): String = when (source) {
    GameSource.STEAM -> "Steam"
    GameSource.GOG -> "GOG"
    GameSource.EPIC -> "Epic"
    GameSource.AMAZON -> "Amazon"
    GameSource.CUSTOM_GAME -> "カスタム"
}
