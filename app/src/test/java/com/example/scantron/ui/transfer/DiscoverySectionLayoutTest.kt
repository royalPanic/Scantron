package com.example.scantron.ui.transfer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.scantron.transfer.Peer
import com.example.scantron.ui.theme.ScantronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guards the crash that appeared as soon as discovery returned a peer.
 *
 * The Transfer screen puts this block inside its own `verticalScroll`. A `LazyColumn` there is
 * measured with unbounded height, which Compose rejects with an `IllegalStateException` - so the
 * screen was fine right up until the first desktop answered the probe, and then died. The block
 * therefore has to render its (short) peer list without a lazy list.
 *
 * Runs at the real handheld size and in the same nesting the screen uses, so a lazy list creeping
 * back in fails here rather than on a warehouse floor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoverySectionLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a_discovered_peer_renders_inside_the_screens_scrollable_column() {
        compose.setContent {
            ScantronTheme {
                // The exact nesting the screen uses: the peer list inside a vertically scrolling
                // column, which is what hands the list an unbounded height constraint.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DiscoverySection(
                        peers = listOf(Peer("ATLAS", "192.168.50.182", 8756, 0L)),
                        isSearching = false,
                        guidance = null,
                        onFindDesktops = {},
                        onPeerSelected = {},
                    )
                }
            }
        }

        compose.onNodeWithText("ATLAS").assertExists()
        compose.onNodeWithText("192.168.50.182:8756").assertExists()
    }
}
