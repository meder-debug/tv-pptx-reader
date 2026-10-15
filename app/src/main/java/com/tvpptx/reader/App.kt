package com.tvpptx.reader

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.io.File
import java.lang.ref.WeakReference

class App : Application(), Application.ActivityLifecycleCallbacks {
    private var resumed: WeakReference<Activity>? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        Thread { UploadServer.start(this) }.start()
    }

    /** A phone finished sending [file]: refresh the list and, if we're on screen, show it right away. */
    fun onFileReceived(file: File) {
        UploadServer.listener?.onReceived(file)
        val activity = resumed?.get() ?: return
        activity.startActivity(ViewerActivity.intent(activity, file))
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
