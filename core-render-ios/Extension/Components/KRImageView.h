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

#import "KRUIKit.h" // [macOS]
#import "KuiklyRenderViewExportProtocol.h"

NS_ASSUME_NONNULL_BEGIN
/*
 * @brief 暴露给Kotlin侧调用的Image组件
 */
@interface KRImageView : UIImageView<KuiklyRenderViewExportProtocol>

/// Load without waiting for a size. A sized view defers every load (whichever setter asked
/// for it) until its first layout with a non-empty size, so the image can be decoded for the
/// pixels it will cover; a view that is never given a frame — the memory-cache module's
/// off-screen loader — sets this to load at once. Default NO.
@property (nonatomic, assign) BOOL kr_loadsWithoutSize;

/// YES while the image is stretched by cap insets (any positive `capInsets`) or drawn as a
/// nine-patch (`dotNineImage`): the stretch is measured in the image's own pixels, so a
/// loader must not decode it smaller than its source.
@property (nonatomic, readonly) BOOL kr_needsSourcePixels;

/// The pixel size the current load was issued for — `kr_displayPixelSize` at the moment the
/// view asked its loader — or CGSizeZero for a load without a size (`kr_loadsWithoutSize`,
/// `kr_needsSourcePixels`, or a source the view decodes itself). A loader reads it to size
/// its decode; the view asks again, keeping the current picture, when it grows by more than
/// an eighth beyond it.
@property (nonatomic, readonly) CGSize kr_requestedPixelSize;

@end



NS_ASSUME_NONNULL_END
