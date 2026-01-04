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

#import "KRView.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * @brief Ripple overlay view for native ripple effect
 * Uses CALayer animations to simulate Material Design ripple effect on iOS
 */
@interface KRRippleOverlayView : UIView

/// The ripple layer for animation
@property (nonatomic, strong, readonly) CAShapeLayer *rippleLayer;

/// Current ripple color
@property (nonatomic, strong) UIColor *rippleColor;

/// Whether ripple is bounded to the view
@property (nonatomic, assign) BOOL bounded;

/// Pressed alpha value
@property (nonatomic, assign) CGFloat pressedAlpha;

/// Show ripple at the specified point
- (void)showRippleAtPoint:(CGPoint)point;

/// Hide the ripple with animation
- (void)hideRipple;

/// Cancel the ripple immediately
- (void)cancelRipple;

/// Update corner radius to match parent view
- (void)updateCornerRadius:(CGFloat)cornerRadius;

@end

/**
 * @brief KRView category for native ripple effect support
 * Provides platform-native ripple animation using CALayer
 */
@interface KRView (Ripple)

#pragma mark - Internal Properties (for category use)

/// The ripple overlay view
@property (nonatomic, weak, nullable) KRRippleOverlayView *kr_rippleView;

/// Whether ripple effect is enabled
@property (nonatomic, assign) BOOL kr_rippleEnabled;

/// The ripple color
@property (nonatomic, strong, nullable) UIColor *kr_rippleColor;

/// The pressed alpha value
@property (nonatomic, assign) CGFloat kr_ripplePressedAlpha;

/// Whether ripple is bounded
@property (nonatomic, assign) BOOL kr_rippleBounded;

#pragma mark - Ripple Methods

/// Setup ripple with JSON configuration
- (void)setupRippleWithConfig:(NSString *)configJson;

/// Update ripple state from JSON
- (void)updateRippleState:(NSString *)stateJson;

/// Remove ripple effect
- (void)removeRipple;

/// Update ripple view frame to match self.bounds
- (void)updateRippleViewFrame;

@end

NS_ASSUME_NONNULL_END
