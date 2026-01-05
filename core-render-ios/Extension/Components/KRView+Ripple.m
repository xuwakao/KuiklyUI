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

#import "KRView+Ripple.h"
#import "KRConvertUtil.h"
#import <objc/runtime.h>

#pragma mark - Associated Object Keys

static const void *kRippleViewKey = &kRippleViewKey;
static const void *kRippleEnabledKey = &kRippleEnabledKey;
static const void *kRippleColorKey = &kRippleColorKey;
static const void *kRipplePressedAlphaKey = &kRipplePressedAlphaKey;
static const void *kRippleBoundedKey = &kRippleBoundedKey;

#pragma mark - Ripple State Constants

static NSString *const kRippleStatePressed = @"pressed";
static NSString *const kRippleStateReleased = @"released";
static NSString *const kRippleStateCancelled = @"cancelled";

#pragma mark - KRRippleOverlayView Implementation

@implementation KRRippleOverlayView {
    CAShapeLayer *_rippleLayer;
    CADisplayLink *_displayLink;
    BOOL _isAnimating;
}

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.userInteractionEnabled = NO;
        self.backgroundColor = [UIColor clearColor];
        self.clipsToBounds = YES;

        _rippleLayer = [CAShapeLayer layer];
        _rippleLayer.opacity = 0;
        _rippleLayer.fillColor = [UIColor blackColor].CGColor;
        [self.layer addSublayer:_rippleLayer];

        _rippleColor = [UIColor blackColor];
        _pressedAlpha = 0.12f;
        _bounded = YES;
        _isAnimating = NO;
    }
    return self;
}

- (CAShapeLayer *)rippleLayer {
    return _rippleLayer;
}

- (void)setRippleColor:(UIColor *)rippleColor {
    _rippleColor = rippleColor ?: [UIColor blackColor];
    _rippleLayer.fillColor = _rippleColor.CGColor;
}

- (void)updateCornerRadius:(CGFloat)cornerRadius {
    self.layer.cornerRadius = cornerRadius;
    [self updateRippleLayerPath];
}

- (void)updateRippleLayerPath {
    if (_bounded) {
        CGFloat radius = MAX(self.bounds.size.width, self.bounds.size.height);
        CGRect rippleRect = CGRectMake(
            (self.bounds.size.width - radius * 2) / 2,
            (self.bounds.size.height - radius * 2) / 2,
            radius * 2,
            radius * 2
        );
        _rippleLayer.path = [UIBezierPath bezierPathWithOvalInRect:rippleRect].CGPath;
    }
}

- (void)layoutSubviews {
    [super layoutSubviews];
    [self updateRippleLayerPath];
}

- (void)showRippleAtPoint:(CGPoint)point {
    [_rippleLayer removeAllAnimations];

    // Calculate ripple size
    CGFloat maxDist = 0;
    CGPoint corners[4] = {
        CGPointMake(0, 0),
        CGPointMake(self.bounds.size.width, 0),
        CGPointMake(0, self.bounds.size.height),
        CGPointMake(self.bounds.size.width, self.bounds.size.height)
    };

    for (int i = 0; i < 4; i++) {
        CGFloat dist = sqrt(pow(corners[i].x - point.x, 2) + pow(corners[i].y - point.y, 2));
        maxDist = MAX(maxDist, dist);
    }

    CGFloat rippleRadius = maxDist * 1.1; // Add 10% margin

    // Set initial state
    CGRect startRect = CGRectMake(point.x - 1, point.y - 1, 2, 2);
    CGRect endRect = CGRectMake(
        point.x - rippleRadius,
        point.y - rippleRadius,
        rippleRadius * 2,
        rippleRadius * 2
    );

    UIBezierPath *startPath = [UIBezierPath bezierPathWithOvalInRect:startRect];
    UIBezierPath *endPath = [UIBezierPath bezierPathWithOvalInRect:endRect];

    _rippleLayer.path = endPath.CGPath;
    _rippleLayer.opacity = _pressedAlpha;

    // Animate scale
    CABasicAnimation *scaleAnim = [CABasicAnimation animationWithKeyPath:@"path"];
    scaleAnim.fromValue = (__bridge id)startPath.CGPath;
    scaleAnim.toValue = (__bridge id)endPath.CGPath;
    scaleAnim.duration = 0.4;
    scaleAnim.timingFunction = [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseOut];
    scaleAnim.fillMode = kCAFillModeForwards;
    scaleAnim.removedOnCompletion = NO;

    // Animate opacity
    CABasicAnimation *opacityAnim = [CABasicAnimation animationWithKeyPath:@"opacity"];
    opacityAnim.fromValue = @0;
    opacityAnim.toValue = @(_pressedAlpha);
    opacityAnim.duration = 0.15;
    opacityAnim.timingFunction = [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseIn];
    opacityAnim.fillMode = kCAFillModeForwards;
    opacityAnim.removedOnCompletion = NO;

    [_rippleLayer addAnimation:scaleAnim forKey:@"rippleScale"];
    [_rippleLayer addAnimation:opacityAnim forKey:@"rippleOpacityIn"];

    _isAnimating = YES;
}

- (void)hideRipple {
    if (!_isAnimating) return;

    // 获取当前实际的 opacity 值（从 presentation layer）
    CGFloat currentOpacity = _pressedAlpha;
    CALayer *presentationLayer = _rippleLayer.presentationLayer;
    if (presentationLayer) {
        currentOpacity = presentationLayer.opacity;
    }

    // 先移除之前的动画
    [_rippleLayer removeAnimationForKey:@"rippleOpacityIn"];

    _isAnimating = NO;

    // 如果 opacity 还很低（快速点击），先快速显示再淡出
    // 这样用户能看到明显的 ripple 反馈
    CGFloat minVisibleOpacity = _pressedAlpha * 0.7f; // 至少达到 70% 才算"可见"

    if (currentOpacity < minVisibleOpacity) {
        // 快速点击：先闪现到目标值，再淡出
        CAKeyframeAnimation *opacityAnim = [CAKeyframeAnimation animationWithKeyPath:@"opacity"];
        opacityAnim.values = @[@(currentOpacity), @(_pressedAlpha), @0];
        opacityAnim.keyTimes = @[@0, @0.2, @1.0]; // 20% 时间到达峰值，剩余 80% 淡出
        opacityAnim.duration = 0.35;
        opacityAnim.timingFunctions = @[
            [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseOut],
            [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseOut]
        ];
        opacityAnim.fillMode = kCAFillModeForwards;
        opacityAnim.removedOnCompletion = NO;

        [_rippleLayer addAnimation:opacityAnim forKey:@"rippleOpacityOut"];

        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.35 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [self->_rippleLayer removeAllAnimations];
            self->_rippleLayer.opacity = 0;
        });
    } else {
        // 正常释放：直接从当前值淡出
        CABasicAnimation *opacityAnim = [CABasicAnimation animationWithKeyPath:@"opacity"];
        opacityAnim.fromValue = @(currentOpacity);
        opacityAnim.toValue = @0;
        opacityAnim.duration = 0.3;
        opacityAnim.timingFunction = [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseOut];
        opacityAnim.fillMode = kCAFillModeForwards;
        opacityAnim.removedOnCompletion = NO;

        [_rippleLayer addAnimation:opacityAnim forKey:@"rippleOpacityOut"];

        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [self->_rippleLayer removeAllAnimations];
            self->_rippleLayer.opacity = 0;
        });
    }
}

- (void)cancelRipple {
    [_rippleLayer removeAllAnimations];
    _rippleLayer.opacity = 0;
    _isAnimating = NO;
}

@end

#pragma mark - KRView (Ripple) Implementation

@implementation KRView (Ripple)

#pragma mark - Associated Object Accessors

- (KRRippleOverlayView *)kr_rippleView {
    return objc_getAssociatedObject(self, kRippleViewKey);
}

- (void)setKr_rippleView:(KRRippleOverlayView *)rippleView {
    objc_setAssociatedObject(self, kRippleViewKey, rippleView, OBJC_ASSOCIATION_ASSIGN);
}

- (BOOL)kr_rippleEnabled {
    return [objc_getAssociatedObject(self, kRippleEnabledKey) boolValue];
}

- (void)setKr_rippleEnabled:(BOOL)enabled {
    objc_setAssociatedObject(self, kRippleEnabledKey, @(enabled), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

- (UIColor *)kr_rippleColor {
    return objc_getAssociatedObject(self, kRippleColorKey);
}

- (void)setKr_rippleColor:(UIColor *)color {
    objc_setAssociatedObject(self, kRippleColorKey, color, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

- (CGFloat)kr_ripplePressedAlpha {
    NSNumber *value = objc_getAssociatedObject(self, kRipplePressedAlphaKey);
    return value ? [value floatValue] : 0.12f;
}

- (void)setKr_ripplePressedAlpha:(CGFloat)alpha {
    objc_setAssociatedObject(self, kRipplePressedAlphaKey, @(alpha), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

- (BOOL)kr_rippleBounded {
    NSNumber *value = objc_getAssociatedObject(self, kRippleBoundedKey);
    return value ? [value boolValue] : YES;
}

- (void)setKr_rippleBounded:(BOOL)bounded {
    objc_setAssociatedObject(self, kRippleBoundedKey, @(bounded), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

#pragma mark - CSS Properties (for prop dispatching)

- (void)setCss_ripple:(NSString *)css_ripple {
    [self setupRippleWithConfig:css_ripple];
}

- (void)setCss_rippleState:(NSString *)css_rippleState {
    [self updateRippleState:css_rippleState];
}

#pragma mark - Ripple Setup

- (void)setupRippleWithConfig:(NSString *)configJson {
    if (!configJson || configJson.length == 0) {
        [self removeRipple];
        return;
    }

    NSError *error = nil;
    NSDictionary *config = [NSJSONSerialization JSONObjectWithData:[configJson dataUsingEncoding:NSUTF8StringEncoding]
                                                           options:0
                                                             error:&error];
    if (error || !config) {
        return;
    }

    BOOL enabled = [config[@"enabled"] boolValue];
    if (!enabled) {
        [self removeRipple];
        return;
    }

    // Parse color
    NSString *colorStr = config[@"color"];
    UIColor *rippleColor = [UIColor blackColor];
    if (colorStr && [colorStr hasPrefix:@"#"]) {
        rippleColor = [self colorFromHexString:colorStr];
    }

    // Parse other properties
    BOOL bounded = config[@"bounded"] ? [config[@"bounded"] boolValue] : YES;
    CGFloat pressedAlpha = config[@"pressedAlpha"] ? [config[@"pressedAlpha"] floatValue] : 0.12f;

    // Store properties
    self.kr_rippleEnabled = YES;
    self.kr_rippleColor = rippleColor;
    self.kr_rippleBounded = bounded;
    self.kr_ripplePressedAlpha = pressedAlpha;

    // Create ripple overlay view if needed
    [self ensureRippleView];
}

- (void)ensureRippleView {
    if (self.kr_rippleView) {
        // Update existing view
        self.kr_rippleView.rippleColor = self.kr_rippleColor;
        self.kr_rippleView.bounded = self.kr_rippleBounded;
        self.kr_rippleView.pressedAlpha = self.kr_ripplePressedAlpha;
        [self.kr_rippleView updateCornerRadius:self.layer.cornerRadius];
        return;
    }

    // Create new ripple view
    KRRippleOverlayView *rippleView = [[KRRippleOverlayView alloc] initWithFrame:self.bounds];
    rippleView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    rippleView.rippleColor = self.kr_rippleColor;
    rippleView.bounded = self.kr_rippleBounded;
    rippleView.pressedAlpha = self.kr_ripplePressedAlpha;
    [rippleView updateCornerRadius:self.layer.cornerRadius];

    // Add as subview at the top
    [self addSubview:rippleView];
    self.kr_rippleView = rippleView;
}

#pragma mark - Ripple State Update

- (void)updateRippleState:(NSString *)stateJson {
    if (!self.kr_rippleEnabled || !stateJson || stateJson.length == 0) {
        return;
    }

    NSError *error = nil;
    NSDictionary *state = [NSJSONSerialization JSONObjectWithData:[stateJson dataUsingEncoding:NSUTF8StringEncoding]
                                                          options:0
                                                            error:&error];
    if (error || !state) {
        return;
    }

    NSString *stateType = state[@"state"];

    if ([stateType isEqualToString:kRippleStatePressed]) {
        CGFloat x = [state[@"x"] floatValue];
        CGFloat y = [state[@"y"] floatValue];

        [self ensureRippleView];
        [self.kr_rippleView showRippleAtPoint:CGPointMake(x, y)];
    } else if ([stateType isEqualToString:kRippleStateReleased]) {
        [self.kr_rippleView hideRipple];
    } else if ([stateType isEqualToString:kRippleStateCancelled]) {
        [self.kr_rippleView cancelRipple];
    }
}

#pragma mark - Ripple Removal

- (void)removeRipple {
    self.kr_rippleEnabled = NO;

    KRRippleOverlayView *rippleView = self.kr_rippleView;
    if (rippleView) {
        [rippleView cancelRipple];
        [rippleView removeFromSuperview];
        self.kr_rippleView = nil;
    }
}

#pragma mark - Frame Update

- (void)updateRippleViewFrame {
    KRRippleOverlayView *rippleView = self.kr_rippleView;
    if (rippleView) {
        rippleView.frame = self.bounds;
        [rippleView updateCornerRadius:self.layer.cornerRadius];
    }
}

#pragma mark - Helper Methods

- (UIColor *)colorFromHexString:(NSString *)hexString {
    NSString *hex = [hexString stringByReplacingOccurrencesOfString:@"#" withString:@""];

    if (hex.length == 3) {
        // Convert short format (e.g., "FFF") to full format ("FFFFFF")
        hex = [NSString stringWithFormat:@"%c%c%c%c%c%c",
               [hex characterAtIndex:0], [hex characterAtIndex:0],
               [hex characterAtIndex:1], [hex characterAtIndex:1],
               [hex characterAtIndex:2], [hex characterAtIndex:2]];
    }

    if (hex.length != 6) {
        return [UIColor blackColor];
    }

    unsigned int r, g, b;
    [[NSScanner scannerWithString:[hex substringWithRange:NSMakeRange(0, 2)]] scanHexInt:&r];
    [[NSScanner scannerWithString:[hex substringWithRange:NSMakeRange(2, 2)]] scanHexInt:&g];
    [[NSScanner scannerWithString:[hex substringWithRange:NSMakeRange(4, 2)]] scanHexInt:&b];

    return [UIColor colorWithRed:r/255.0f green:g/255.0f blue:b/255.0f alpha:1.0f];
}

@end
