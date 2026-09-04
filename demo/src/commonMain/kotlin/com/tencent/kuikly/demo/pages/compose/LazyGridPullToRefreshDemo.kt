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

package com.tencent.kuikly.demo.pages.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.ComposeContainer
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.lazy.grid.GridCells
import com.tencent.kuikly.compose.foundation.lazy.grid.GridItemSpan
import com.tencent.kuikly.compose.foundation.lazy.grid.LazyVerticalGrid
import com.tencent.kuikly.compose.foundation.lazy.grid.rememberLazyGridState
import com.tencent.kuikly.compose.foundation.lazy.grid.stickyHeader
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.pullToRefreshItem
import com.tencent.kuikly.compose.material3.rememberPullToRefreshState
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pull-to-refresh on a LazyVerticalGrid, in the shape a production feed actually has:
 * the PTR item first, a few full-span rows above a grid stickyHeader, one full-span
 * row that arrives LATE (a banner delivered by a slower request), and a finite run of
 * half-span cards after it.
 *
 * Exists to exercise the interaction of three pieces on one scroller: the grid PTR
 * entry point, the grid sticky header (a pinned item), and the content-size sync that
 * turns the lazy layout's estimated content into the native scroller's real one.
 */
@Page("LazyGridPullToRefreshDemo")
class LazyGridPullToRefreshDemo : ComposeContainer() {

    override fun willInit() {
        super.willInit()
        setContent {
            GridPtrExample()
        }
    }

    @Composable
    fun GridPtrExample() {
        var isRefreshing by remember { mutableStateOf(false) }
        var bannerArrived by remember { mutableStateOf(false) }
        val pullState = rememberPullToRefreshState(isRefreshing)
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()

        // The late item: inserted above the sticky header after the first paint,
        // the way a banner from a slower endpoint lands on a real feed.
        LaunchedEffect(Unit) {
            delay(1200)
            bannerArrived = true
        }

        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF17131F))) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 14.dp, bottom = 96.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pullToRefreshItem(
                    state = pullState,
                    onRefresh = {
                        scope.launch {
                            isRefreshing = true
                            delay(1500)
                            isRefreshing = false
                        }
                    },
                    scrollState = gridState,
                )
                item(key = "top_bar", span = { GridItemSpan(maxLineSpan) }) {
                    DemoBar("top bar", 110.dp, Color(0xFF2A2140))
                }
                if (bannerArrived) {
                    item(key = "banner", span = { GridItemSpan(maxLineSpan) }) {
                        DemoBar("banner (late)", 120.dp, Color(0xFF483060))
                    }
                }
                item(key = "checkin", span = { GridItemSpan(maxLineSpan) }) {
                    DemoBar("check-in", 82.dp, Color(0xFF2A2140))
                }
                stickyHeader(key = "strip") {
                    DemoBar("sticky strip", 55.dp, Color(0xFF6C4DE6))
                }
                items(count = 28, key = { it }) { index ->
                    DemoBar("card $index", 180.dp, Color(0xFF221B33))
                }
            }
        }
    }

    @Composable
    private fun DemoBar(
        label: String,
        height: com.tencent.kuikly.compose.ui.unit.Dp,
        color: Color
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(height).background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(label, fontSize = 14.sp, color = Color.White)
        }
    }
}
