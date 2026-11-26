package app.portland.data

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Light, dark, or whatever the system says. */
enum class ThemeMode { System, Light, Dark }

/** The user's choices in Settings. Small enough to keep in one immutable value. */
data class UserPrefs(
    val theme: ThemeMode = ThemeMode.System,
    val haptics: Boolean = true,
    /** Accessibility: solid surfaces instead of Liquid Glass and window blur. Also the cheapest way to draw. */
    val reduceTransparency: Boolean = false,
    /** Columns in the pin grids, changed by pinching the grid. */
    val gridColumns: Int = GridColumns.Default,
)

/**
 * How many columns the pin grids can show: pinch in to see more at once, out to go back to bigger pins. Two is
 * the biggest because grid thumbnails are made for it (560 px on the short side); one column would show them
 * blurry.
 */
object GridColumns {
    const val Min = 2
    const val Max = 4
    const val Default = 2

    fun clamp(columns: Int): Int = columns.coerceIn(Min, Max)
}

/**
 * Settings that shape every screen, kept in SharedPreferences and mirrored as a [StateFlow] so the UI follows
 * a change at once. [warm] starts the file read on a background thread at process start, so the first
 * [state] call from the UI rarely waits on disk.
 */
object Prefs {
    private const val FILE = "portland_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_REDUCE_TRANSPARENCY = "reduce_transparency"
    private const val KEY_GRID_COLUMNS = "grid_columns"

    private val lock = Any()
    @Volatile private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(UserPrefs())

    /** Called off the main thread at startup: loads the file before the first frame needs it. */
    fun warm(context: Context) {
        load(context)
    }

    fun state(context: Context): StateFlow<UserPrefs> {
        load(context)
        return _state.asStateFlow()
    }

    fun setTheme(context: Context, mode: ThemeMode) {
        update(context) { it.copy(theme = mode) }
        applyNightMode(context, mode)
    }

    fun setHaptics(context: Context, on: Boolean) = update(context) { it.copy(haptics = on) }

    fun setReduceTransparency(context: Context, on: Boolean) = update(context) { it.copy(reduceTransparency = on) }

    fun setGridColumns(context: Context, columns: Int) = update(context) { it.copy(gridColumns = GridColumns.clamp(columns)) }

    /**
     * Android 12+ keeps an app's own night mode across launches and applies it before the first frame, so the
     * splash screen and window background match the chosen theme too. MainActivity handles the resulting
     * uiMode change itself (configChanges), so nothing is recreated. Android 11 only themes the Compose UI.
     */
    private fun applyNightMode(context: Context, mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ui = context.getSystemService(UiModeManager::class.java) ?: return
        ui.setApplicationNightMode(
            when (mode) {
                ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    private fun load(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(lock) {
            prefs?.let { return it }
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _state.value = UserPrefs(
                theme = p.getString(KEY_THEME, null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.System,
                haptics = p.getBoolean(KEY_HAPTICS, true),
                reduceTransparency = p.getBoolean(KEY_REDUCE_TRANSPARENCY, false),
                gridColumns = GridColumns.clamp(p.getInt(KEY_GRID_COLUMNS, GridColumns.Default)),
            )
            prefs = p // published last: a non-null prefs means the state above is loaded
            return p
        }
    }

    private fun update(context: Context, transform: (UserPrefs) -> UserPrefs) {
        val p = load(context)
        synchronized(lock) {
            val next = transform(_state.value)
            if (next == _state.value) return
            _state.value = next
            p.edit()
                .putString(KEY_THEME, next.theme.name)
                .putBoolean(KEY_HAPTICS, next.haptics)
                .putBoolean(KEY_REDUCE_TRANSPARENCY, next.reduceTransparency)
                .putInt(KEY_GRID_COLUMNS, next.gridColumns)
                .apply()
        }
    }
}
