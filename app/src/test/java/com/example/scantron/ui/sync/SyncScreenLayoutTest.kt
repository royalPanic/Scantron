package com.example.scantron.ui.sync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * Guards the layout defect that made pull-import unreachable on a CK65, now against the Sync screen.
 *
 * The screen holds more content than the handheld's 480x800 display can show. In a plain `Column`
 * the overflowing children are laid out but never measured, so they are dropped entirely and are
 * absent from the semantics tree - the destructive half of the file-transfer path could not be
 * started at all, and no amount of fixing the network, the parser or the database would have helped
 * because the button simply was not there.
 *
 * These run at the real device size because that is the only size at which the overflow occurs: on a
 * tall emulator window the layout happens to fit, which is how the defect reached a handheld in the
 * first place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncScreenLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showScreen(): SyncViewModel {
        val app = ApplicationProvider.getApplicationContext<ScantronApplication>()
        val viewModel = SyncViewModel(
            application = app,
            repository = ContainerRepository(app.database.containerDao(), app.database.itemDao()),
        )

        compose.setContent {
            // Supplied explicitly for the same reason MainActivity does it: the composable under test
            // collects with collectAsStateWithLifecycle, which needs the local populated.
            CompositionLocalProvider(LocalLifecycleOwner provides ResumedOwner) {
                ScantronTheme { SyncScreen(viewModel = viewModel) }
            }
        }

        return viewModel
    }

    @Test
    fun every_sync_control_is_present_and_reachable_on_a_ck65() {
        showScreen()

        // assertExists, not assertIsDisplayed. Under Robolectric the host window has no real bounds,
        // so nothing can be proven "displayed"; what this regression actually needs is that every
        // control was composed and is reachable by scrolling, which is exactly what was broken -
        // before the fix the overflowing children were never measured and did not exist at all.
        compose.onNodeWithText("Pair and sync", substring = true).assertExists()
        compose.onNodeWithText("Desktop address", substring = true).assertExists()

        // The pairing code field and the discovery block sit below the fold on this screen size.
        // performScrollTo fails when the node is absent, which is precisely the regression guarded.
        compose.onNodeWithText("Pairing code", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Find desktops", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun the_file_transfer_fallback_is_collapsed_but_still_reachable() {
        showScreen()

        // The push/pull buttons are now the fallback path, so they start hidden - but they must still
        // be reachable, because a device that cannot pair has no other way to move data.
        compose.onNodeWithText("More options (file transfer)", substring = true)
            .performScrollTo()
            .performClick()

        compose.onNodeWithText("Send to desktop", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Get from desktop", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun the_destructive_confirm_is_not_on_the_live_sync_path() {
        showScreen()

        // Live sync merges; it never clears and replaces. A confirm dialog offering to wipe the
        // device must therefore not be reachable without opening the manual file-transfer section.
        compose.onNodeWithText("Replace everything", substring = true).assertDoesNotExist()
    }

    private object ResumedOwner : LifecycleOwner {
        override val lifecycle: Lifecycle =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
}
