package com.freelancehub.app.update

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

class PlayUpdateManager(private val activity: Activity) {
    private val manager = AppUpdateManagerFactory.create(activity)
    private var checkInFlight = false
    private var promptedThisSession = false

    fun checkForUpdate() {
        if (checkInFlight || promptedThisSession) return
        checkInFlight = true

        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                checkInFlight = false

                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                ) {
                    promptedThisSession = true
                    manager.startUpdateFlowForResult(
                        info,
                        AppUpdateType.IMMEDIATE,
                        activity,
                        UPDATE_REQUEST_CODE
                    )
                }
            }
            .addOnFailureListener {
                checkInFlight = false
            }
    }

    fun resumeIfInterrupted() {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.updateAvailability() ==
                    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                ) {
                    manager.startUpdateFlowForResult(
                        info,
                        AppUpdateType.IMMEDIATE,
                        activity,
                        UPDATE_REQUEST_CODE
                    )
                }
            }
    }

    companion object {
        const val UPDATE_REQUEST_CODE = 1701
    }
}
