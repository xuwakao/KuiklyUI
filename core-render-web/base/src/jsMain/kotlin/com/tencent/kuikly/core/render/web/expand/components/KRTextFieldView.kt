package com.tencent.kuikly.core.render.web.expand.components

import com.tencent.kuikly.core.render.web.const.KREventConst
import com.tencent.kuikly.core.render.web.const.KRInputTypeConst
import com.tencent.kuikly.core.render.web.const.KRKeyboardConst
import com.tencent.kuikly.core.render.web.const.KRParamConst
import com.tencent.kuikly.core.render.web.const.KRStyleConst
import com.tencent.kuikly.core.render.web.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.web.ktx.Frame
import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback
import com.tencent.kuikly.core.render.web.ktx.kuiklyDocument
import com.tencent.kuikly.core.render.web.ktx.setPlaceholderColor
import com.tencent.kuikly.core.render.web.ktx.setSelectionColor
import com.tencent.kuikly.core.render.web.ktx.toJSONObjectSafely
import com.tencent.kuikly.core.render.web.ktx.toNumberFloat
import com.tencent.kuikly.core.render.web.ktx.toPxF
import com.tencent.kuikly.core.render.web.ktx.toRgbColor
import com.tencent.kuikly.core.render.web.runtime.dom.element.ElementType
import com.tencent.kuikly.core.render.web.scheduler.KuiklyRenderCoreContextScheduler
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.InputEvent
import org.w3c.dom.events.KeyboardEvent

/**
 * KRTextFieldView, corresponding to Kuikly's Input
 */
class KRTextFieldView : IKuiklyRenderViewExport {
    // text value changed event callback
    private var textDidChangedEventCallback: KuiklyRenderCallback? = null

    // Full text-input-state change callback (text plus selection), the event the
    // Kotlin side prefers over textDidChange when both are registered
    private var textInputStateChangeEventCallback: KuiklyRenderCallback? = null

    // A prop update replaces a callback but must not add another DOM listener. Kuikly
    // re-applies an event prop whenever its handler lambda recomposes; N listeners would
    // deliver each keystroke N times because every listener reads the same latest
    // callback field. One flag per DOM listener below.
    private var inputListenerInstalled = false
    private var focusListenerInstalled = false
    private var blurListenerInstalled = false
    private var returnListenerInstalled = false
    private var keyboardListenerInstalled = false
    private var lengthLimitListenersInstalled = false

    // Count of user edits: +1 per DOM `input` event, never for programmatic writes.
    // Every reported state is stamped with it, every pushed state carries the last
    // value the pusher saw, and a push composed before an edit already applied here
    // is refused instead of rolling that edit back (see setTextInputState).
    private var editGeneration = 0

    // Focus event callback
    private var focusedEventCallback: KuiklyRenderCallback? = null

    // Blur event callback
    private var blurEventCallback: KuiklyRenderCallback? = null

    // Return key click callback
    private var clickReturnEventCallback: KuiklyRenderCallback? = null

    // Text length limit exceeded callback
    private var textLengthLimitEventCallback: KuiklyRenderCallback? = null

    // Keyboard height change callback (iOS/Android native parity)
    private var keyboardHeightChangeCallback: KuiklyRenderCallback? = null

    // Whether a VisualViewport-based keyboard listener has been bound (H5 only).
    private var keyboardTrackingBound = false
    // Last reported keyboard height, used to de-dup resize events.
    private var lastKeyboardHeight: Float = 0f

    // Track current fontSize for minimum height fallback (default 15px as per Kuikly convention)
    private var currentFontSize: Float = DEFAULT_FONT_SIZE

    // Input element
    private val input = kuiklyDocument.createElement(ElementType.INPUT).apply {
        val style = this.unsafeCast<HTMLTextAreaElement>().style
        style.border = CSS_BORDER_NONE
        style.backgroundColor = CSS_BG_TRANSPARENT
    }
    // Current text length
    private var currentLength = 0

    override val ele: HTMLInputElement
        get() = input.unsafeCast<HTMLInputElement>()

    /**
     * Adapt differences between web and kotlin
     */
    override fun setProp(propKey: String, propValue: Any): Boolean {
        return when (propKey) {
            SRC -> {
                ele.value = propValue.unsafeCast<String>()
                // Notify content change
                notifyTextValueChanged(ele.value)
                true
            }

            TEXT_DID_CHANGE -> {
                // Text change callback event, web needs adaptation, initiate notification
                textDidChangedEventCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                installInputListenerOnce()
                true
            }

            TEXT_INPUT_STATE_CHANGE -> {
                // Full editing-state event, reporting the REAL selection alongside the
                // text. Compose's CoreTextField needs it: its textDidChange fallback
                // carries no selection, so once setTextInputState exists (see call()),
                // a keystroke's echo would sync a zero selection back to this input and
                // pin the caret to the start — every typed string arrived reversed.
                // With this event the Kotlin side sees the same editing state the DOM
                // holds and skips the resync, exactly as it does against the Android
                // renderer, whose TextWatcher raises the same event.
                textInputStateChangeEventCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                installInputListenerOnce()
                true
            }

            PLACEHOLDER -> {
                ele.placeholder = propValue.unsafeCast<String>()
                true
            }

            PLACEHOLDER_COLOR -> {
                val rgbColor = propValue.unsafeCast<String>().toRgbColor()
                // On mini-program, `ele` is a MiniInputElement which advertises
                // `__krSupportsPlaceholderColor = true` and hosts a `placeholderColor` setter
                // that forwards the value to WX native `<input>`'s `placeholder-style`
                // attribute. On H5 / real browsers, `ele` is a plain HTMLInputElement with
                // no such marker, so we keep the original `::placeholder` pseudo-class
                // injection unchanged to preserve H5 behavior.
                if (jsTypeOf(ele.asDynamic().__krSupportsPlaceholderColor) != "undefined") {
                    ele.asDynamic().placeholderColor = rgbColor
                } else {
                    // set through pseudo-class
                    setPlaceholderColor(ele, rgbColor)
                }
                true
            }

            TEXT_ALIGN -> {
                ele.style.textAlign = propValue.unsafeCast<String>()
                true
            }

            FONT_WEIGHT -> {
                ele.style.fontWeight = propValue.unsafeCast<String>()
                true
            }

            FONT_SIZE -> {
                currentFontSize = propValue.toNumberFloat()
                ele.style.fontSize = currentFontSize.toPxF()
                true
            }

            MAX_TEXT_LENGTH -> {
                // Compose sends -1 to clear the limit (CoreTextField), and Android treats any
                // non-positive value as "unlimited". The DOM refuses a negative maxLength with
                // IndexSizeError, which aborts the whole render batch and leaves a blank page,
                // so drop the attribute instead. Absent maxlength reads back as -1, keeping the
                // `ele.maxLength > 0` guards below correct.
                val limit = propValue.unsafeCast<Int>()
                if (limit > 0) ele.maxLength = limit else ele.removeAttribute("maxlength")
                true
            }

            EDIT_ABLE -> {
                ele.readOnly = propValue.unsafeCast<Int>() != 1
                true
            }

            AUTO_FOCUS -> {
                ele.autofocus = propValue.unsafeCast<Int>() == 1
                true
            }

            TINT_COLOR -> {
                ele.style.asDynamic().caretColor = propValue.unsafeCast<String>().toRgbColor()
                true
            }

            SELECTION_COLOR -> {
                setSelectionColor(ele, propValue.unsafeCast<String>().toRgbColor())
                true
            }

            KEYBOARD_TYPE -> {
                setKeyBoardType(propValue.unsafeCast<String>())
                true
            }

            RETURN_KEY_TYPE -> {
                // set return key type
                setReturnKeyType(propValue.unsafeCast<String>())
                true
            }

            INPUT_FOCUS -> {
                // Focus event callback; the DOM listener is installed once and later
                // prop applications replace only the callback (see installInputListenerOnce)
                focusedEventCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                if (!focusListenerInstalled) {
                    focusListenerInstalled = true
                    ele.addEventListener(EVENT_FOCUS, {
                        val map = mutableMapOf<String, Any>()
                        map[MAP_KEY_TEXT] = ele.value
                        // Notify kotlin side
                        focusedEventCallback?.invoke(map)
                    })
                }
                true
            }

            INPUT_BLUR -> {
                // Blur event callback; listener installed once, callback replaceable
                blurEventCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                if (!blurListenerInstalled) {
                    blurListenerInstalled = true
                    ele.addEventListener(EVENT_BLUR, {
                        val map = mutableMapOf<String, Any>()
                        map[MAP_KEY_TEXT] = ele.value
                        // Notify kotlin side
                        blurEventCallback?.invoke(map)
                    })
                }
                true
            }

            INPUT_RETURN -> {
                // Return key callback; listener installed once, callback replaceable —
                // an accumulated listener here means one Enter press submits N times
                clickReturnEventCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                if (!returnListenerInstalled) {
                    returnListenerInstalled = true
                    ele.addEventListener(EVENT_KEYDOWN, {
                        val event = it.unsafeCast<KeyboardEvent>()
                        // Keyboard event
                        if (event.key === KEY_ENTER || event.keyCode == ENTER_KEY_CODE) {
                            val map = mutableMapOf<String, Any>()
                            map[MAP_KEY_TEXT] = ele.value
                            // Return key clicked
                            clickReturnEventCallback?.invoke(map)
                        }
                    })
                }
                true
            }

            KEYBOARD_HEIGHT_CHANGE -> {
                keyboardHeightChangeCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                // Listen for a unified DOM-level `keyboardheightchange` event on this element.
                // - On mini-program, MiniInputElement translates WX native `bindkeyboardheightchange`
                //   into this DOM event and already provides `{height, duration, curve}` in detail.
                // - On H5 browsers, there is no native keyboardheightchange DOM event on <input>,
                //   so we additionally bind a VisualViewport-based tracker (see below) that
                //   dispatches the same DOM event on this element.
                // Listener installed once, callback replaceable (see installInputListenerOnce).
                if (!keyboardListenerInstalled) {
                    keyboardListenerInstalled = true
                    ele.addEventListener(EVENT_KEYBOARD_HEIGHT_CHANGE, {
                        val detail = it.asDynamic().detail
                        val height = (detail?.height ?: 0).unsafeCast<Number>().toFloat()
                        val duration = (detail?.duration ?: 0).unsafeCast<Number>().toFloat()
                        val curve = (detail?.curve ?: 0).unsafeCast<Number>().toInt()
                        val map = mutableMapOf<String, Any>()
                        map[MAP_KEY_HEIGHT] = height
                        map[MAP_KEY_DURATION] = duration
                        map[MAP_KEY_CURVE] = curve
                        keyboardHeightChangeCallback?.invoke(map)
                    })
                }
                bindKeyboardHeightTrackingIfNeeded()
                true
            }

            TEXT_LENGTH_BEYOND_LIMIT -> {
                textLengthLimitEventCallback = propValue.unsafeCast<KuiklyRenderCallback>()
                // Listeners installed once, callback replaceable (see installInputListenerOnce)
                if (lengthLimitListenersInstalled) return true
                lengthLimitListenersInstalled = true
                // Whether it is in text combination state
                var isComposing = false

                ele.addEventListener(EVENT_COMPOSITION_START, { isComposing = true })
                ele.addEventListener(EVENT_COMPOSITION_END, {
                    currentLength = ele.value.length + 1
                    isComposing = false
                    if (ele.maxLength > 0 && currentLength > ele.maxLength) {
                        val map = mutableMapOf<String, Any>()
                        map[MAP_KEY_TEXT] = ele.value
                        textLengthLimitEventCallback?.invoke(map)
                        ele.value = ele.value.substring(0, ele.maxLength)
                    }
                })
                ele.addEventListener(EVENT_BEFORE_INPUT, {
                    // Input text exceeds maximum limit, callback notification
                    val event = it.unsafeCast<InputEvent>()
                    if (event.isComposing || isComposing) return@addEventListener
                    // 针对safari浏览器中，若输入超过最大长度时，inserted为空的情况，采用手动计数方式
                    if (event.asDynamic().inputType == INPUT_TYPE_INSERT_TEXT) {
                        currentLength = ele.value.length + 1
                    } else if (event.asDynamic().inputType == INPUT_TYPE_DELETE_BACKWARD) {
                        currentLength = ele.value.length - 1
                    }
                    val inserted = it.unsafeCast<InputEvent>().data ?: ""
                    val newLength = ele.value.length + inserted.length
                    if (ele.maxLength > 0 && (newLength > ele.maxLength || currentLength > ele.maxLength)) {
                        // Cancel the default behavior of this input event
                        it.preventDefault()
                        val map = mutableMapOf<String, Any>()
                        map[MAP_KEY_TEXT] = ele.value
                        textLengthLimitEventCallback?.invoke(map)
                    }
                })
                true
            }

            else -> super.setProp(propKey, propValue)
        }
    }

    /**
     * One `input` listener serves both text events, in the Android renderer's order:
     * its single TextWatcher (installed once, `observeTextWatcher`) invokes
     * textInputStateChange first, then textDidChange, for every edit. Two properties of
     * that arrangement matter, and this view used to break both:
     *
     * - Install-once. There was one listener per PROP APPLICATION, and Kuikly re-applies
     *   an event prop whenever its handler lambda recomposes, so textDidChange gained a
     *   DOM listener per recomposition and a keystroke was reported N times. Compose's
     *   CoreTextField suppresses exactly ONE textDidChange per textInputStateChange
     *   (`pendingTextInputStateText` is one-shot); every duplicate walked its fallback
     *   path, which fabricates a selection because textDidChange carries none.
     * - Order. The full-state event must reach the Kotlin side before the text-only
     *   fallback. Fired the other way round on a cold field, the fallback reports text
     *   the sync mirror has not seen with a fabricated zero selection, and the next
     *   programmatic sync faithfully pins the caret to 0 — measured on a freshly
     *   mounted sign-in field: "0123456789" typed, "1234567890" stored, the first
     *   keystroke displaced to the end.
     */
    private fun installInputListenerOnce() {
        if (inputListenerInstalled) return
        inputListenerInstalled = true
        ele.addEventListener(EVENT_INPUT, {
            editGeneration++
            textInputStateChangeEventCallback?.invoke(currentTextInputStateMap())
            notifyTextValueChanged(ele.value)
        })
    }

    /**
     * Override onFrameChange to enforce a minimum height for the input element.
     * When the layout engine calculates height as 0 (due to alignItemsCenter + no explicit height
     * + no measureFunction on InputView), apply a fontSize-based minimum height so the input
     * remains clickable and focusable on H5.
     */
    override fun onFrameChange(frame: Frame) {
        if (frame.height <= 0.0) {
            // Use fontSize * 1.5 as a reasonable minimum height (matches native input intrinsic height)
            val minHeight = (currentFontSize * MIN_HEIGHT_FONT_SIZE_MULTIPLIER)
            ele.unsafeCast<HTMLInputElement>().style.minHeight = minHeight.toPxF()
        } else {
            // Clear any previously set minHeight when layout provides a valid height
            ele.unsafeCast<HTMLInputElement>().style.minHeight = ""
        }
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            SET_TEXT -> {
                // Set input value
                val text = params ?: return null
                ele.value = text
                // Notify content change
                notifyTextValueChanged(ele.value)
            }

            FOCUS -> {
                // Input gets focus, considering UI element insertion event issues, need to schedule execution
                KuiklyRenderCoreContextScheduler.scheduleTask {
                    ele.focus()
                }
            }

            BLUR -> {
                // Input loses focus, considering UI element insertion event issues, need to schedule execution
                KuiklyRenderCoreContextScheduler.scheduleTask {
                    ele.blur()
                }
            }

            GET_CURSOR_INDEX -> {
                // get input cursor index
                KuiklyRenderCoreContextScheduler.scheduleTask {
                    callback?.invoke(mapOf(
                        MAP_KEY_CURSOR_INDEX to ele.selectionStart
                    ))
                }
            }

            SET_CURSOR_INDEX -> {
                val index = params?.toIntOrNull() ?: return null
                // set input cursor index, focus first
                ele.focus()
                ele.setSelectionRange(index, index)
            }

            SET_TEXT_INPUT_STATE -> setTextInputState(params)

            GET_TEXT_INPUT_STATE -> getTextInputState(callback)

            else -> super.call(method, params, callback)
        }
    }

    /**
     * Atomically apply text and selection pushed from the Kotlin side.
     *
     * This is the only road a programmatic value takes to an existing input:
     * Compose's CoreTextField delivers every non-typed value change through this
     * method, after priming the "text" prop cache (so a business-side
     * setProp("text", ...) of the same value is deduplicated and cannot repair a
     * miss). Android and iOS implement it; without this handler the web input
     * reflected typed input only, and a prefilled or programmatically cleared
     * field kept stale text. Mirrors the Android renderer's setTextInputState:
     * apply the text, clamp and apply the selection, then notify the text-change
     * callback so the Kotlin side's bookkeeping follows the value it just pushed.
     */
    private fun setTextInputState(params: String?) {
        val state = params.toJSONObjectSafely()
        // Refuse a push composed before an edit this input has already applied. The
        // Kotlin side builds a push from the app's value, which on JS can lag one
        // scheduler hop behind the events already reported; unguarded, that push
        // arrived one frame after a keystroke and rolled the field back to the
        // pre-keystroke text (or pinned the caret into it), displacing what was
        // typed. Each reported state is stamped with [editGeneration]; a push whose
        // echoed generation is older than the current one was, by construction, built
        // without knowledge of the newest edits. Reporting the CURRENT state back
        // (below) lets the Kotlin mirror converge, so a deliberate programmatic
        // write that lost the race — a composer cleared on Send mid-typing — is
        // re-pushed by the next sync with a fresh generation and applied then.
        // A push with no generation (a non-Compose caller) applies unconditionally.
        val seenGeneration = state.optInt(KEY_GENERATION, NO_GENERATION)
        if (seenGeneration != NO_GENERATION && seenGeneration < editGeneration) {
            textInputStateChangeEventCallback?.invoke(currentTextInputStateMap())
            return
        }
        var text = state.optString(MAP_KEY_TEXT)
        // A programmatic assignment bypasses the DOM's own maxlength enforcement,
        // so apply the truncation typed input would have received (the Android
        // renderer truncates programmatic text the same way).
        if (ele.maxLength > 0 && text.length > ele.maxLength) {
            text = text.substring(0, ele.maxLength)
        }
        ele.value = text
        val length = ele.value.length
        val selectionStart = state.optInt(KEY_SELECTION_START, length).coerceIn(0, length)
        val selectionEnd = state.optInt(KEY_SELECTION_END, selectionStart).coerceIn(0, length)
        // Some input types (number, email) do not support selection and throw on
        // setSelectionRange; the text is applied either way. Unlike setCursorIndex
        // this never focuses: a programmatic value must not steal focus.
        try {
            ele.setSelectionRange(selectionStart, selectionEnd)
        } catch (_: Throwable) {
        }
        // A programmatic change raises textInputStateChange, not textDidChange —
        // the Android renderer suppresses its TextWatcher during the set and then
        // invokes exactly this callback, and the Kotlin side's echo suppression
        // relies on hearing the full editing state back.
        textInputStateChangeEventCallback?.invoke(currentTextInputStateMap())
    }

    /**
     * Report the input's current text and selection to the Kotlin side. The DOM
     * does not expose the composing range on a plain input, so it reports "none".
     */
    private fun getTextInputState(callback: KuiklyRenderCallback?) {
        callback?.invoke(currentTextInputStateMap())
    }

    /**
     * The input's current editing state, in the payload shape the Android
     * renderer's createTextInputStateParamMap answers with.
     */
    private fun currentTextInputStateMap(): Map<String, Any> {
        val text = ele.value
        val selectionStart = (try { ele.selectionStart } catch (_: Throwable) { null } ?: text.length)
            .coerceIn(0, text.length)
        val selectionEnd = (try { ele.selectionEnd } catch (_: Throwable) { null } ?: selectionStart)
            .coerceIn(0, text.length)
        return mapOf(
            MAP_KEY_TEXT to text,
            KEY_SELECTION_START to selectionStart,
            KEY_SELECTION_END to selectionEnd,
            KEY_COMPOSITION_START to NO_COMPOSITION,
            KEY_COMPOSITION_END to NO_COMPOSITION,
            KEY_GENERATION to editGeneration,
        )
    }

    /**
     * Text content has changed, notify kuikly side
     */
    private fun notifyTextValueChanged(text: String) {
        val map = mutableMapOf<String, Any>()
        map[MAP_KEY_TEXT] = text
        // Notify kotlin side
        textDidChangedEventCallback?.invoke(map)
    }

    /**
     * Bind a VisualViewport-based keyboard height tracker.
     *
     * Rationale: browsers (H5) do not emit a `keyboardheightchange` DOM event on <input>.
     * On mobile browsers the soft-keyboard shrinks the visual viewport, so the delta
     * `window.innerHeight - visualViewport.height` approximates the keyboard height.
     *
     * We only track while this input element owns focus, so that viewport resize from
     * orientation change or browser UI does not accidentally fire spurious callbacks.
     * The tracker dispatches a synthetic `keyboardheightchange` CustomEvent on the
     * input element so it goes through the same code path as mini-program.
     *
     * This is a no-op on platforms where `MiniInputElement` already feeds real
     * `keyboardheightchange` events (it does not expose `window.visualViewport`).
     */
    private fun bindKeyboardHeightTrackingIfNeeded() {
        if (keyboardTrackingBound) return
        val vv = js("(typeof window !== 'undefined' && window.visualViewport) ? window.visualViewport : null")
        if (vv == null) return
        keyboardTrackingBound = true

        var isFocused = false
        ele.addEventListener(EVENT_FOCUS, { isFocused = true })
        ele.addEventListener(EVENT_BLUR, {
            isFocused = false
            // Treat blur as keyboard fully collapsed.
            if (lastKeyboardHeight != 0f) {
                lastKeyboardHeight = 0f
                dispatchKeyboardHeightChangeEvent(0f, DEFAULT_KEYBOARD_DURATION, DEFAULT_KEYBOARD_CURVE)
            }
        })

        val onResize: (dynamic) -> Unit = {
            if (isFocused) {
                val innerHeight = js("window.innerHeight").unsafeCast<Number>().toFloat()
                val viewportHeight = vv.height.unsafeCast<Number>().toFloat()
                val height = (innerHeight - viewportHeight).coerceAtLeast(0f)
                if (height != lastKeyboardHeight) {
                    lastKeyboardHeight = height
                    dispatchKeyboardHeightChangeEvent(
                        height,
                        DEFAULT_KEYBOARD_DURATION,
                        DEFAULT_KEYBOARD_CURVE
                    )
                }
            }
        }
        vv.addEventListener(EVENT_RESIZE, onResize)
    }

    /**
     * Dispatch a unified `keyboardheightchange` CustomEvent on this input element so the
     * listener installed in `setProp(KEYBOARD_HEIGHT_CHANGE, ...)` can handle it uniformly
     * on both H5 and mini-program.
     */
    private fun dispatchKeyboardHeightChangeEvent(height: Float, duration: Float, curve: Int) {
        val detail: dynamic = js("({})")
        detail.height = height
        detail.duration = duration
        detail.curve = curve
        val event = js("new CustomEvent('keyboardheightchange', { detail: detail })")
        ele.asDynamic().dispatchEvent(event)
    }


    /**
     * Set input and keyboard input type
     */
    private fun setKeyBoardType(keyboardType: String) {
        ele.type = when (keyboardType) {
            KEYBOARD_PASSWORD -> KEYBOARD_PASSWORD
            KEYBOARD_NUMBER -> KEYBOARD_NUMBER
            KEYBOARD_EMAIL -> KEYBOARD_EMAIL
            else -> KEYBOARD_TEXT
        }
    }

    /**
     * Set return key type
     */
    private fun setReturnKeyType(returnKeyType: String) {
        // 支持的返回键类型集合
        val supportedTypes = setOf(RETURN_KEY_SEARCH, RETURN_KEY_SEND, RETURN_KEY_DONE, RETURN_KEY_GO)

        val returnKey = if (returnKeyType in supportedTypes) {
            returnKeyType
        } else {
            // default
            RETURN_KEY_NEXT
        }
        ele.asDynamic().enterKeyHint = returnKey
    }

    companion object {
        const val VIEW_NAME = "KRTextFieldView"

        // Default font size (Kuikly convention)
        private const val DEFAULT_FONT_SIZE = 15f
        // Multiplier to calculate minimum height from fontSize (fontSize * 1.5 ≈ native input intrinsic height)
        private const val MIN_HEIGHT_FONT_SIZE_MULTIPLIER = 1.5f

        // Properties
        private const val SRC = "text"
        private const val PLACEHOLDER = "placeholder"
        private const val PLACEHOLDER_COLOR = "placeholderColor"
        private const val TEXT_ALIGN = "textAlign"
        private const val FONT_SIZE = "fontSize"
        private const val FONT_WEIGHT = "fontWeight"
        private const val TINT_COLOR = "tintColor"
        private const val SELECTION_COLOR = "selectionColor"
        private const val MAX_TEXT_LENGTH = "maxTextLength"
        private const val AUTO_FOCUS = "autofocus"
        private const val EDIT_ABLE = "editable"
        private const val KEYBOARD_TYPE = "keyboardType"
        private const val RETURN_KEY_TYPE = "returnKeyType"

        // Methods
        private const val SET_TEXT = "setText"
        private const val FOCUS = "focus"
        private const val BLUR = "blur"
        private const val GET_CURSOR_INDEX = "getCursorIndex"
        private const val SET_CURSOR_INDEX = "setCursorIndex"
        private const val SET_TEXT_INPUT_STATE = "setTextInputState"
        private const val GET_TEXT_INPUT_STATE = "getTextInputState"

        // Text input state payload keys, aligned with core's TextInputState
        private const val KEY_SELECTION_START = "selectionStart"
        private const val KEY_SELECTION_END = "selectionEnd"
        private const val KEY_COMPOSITION_START = "compositionStart"
        private const val KEY_COMPOSITION_END = "compositionEnd"
        private const val KEY_GENERATION = "generation"
        private const val NO_COMPOSITION = -1
        private const val NO_GENERATION = -1


        // Events
        private const val TEXT_DID_CHANGE = "textDidChange"
        private const val TEXT_INPUT_STATE_CHANGE = "textInputStateChange"
        private const val INPUT_FOCUS = "inputFocus"
        private const val INPUT_BLUR = "inputBlur"
        private const val INPUT_RETURN = "inputReturn"
        private const val TEXT_LENGTH_BEYOND_LIMIT = "textLengthBeyondLimit"
        // Keyboard height change event name (aligns with core's InputView.KEYBOARD_HEIGHT_CHANGE)
        private const val KEYBOARD_HEIGHT_CHANGE = "keyboardHeightChange"
        
        // Keyboard key codes - reuse from KRKeyboardConst
        private val ENTER_KEY_CODE = KRKeyboardConst.ENTER_KEY_CODE

        // DOM event names - reuse from KREventConst
        private val EVENT_INPUT = KREventConst.INPUT
        private val EVENT_FOCUS = KREventConst.FOCUS
        private val EVENT_BLUR = KREventConst.BLUR
        private val EVENT_KEYDOWN = KREventConst.KEYDOWN
        private val EVENT_COMPOSITION_START = KREventConst.COMPOSITION_START
        private val EVENT_COMPOSITION_END = KREventConst.COMPOSITION_END
        private val EVENT_BEFORE_INPUT = KREventConst.BEFORE_INPUT
        // Unified DOM event name used by both mini-program (real) and H5 (synthesized
        // from visualViewport.resize) to deliver keyboard height change signals.
        private const val EVENT_KEYBOARD_HEIGHT_CHANGE = "keyboardheightchange"
        // Browser VisualViewport resize event name.
        private const val EVENT_RESIZE = "resize"

        // Keyboard keys - reuse from KRKeyboardConst
        private val KEY_ENTER = KRKeyboardConst.KEY_ENTER

        // Input types - reuse from KRInputTypeConst
        private val INPUT_TYPE_INSERT_TEXT = KRInputTypeConst.INSERT_TEXT
        private val INPUT_TYPE_DELETE_BACKWARD = KRInputTypeConst.DELETE_BACKWARD

        // Keyboard type values - reuse from KRInputTypeConst
        private val KEYBOARD_PASSWORD = KRInputTypeConst.PASSWORD
        private val KEYBOARD_NUMBER = KRInputTypeConst.NUMBER
        private val KEYBOARD_EMAIL = KRInputTypeConst.EMAIL
        private val KEYBOARD_TEXT = KRInputTypeConst.TEXT

        // Return key type values - reuse from KRKeyboardConst
        private val RETURN_KEY_SEARCH = KRKeyboardConst.RETURN_KEY_SEARCH
        private val RETURN_KEY_SEND = KRKeyboardConst.RETURN_KEY_SEND
        private val RETURN_KEY_DONE = KRKeyboardConst.RETURN_KEY_DONE
        private val RETURN_KEY_GO = KRKeyboardConst.RETURN_KEY_GO
        private val RETURN_KEY_NEXT = KRKeyboardConst.RETURN_KEY_NEXT

        // Map keys - reuse from KRParamConst
        private val MAP_KEY_TEXT = KRParamConst.TEXT
        private val MAP_KEY_CURSOR_INDEX = KRParamConst.CURSOR_INDEX
        // Keyboard height change payload keys (keep identifiers aligned with
        // iOS/Android/OHOS native outputs so core layer can parse uniformly).
        private const val MAP_KEY_HEIGHT = "height"
        private const val MAP_KEY_DURATION = "duration"
        private const val MAP_KEY_CURVE = "curve"

        // Fallback animation values for H5 where VisualViewport does not provide
        // keyboard animation timing info; match typical iOS keyboard animation.
        private const val DEFAULT_KEYBOARD_DURATION = 0.25f
        private const val DEFAULT_KEYBOARD_CURVE = 0

        // CSS values - reuse from KRStyleConst
        private val CSS_BORDER_NONE = KRStyleConst.BORDER_NONE
        private val CSS_BG_TRANSPARENT = KRStyleConst.BG_TRANSPARENT
    }
}
