package com.tencent.kuikly.compose.coil3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.coil3.AsyncImagePainter.State
import com.tencent.kuikly.compose.ui.graphics.painter.Painter
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.KuiklyPainter
import kotlinx.coroutines.flow.StateFlow

/**
 * Return an [AsyncImagePainter] that executes an [ImageRequest] asynchronously and renders the result.
 *
 * @param model Either an [ImageRequest] or the [ImageRequest.data] value.
 * @param placeholder A [Painter] that is displayed while the image is loading.
 * @param error A [Painter] that is displayed when the image request is unsuccessful.
 * @param fallback A [Painter] that is displayed when the request's [ImageRequest.data] is null.
 * @param onLoading Called when the image request begins loading.
 * @param onSuccess Called when the image request completes successfully.
 * @param onError Called when the image request completes unsuccessfully.
 * @param contentScale Used to determine the aspect ratio scaling to be used if the canvas bounds
 *  are a different size from the intrinsic size of the image loaded by [model]. This should be set
 *  to the same value that's passed to [Image].
 * @param filterQuality Sampling algorithm applied to a bitmap when it is scaled and drawn into the
 *  destination.
 */
@Composable
@NonRestartableComposable
fun rememberAsyncImagePainter(
    model: String?,
    placeholder: Painter? = null,
    error: Painter? = null,
    fallback: Painter? = error,
    onLoading: ((State.Loading) -> Unit)? = null,
    onSuccess: ((State.Success) -> Unit)? = null,
    onError: ((State.Error) -> Unit)? = null,
    // contentScale: ContentScale = ContentScale.Fit,
    // filterQuality: FilterQuality = DefaultFilterQuality,
) = rememberAsyncImagePainterInternal(
    src = model,
    placeholder = placeholder,
    error = error,
    fallback = fallback,
    onState = onStateOf(onLoading, onSuccess, onError),
)

/**
 * Return an [AsyncImagePainter] that executes an [ImageRequest] asynchronously and renders the result.
 *
 * @param model Either an [ImageRequest] or the [ImageRequest.data] value.
 * @param transform A callback to transform a new [State] before it's applied to the
 *  [AsyncImagePainter]. Typically this is used to overwrite the state's [Painter].
 * @param onState Called when the state of this painter changes.
 * @param contentScale Used to determine the aspect ratio scaling to be used if the canvas bounds
 *  are a different size from the intrinsic size of the image loaded by [model]. This should be set
 *  to the same value that's passed to [Image].
 * @param filterQuality Sampling algorithm applied to a bitmap when it is scaled and drawn into the
 *  destination.
 */
@Composable
@NonRestartableComposable
fun rememberAsyncImagePainter(
    model: String?,
    // transform: (State) -> State = DefaultTransform,
    onState: ((State) -> Unit)? = null,
    // contentScale: ContentScale = ContentScale.Fit,
    // filterQuality: FilterQuality = DefaultFilterQuality,
) = rememberAsyncImagePainterInternal(
    src = model,
    placeholder = null,
    error = null,
    fallback = null,
    onState = onState,
)

/**
 * Ronaq fork (CHANGES.md §45): whether a painter below may fetch a REMOTE image now.
 *
 * True by default, so nothing changes where nobody provides it. A pager page composed off screen
 * provides false: its layout is built, but its `http(s)` images are not requested until the page
 * becomes current (the reference's `BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT`). Bundled and inline
 * sources keep drawing. When the local turns true the painter is re-created with its source and
 * the load starts.
 */
val LocalAsyncImageLoading: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }

/**
 * The source a painter is given: [src] itself, or null for a remote source while [loading] is
 * false (Ronaq fork, CHANGES.md §45). A null source draws the painter's fallback, as it always has.
 */
internal fun deferredImageSource(src: String?, loading: Boolean): String? =
    if (!loading && src != null && (src.startsWith("http://") || src.startsWith("https://"))) null else src

@Composable
private fun rememberAsyncImagePainterInternal(
    src: String?,
    placeholder: Painter?,
    error: Painter?,
    fallback: Painter?,
    onState: ((State) -> Unit)?,
): AsyncImagePainter {
    val context = LocalActivity.current
    val source = deferredImageSource(src, LocalAsyncImageLoading.current)
    return remember(source, placeholder) {
        KuiklyPainter(
            context,
            src = source,
            placeHolder = placeholder,
            error = error,
            fallback = fallback,
        )
    }.also {
        it.onState = onState
    }
}

@Stable
private fun onStateOf(
    onLoading: ((State.Loading) -> Unit)?,
    onSuccess: ((State.Success) -> Unit)?,
    onError: ((State.Error) -> Unit)?,
): ((State) -> Unit)? {
    return if (onLoading != null || onSuccess != null || onError != null) {
        { state ->
            when (state) {
                is State.Loading -> onLoading?.invoke(state)
                is State.Success -> onSuccess?.invoke(state)
                is State.Error -> onError?.invoke(state)
                is State.Empty -> {}
            }
        }
    } else {
        null
    }
}

abstract class AsyncImagePainter internal constructor() : Painter() {

    abstract val state: StateFlow<State>

    abstract fun prefetch()

    abstract fun restart()

    /**
     * The current state of the [AsyncImagePainter].
     */
    sealed interface State {

        /** The current painter being drawn by [AsyncImagePainter]. */
        val painter: Painter?

        /** The request has not been started. */
        data object Empty : State {
            override val painter: Painter? get() = null
        }

        /** The request is in-progress. */
        data class Loading(
            override val painter: Painter?,
        ) : State

        /** The request was successful. */
        data class Success(
            override val painter: Painter,
            // val result: Unit,
        ) : State

        /** The request failed due to [ErrorResult.throwable]. */
        data class Error(
            override val painter: Painter?,
            // val result: Unit,
        ) : State
    }
}