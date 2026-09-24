/*
 * Ronaq addition to KuiklyUI (see CHANGES.md, "Image views load at their on-screen pixel
 * size"). Framework-level and vendor-neutral: nothing here knows which image loader the
 * host plugs in.
 */

#import "KRUIKit.h" // [macOS]

NS_ASSUME_NONNULL_BEGIN

/// What changed somewhere in the view tree since the last delivery. A renderer component
/// that depends on its ancestors (how large it is drawn, whether it can be seen) cannot
/// learn of an ancestor's change from UIKit, so the framework posts these notices where it
/// makes such changes itself.
typedef NS_OPTIONS(NSUInteger, KRViewTreeChange) {
    /// A view became hidden or shown, crossed zero opacity, was marked occluded or not, or a
    /// scroll view moved its content.
    KRViewTreeChangeVisibility = 1 << 0,
    /// A transform that enlarges its view (a scale above 1 on either axis) was set.
    KRViewTreeChangeGeometry = 1 << 1,
};

/// Hears the coalesced view-tree notices. Held weakly; main thread only.
@protocol KRViewTreeObserver <NSObject>
- (void)kr_viewTreeDidChange:(KRViewTreeChange)changes;
@end

@interface UIView (KRVisibility)

/// How many pixels this view covers on the screen: its bounds, times the screen scale,
/// times the scale of its own transform and of every ancestor's. Each axis's transform
/// product is clamped to at least 1, so a view momentarily shrunk (the first frame of an
/// entrance) is never sized below its unscaled bounds. CGSizeZero while the view has no
/// size.
@property (nonatomic, readonly) CGSize kr_displayPixelSize;

/// Registers `observer` for the coalesced notices; a weak reference, so an observer that goes
/// away simply stops hearing. Main thread only.
+ (void)kr_addViewTreeObserver:(id<KRViewTreeObserver>)observer;
+ (void)kr_removeViewTreeObserver:(id<KRViewTreeObserver>)observer;

/// Posts `changes`. However many are posted within one turn of the main run loop, the
/// observers hear one call with all of them, on the next turn. Main thread only.
+ (void)kr_noteViewTreeChange:(KRViewTreeChange)changes;

/// Delivers any posted notice now instead of on the next turn. For tests, which cannot wait
/// for a run-loop turn in the middle of an assertion.
+ (void)kr_deliverPendingViewTreeChanges;

@end

NS_ASSUME_NONNULL_END
