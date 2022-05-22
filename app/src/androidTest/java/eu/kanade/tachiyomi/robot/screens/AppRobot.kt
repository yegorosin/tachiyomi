package eu.kanade.tachiyomi.robot.screens

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.robot.screens.browse.BrowseRobot
import eu.kanade.tachiyomi.utils.Waiting.waitForActivity
import org.hamcrest.core.AllOf.allOf

fun app(func: AppRobot.() -> Unit) = AppRobot().apply {
    waitForActivity(InstrumentationRegistry.getInstrumentation())
    func()
}

class AppRobot {

    fun openBrowse(func: BrowseRobot.() -> Unit) = BrowseRobot().apply {
        onView(
            allOf(
                withId(R.id.navigation_bar_item_large_label_view),
                withText(R.string.browse),
            ),
        ).perform(click())
        waitForScreen()
        func()
    }

}
