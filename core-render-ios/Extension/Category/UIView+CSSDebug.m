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

#import "UIView+CSSDebug.h"
#import "UIView+CSS.h"
#import <objc/runtime.h>

/**
 FIXME: [FIXME]
 
 TODO By Chenkaijie
 */

@implementation UIView (CSSDebug)

- (NSString *)css_debugName {
    return objc_getAssociatedObject(self, @selector(css_debugName));
}
// kotiln侧统一驱动该debug能力(重写Pager.debugUIInspector方法返回true or false打开该能力)
- (void)setCss_debugName:(NSString *)css_debugName {
    objc_setAssociatedObject(self, @selector(css_debugName), css_debugName, OBJC_ASSOCIATION_RETAIN);
    [self kr_updateAccessibilityIdentifier];
    [self kr_syncAccessibilityElement];

#if DEBUG
    [UIView ktv_replaceSubclass:self debugName:css_debugName];
#endif
}

/// A marked view that has children. Either mark counts.
- (BOOL)kr_isAccessibilityContainer {
    return (self.css_debugName.length > 0 || self.css_testTag.length > 0) && self.subviews.count > 0;
}

/// Every marked view currently alive, held weakly.
static NSHashTable<UIView *> *kr_markedViews(void) {
    static NSHashTable *views = nil;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ views = [NSHashTable weakObjectsHashTable]; });
    return views;
}

/// Whether one of this view's DIRECT children is already reachable by accessibility.
///
/// Direct children only: a Kuikly view that wraps a NATIVE control wraps it directly, and
/// that is the case this has to catch — a marked wrapper around a text field must stay a
/// container, because an element is opaque and hiding the field costs it keyboard focus.
- (BOOL)kr_hasAccessibleChild {
    for (UIView *sub in self.subviews) {
        if (sub.isAccessibilityElement || sub.css_testTag.length > 0) {
            return YES;
        }
    }
    return NO;
}

/// Settle isAccessibilityElement across every marked view, once, after the tree is built.
///
/// A marked view has to be REACHABLE, and UIKit offers exactly two ways to be: be an
/// element, or contain one. A view that is neither — not an element itself, holding
/// nothing accessible — is absent from the accessibility tree whatever identifier it
/// carries. Marking only view-tree leaves left every marked container in that state.
/// Measured on the room screen: fully rendered, and its root exposed ONE descendant, the
/// single marked node that happened to be a leaf. The composer, the send button and the
/// feed were all marked, all on screen, and none of them reachable.
///
/// Done as ONE pass rather than per view as the tree is assembled, and both halves of
/// that matter:
///
///   - Per-insert work was tried twice and failed twice. Searching each attached subtree
///     hung the main thread outright (the watchdog killed the app mid-test); carrying a
///     "contains marked content" flag up link by link was cheap but order-dependent, and
///     a re-layout that re-parented an already-flagged wrapper spread the flag to views
///     that did not contain anything, hiding them instead.
///   - Subtrees are assembled detached and attached whole, so at the moment a view is
///     marked it may have no ancestors at all. Any decision taken then is provisional.
///
/// Deferred to the end of the run loop, so it sees the finished tree and runs once no
/// matter how many views changed. The pass is two sweeps and no recursion: provisional
/// state first, then every marked ancestor demoted — an element containing another marked
/// view would hide it. Marked nodes end up forming their own tree, innermost as elements
/// and everything above as containers, and both kinds are reachable.
///
/// Trade-off, deliberately taken: a marked view wrapping only self-drawn content reads to
/// VoiceOver as one element rather than as its inner text. That is what merged semantics
/// does on the other hosts, and the alternative measured here is a screen no automated
/// test can address at all.
static void kr_scheduleAccessibilityPass(void) {
    static BOOL scheduled = NO;
    if (scheduled) {
        return;
    }
    scheduled = YES;
    dispatch_async(dispatch_get_main_queue(), ^{
        scheduled = NO;
        NSArray<UIView *> *marked = kr_markedViews().allObjects;
        for (UIView *view in marked) {
            if (view.css_testTag.length == 0) {
                continue;
            }
            if ([view kr_hasAccessibleChild]) {
                view.isAccessibilityElement = NO;
            } else {
                view.isAccessibilityElement = YES;
                if (view.accessibilityTraits == UIAccessibilityTraitNone) {
                    view.accessibilityTraits = UIAccessibilityTraitButton;
                }
            }
        }
        for (UIView *view in marked) {
            if (view.css_testTag.length == 0) {
                continue;
            }
            for (UIView *parent = view.superview; parent != nil; parent = parent.superview) {
                if (parent.css_testTag.length > 0 || parent.css_debugName.length > 0) {
                    parent.isAccessibilityElement = NO;
                }
            }
        }
    });
}

- (void)kr_syncAccessibilityElement {
    if (self.css_testTag.length == 0) {
        if ([self kr_isAccessibilityContainer]) {
            self.isAccessibilityElement = NO;
        }
        return;
    }
    [kr_markedViews() addObject:self];
    kr_scheduleAccessibilityPass();
}

- (void)kr_updateAccessibilityIdentifier {
    NSString *debugName = self.css_debugName;
    NSString *testTag = self.css_testTag;
    NSMutableString *identifier = [NSMutableString string];
    if (debugName.length > 0) {
        [identifier appendString:debugName];
    }
    if (testTag.length > 0) {
        if (identifier.length > 0) {
            [identifier appendString:@" "];
        }
        [identifier appendString:testTag];
    }
    self.accessibilityIdentifier = identifier.length > 0 ? identifier : nil;
    [self kr_syncAccessibilityElement];
}

#if DEBUG

#define KTVObjectHookSubClassPrefix     @"KT"

+ (void)ktv_replaceSubclass:(id)object debugName:(NSString *)debugName {
    if (debugName.length == 0) {
        return;
    }
    Class subClass = [UIView ktv_createSubClass:object debugName:debugName];
    if (!subClass) {
        return;
    }
    NSLog(@"Replace subClass success, class = %@", [object class]);
}

+ (Class)ktv_createSubClass:(id)object debugName:(NSString *)debugName {
    if (!object) {
        return nil;
    }
    
    Class statedClass = [object class];
    Class baseClass = object_getClass(object);
    
    // 非NSObject的子类，不做subclasshook处理
    if (![baseClass isKindOfClass:[NSObject class]]) {
        return statedClass;
    }
    
    // 元类，不做处理
    if (class_isMetaClass(baseClass)) {
        return baseClass;
    }
    
    // 对象存在KVO，忽略这类case
    if (statedClass != baseClass) {
        return baseClass;
    }

    // 由于KTV的重用机制，已替换过类名的情况下要特殊处理，重新替换其实现类
    NSString *name = NSStringFromClass(baseClass);
    if ([name hasPrefix:KTVObjectHookSubClassPrefix]) {
        NSArray *array = [name componentsSeparatedByString:@"_"];
        if (array.count >= 3) {
            name = [array objectAtIndex:1];
        }
    }
    
    name = [NSString stringWithFormat:@"%@_%@_%@", KTVObjectHookSubClassPrefix, name, debugName];
    Class subClass = NSClassFromString(name);
    if (!subClass) {
        subClass = objc_allocateClassPair(baseClass, name.UTF8String, 0);
        // [UIView ktv_registClass:subClass stated:statedClass];
        // [UIView ktv_registClass:object_getClass(subClass) stated:statedClass];
        objc_registerClassPair(subClass);
    }
    object_setClass(object, subClass);
    return subClass;
}

+ (void)ktv_registClass:(Class)klass stated:(Class)statedClass {
    IMP classImp = imp_implementationWithBlock((Class)^(id s){
        return statedClass;
    });
    Method classMethod = class_getInstanceMethod(klass, @selector(class));
    class_replaceMethod(klass, @selector(class), classImp, method_getTypeEncoding(classMethod));
}

#endif

@end
