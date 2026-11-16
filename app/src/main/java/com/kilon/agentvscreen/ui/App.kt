package com.kilon.agentvscreen.ui

import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.Prefs
import com.kilon.agentvscreen.host.Setup
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/*
 * 1.4: the app is organised the way miuix apps are, not as the 1.3 home page with new widgets on it.
 *
 *  - Three tabs at the bottom, by what the user came for: 现在 (how it is now, does anything need me), 记录 (what the
 *    agent did, booked, watches and learned), 设置 (how agents connect, what they may do, how the screen behaves).
 *  - Every second-level page is a real page on a stack (miuix-nav): its own large title that folds into the bar, swipe
 *    and predictive back. Nothing swaps content inside one page any more.
 *  - Each page's content scrolls BEHIND its glass top bar: the bar blurs the page's own layer, which starts with the
 *    page colour. (beta1 put the content below the bar and blurred an empty layer: the grey block behind the title.)
 *  - Rows are miuix preference rows in cards under small titles. A row says its current value; explanations live on
 *    the Help page, not behind a "?" on every row. Results are said in a snackbar or on the row, never a system toast.
 */

/** Every page of the app. Saved with the back stack, so a page survives the activity being recreated. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Main : Route
    @Serializable data object Setup : Route
    @Serializable data object Connect : Route
    @Serializable data object Remote : Route
    @Serializable data object Tokens : Route
    @Serializable data object Rules : Route
    @Serializable data object Schedules : Route
    @Serializable data object Lessons : Route
    @Serializable data object Log : Route
    @Serializable data object Diag : Route
    @Serializable data object Help : Route
    @Serializable data class Task(val id: Long) : Route
}

/** What a page may ask of the activity. */
interface AppHost {
    fun copy(label: String, text: String)
    fun say(text: String)
    fun grantShizuku()
    fun diagnostics(items: List<Setup.Item>): String
    fun recreateForLanguage()
}

/** Moving between pages. */
@Stable
class Nav(val stack: NavBackStack, private val selectTab: (Int) -> Unit) {
    fun go(r: Route) { if (r !in stack) stack.add(r) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    /** Back to the main tabs, on tab [i]. */
    fun tab(i: Int) { while (stack.size > 1) stack.removeAt(stack.lastIndex); selectTab(i) }
}

/** What several pages read: the setup check, the switch, background trouble; refreshed every 2 s while shown. */
@Stable
class AppState {
    var items by mutableStateOf(emptyList<Setup.Item>())
    var serviceOn by mutableStateOf(false)
    var liveProblem by mutableStateOf<String?>(null)
    /** Bumped after an action so lists read their store again. */
    var tick by mutableIntStateOf(0)
    val allOk get() = items.isNotEmpty() && items.filter { it.key != "accessibility" }.all { it.ok }
    val left get() = items.count { !it.ok && it.key != "accessibility" }
    fun bump() { tick++ }
}

val LocalHost = staticCompositionLocalOf<AppHost> { error("no host") }
val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }
val LocalApp = staticCompositionLocalOf<AppState> { error("no state") }

/** The whole app: the page stack over the tabs, and one snackbar for short results. */
@Composable
fun AppRoot(host: AppHost, firstPage: Route?, snack: SnackbarHostState, firstTab: Int = 0) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = remember { AppState() }
    LaunchedEffect(Unit) {
        while (true) {
            app.items = withContext(Dispatchers.IO) { runCatching { BackgroundOperation.refreshStatus(); Setup.check(ctx) }.getOrDefault(app.items) }
            app.liveProblem = com.kilon.agentvscreen.host.Liveness.problem()
            app.serviceOn = Prefs.serviceOn(ctx)
            delay(2_000)
        }
    }
    val pager = rememberPagerState(initialPage = firstTab, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val stack = rememberNavBackStack<Route>(Route.Main)
    val nav = remember(stack) { Nav(stack) { i -> scope.launch { pager.animateScrollToPage(i) } } }
    LaunchedEffect(firstPage) { if (firstPage != null && firstPage != Route.Main) nav.go(firstPage) }
    CompositionLocalProvider(LocalHost provides host, LocalNav provides nav, LocalApp provides app) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(backStack = stack, onBack = { nav.back() }) {
                val swipe = NavSwipeDirection.LeftToRight
                entry<Route.Main> { MainTabs(pager) }
                entry<Route.Setup>(swipeDismiss = swipe) { SetupFlowPage() }
                entry<Route.Connect>(swipeDismiss = swipe) { ConnectPage() }
                entry<Route.Remote>(swipeDismiss = swipe) { RemotePage() }
                entry<Route.Tokens>(swipeDismiss = swipe) { TokensPage() }
                entry<Route.Rules>(swipeDismiss = swipe) { RulesPage() }
                entry<Route.Schedules>(swipeDismiss = swipe) { SchedulesPage() }
                entry<Route.Lessons>(swipeDismiss = swipe) { LessonsPage() }
                entry<Route.Log>(swipeDismiss = swipe) { LogPage() }
                entry<Route.Diag>(swipeDismiss = swipe) { DiagPage() }
                entry<Route.Help>(swipeDismiss = swipe) { HelpPage() }
                entry<Route.Task>(swipeDismiss = swipe) { r -> TaskPage(r.id) }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp))
        }
    }
}

/** The three tabs, swiped or picked in the floating bar; the bar blurs the tab behind it. */
@Composable
private fun MainTabs(pager: androidx.compose.foundation.pager.PagerState) {
    val scope = rememberCoroutineScope()
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = if (Glass.blurOk) rememberLayerBackdrop { drawRect(surface); drawContent() } else null
    val app = LocalApp.current
    val tabs = listOf(
        Triple(tr("现在", "Now"), MiuixIcons.Home, 0),
        Triple(tr("记录", "Records"), MiuixIcons.Recent, 1),
        Triple(tr("设置", "Settings"), MiuixIcons.Settings, 2),
    )
    Scaffold(
        bottomBar = {
            FloatingNavigationBar(
                modifier = if (backdrop != null) Modifier.textureBlur(
                    backdrop = backdrop, shape = RoundedCornerShape(50), blurRadius = 25f,
                    colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.6f)))),
                ) else Modifier,
                color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
            ) {
                tabs.forEach { (label, icon, i) ->
                    val needs = i == 0 && (pendingAsks().isNotEmpty() || (app.items.isNotEmpty() && !app.allOk))
                    val badge: (@Composable () -> Unit)? = if (needs) { { Dot(Palette.stopped) } } else null
                    FloatingNavigationBarItem(
                        selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        icon = icon, label = label,
                        // A dot on 现在 while something there needs the user.
                        badge = badge,
                    )
                }
            }
        },
    ) { pad ->
        Box(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            HorizontalPager(
                state = pager, beyondViewportPageCount = 1, verticalAlignment = Alignment.Top,
                flingBehavior = PagerDefaults.flingBehavior(state = pager),
            ) { page ->
                val bottom = pad.calculateBottomPadding()
                when (page) {
                    0 -> OverviewTab(bottom)
                    1 -> RecordsTab(bottom)
                    else -> SettingsTab(bottom)
                }
            }
        }
    }
}

@Composable
fun Dot(c: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(c))

/**
 * One page: a glass top bar with a large title that folds as the page scrolls under it, the content in a scrolling
 * column. [back] shows the back arrow (every page but the three tabs). [bottom] keeps the last group clear of the
 * floating bar on a tab.
 */
@Composable
fun MiuixPage(
    title: String,
    back: Boolean = true,
    bottom: Dp = 0.dp,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalNav.current
    val surface = MiuixTheme.colorScheme.surface
    val backdrop: LayerBackdrop? = if (Glass.blurOk) rememberLayerBackdrop { drawRect(surface); drawContent() } else null
    val bar = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            Box(if (backdrop != null) Modifier.textureBlur(backdrop = backdrop, shape = RectangleShape, blurRadius = 25f,
                colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(color = surface.copy(0.8f))))) else Modifier) {
                TopAppBar(
                    title = title, subtitle = subtitle, scrollBehavior = bar,
                    color = if (backdrop != null) Color.Transparent else surface,
                    navigationIcon = {
                        if (back) IconButton(onClick = { nav.back() }, modifier = Modifier.padding(start = 16.dp)) {
                            Icon(MiuixIcons.Back, tr("返回", "Back"))
                        }
                    },
                    actions = actions,
                )
            }
        },
    ) { pad ->
        Box(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            Column(
                Modifier.fillMaxSize().overScrollVertical().nestedScroll(bar.nestedScrollConnection).verticalScroll(rememberScrollState())
                    .padding(top = pad.calculateTopPadding(), bottom = maxOf(bottom, pad.calculateBottomPadding()) + 24.dp),
            ) { content() }
        }
    }
}

/** A group on a page: a small title, then a card of rows. Rows bring their own padding ([padded] for free content). */
@Composable
fun Group(title: String? = null, padded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) SmallTitle(title) else Spacer(Modifier.height(12.dp))
    Card(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp).fillMaxWidth(),
        insideMargin = if (padded) PaddingValues(16.dp) else PaddingValues(0.dp),
    ) {
        if (padded) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } else content()
    }
}
