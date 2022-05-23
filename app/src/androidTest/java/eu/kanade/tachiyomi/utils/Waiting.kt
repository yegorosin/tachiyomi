package eu.kanade.tachiyomi.utils

import android.app.Activity
import android.app.Instrumentation
import android.util.Log
import android.view.View
import androidx.annotation.IdRes
import androidx.core.view.isVisible
import androidx.test.espresso.Espresso
import androidx.test.espresso.PerformException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.util.HumanReadables
import androidx.test.espresso.util.TreeIterables
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.Matcher
import java.util.concurrent.TimeoutException

const val timeoutDefault: Long = 5000

object Waiting {

    fun waitForActivity(instrumentation: Instrumentation, timeoutMillis: Long = timeoutDefault) {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + timeoutMillis
        do {
            waitMillis(instrumentation, 100)
            val activityInstance = getActivityInstance(instrumentation)
            if (activityInstance != null) {
                return
            }
        } while (System.currentTimeMillis() < endTime)
        throw PerformException.Builder()
            .withActionDescription("Activity not found")
            .withViewDescription("Initial")
            .withCause(TimeoutException())
            .build()
    }

    fun waitForView(
        @IdRes id: Int,
        waitTime: Long = timeoutDefault,
        checkVisibility: Boolean = false,
    ) {
        Espresso.onView(ViewMatchers.isRoot())
            .perform(waitForViewMatcher(ViewMatchers.withId(id), waitTime, checkVisibility))
    }

    // privates go below

    private fun waitMillis(instrumentation: Instrumentation, delay: Int) {
        synchronized(instrumentation) {
            try {
                (instrumentation as Object).wait(delay.toLong())
            } catch (e: InterruptedException) {
                Log.e("interruptedException", e.toString())
            }
        }
    }

    private fun getActivityInstance(instrumentation: Instrumentation): Activity? {
        val currentActivity = arrayOfNulls<Activity>(1)
        instrumentation.runOnMainSync {
            val resumedActivities: Collection<*> =
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            if (resumedActivities.iterator().hasNext()) {
                currentActivity[0] = resumedActivities.iterator().next() as Activity?
            }
        }
        return currentActivity[0]
    }

    private fun waitForViewMatcher(
        viewMatcher: Matcher<View>,
        millis: Long = timeoutDefault,
        checkVisibility: Boolean = false,
    ) = object : ViewAction {
        override fun getConstraints(): Matcher<View> {
            return ViewMatchers.isRoot()
        }

        override fun getDescription(): String {
            return "wait for a specific view with <$viewMatcher> during $millis millis."
        }

        override fun perform(uiController: UiController, view: View) {
            uiController.loopMainThreadUntilIdle()
            val startTime = System.currentTimeMillis()
            val endTime = startTime + millis
            do {
                for (child in TreeIterables.breadthFirstViewTraversal(view)) {
                    if (viewMatcher.matches(child) && (!checkVisibility || child.isVisible)) {
                        return
                    }
                }
                uiController.loopMainThreadForAtLeast(50)
            } while (System.currentTimeMillis() < endTime)
            throw PerformException.Builder()
                .withActionDescription(this.description)
                .withViewDescription(HumanReadables.describe(view))
                .withCause(TimeoutException())
                .build()
        }
    }

}
