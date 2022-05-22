package eu.kanade.tachiyomi.ui.functional.browse

import android.Manifest
import androidx.test.rule.ActivityTestRule
import androidx.test.rule.GrantPermissionRule
import eu.kanade.tachiyomi.robot.screens.app
import eu.kanade.tachiyomi.ui.main.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BrowseTest {

    val activityRule = ActivityTestRule(MainActivity::class.java, true, false)

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    @Before
    fun setUp() {
        activityRule.launchActivity(null)
    }

    @Test
    fun openArrival() {
        app {
            openBrowse {
                assertBrowseTabWasShown()
            }
        }
    }

}
