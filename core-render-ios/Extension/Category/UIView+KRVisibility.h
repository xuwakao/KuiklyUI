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
    /// A view became hidden or shown, crossed zero opacity, was marked occluded or not, a
    /// scroll view moved its content, or the application entered or left the background.
    KRViewTreeChangeVisibility = 1 << 0,
    /// A transform that enlarges its view (a scale above 1 on either axis) was set.
    KRViewTreeChangeGeometry = 1 << 1,
};

/// Hears the coalesced view-tree notices. Held weakly; main thread only.
@protocol KRViewTreeObserver <NSObject>
- (void)kr_viewTreeDidChange:(KRViewTreeChange)changes;
@optional
/// Ronaq (CHANGES.md §57): whether this observer's answer depends on `view`'s own visibility.
/// An observer that is a view and does not implement this is taken to watch itself; any other
/// observer that does not implement it is taken to watch every view, as before.
- (BOOL)kr_watchesView:(UIView *)view;
@end

@interface UIView (KRVisibility)

/// The generic `occluded` prop (1/0): this subtree is laid out and attached but not on the
/// glass — a page under an overlay, a tab that is not in front. Setting it posts a
/// visibility notice. Reset on reuse like every common prop.
@property (nonatomic, strong, nullable) NSNumber *css_occluded;

/// The generic `occludes` prop (1/0, CHANGES.md §51): this view is an opaque cover of the rect it
/// occupies — a sheet's solid body. A view all of whose visible part lies under a cover drawn above
/// it is not effectively visible. Setting it posts a visibility notice. Reset on reuse.
@property (nonatomic, strong, nullable) NSNumber *css_occludes;

/// Whether anything of this view can be seen: the application is not in the background (a
/// visibility notice is posted when it enters the background and when it comes back; CHANGES.md
/// §48), the view is in a window, neither it nor any ancestor (the window included) is hidden, at
/// alpha 0.01 or less, or marked occluded, some of its bounds lies inside the window and inside
/// every ancestor that clips (`clipsToBounds`, `masksToBounds`: a list's viewport, a clipped
/// carousel; §51), and that part does not lie wholly under an `occludes` cover drawn above it
/// (§51). The same predicate the Android renderer uses (Ronaq design image-pipeline INV-6).
@property (nonatomic, readonly) BOOL kr_isEffectivelyVisible;

/// How many pixels this view covers on the screen: its bounds, times the screen scale,
/// times the scale of its own transform and of every ancestor's. Each axis's transform
/// product is clamped to at least 1, so a view momentarily shrunk (the first frame of an
/// entrance) is never sized below its unscaled bounds. CGSizeZero while the view has no
/// size.
@property (nonatomic, readonly) CGSize kr_displayPixelSize;

/// Ronaq (CHANGES.md §57): this view has no subviews, is not an `occludes` cover (§51: moving a
/// cover changes what lies under it), and no registered observer watches it, so moving it (a
/// transform) cannot change any observer's answer.
@property (nonatomic, readonly) BOOL kr_isUnwatchedLeaf;

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
