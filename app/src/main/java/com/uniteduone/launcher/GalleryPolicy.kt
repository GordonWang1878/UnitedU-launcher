package com.uniteduone.launcher

import java.net.URI

const val GALLERY_BASE_URL = "https://dl.uniteduone.com/gallery/"
const val GALLERY_MAX_CATALOG_BYTES = 2 * 1024 * 1024
const val GALLERY_MAX_ITEMS = 2000
const val GALLERY_MAX_THUMBNAIL_BYTES = 512L * 1024
const val GALLERY_MAX_ORIGINAL_BYTES = 32L * 1024 * 1024
const val GALLERY_MAX_DIMENSION = 8192

/** Restrict relative URLs before resolution; encoded separators and nested encodings are forbidden. */
fun galleryUrl(base: String, relativePath: String): Result<String> = runCatching {
    val root = URI(base)
    require(root.scheme == "https" && root.host != null && root.rawUserInfo == null)
    require(root.rawQuery == null && root.rawFragment == null && root.path.endsWith("/"))
    require(root.rawPath.split('/').none { it == "." || it == ".." })
    require(!root.rawPath.contains('%') && !root.rawPath.contains('\\'))
    require(relativePath.isNotEmpty() && !relativePath.startsWith('/'))
    // Published content-hash filenames need no escapes. Reject all percent encodings to
    // remove server-specific decoding, double decoding, and encoded control ambiguity.
    require(relativePath.none { it == '%' || it == '\\' || it.isWhitespace() || it.isISOControl() })
    val relative = URI(relativePath)
    require(!relative.isAbsolute && relative.rawAuthority == null)
    require(relative.rawQuery == null && relative.rawFragment == null)
    require(relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." })
    val resolved = root.resolve(relative)
    require(resolved.scheme == root.scheme && resolved.rawAuthority == root.rawAuthority)
    require(resolved.rawPath.startsWith(root.rawPath))
    resolved.toASCIIString()
}
