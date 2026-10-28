package com.alikarami.drooz.ui

import android.widget.Toast
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.alikarami.drooz.AppGraph
import com.alikarami.drooz.R
import com.alikarami.drooz.player.PlayerActivity
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.home.HomeScreen
import com.alikarami.drooz.ui.home.HomeViewModel
import com.alikarami.drooz.ui.home.HomeViewModelFactory
import com.alikarami.drooz.ui.home.RailItem
import com.alikarami.drooz.ui.list.ListScreen
import com.alikarami.drooz.ui.list.ListViewModel
import com.alikarami.drooz.ui.list.ListViewModelFactory
import com.alikarami.drooz.ui.login.LoginScreen
import com.alikarami.drooz.ui.login.LoginViewModel
import com.alikarami.drooz.ui.login.LoginViewModelFactory
import com.alikarami.drooz.ui.search.SearchScreen
import com.alikarami.drooz.ui.search.SearchViewModel
import com.alikarami.drooz.ui.search.SearchViewModelFactory
import com.alikarami.drooz.ui.title.TitleScreen
import com.alikarami.drooz.ui.title.TitleViewModel
import com.alikarami.drooz.ui.title.TitleViewModelFactory
import kotlinx.coroutines.flow.first

/** Debug-build launch shortcuts (MainActivity reads them from the intent on a fresh start only). */
data class DebugLaunch(val login: DebugLogin? = null, val playPath: String? = null)
data class DebugLogin(val username: String, val password: String, val mobile: String?)

/**
 * The app's screens. Each back stack entry owns its ViewModels and saved UI state, so Back restores the
 * focused card, query and scroll of the page below. No transitions: TV pages switch instantly, and a
 * page that is being left never keeps focus or reacts to a late result.
 */
@Composable
fun DroozNavHost(
    graph: AppGraph,
    appVm: AppViewModel,
    nav: NavHostController,
    onExit: () -> Unit,
    debug: DebugLaunch? = null,
) {
    val context = LocalContext.current
    var pendingLogin by remember { mutableStateOf(debug?.login) }

    /** Clears the whole back stack (stale focus / scroll / view models go with it) and shows [route]. */
    fun resetTo(route: Any) = nav.navigate(route) {
        popUpTo(nav.graph.id) { inclusive = true }
        launchSingleTop = true
    }

    /** Opens a title; home's featured backdrop for it (if home has one) rides along. */
    fun openTitle(from: NavBackStackEntry?, ref: TitleRef, autoPlay: Boolean = false) {
        if (from != null && !from.isResumed()) return // a second OK while the first navigation is under way
        val home = runCatching { nav.getBackStackEntry<Home>() }.getOrNull()
        val featured = home?.let { ViewModelProvider(it, HomeViewModelFactory(graph))[HomeViewModel::class.java] }
            ?.featuredBackdrop(ref)
        nav.navigate(Title.of(ref, featured, autoPlay))
    }

    fun go(from: NavBackStackEntry, route: Any) {
        if (from.isResumed()) nav.navigate(route)
    }

    LaunchedEffect(appVm) {
        appVm.navigation.collect { if (it == AppNav.ToLogin) resetTo(Login) }
    }
    LaunchedEffect(Unit) {
        if (debug?.login != null) resetTo(Login)
        val ref = debug?.playPath?.let(TitleRef::fromPath)
        if (ref != null && graph.authApi.isLoggedIn) openTitle(null, ref, autoPlay = true)
    }

    NavHost(
        nav,
        startDestination = appVm.startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable<Login> {
            val vm: LoginViewModel = viewModel(factory = LoginViewModelFactory(graph))
            // Debug login: fill the view model before the form first composes (the form seeds its fields
            // from it once), then submit.
            val debugLogin = remember(vm) {
                pendingLogin?.also { p ->
                    vm.onUsername(p.username)
                    vm.onPassword(p.password)
                    p.mobile?.let(vm::onMobile)
                }
            }
            LaunchedEffect(debugLogin) {
                if (debugLogin == null) return@LaunchedEffect
                pendingLogin = null
                vm.submit()
            }
            LoginScreen(vm) {
                appVm.onLoggedIn()
                resetTo(Home)
            }
        }
        composable<Home> { entry ->
            val vm: HomeViewModel = viewModel(factory = HomeViewModelFactory(graph))
            HomeScreen(vm, onOpen = { openTitle(entry, it) }, onExit = onExit, onRailSelect = { item ->
                when (item) {
                    RailItem.Search -> go(entry, Search)
                    RailItem.Home -> Unit // HomeScreen closes the rail
                    RailItem.Series ->
                        go(entry, ListRoute(context.getString(R.string.list_updated_series), "/archive/series/"))
                    RailItem.Films ->
                        go(entry, ListRoute(context.getString(R.string.list_new_films), "/archive/category/new-films/"))
                    RailItem.MyList, RailItem.Settings -> // Plan 4
                        Toast.makeText(context, R.string.soon, Toast.LENGTH_SHORT).show()
                }
            })
        }
        composable<Title> { entry ->
            val route = entry.toRoute<Title>()
            val vm: TitleViewModel = viewModel(factory = TitleViewModelFactory(graph, route.ref))
            val played by graph.titleInfo.playedBackdrops.collectAsStateWithLifecycle()
            if (route.autoPlay) {
                var started by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(vm) {
                    if (started) return@LaunchedEffect
                    vm.uiState.first { it.primaryEpisode != null }
                    started = true
                    vm.play()
                }
            }
            TitleScreen(
                route.ref, vm,
                onStartPlayer = { context.startActivity(PlayerActivity.intent(context, it)) },
                onOpenTitle = { openTitle(entry, it) },
                featuredBackdropHash = route.featuredBackdrop,
                playedBackdrops = played,
            )
        }
        composable<ListRoute> { entry ->
            val route = entry.toRoute<ListRoute>()
            val vm: ListViewModel = viewModel(factory = ListViewModelFactory(graph, route.path, route.title))
            ListScreen(vm, onOpen = { openTitle(entry, it) })
        }
        composable<Search> { entry ->
            val vm: SearchViewModel = viewModel(factory = SearchViewModelFactory(graph))
            SearchScreen(vm, onOpen = { openTitle(entry, it) })
        }
    }
}

private fun NavBackStackEntry.isResumed() = lifecycle.currentState == Lifecycle.State.RESUMED
