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

/// Ronaq (CHANGES.md §57): the observers a leaf has to ask — those that say what they watch
/// (`kr_watchesView:`) and those that are not views and do not say (they watch every view). An
/// observer that is a view and does not say watches only itself, which one lookup in the full
/// registry answers, so a leaf's check does not grow with the image views on a page. A subset of
/// the registry, weak like it.
static NSHashTable<id<KRViewTreeObserver>> *KRViewTreeAskedObservers(void) {
    static NSHashTable *observers;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        observers = [NSHashTable weakObjectsHashTable];
    });
    return observers;
}

/// Changes posted since the last delivery; 0 when nothing is pending.
static KRViewTreeChange sKRPendingViewTreeChanges = 0;

#if !TARGET_OS_OSX // [macOS]
static BOOL KRDrawnAbove(UIView *cover, UIView *view);
#endif // [macOS]

/// Views marked `occludes` (CHANGES.md §51), held weakly.
static NSHashTable<UIView *> *KRCovers(void) {
    static NSHashTable *covers;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        covers = [NSHashTable weakObjectsHashTable];
    });
    return covers;
}

#if !TARGET_OS_OSX // [macOS]
#pragma mark - The app's term (CHANGES.md §48)

/// Whether the application is in the background, as of its lifecycle notifications: set on
/// DidEnterBackground, cleared on WillEnterForeground (during which `applicationState` still reads
/// Background). Read from `applicationState` until the first notification. Main thread.
static BOOL sKRAppInBackground = NO;
static BOOL sKRAppStateKnown = NO;

static BOOL KRAppInBackground(void) {
    if (!sKRAppStateKnown) {
        sKRAppStateKnown = YES;
        sKRAppInBackground = UIApplication.sharedApplication.applicationState == UIApplicationStateBackground;
    }
    return sKRAppInBackground;
}

/// Hears the application's lifecycle for the predicate, from launch.
@interface KRAppPresence : NSObject
@end

@implementation KRAppPresence

+ (void)load {
    NSNotificationCenter *center = NSNotificationCenter.defaultCenter;
    [center addObserverForName:UIApplicationDidEnterBackgroundNotification object:nil
                         queue:nil usingBlock:^(NSNotification *note) {
        sKRAppStateKnown = YES;
        sKRAppInBackground = YES;
        [UIView kr_noteViewTreeChange:KRViewTreeChangeVisibility];
    }];
    [center addObserverForName:UIApplicationWillEnterForegroundNotification object:nil
                         queue:nil usingBlock:^(NSNotification *note) {
        sKRAppStateKnown = YES;
        sKRAppInBackground = NO;
        [UIView kr_noteViewTreeChange:KRViewTreeChangeVisibility];
    }];
}

@end
#endif // [macOS]

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

- (NSNumber *)css_occludes {
    return objc_getAssociatedObject(self, @selector(css_occludes));
}

- (void)setCss_occludes:(NSNumber *)css_occludes {
    BOOL was = [self.css_occludes boolValue];
    objc_setAssociatedObject(self, @selector(css_occludes), css_occludes, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    BOOL now = [css_occludes boolValue];
    if (now) {
        [KRCovers() addObject:self];
    } else {
        [KRCovers() removeObject:self];
    }
    if (was != now) {
        [UIView kr_noteViewTreeChange:KRViewTreeChangeVisibility];
    }
}

- (BOOL)kr_isEffectivelyVisible {
    return [self kr_isVisibleConsideringCovers:YES];
}

- (BOOL)kr_isVisibleConsideringCovers:(BOOL)considerCovers {
    UIView *window = (UIView *)self.window;
    if (window == nil) {
        return NO;
    }
#if !TARGET_OS_OSX // [macOS]
    // Nothing of the app can be seen while it is in the background (CHANGES.md §48).
    if (KRAppInBackground()) {
        return NO;
    }
#endif // [macOS]
    for (UIView *view = self; view != nil; view = view.superview) {
        if (view.hidden || view.alpha <= 0.01 || [view.css_occluded boolValue]) {
            return NO;
        }
    }
#if TARGET_OS_OSX // [macOS]
    return YES;
#else
    CGRect visible = [self kr_visibleRectInWindow];
    if (CGRectIsNull(visible) || CGRectIsEmpty(visible)) {
        return NO;
    }
    return !(considerCovers && [self kr_isCoveredWithin:visible]);
#endif // [macOS]
}

#if !TARGET_OS_OSX // [macOS]
/// The part of this view that can be on the glass, in window coordinates: its bounds inside the
/// window and inside every ancestor that clips (CHANGES.md §51) — a list's viewport, a clipped
/// carousel, a zero-height clipped container. CGRectNull when nothing is left.
- (CGRect)kr_visibleRectInWindow {
    UIView *window = (UIView *)self.window;
    CGRect rect = CGRectIntersection([self convertRect:self.bounds toView:nil], window.bounds);
    for (UIView *ancestor = self.superview; ancestor != nil && ancestor != window && !CGRectIsNull(rect);
         ancestor = ancestor.superview) {
        if (ancestor.clipsToBounds || ancestor.layer.masksToBounds) {
            rect = CGRectIntersection(rect, [ancestor convertRect:ancestor.bounds toView:nil]);
        }
    }
    return rect;
}

/// Whether an `occludes` cover drawn above this view covers all of `visible`, its visible part.
/// A cover that cannot be seen itself hides nothing; covers are not checked for covers.
- (BOOL)kr_isCoveredWithin:(CGRect)visible {
    NSArray<UIView *> *covers = KRCovers().allObjects;
    for (UIView *cover in covers) {
        if (cover == self || cover.window != self.window) { continue; }
        if (!KRDrawnAbove(cover, self)) { continue; }
        if (![cover kr_isVisibleConsideringCovers:NO]) { continue; }
        CGRect coverRect = [cover kr_visibleRectInWindow];
        // Half a point of slack: conversions through transforms are not exact.
        if (CGRectContainsRect(CGRectInset(coverRect, -0.5, -0.5), visible)) {
            return YES;
        }
    }
    return NO;
}

/// Whether `cover` draws after `view`: their branches compared at the lowest common ancestor —
/// the higher `zPosition` first (how the renderer applies `zIndex`), then the later subview. NO
/// when they share no ancestor or one contains the other (a cover never hides its own subtree).
static BOOL KRDrawnAbove(UIView *cover, UIView *view) {
    NSMutableArray<UIView *> *coverPath = [NSMutableArray array];
    for (UIView *v = cover; v != nil; v = v.superview) { [coverPath insertObject:v atIndex:0]; }
    NSMutableArray<UIView *> *viewPath = [NSMutableArray array];
    for (UIView *v = view; v != nil; v = v.superview) { [viewPath insertObject:v atIndex:0]; }
    NSUInteger depth = 0;
    while (depth < coverPath.count && depth < viewPath.count && coverPath[depth] == viewPath[depth]) {
        depth++;
    }
    if (depth == 0 || depth >= coverPath.count || depth >= viewPath.count) {
        return NO;
    }
    UIView *parent = coverPath[depth - 1];
    UIView *c = coverPath[depth];
    UIView *v = viewPath[depth];
    CGFloat cz = c.layer.zPosition, vz = v.layer.zPosition;
    if (cz != vz) {
        return cz > vz;
    }
    return [parent.subviews indexOfObject:c] > [parent.subviews indexOfObject:v];
}
#endif // [macOS]

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
    // A cover's move changes what lies wholly under it (CHANGES.md §51), though nothing is under it
    // in the tree and nobody watches it.
    if ([self.css_occludes boolValue]) {
        return NO;
    }
    // An observer that is this view watches it (whether or not it also says what it watches).
    if ([KRViewTreeObservers() containsObject:(id<KRViewTreeObserver>)self]) {
        return NO;
    }
    for (id<KRViewTreeObserver> observer in KRViewTreeAskedObservers()) {
        if (![observer respondsToSelector:@selector(kr_watchesView:)]) {
            // Not a view and not saying what it watches: it may watch this one.
            return NO;
        }
        if ([observer kr_watchesView:self]) {
            return NO;
        }
    }
    return YES;
}

+ (void)kr_addViewTreeObserver:(id<KRViewTreeObserver>)observer {
    if (observer) {
        [KRViewTreeObservers() addObject:observer];
        if ([observer respondsToSelector:@selector(kr_watchesView:)] ||
            ![(NSObject *)observer isKindOfClass:UIView.class]) {
            [KRViewTreeAskedObservers() addObject:observer];
        }
    }
}

+ (void)kr_removeViewTreeObserver:(id<KRViewTreeObserver>)observer {
    if (observer) {
        [KRViewTreeObservers() removeObject:observer];
        [KRViewTreeAskedObservers() removeObject:observer];
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
