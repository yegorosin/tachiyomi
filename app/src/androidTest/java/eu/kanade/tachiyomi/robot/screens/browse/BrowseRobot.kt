package eu.kanade.tachiyomi.robot.screens.browse

import com.adevinta.android.barista.assertion.BaristaVisibilityAssertions.assertDisplayed
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.utils.Waiting.waitForView

class BrowseRobot {

    fun waitForScreen() = waitForView(R.id.pager)

    fun assertBrowseTabWasShown() {
        assertDisplayed(R.id.tabs)
    }

}
