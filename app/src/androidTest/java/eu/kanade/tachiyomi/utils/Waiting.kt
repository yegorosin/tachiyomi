package eu.kanade.tachiyomi.utils

import android.app.Activity
import android.app.Instrumentation
import android.util.Log
import androidx.test.espresso.PerformException
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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

    fun waitMillis(instrumentation: Instrumentation, delay: Int) {
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

}
