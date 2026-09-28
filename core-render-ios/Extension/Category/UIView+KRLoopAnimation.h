/*
 * Ronaq addition to KuiklyUI (CHANGES.md §61, "A loop the renderer runs by itself"). Framework-level and
 * vendor-neutral: a generic common prop any node can carry.
 */

#import "KRUIKit.h" // [macOS]

NS_ASSUME_NONNULL_BEGIN

@interface UIView (KRLoopAnimation)

/// The generic `loopAnimation` prop: a motion Core Animation repeats by itself for as long as the prop is
/// set, so Compose writes it once and asks for no frame. The value is
/// "<rotate|scaleX|scaleY|opacity> <from> <to> <legMillis> <reverse 0|1> <linear|easeInOut> <phase 0..1>
/// <pivotX> <pivotY>"; an empty value or nil stops the loop and leaves the view at rest. A malformed value
/// or an unknown property is ignored with one log line. Reset on reuse like every common prop.
@property (nonatomic, copy, nullable) NSString *css_loopAnimation;

/// Called by the renderer's frame path after every frame it sets (`setCss_frame:`): a scale is built from
/// the view's size, so a new size rebuilds it, keeping the loop where it was.
- (void)kr_loopAnimationDidLayout;

@end

NS_ASSUME_NONNULL_END
