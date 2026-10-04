package com.igng.opencode.lagoon.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
  private var deepLink by mutableStateOf<Pair<String, String>?>(null)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    parseDeepLink(intent)
    val controller = LagoonController.get(this)
    // Opening the app refreshes from the server; leaving it lets the live stream wind down.
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) controller.onForeground()
      else if (event == Lifecycle.Event.ON_STOP) controller.onBackground()
    })
    setContent {
      val state by controller.state.collectAsState()
      val preferences = remember { getSharedPreferences("ui", MODE_PRIVATE) }
      var themeMode by remember { mutableStateOf(runCatching { ThemeMode.valueOf(preferences.getString("themeMode", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)) }
      val systemDark = isSystemInDarkTheme()
      val dark = themeMode == ThemeMode.DARK || themeMode == ThemeMode.SYSTEM && systemDark
      var previewBack by remember { mutableStateOf(preferences.getBoolean("previewBack", true)) }
      var currentTab by rememberSaveable { mutableStateOf(RootTab.SESSIONS) }
      var sessionStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
      var navigationServer by rememberSaveable { mutableStateOf(state.serverId) }
      var showingServersSheet by remember { mutableStateOf(false) }
      var showingProjects by remember { mutableStateOf(false) }
      var showingAddProject by remember { mutableStateOf(false) }
      // Each blank draft gets a fresh saveable scope so a sent draft never reappears.
      var draftNonce by rememberSaveable { mutableIntStateOf(0) }
      var chatModal by remember { mutableStateOf(false) }
      var globalMessage by remember { mutableStateOf<String?>(null) }
      var globalMessageType by remember { mutableStateOf(MiuixToastType.INFO) }
      val holder = rememberSaveableStateHolder()
      val snapshots = remember { mutableMapOf<String, LagoonState>() }
      val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
      val focusManager = LocalFocusManager.current
      val density = LocalDensity.current
      val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
      val wide = LocalConfiguration.current.screenWidthDp >= 640
      val inChatDetail = sessionStack.isNotEmpty()
      val route = NavRoute(currentTab, sessionStack.lastOrNull(), sessionStack.size)
      fun openSession(id: String, child: Boolean = false) {
        focusManager.clearFocus()
        if (state.session != null) snapshots["${state.serverId}:${state.sessionId}"] = state
        val known = controller.state.value.sessions.firstOrNull { it.id == id }
        if (child || known?.parentId == null && known != null) {
          sessionStack = SessionNavigation(currentTab, sessionStack).open(id, child).sessions
          if (controller.state.value.sessionId != id) controller.selectSession(id)
        } else {
          val originTab = currentTab; val originStack = sessionStack
          controller.resolveSession(id) { lineage ->
          if (currentTab != originTab || sessionStack != originStack) return@resolveSession
            sessionStack = lineage
            if (controller.state.value.sessionId != id) controller.selectSession(id)
          }
        }
      }
      fun openDraft() {
        focusManager.clearFocus()
        controller.beginDraft()
        draftNonce += 1
        sessionStack = listOf(DRAFT_SESSION)
      }
      fun goBack() {
        focusManager.clearFocus()
        val next = SessionNavigation(currentTab, sessionStack).back()
        sessionStack = next.sessions; currentTab = next.tab
        next.sessions.lastOrNull()?.let(controller::selectSession)
      }
      LaunchedEffect(state.serverId) {
        if (navigationServer != state.serverId) { sessionStack = emptyList(); snapshots.clear(); navigationServer = state.serverId }
      }
      LaunchedEffect(state.sessionId) {
        val id = state.sessionId
        if (inChatDetail && id != null && sessionStack.lastOrNull() != id) {
          sessionStack = if (state.session?.parentId == sessionStack.lastOrNull()) sessionStack + id else sessionStack.dropLast(1) + id
        }
      }
      SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        if (state.sessionId != null) {
          snapshots["${state.serverId}:${state.sessionId}"] = state
          if (snapshots.size > 8) snapshots.keys.firstOrNull { it != "${state.serverId}:${state.sessionId}" }?.let(snapshots::remove)
        }
      }
      DisposableEffect(state.serverId, state.sessionId, inChatDetail) {
        val server = state.serverId; val session = state.sessionId
        fun visible(value: Boolean) { if (server != null && session != null) controller.conversationVisible(server, session, value && inChatDetail) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) visible(true) else if (event == Lifecycle.Event.ON_PAUSE) visible(false) }
        lifecycle.addObserver(observer); visible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); visible(false) }
      }
      LaunchedEffect(state.profiles.isEmpty()) { if (state.profiles.isEmpty()) showingServersSheet = true }
      LaunchedEffect(state.error) { state.error?.let { globalMessage = it; globalMessageType = MiuixToastType.ERROR; controller.clearError() } }
      LaunchedEffect(state.message) { state.message?.let { globalMessage = it; globalMessageType = MiuixToastType.SUCCESS; controller.clearMessage() } }
      LaunchedEffect(deepLink, state.serverId, state.connected) {
        val (server, session) = deepLink ?: return@LaunchedEffect
        if (state.serverId != server) {
          if (state.profiles.any { it.id == server }) controller.connect(server) else { globalMessage = "这个服务器已移除"; globalMessageType = MiuixToastType.ERROR; deepLink = null }
        } else if (state.connected) { openSession(session); deepLink = null }
      }
      val backProgress = remember { Animatable(0f) }
      var gestureActive by remember { mutableStateOf(false) }
      var gestureRoute by remember { mutableStateOf<NavRoute?>(null) }
      PredictiveBackHandler(enabled = SessionNavigation(currentTab, sessionStack).canGoBack && !keyboardOpen && !chatModal && !showingServersSheet && !showingProjects && !showingAddProject) { progress ->
        gestureActive = true; gestureRoute = route
        try {
          progress.collect { if (previewBack) backProgress.snapTo(it.progress.coerceIn(0f, 1f)) }
          goBack()
          // Leave the outgoing surface at its gesture position until its exit transition finishes.
          delay(240); backProgress.snapTo(0f)
        } catch (cancel: CancellationException) {
          withContext(NonCancellable) { backProgress.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 500f)) }
          throw cancel
        } finally { gestureActive = false }
      }
      OpenCodeMiuixTheme(dark) {
        Scaffold(
          modifier = Modifier.imePadding(),
          contentWindowInsets = WindowInsets(0, 0, 0, 0),
          topBar = {},
          bottomBar = {}
        ) { insets ->
          Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            if (gestureActive && inChatDetail) {
              val parent = sessionStack.dropLast(1).lastOrNull()?.let { snapshots["${state.serverId}:$it"] }
              Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text(parent?.session?.let(parent::title) ?: currentTab.label, style = MiuixTheme.textStyles.title2)
                parent?.messages?.filter { it.isDisplayable }?.takeLast(3)?.forEach { message -> Text(message.parts.filter { it.type == "text" }.joinToString("\n") { it.text }.take(500), modifier = Modifier.padding(top = 16.dp)) }
              }
            }
            AnimatedContent(route, transitionSpec = {
              // The first send turns the draft into the real session in place, without a page change.
              if (initialState.session == DRAFT_SESSION && targetState.depth == initialState.depth && targetState.session != DRAFT_SESSION) {
                EnterTransition.None togetherWith ExitTransition.None
              } else {
                // Direction comes from the route change itself: tabs to the right / deeper pages enter
                // from the right, tabs to the left / going back enter from the left.
                val direction = slideDirection(initialState, targetState)
                val motion = tween<IntOffset>(300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                (fadeIn(tween(220, delayMillis = 40)) + slideInHorizontally(motion) { it * direction / 4 }) togetherWith
                  (fadeOut(tween(160)) + slideOutHorizontally(motion) { -it * direction / 4 })
              }
            }, label = "navigation") { target ->
              val active = target == route
              val displayed = if (target.session == state.sessionId) state else snapshots["${state.serverId}:${target.session}"] ?: state
              Box(Modifier.fillMaxSize().graphicsLayer {
                if (target == gestureRoute && gestureActive) { translationX = backProgress.value * size.width * 0.16f; scaleX = 1f - backProgress.value * 0.02f; scaleY = scaleX }
              }.then(if (active) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } } })
                .background(MiuixTheme.colorScheme.background)) {
                if (target.session == DRAFT_SESSION) holder.SaveableStateProvider("draft:${state.serverId}:$draftNonce") {
                  DraftScreen(state, controller, onBack = { goBack() }, onModal = { if (active) chatModal = it }, interactive = active)
                } else if (target.session != null) holder.SaveableStateProvider("chat:${state.serverId}:${target.session}") {
                  ChatScreen(displayed, controller, onBack = { goBack() }, onOpenChild = { openSession(it, true) }, onModal = { if (active) chatModal = it }, interactive = active)
                } else holder.SaveableStateProvider("root:${state.serverId}:${target.tab}") {
                  // HyperOS large-title bars that collapse on scroll; refresh is pull-to-refresh.
                  val scroll = MiuixScrollBehavior(rememberTopAppBarState())
                  Column(Modifier.fillMaxSize()) {
                    when (target.tab) {
                      RootTab.SESSIONS -> SelectorTopBar(
                        title = state.scopeProjectId?.let { id -> state.projects.firstOrNull { it.id == id }?.name } ?: "全部会话",
                        onTitleClick = { showingProjects = true },
                        scrollBehavior = scroll,
                        navigation = {
                          CapsuleSelector(state.server?.name ?: "选择服务器", { showingServersSheet = true }, maxTextWidth = 120.dp, leading = {
                            StatusDot(when {
                              state.connected && state.streamConnected -> MiuixColorTokens.Success
                              state.connected || state.loading -> MiuixColorTokens.Warning
                              else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            })
                          })
                        },
                        actions = { IconButton(onClick = { openDraft() }, enabled = state.connected) { Icon(MiuixIcons.Add, "新建会话") } }
                      )
                      RootTab.ACTIVITY -> TopAppBar(title = "活动", color = MiuixTheme.colorScheme.background, scrollBehavior = scroll)
                      RootTab.SETTINGS -> TopAppBar(title = "设置", color = MiuixTheme.colorScheme.background, scrollBehavior = scroll)
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                      if (wide) NavigationRail {
                        val icons = listOf(MiuixIcons.VerticalSplit, MiuixIcons.Tasks, MiuixIcons.Settings)
                        RootTab.entries.forEachIndexed { i, tab -> NavigationRailItem(target.tab == tab, { currentTab = tab }, icons[i], tab.label) }
                      }
                      Box(Modifier.weight(1f).fillMaxHeight()) {
                      when (target.tab) {
                        RootTab.SESSIONS -> SessionsHomeScreen(state, controller, { openSession(it) }, { showingServersSheet = true }, { openDraft() }, scroll)
                        RootTab.ACTIVITY -> ActivityScreen(state, controller, scroll) { openSession(it) }
                        RootTab.SETTINGS -> SettingsScreen(state, controller, themeMode, { themeMode = it; preferences.edit().putString("themeMode", it.name).apply() }, previewBack, { previewBack = it; preferences.edit().putBoolean("previewBack", it).apply() }, {
                          if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                          else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        }, { showingServersSheet = true }, scroll)
                      }
                      }
                    }
                    if (!wide && state.profiles.isNotEmpty()) MiuixNavigationDock(target.tab, { currentTab = it }, dark)
                  }
                }
              }
            }
            if (state.loading || state.pendingOperations.any { ":open:" in it }) InfiniteProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).height(3.dp))
            MiuixToastHost(globalMessage, globalMessageType, { globalMessage = null }, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp))
            // MIUIX popups must stay inside this Scaffold content's popup registry.
            if (showingServersSheet) ServersModal(state, controller, { showingServersSheet = false }, { showingServersSheet = false; currentTab = RootTab.SESSIONS })
            if (showingProjects) ProjectScopeSheet(state, controller, { showingProjects = false }) { showingProjects = false; showingAddProject = true }
            if (showingAddProject) DirectoryBrowserSheet(state, controller, { showingAddProject = false }) { showingAddProject = false }
          }
        }
      }
    }
  }
  override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); parseDeepLink(intent) }
  private fun parseDeepLink(intent: Intent?) {
    val uri = intent?.data ?: return
    if (uri.scheme != "opencode-lagoon" || uri.host != "server") return
    val parts = uri.pathSegments
    if (parts.size >= 3 && parts[1] == "session") deepLink = parts[0] to parts[2]
  }
}
