package io.samcnpc.core.api

/** Current physical block facts; null environment in an adapter means unknown, never safe ground. */
data class NpcBlockEnvironment(
    val destroySpeed: Float,
    val fluidId: String?,
    val falling: Boolean,
    val replaceable: Boolean,
    val light: Int,
    val growth: NpcPlantGrowth? = null,
) {
    val unbreakable: Boolean get() = destroySpeed < 0.0F
}

enum class NpcPlantKind { CROP, BERRY_BUSH }

/** Engine age facts. Saplings have no harvest-age observation and are not counted as grown trees. */
data class NpcPlantGrowth(val kind: NpcPlantKind, val age: Int, val maxAge: Int)

/** A supplied carried stack at a supplied planting cell; no item lookup or target selection. */
data class NpcPlantingSiteQuery(val inventorySlot: Int, val position: NpcBlockPosition)

data class NpcPlantingSiteObservation(
    val position: NpcBlockPosition,
    val itemId: String,
    val plantBlockId: String,
    val soilBlockId: String,
    val targetIsAir: Boolean,
    val inFluid: Boolean,
    val canSurvive: Boolean,
    val light: Int,
)
