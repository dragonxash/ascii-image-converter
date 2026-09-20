package com.tans.tasciiartplayer.image

import android.app.Application
import androidx.annotation.WorkerThread
import com.tans.tasciiartplayer.AppLog
import com.tans.tasciiartplayer.appGlobalCoroutineScope
import com.tans.tuiutils.mediastore.queryImageFromMediaStore
import com.tans.tuiutils.state.CoroutineState
import kotlinx.coroutines.CoroutineScope

/**
 * The pictures of the device, used by the IMAGES tab of the main activity.
 */
object ImageManager : CoroutineState<ImageManagerState> by CoroutineState(ImageManagerState()),
    CoroutineScope by appGlobalCoroutineScope {

    private var application: Application? = null

    fun init(application: Application) {
        ImageManager.application = application
    }

    @WorkerThread
    suspend fun refreshMediaStoreImages() {
        val context = application ?: error("Application is null.")
        val mediaStoreImages = context.queryImageFromMediaStore()
        val images = mediaStoreImages.mapNotNull { mediaStoreImage ->
            val file = mediaStoreImage.file
            if (file == null || file.canRead()) {
                ImageModel(mediaStoreImage = mediaStoreImage)
            } else {
                null
            }
        }
        AppLog.d(TAG, "Refresh media store images: ${images.size}")
        updateState { it.copy(images = images) }
    }

    private const val TAG = "ImageManager"
}
