/*
 * Ronaq addition to KuiklyUI (CHANGES.md §61). See UIView+KRLoopAnimation.h.
 *
 * A keyed CABasicAnimation that repeats for ever (`autoreverses` for a there-and-back loop, `timeOffset`
 * for the phase) and survives the app going to the background (`removedOnCompletion = NO`). Core Animation
 * draws nothing for a layer off the glass or in the background, and the Kotlin thread is never involved.
 *
 * The renderer owns the layer's anchor point and resets it on every frame (`setCss_frame:` →
 * `CSSTransform resetTransformWithView:`), so a scale about a pivot other than the centre is built as a
 * whole transform — the scale, then the distance the pivot would have travelled — from the view's size; a
 * rotation turns about the centre. The loop owns its property: the node must carry no transform (rotate,
 * scale) or opacity (opacity) of its own.
 */

#import "UIView+KRLoopAnimation.h"
#import <QuartzCore/QuartzCore.h>
#import <objc/runtime.h>

static NSString *const KRLoopAnimationKey = @"kr.loopAnimation";
static char KRLoopStartKey;
static char KRLoopSizeKey;

@implementation UIView (KRLoopAnimation)

- (NSString *)css_loopAnimation {
    return objc_getAssociatedObject(self, @selector(css_loopAnimation));
}

- (void)setCss_loopAnimation:(NSString *)spec {
    NSString *value = [spec isKindOfClass:NSString.class] && spec.length > 0 ? spec : nil;
    NSString *old = self.css_loopAnimation;
    if (old == value || [old isEqualToString:value]) {
        return;
    }
    objc_setAssociatedObject(self, @selector(css_loopAnimation), value, OBJC_ASSOCIATION_COPY_NONATOMIC);
    // A new value starts at its own phase; a rebuild for a new size continues from here.
    objc_setAssociatedObject(self, &KRLoopStartKey, value ? @(CACurrentMediaTime()) : nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    [self kr_buildLoopAnimation];
}

- (void)kr_loopAnimationDidLayout {
    if (self.css_loopAnimation == nil) {
        return;
    }
    NSValue *built = objc_getAssociatedObject(self, &KRLoopSizeKey);
    if (built != nil && CGSizeEqualToSize(built.CGSizeValue, self.bounds.size)) {
        return;
    }
    [self kr_buildLoopAnimation];
}

- (void)kr_buildLoopAnimation {
    [self.layer removeAnimationForKey:KRLoopAnimationKey];
    objc_setAssociatedObject(self, &KRLoopSizeKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    NSString *value = self.css_loopAnimation;
    if (value == nil) {
        return;
    }
    NSArray<NSString *> *p = [value componentsSeparatedByString:@" "];
    // The numbers: from, to, leg, then phase, pivotX, pivotY — fields 1, 2, 3, 6, 7, 8.
    double numbers[6] = {0};
    const NSUInteger fields[6] = {1, 2, 3, 6, 7, 8};
    BOOL wellFormed = p.count == 9;
    for (NSUInteger i = 0; wellFormed && i < 6; i++) {
        NSScanner *scan = [NSScanner scannerWithString:p[fields[i]]];
        wellFormed = [scan scanDouble:&numbers[i]] && scan.isAtEnd;
    }
    double from = numbers[0], to = numbers[1], legMillis = numbers[2];
    double phase = numbers[3], pivotX = numbers[4], pivotY = numbers[5];
    NSString *property = wellFormed ? p[0] : nil;
    if (!wellFormed || legMillis <= 0) {
        NSLog(@"[KuiklyRender] loopAnimation ignored: %@", value);
        return;
    }
    CGSize size = self.bounds.size;
    CFTimeInterval leg = legMillis / 1000.0;
    BOOL reverse = [p[4] isEqualToString:@"1"];

    CABasicAnimation *animation;
    if ([property isEqualToString:@"rotate"]) {
        animation = [CABasicAnimation animationWithKeyPath:@"transform.rotation.z"];
        animation.fromValue = @(from * M_PI / 180.0);
        animation.toValue = @(to * M_PI / 180.0);
    } else if ([property isEqualToString:@"scaleX"] || [property isEqualToString:@"scaleY"]) {
        BOOL y = [property isEqualToString:@"scaleY"];
        CATransform3D (^scaled)(double) = ^CATransform3D(double s) {
            CGFloat tx = y ? 0 : (pivotX - 0.5) * size.width * (1 - s);
            CGFloat ty = y ? (pivotY - 0.5) * size.height * (1 - s) : 0;
            return CATransform3DScale(CATransform3DMakeTranslation(tx, ty, 0), y ? 1 : s, y ? s : 1, 1);
        };
        animation = [CABasicAnimation animationWithKeyPath:@"transform"];
        animation.fromValue = [NSValue valueWithCATransform3D:scaled(from)];
        animation.toValue = [NSValue valueWithCATransform3D:scaled(to)];
    } else if ([property isEqualToString:@"opacity"]) {
        animation = [CABasicAnimation animationWithKeyPath:@"opacity"];
        animation.fromValue = @(from);
        animation.toValue = @(to);
    } else {
        NSLog(@"[KuiklyRender] loopAnimation ignored: %@", value);
        return;
    }
    animation.duration = leg;
    animation.autoreverses = reverse;
    animation.repeatCount = HUGE_VALF;
    animation.timingFunction = [CAMediaTimingFunction functionWithName:
        [p[5] isEqualToString:@"easeInOut"] ? kCAMediaTimingFunctionEaseInEaseOut : kCAMediaTimingFunctionLinear];
    animation.removedOnCompletion = NO;
    CFTimeInterval cycle = reverse ? 2 * leg : leg;
    CFTimeInterval started = [objc_getAssociatedObject(self, &KRLoopStartKey) doubleValue];
    CFTimeInterval elapsed = started > 0 ? CACurrentMediaTime() - started : 0;
    animation.timeOffset = fmod(phase * cycle + elapsed, cycle);
    objc_setAssociatedObject(self, &KRLoopSizeKey, [NSValue valueWithCGSize:size], OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    [self.layer addAnimation:animation forKey:KRLoopAnimationKey];
}

@end
