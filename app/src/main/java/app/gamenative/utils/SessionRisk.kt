package app.gamenative.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A risk that shows up *while* a session runs, for the game screen to turn into a dialog.
 *
 * The pre-launch check can only see what is true before the session starts. Heat builds up and
 * memory runs out during play, and those are the two ways a session ends by itself: Android kills
 * the app, or the device shuts down under it. Both give a warning first - a thermal critical
 * status and `onTrimMemory(RUNNING_CRITICAL)` - and this carries that warning to the one screen
 * that is in front of the user at the time.
 */
object SessionRisk {
    enum class Kind { HEAT, MEMORY }

    data class Event(val kind: Kind, val detail: String)

    private val _event = MutableStateFlow<Event?>(null)

    /** The risk waiting to be shown, or null. The screen clears it when it is dismissed. */
    val event: StateFlow<Event?> = _event.asStateFlow()

    /** Publishes a risk. Repeated identical warnings while one is on screen are dropped. */
    fun raise(kind: Kind, detail: String) {
        val current = _event.value
        if (current != null && current.kind == kind) return
        _event.value = Event(kind, detail)
    }

    fun clear() {
        _event.value = null
    }
}
