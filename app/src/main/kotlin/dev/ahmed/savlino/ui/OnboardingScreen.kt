package dev.ahmed.savlino.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut
import dev.ahmed.savlino.ui.theme.rememberReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A local, illustrative tour: none of these drawings owns intake, jobs or an engine. */
@Composable
internal fun OnboardingScreen(onFinish: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val landscape = LocalConfiguration.current.screenHeightDp < 480
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    fun go(page: Int) { scope.launch {
        if (reduced) pager.scrollToPage(page) else pager.animateScrollToPage(page)
    } }
    BackHandler(pager.currentPage > 0) { go(pager.currentPage - 1) }
    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        Surface(Modifier.fillMaxSize().testTag("onboarding"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Space.xl)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.End) {
                    if (pager.currentPage < 3) TextButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                }
                // Pager and indicators inherit direction: the first page is at start in either language.
                HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth().testTag("onboarding_pager")) { page ->
                    val title = stringResource(when (page) {
                        0 -> R.string.onboarding_anywhere_title
                        1 -> R.string.onboarding_copy_title
                        2 -> R.string.onboarding_quality_title
                        else -> R.string.onboarding_saved_title
                    })
                    val body = stringResource(when (page) {
                        0 -> R.string.onboarding_anywhere_body
                        1 -> R.string.onboarding_copy_body
                        2 -> R.string.onboarding_quality_body
                        else -> R.string.onboarding_saved_body
                    })
                    val description = stringResource(when (page) {
                        0 -> R.string.onboarding_anywhere_illustration
                        1 -> R.string.onboarding_copy_illustration
                        2 -> R.string.onboarding_quality_illustration
                        else -> R.string.onboarding_saved_illustration
                    })
                    val illustration: @Composable (Modifier) -> Unit = { modifier ->
                        TourIllustration(page, description, active = pager.settledPage == page, modifier =
                            modifier.graphicsLayer {
                                translationX = if (reduced) 0f else
                                    (pager.currentPage - page + pager.currentPageOffsetFraction) * .25f * size.width * direction
                            })
                    }
                    val copy: @Composable (Modifier) -> Unit = { modifier ->
                        Column(modifier.graphicsLayer {
                            val offset = if (reduced) 0f else abs(pager.currentPage - page + pager.currentPageOffsetFraction)
                            alpha = (1f - offset).coerceIn(0f, 1f)
                            translationY = offset * 24.dp.toPx()
                        }, verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            Text(title, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold)
                            Text(body, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val heroHeight = (maxHeight * .5f).coerceIn(160.dp, 340.dp)
                        // Keep the scaled drawing inside the pager; only long landscape copy scrolls.
                        if (landscape) Row(Modifier.fillMaxSize().padding(vertical = Space.s),
                            horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.CenterVertically) {
                            illustration(Modifier.weight(1f).fillMaxHeight())
                            copy(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = Space.l))
                        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Space.s),
                            verticalArrangement = Arrangement.spacedBy(Space.xl)) {
                            illustration(Modifier.fillMaxWidth().height(heroHeight))
                            copy(Modifier.fillMaxWidth().padding(bottom = Space.l))
                        }
                    }
                }
                val position = stringResource(R.string.onboarding_page,
                    NezzelFormat.count(pager.settledPage + 1, "en"), NezzelFormat.count(4, "en"))
                val currentTitle = stringResource(when (pager.settledPage) {
                    0 -> R.string.onboarding_anywhere_title
                    1 -> R.string.onboarding_copy_title
                    2 -> R.string.onboarding_quality_title
                    else -> R.string.onboarding_saved_title
                })
                val announcement = stringResource(R.string.onboarding_page_announcement, position, currentTitle)
                Row(Modifier.align(Alignment.CenterHorizontally).padding(vertical = Space.s).testTag("onboarding_position")
                    .clearAndSetSemantics { contentDescription = announcement; liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    repeat(4) { index ->
                        val width by animateDpAsState(if (index == pager.currentPage) 24.dp else 8.dp,
                            easeOut(reduced), label = "page indicator")
                        Box(Modifier.testTag("onboarding_dot_$index").width(width).height(8.dp).background(
                            if (index == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    if (pager.currentPage > 0) TextButton(onClick = { go(pager.currentPage - 1) },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                    Button(onClick = { if (pager.currentPage == 3) onFinish() else go(pager.currentPage + 1) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(if (pager.currentPage == 3) R.string.onboarding_start else R.string.onboarding_next))
                    }
                }
            }
        }
    }
}
