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
import androidx.compose.animation.core.tween
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

private data class Route(val tab: RootTab, val session: String?)

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
      var showingNewSession by remember { mutableStateOf(false) }
      var showingProjects by remember { mutableStateOf(false) }
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
      val route = Route(currentTab, sessionStack.lastOrNull())
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
      var backDirection by remember { mutableStateOf(false) }
      var gestureRoute by remember { mutableStateOf<Route?>(null) }
      PredictiveBackHandler(enabled = SessionNavigation(currentTab, sessionStack).canGoBack && !keyboardOpen && !chatModal && !showingServersSheet && !showingNewSession && !showingProjects) { progress ->
        gestureActive = true; gestureRoute = route; backDirection = true
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
              val enter = if (backDirection) -1 else 1
              (fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it * enter / 12 }) togetherWith
                (fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { -it * enter / 12 })
            }, label = "navigation") { target ->
              val active = target == route
              val displayed = if (target.session == state.sessionId) state else snapshots["${state.serverId}:${target.session}"] ?: state
              Box(Modifier.fillMaxSize().graphicsLayer {
                if (target == gestureRoute && gestureActive) { translationX = backProgress.value * size.width * 0.16f; scaleX = 1f - backProgress.value * 0.02f; scaleY = scaleX }
              }.then(if (active) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } } })
                .background(MiuixTheme.colorScheme.background)) {
                if (target.session != null) holder.SaveableStateProvider("chat:${state.serverId}:${target.session}") {
                  ChatScreen(displayed, controller, onBack = { backDirection = true; goBack() }, onOpenChild = { backDirection = false; openSession(it, true) }, onModal = { if (active) chatModal = it }, interactive = active)
                } else holder.SaveableStateProvider("root:${state.serverId}:${target.tab}") {
                  Column(Modifier.fillMaxSize().then(if (target.tab == RootTab.SESSIONS)
                    Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) else Modifier)) {
                    when (target.tab) {
                      RootTab.SESSIONS -> Unit
                      RootTab.ACTIVITY -> SmallTopAppBar(title = "活动", actions = { IconButton(onClick = controller::reload) { Icon(MiuixIcons.Refresh, "刷新") } })
                      RootTab.SETTINGS -> SmallTopAppBar(title = "设置")
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                      if (wide) NavigationRail {
                        val icons = listOf(MiuixIcons.VerticalSplit, MiuixIcons.Tasks, MiuixIcons.Settings)
                        RootTab.entries.forEachIndexed { i, tab -> NavigationRailItem(target.tab == tab, { backDirection = false; currentTab = tab }, icons[i], tab.label) }
                      }
                      Box(Modifier.weight(1f).fillMaxHeight()) {
                      when (target.tab) {
                        RootTab.SESSIONS -> SessionsHomeScreen(state, controller, { backDirection = false; openSession(it) }, { showingServersSheet = true }, { showingNewSession = true }, { showingProjects = true })
                        RootTab.ACTIVITY -> ActivityScreen(state, controller) { backDirection = false; openSession(it) }
                        RootTab.SETTINGS -> SettingsScreen(state, controller, themeMode, { themeMode = it; preferences.edit().putString("themeMode", it.name).apply() }, previewBack, { previewBack = it; preferences.edit().putBoolean("previewBack", it).apply() }, {
                          if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                          else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        }, { showingServersSheet = true })
                      }
                      }
                    }
                    if (!wide && state.profiles.isNotEmpty()) MiuixNavigationDock(target.tab, { backDirection = false; currentTab = it }, dark)
                  }
                }
              }
            }
            if (state.loading || state.pendingOperations.any { ":open:" in it }) InfiniteProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).height(3.dp))
            MiuixToastHost(globalMessage, globalMessageType, { globalMessage = null }, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp))
            // MIUIX popups must stay inside this Scaffold content's popup registry.
            if (showingServersSheet) ServersModal(state, controller, { showingServersSheet = false }, { showingServersSheet = false; currentTab = RootTab.SESSIONS })
            if (showingNewSession) NewSessionSheet(state, controller, { showingNewSession = false }, { showingNewSession = false; showingServersSheet = true }) { session -> showingNewSession = false; openSession(session.id) }
            if (showingProjects) ProjectPicker(state, controller) { showingProjects = false }
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

@Composable
private fun ProjectPicker(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  var directory by rememberSaveable(state.serverId) { mutableStateOf("") }
  var query by rememberSaveable(state.serverId) { mutableStateOf("") }
  val projects = state.projects.filter { query.isBlank() || it.name.contains(query, true) || it.directory.contains(query, true) }
  var error by remember { mutableStateOf<String?>(null) }
  SuperBottomSheet(title = "切换项目 / 目录", show = true, onDismissRequest = onDismiss) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(16.dp)) {
      item { TextField(query, { query = it }, label = "搜索项目名称或目录", modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
      items(projects.size) { index -> val project = projects[index]
        Card(onClick = { controller.selectProject(project.id); onDismiss() }, insideMargin = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
          Text(project.name, color = if (state.projectId == project.id) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
          Text(project.directory, style = MiuixTheme.textStyles.footnote2)
        }
      }
      item { TextField(directory, { directory = it }, label = "添加服务器上的绝对目录", modifier = Modifier.fillMaxWidth()); error?.let { Text(it, color = MiuixColorTokens.Error) }
        TextButton(text = "添加并切换", enabled = directory.isNotBlank() && state.connected, onClick = {
          runCatching { controller.addProjectDirectory(directory) }.onSuccess { directory = ""; onDismiss() }.onFailure { error = it.message }
        })
      }
    }
  }
}
