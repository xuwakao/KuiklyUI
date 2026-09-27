/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.kuikly.core.render.android.expand.module

import android.view.Choreographer
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

/**
 *  监听Vsync回调
 *
 *  created by zhenhuachen on 2025/4/27.
 */
class KRVsyncModule : KuiklyRenderBaseModule() {

    private var vsyncFrameCallback: Choreographer.FrameCallback? = null

    /** Ronaq fork (CHANGES.md §59): ticks are paused; the callback stays registered. */
    private var paused = false

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_REGISTER_VSYNC -> registerVsync(callback)
            METHOD_UNREGISTER_VSYNC -> unRegisterVsync(callback)
            METHOD_PAUSE_VSYNC -> pauseVsync()
            METHOD_RESUME_VSYNC -> resumeVsync()
            else -> super.call(method, params, callback)
        }
    }

    private fun registerVsync(callback: KuiklyRenderCallback?) {
        if (vsyncFrameCallback == null) {
            vsyncFrameCallback = Choreographer.FrameCallback {
                callback?.invoke(null)
                if (!paused) {
                    Choreographer.getInstance().postFrameCallback(vsyncFrameCallback);
                }
            }
            if (!paused) {
                Choreographer.getInstance().postFrameCallback(vsyncFrameCallback);
            }
        }
    }

    /** Ronaq fork (CHANGES.md §59): nothing needs drawing; stop posting the frame callback. */
    private fun pauseVsync() {
        if (paused) return
        paused = true
        vsyncFrameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
    }

    /** Ronaq fork (CHANGES.md §59): something needs drawing; tick again from the next vsync. */
    private fun resumeVsync() {
        if (!paused) return
        paused = false
        vsyncFrameCallback?.let {
            // Never twice in one frame: a callback already posted is removed first.
            Choreographer.getInstance().removeFrameCallback(it)
            Choreographer.getInstance().postFrameCallback(it)
        }
    }

    private fun unRegisterVsync(callback: KuiklyRenderCallback?) {
        if (vsyncFrameCallback != null) {
            Choreographer.getInstance().removeFrameCallback(vsyncFrameCallback);
            vsyncFrameCallback = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vsyncFrameCallback != null) {
            Choreographer.getInstance().removeFrameCallback(vsyncFrameCallback);
        }
    }

    companion object {
        const val MODULE_NAME = "KRVsyncModule"
        const val METHOD_REGISTER_VSYNC = "registerVsync"
        const val METHOD_UNREGISTER_VSYNC = "unRegisterVsync"
        const val METHOD_PAUSE_VSYNC = "pauseVsync"
        const val METHOD_RESUME_VSYNC = "resumeVsync"

    }
}