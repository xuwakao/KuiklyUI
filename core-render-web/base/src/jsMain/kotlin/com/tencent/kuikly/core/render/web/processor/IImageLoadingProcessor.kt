package com.tencent.kuikly.core.render.web.processor

/**
 * Optional image loading, for a host that wants to produce the picture itself.
 *
 * The default [KuiklyProcessor.imageLoadingProcessor] is null, and with it null every
 * image behaves exactly as it always has: the resolved URL is handed to the `<img>` and
 * the browser does the decoding. A host registers an implementation to take that over —
 * to decode a large source off the main thread at display size, share one download
 * between views, or bound how much decoded memory it holds. Kuikly owns none of that; it
 * only forwards what the view knows and delivers what the host returns.
 *
 * Vendor-neutral by construction: no URL policy, cache, worker or budget lives here.
 * The frame is generic so the types a host passes through are not pinned by this file.
 *
 * No @JsExport, like [IImageProcessor]: these are implemented and registered by hosts
 * written in Kotlin, never called from JavaScript, and exporting would forbid `Any?`.
 */
interface IImageLoadingProcessor {
    /**
     * Start (or join) loading [resolvedSrc] for a view of [frameWidth] x [frameHeight] CSS
     * pixels, drawn with [resize] (`contain` / `cover` / `fill`).
     *
     * [logicalSrc] is the address the page asked for, before [IImageProcessor] resolved
     * it — callbacks report THAT, never anything the loader made up. [frame] carries the
     * unit the host measures in, through untouched.
     *
     * Returns a handle the view updates and releases; null means "not taken", and the
     * view then falls back to its own `<img>` path for this request.
     */
    fun load(
        logicalSrc: String,
        resolvedSrc: String,
        frameWidth: Double,
        frameHeight: Double,
        resize: String,
        frame: Any?,
        callbacks: ImageLoadingCallbacks,
    ): ImageLoadingHandle?
}

/**
 * What the loader reports back for one request. Every call is optional and late calls
 * after [ImageLoadingHandle.release] are ignored by the view.
 */
interface ImageLoadingCallbacks {
    /** A picture is ready to show at [displayUrl]; the view sets it on its `<img>`. */
    /** Clear the display before an offscreen lease is released. */
    fun onSuspend() {}

    fun onReady(displayUrl: String, logicalSrc: String, width: Int, height: Int)

    /**
     * The loader will not produce this picture; [reason] is the host's own word for it.
     * The view shows [resolvedSrc] on its `<img>` instead — the one fallback it has.
     */
    fun onBypass(reason: String, resolvedSrc: String)

    /**
     * The loader tried and could not. That is not the picture failing to load: the view
     * takes the same single fallback and shows the source itself. Only if THAT does not
     * load does the view report a failure, as it always has.
     */
    fun onFailed(reason: String)
}

/**
 * A live request. The view calls [updateSize] when its box changes and [release] exactly
 * once when the image is replaced or the view goes away.
 */
interface ImageLoadingHandle {
    fun updateSize(width: Double, height: Double)

    /** A fit change is a new rendering target even when the box is unchanged. */
    fun updateResize(resize: String) {}

    /**
     * The view has put the newest picture on screen. Until it says so, a loader that was
     * asked for a new size keeps the picture it replaced alive — a swap must never revoke
     * the copy still under the `<img>`. Optional: a loader with no use for it ignores it.
     */
    fun shown() {}

    fun release()
}
