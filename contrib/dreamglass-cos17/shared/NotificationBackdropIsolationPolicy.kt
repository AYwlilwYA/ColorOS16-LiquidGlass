/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** Panel dimming belongs behind the notification content, never to its foreground scrims. */
internal object NotificationBackdropIsolationPolicy {
    enum class ScrimRole { BEHIND, NOTIFICATIONS, FRONT, UNKNOWN }

    /** Both native names and the audited Android resource names are accepted. A conflicting
     * pair is not evidence that a scrim is behind the cards, so it must not gain a black tint. */
    fun scrimRole(nativeName: String?, resourceEntryName: String? = null): ScrimRole {
        val native = role(nativeName)
        val resource = role(resourceEntryName)
        return when {
            native == ScrimRole.UNKNOWN -> resource
            resource == ScrimRole.UNKNOWN -> native
            native == resource -> native
            else -> ScrimRole.UNKNOWN
        }
    }

    fun mayDrawPanelMask(role: ScrimRole): Boolean = role == ScrimRole.BEHIND

    /** A missing SurfaceControl is not proof that the shade is hidden. If the shade may
     * contribute to the screenshot, require its successful exclusion before caching that
     * screenshot as a card's independent background. Reuse a safe input on false. */
    fun mayCaptureIndependentBackdrop(
        shadeMayContribute: Boolean,
        shadeLayerExcluded: Boolean,
    ): Boolean = !shadeMayContribute || shadeLayerExcluded

    private fun role(name: String?): ScrimRole = when (name) {
        "behind_scrim", "scrim_behind" -> ScrimRole.BEHIND
        "notifications_scrim", "scrim_notifications" -> ScrimRole.NOTIFICATIONS
        "front_scrim", "scrim_in_front", "scrim_front" -> ScrimRole.FRONT
        else -> ScrimRole.UNKNOWN
    }
}
