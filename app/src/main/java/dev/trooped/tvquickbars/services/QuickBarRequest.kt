package dev.trooped.tvquickbars.services

import dev.trooped.tvquickbars.data.QuickBar

/** Public activity intent contract. Exactly one selector is required. */
object QuickBarRequest {
    const val ACTION_OPEN = "dev.trooped.tvquickbars.OPEN_QUICKBAR"
    const val EXTRA_ID = "QUICKBAR_ID"
    const val EXTRA_NAME = "QUICKBAR_NAME"

    internal const val EXTRA_RESULT = "dev.trooped.tvquickbars.LAUNCH_RESULT"

    fun resolve(bars: List<QuickBar>, id: String?, name: String?): QuickBar? {
        if ((id != null) == (name != null)) return null
        if ((id != null && id.isBlank()) || (name != null && name.isBlank())) return null
        // Reject duplicate names rather than opening an arbitrary bar.
        return bars.filter { if (id != null) it.id == id else it.name == name }
            .singleOrNull()?.takeIf { it.isEnabled }
    }
}
