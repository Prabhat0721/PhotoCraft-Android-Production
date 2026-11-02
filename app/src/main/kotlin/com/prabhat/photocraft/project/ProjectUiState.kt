package com.prabhat.photocraft.project

data class ProjectUiState(
    val category: String = "LIGHT",
    val qualityOrdinal: Int = 0,
    val sizeOrdinal: Int = 0,
    val colorSpaceOrdinal: Int = 0,
    val includeEditHistory: Boolean = true,
    val healingRadius: Float = 0.05f,
    val healingToolActive: Boolean = false,
    val curveChannelOrdinal: Int = 0,
    val maskOverlayVisible: Boolean = false,
    val maskActiveTool: String = "Brush"
)
