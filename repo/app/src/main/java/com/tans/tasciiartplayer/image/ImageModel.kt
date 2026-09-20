package com.tans.tasciiartplayer.image

import com.tans.tuiutils.mediastore.MediaStoreImage

data class ImageModel(
    val mediaStoreImage: MediaStoreImage
) {

    val id: Long
        get() = mediaStoreImage.id

    val displayName: String
        get() = mediaStoreImage.displayName
}
