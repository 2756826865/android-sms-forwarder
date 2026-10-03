package org.fossify.messages.activities

internal fun resolveThreadBottomInset(
    navigationBottom: Int,
    tappableBottom: Int,
    gestureBottom: Int,
    imeBottom: Int,
    imeVisible: Boolean,
): Int {
    val systemBottom = maxOf(navigationBottom, tappableBottom, gestureBottom)
    return if (imeVisible) maxOf(systemBottom, imeBottom) else systemBottom
}
