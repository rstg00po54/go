package com.badukai.ui

import androidx.compose.ui.graphics.Color

/**
 * Minimalist color scheme for Badukai
 */
object BadukaiColors {
    // Board colors
    val BoardBackground = Color(0xFFDEB887) // Burlywood - traditional Go board
    val BoardLine = Color(0xFF2D2D2D)       // Dark gray lines
    val StarPoint = Color(0xFF2D2D2D)       // Star points (hoshi)
    
    // Stone colors
    val BlackStone = Color(0xFF1A1A1A)      // Almost black
    val BlackStoneHighlight = Color(0xFF3D3D3D)
    val WhiteStone = Color(0xFFF5F5F5)      // Off-white
    val WhiteStoneHighlight = Color(0xFFFFFFFF)
    val WhiteStoneBorder = Color(0xFF888888)
    
    // Last move marker
    val LastMoveMarker = Color(0xFFE53935) // Red accent
    
    // UI colors - minimal palette
    val Background = Color(0xFFFAFAFA)      // Almost white
    val Surface = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF1A1A1A)
    val TextSecondary = Color(0xFF757575)
    val Accent = Color(0xFF2D2D2D)          // Dark accent
    val AccentLight = Color(0xFFE0E0E0)     // Light gray
    
    // Button states
    val ButtonActive = Color(0xFF1A1A1A)
    val ButtonDisabled = Color(0xFFBDBDBD)
    
    // Status colors
    val ThinkingIndicator = Color(0xFF4CAF50) // Green for AI thinking
}
