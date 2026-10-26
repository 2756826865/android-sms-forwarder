package org.fossify.messages.ui.compose.ksu

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance

@Composable
fun isInDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
