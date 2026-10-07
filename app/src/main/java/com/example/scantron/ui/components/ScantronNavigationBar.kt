package com.example.scantron.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Height of [ScantronNavigationBar].
 *
 * Material3's `NavigationBar` is 80dp; this is 52dp. The difference is taken from the generous
 * padding around the icon and label, not from either of them.
 */
val ScantronNavBarHeight = 52.dp

/** One destination in a [ScantronNavigationBar]. */
data class ScantronNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String,
)

/**
 * A compact replacement for [androidx.compose.material3.NavigationBar].
 *
 * Semantics are kept identical to the Material3 bar - each destination is a `Role.Tab` in a
 * `selectableGroup`, so accessibility services and TalkBack announce it the same way - but the
 * fixed 80dp height is traded for [ScantronNavBarHeight]. The selected destination gets a pill
 * behind its icon and a bolder label, so the current screen is still obvious at a glance.
 */
@Composable
fun ScantronNavigationBar(
    items: List<ScantronNavItem>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Reserve the system navigation-bar strip *inside* the Surface so the bar's colour
                // runs all the way to the bottom edge while its icons and labels stay clear of the
                // system buttons. The Material3 NavigationBar did this for us; a custom bar has to
                // do it itself, and without it the labels sit under the system buttons.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(ScantronNavBarHeight)
                .selectableGroup(),
        ) {
            items.forEach { item ->
                ScantronNavBarItem(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onSelect(item.route) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ScantronNavBarItem(
    item: ScantronNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 26.dp)
                .background(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
