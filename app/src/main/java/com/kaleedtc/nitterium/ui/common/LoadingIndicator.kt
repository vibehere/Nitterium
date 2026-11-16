package com.kaleedtc.nitterium.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * @param fullscreen When true, fills the parent with a centered spinner (settings
 * placeholder). When false, a top progress bar that does **not** block taps on
 * content underneath—critical for Search/Feed/Profile while a page loads.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    fullscreen: Boolean = false
) {
    if (!isLoading) return
    if (fullscreen) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(contentAlignment = Alignment.Center) {
                LoadingIndicator(
                    modifier = Modifier.testTag("LoadingIndicator"),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        return
    }
    LinearProgressIndicator(
        modifier = modifier
            .fillMaxWidth()
            .testTag("LoadingIndicator"),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}
