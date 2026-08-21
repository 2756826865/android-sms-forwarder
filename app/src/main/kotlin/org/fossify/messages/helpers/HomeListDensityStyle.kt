package org.fossify.messages.helpers

/**
 * Maps home list density setting (4 / 6 / 8 / 10 visible rows) to row metrics.
 */
object HomeListDensityStyle {
    data class Metrics(
        val avatarDp: Int,
        val verticalPadDp: Int,
        val titleSp: Float,
        val bodySp: Float,
        val dateSp: Float,
        val avatarIconPadDp: Int,
    )

    fun forDensity(density: Int): Metrics = when (density) {
        HOME_LIST_DENSITY_4 -> Metrics(
            avatarDp = 60,
            verticalPadDp = 18,
            titleSp = 21f,
            bodySp = 17f,
            dateSp = 13f,
            avatarIconPadDp = 14,
        )
        HOME_LIST_DENSITY_6 -> Metrics(
            avatarDp = 52,
            verticalPadDp = 14,
            titleSp = 20f,
            bodySp = 16f,
            dateSp = 12f,
            avatarIconPadDp = 12,
        )
        HOME_LIST_DENSITY_10 -> Metrics(
            avatarDp = 40,
            verticalPadDp = 8,
            titleSp = 18f,
            bodySp = 15f,
            dateSp = 12f,
            avatarIconPadDp = 10,
        )
        else -> Metrics(
            avatarDp = 44,
            verticalPadDp = 10,
            titleSp = 18f,
            bodySp = 15f,
            dateSp = 12f,
            avatarIconPadDp = 11,
        )
    }
}
