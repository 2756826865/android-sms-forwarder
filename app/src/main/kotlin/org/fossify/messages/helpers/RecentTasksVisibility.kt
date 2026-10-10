package org.fossify.messages.helpers

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.fossify.messages.extensions.config

/** Controls only this application's task cards; never removes tasks or stops services. */
object RecentTasksVisibility {
    fun setHidden(context: Context, hidden: Boolean): Boolean {
        context.config.hideFromRecentTasks = hidden
        return apply(context)
    }

    fun apply(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val hidden = context.config.hideFromRecentTasks
            val tasks = manager.appTasks
            if (tasks.isEmpty()) return false // No task exists yet; the next Activity callback retries.
            var successful = true
            tasks.forEach { task ->
                try {
                    task.setExcludeFromRecents(hidden)
                    val info = task.taskInfo ?: throw IllegalStateException("Task no longer available")
                    val excluded = info.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0
                    if (excluded != hidden) {
                        successful = false
                        Log.w("RecentTasksVisibility", "System task visibility did not match the requested setting")
                    }
                } catch (_: RuntimeException) {
                    successful = false
                    Log.w("RecentTasksVisibility", "Could not apply task visibility")
                }
            }
            successful
        } catch (_: RuntimeException) {
            Log.w("RecentTasksVisibility", "Could not read application tasks")
            false
        }
    }

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { apply(activity) }
            override fun onActivityResumed(activity: Activity) { apply(activity) }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        apply(application)
    }
}
