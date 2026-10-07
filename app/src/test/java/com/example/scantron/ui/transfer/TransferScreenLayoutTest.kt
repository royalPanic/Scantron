package com.example.scantron.ui.transfer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.example.scantron.ScantronApplication
import com.example.scantron.data.ContainerRepository
import com.example.scantron.ui.theme.ScantronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guards the layout defect that made pull-import unreachable on a CK65.
 *
 * The Transfer screen held more content than the handheld's 480x800 screen can show. In a plain
 * `Column` the overflowing children are laid out but never measured, so they are dropped
 * entirely: "Get from desktop" was absent from the semantics tree, and the destructive half of
 * the feature could not be started at all. No amount of fixing the network, the parser or the
 * database would have helped, because the button simply was not there.
 *
 * These run at the real device size because that is the only size at which the overflow occurs -
 * on a tall emulator window the old layout happened to fit, which is how it reached a handheld
 * in the first place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransferScreenLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun every_transfer_control_is_present_and_reachable_on_a_ck65() {
        val app = ApplicationProvider.getApplicationContext<ScantronApplication>()

        compose.setContent {
            // Supplied explicitly for the same reason MainActivity does it: the composable under
            // test collects with collectAsStateWithLifecycle, which needs the local populated.
            CompositionLocalProvider(LocalLifecycleOwner provides ResumedOwner) {
                ScantronTheme {
                    TransferScreen(
                        viewModel = TransferViewModel(
                            application = app,
                            repository = ContainerRepository(
                                app.database.containerDao(),
                                app.database.itemDao(),
                            ),
                        ),
                    )
                }
            }
        }

        // assertExists, not assertIsDisplayed. Under Robolectric the host window has no real bounds,
        // so nothing can be proven "displayed"; what this regression actually needs is that every
                // control was composed and is reachable by scrolling, which is exactly what was broken:
                // before the fix the overflowing children were never measured and did not exist at all.
                // Substring matching throughout: the button labels carry decorative leading spaces
                // ("  Check desktop") for icon spacing, which onNodeWithText treats as significant.
                compose.onNodeWithText("Check desktop", substring = true).assertExists()
                compose.onNodeWithText("Send to desktop", substring = true).assertExists()

                // The button that was clipped away, and the discovery block below it. performScrollTo
                // fails when the node is absent, which is precisely the regression being guarded.
                compose.onNodeWithText("Get from desktop", substring = true).performScrollTo().assertExists()
                compose.onNodeWithText("Find desktops", substring = true).performScrollTo().assertExists()
    }

    private object ResumedOwner : LifecycleOwner {
        override val lifecycle: Lifecycle =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
}