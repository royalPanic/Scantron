package com.example.scantron.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height of [ScantronTopBar].
 *
 * Deliberately shorter than Material3's 64dp `TopAppBar`. The target device is a 4.0" 480x800
 * handheld, and the 16dp difference is 16dp of a screen it does not have.
 */
val ScantronTopBarHeight: Dp = 48.dp

/**
 * A compact replacement for [androidx.compose.material3.TopAppBar].
 *
 * The slots mirror the Material3 bar - title, navigationIcon, actions - so call sites read the
 * same way, but the height is fixed at [ScantronTopBarHeight] and this bar applies **no window
 * insets of its own**.
 *
 * That last point is the important one. The single host `Scaffold` in `MainActivity` is the only
 * thing that consumes the status-bar and navigation-bar insets; a bar here that also consumed them
 * would put the insets back a second time, which is exactly the duplication the old nested-Scaffold
 * arrangement produced (a doubled status-bar strip up top and a dead strip over the nav bar).
 */
@Composable
fun ScantronTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ScantronTopBarHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                title()
            }
            actions()
        }
    }
}
