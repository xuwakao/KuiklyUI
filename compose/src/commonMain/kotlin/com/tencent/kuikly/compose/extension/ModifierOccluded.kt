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

package com.tencent.kuikly.compose.extension

import com.tencent.kuikly.compose.ui.Modifier

/** The generic common prop the renderers read (CHANGES.md §35). */
const val OCCLUDED_PROP = "occluded"

/**
 * Ronaq fork (CHANGES.md §35): marks this node's subtree as laid out and attached but not on
 * the glass — a page under an overlay, a tab that is not in front. Unlike hiding it, nothing
 * about layout or drawing changes; the native renderers' effective-visibility predicate reads it,
 * so animated content below it stops advancing and gives its frames back. One prop per subtree:
 * every animated view inside hears it, whatever composable drew it.
 */
fun Modifier.occluded(occluded: Boolean): Modifier = setProp(OCCLUDED_PROP, if (occluded) 1 else 0)

/** The generic common prop the renderers read (CHANGES.md §51). */
const val OCCLUDES_PROP = "occludes"

/**
 * Ronaq fork (CHANGES.md §51): marks this node as an opaque cover of the rect it occupies — a
 * sheet's solid body. A view all of whose visible part lies under a cover drawn above it is not
 * effectively visible to the native renderers, so animated content wholly under a sheet stops; a
 * view only partly covered stays visible. Mark only what is opaque: a translucent surface hides
 * nothing.
 */
fun Modifier.occludes(occludes: Boolean): Modifier = setProp(OCCLUDES_PROP, if (occludes) 1 else 0)
