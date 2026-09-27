/*
 * Ronaq addition to KuiklyUI (see CHANGES.md, "Image views load at their on-screen pixel
 * size").
 */

#import "UIView+KRVisibility.h"
#import <objc/runtime.h>

#pragma mark - Coalesced view-tree notices

static NSHashTable<id<KRViewTreeObserver>> *KRViewTreeObservers(void) {
    static NSHashTable *observers;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        observers = [NSHashTable weakObjectsHashTable];
    });
    return observers;
}

/// Changes posted since the last delivery; 0 when nothing is pending.
static KRViewTreeChange sKRPendingViewTreeChanges = 0;

@implementation UIView (KRVisibility)

- (NSNumber *)css_occluded {
    return objc_getAssociatedObject(self, @selector(css_occluded));
}

- (void)setCss_occluded:(NSNumber *)css_occluded {
    BOOL was = [self.css_occluded boolValue];
    objc_setAssociatedObject(self, @selector(css_occluded), css_occluded, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    if (was != [css_occluded boolValue]) {
        [UIView kr_noteViewTreeChange:KRViewTreeChangeVisibility];
    }
}

- (BOOL)kr_isEffectivelyVisible {
    UIView *window = (UIView *)self.window;
    if (window == nil) {
        return NO;
    }
    for (UIView *view = self; view != nil; view = view.superview) {
        if (view.hidden || view.alpha <= 0.01 || [view.css_occluded boolValue]) {
            return NO;
        }
    }
#if TARGET_OS_OSX // [macOS]
    return YES;
#else
    // Only the window's bounds, not every clipping ancestor's: an item a lazy list or a pager
    // has placed outside the viewport is outside the window too, and this costs one
    // conversion rather than a walk of clip rectangles.
    CGRect inWindow = [self convertRect:self.bounds toView:nil];
    return CGRectIntersectsRect(inWindow, window.bounds);
#endif // [macOS]
}

- (CGSize)kr_displayPixelSize {
    CGSize size = self.bounds.size;
    if (size.width <= 0 || size.height <= 0) {
        return CGSizeZero;
    }
#if TARGET_OS_OSX // [macOS]
    CGFloat screenScale = self.window.backingScaleFactor ?: NSScreen.mainScreen.backingScaleFactor ?: 1;
    return CGSizeMake(ceil(size.width * screenScale), ceil(size.height * screenScale));
#else
    // The scale magnitude of each axis of every transform on the way up: the length of the
    // transformed unit vector, so a rotation does not read as a shrink.
    CGFloat scaleX = 1, scaleY = 1;
    for (UIView *view = self; view != nil; view = view.superview) {
        CATransform3D t = view.layer.transform;
        scaleX *= sqrt(t.m11 * t.m11 + t.m12 * t.m12);
        scaleY *= sqrt(t.m21 * t.m21 + t.m22 * t.m22);
    }
    // Never below the unscaled bounds: a shrinking transform is usually the first frame of an
    // entrance, and a picture sized at that moment would be drawn upscaled for the rest of it.
    scaleX = MAX(scaleX, 1);
    scaleY = MAX(scaleY, 1);
    UIScreen *screen = self.window.screen ?: UIScreen.mainScreen;
    CGFloat screenScale = screen.scale > 0 ? screen.scale : 1;
    return CGSizeMake(ceil(size.width * screenScale * scaleX - 0.001),
                      ceil(size.height * screenScale * scaleY - 0.001));
#endif // [macOS]
}

- (BOOL)kr_isUnwatchedLeaf {
    if (self.subviews.count > 0) {
        return NO;
    }
    for (id<KRViewTreeObserver> observer in KRViewTreeObservers()) {
        if ((id)observer == (id)self) {
            return NO;
        }
        if ([observer respondsToSelector:@selector(kr_watchesView:)]) {
            if ([observer kr_watchesView:self]) {
                return NO;
            }
        } else if (![(NSObject *)observer isKindOfClass:UIView.class]) {
            // Not a view and not saying what it watches: it may watch this one.
            return NO;
        }
    }
    return YES;
}

+ (void)kr_addViewTreeObserver:(id<KRViewTreeObserver>)observer {
    if (observer) {
        [KRViewTreeObservers() addObject:observer];
    }
}

+ (void)kr_removeViewTreeObserver:(id<KRViewTreeObserver>)observer {
    if (observer) {
        [KRViewTreeObservers() removeObject:observer];
    }
}

+ (void)kr_noteViewTreeChange:(KRViewTreeChange)changes {
    if (changes == 0) {
        return;
    }
    BOOL scheduled = sKRPendingViewTreeChanges != 0;
    sKRPendingViewTreeChanges |= changes;
    if (scheduled) {
        return;
    }
    // One delivery per turn of the main run loop, however many changes a render flush or a
    // scroll produced within it.
    dispatch_async(dispatch_get_main_queue(), ^{
        [UIView kr_deliverPendingViewTreeChanges];
    });
}

+ (void)kr_deliverPendingViewTreeChanges {
    KRViewTreeChange changes = sKRPendingViewTreeChanges;
    sKRPendingViewTreeChanges = 0;
    if (changes == 0) {
        return;
    }
    // A snapshot: an observer may register or leave while it is being told.
    for (id<KRViewTreeObserver> observer in KRViewTreeObservers().allObjects) {
        [observer kr_viewTreeDidChange:changes];
    }
}

@end
