package com.uniteduone.launcher

internal data class GalleryThumbnailPlan(val sourceWidth: Int, val sourceHeight: Int, val sampleSize: Int)

/** Bound both decoded axes to the cell's longest physical pixel edge; preserve source aspect metadata. */
internal fun galleryThumbnailPlan(width: Int, height: Int, targetWidth: Int, targetHeight: Int): GalleryThumbnailPlan {
    require(width in 1..GALLERY_MAX_DIMENSION && height in 1..GALLERY_MAX_DIMENSION) { "Cannot decode gallery thumbnail bounds" }
    require(targetWidth > 0 && targetHeight > 0)
    val longest = maxOf(width, height)
    val target = maxOf(targetWidth, targetHeight)
    var sample = 1
    // Native decoders honor powers of two; round dimensions up, including narrow/odd-sized images.
    while ((longest + sample - 1) / sample > target) sample *= 2
    return GalleryThumbnailPlan(width, height, sample)
}
