@file:Suppress("DEPRECATION")

package com.magic3d.gcalsearchadd

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.signin.GoogleSignIn

/** מסנכרן את תור הפעולות ברגע שחיבור לרשת זמין. */
class OfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val account = GoogleSignIn.getLastSignedInAccount(applicationContext)?.account
            ?: return Result.success()
        return try {
            CalendarRepository(applicationContext, account).syncPendingActions()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "eventspot_offline_sync"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
