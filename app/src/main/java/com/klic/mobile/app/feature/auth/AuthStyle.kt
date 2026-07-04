package com.klic.mobile.app.feature.auth

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One-off constants for the Login / Sign Up pages — values pulled straight from the design
 * mock that don't map onto the shared theme colors. Scoped to the auth flow only, mirrors
 * the iOS AuthStyle.
 */
object AuthStyle {
    /** Primary CTA red ("Login" / "Sign up" buttons) — distinct from the brand primary red. */
    val ctaRed = Color(0xFFD90429)

    /** Placeholder / hint text inside the capsule inputs — same value in both themes. */
    val fieldHint = Color(0xFFC7C7C7)

    /** Small secondary/link text (passkey row, "Create an account", "I already have an
     *  account", the privacy-policy agreement line) — same value in both themes. */
    val smallText = Color(0xFFB2B2B2)

    /** Radius of the big rounded "sheet" the content sits on. */
    val circleRadius: Dp = 546.dp

    /** Cap on the auth content column's width so form fields/buttons stay a readable line
     *  length instead of stretching edge-to-edge on tablets. */
    val contentMaxWidth: Dp = 500.dp

    /** Phone width the circle radius / background-art constants were tuned against;
     *  AuthScaffold scales those values by (canvas width / this). */
    val referenceWidth: Dp = 390.dp

    fun fieldFill(isDark: Boolean): Color = if (isDark) Color(0xFF202020) else Color(0xFFF2F2F2)

    /** Dark theme reuses the existing surface token (near-black, distinct from the pure-black
     *  background) rather than inventing a new shade. */
    fun circleFill(isDark: Boolean, surface: Color): Color = if (isDark) surface else Color.White

    fun titleColor(isDark: Boolean): Color = if (isDark) Color.White else Color(0xFF111111)
}
