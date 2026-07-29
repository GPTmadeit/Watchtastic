package com.watchtastic.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.NoticeHost
import com.watchtastic.ui.screens.ChannelsScreen
import com.watchtastic.ui.screens.ChatScreen
import com.watchtastic.ui.screens.CompassScreen
import com.watchtastic.ui.screens.ConnectScreen
import com.watchtastic.ui.screens.ConversationsScreen
import com.watchtastic.ui.screens.HomeScreen
import com.watchtastic.ui.screens.MapScreen
import com.watchtastic.ui.screens.NodeDetailScreen
import com.watchtastic.ui.screens.NodesScreen
import com.watchtastic.ui.screens.QuickRepliesScreen
import com.watchtastic.ui.screens.RadioConfigScreen
import com.watchtastic.ui.screens.SettingsScreen
import com.watchtastic.ui.screens.StatusScreen
import com.watchtastic.ui.screens.UpdateScreen
import com.watchtastic.ui.screens.WaypointsScreen

/**
 * The whole app's navigation.
 *
 * [AppScaffold] owns the persistent time text at the top of every screen, and
 * [SwipeDismissableNavHost] wires the Wear back gesture — a swipe from the left edge —
 * to popping the stack, which is what wearers expect from every first-party watch app.
 */
@Composable
fun WatchtasticNav(
    startDestination: String,
    deepLinkRoute: String? = null,
    onDeepLinkHandled: () -> Unit = {},
    navController: NavHostController = rememberSwipeDismissableNavController(),
) {
    val graph = LocalAppGraph.current
    val savedRadio by graph.prefs.radioAddress.collectAsStateWithLifecycle()

    // A notification tap arrives as a conversation key. Clearing it afterwards matters:
    // otherwise tapping the same conversation's notification twice would be a no-op,
    // because the key wouldn't have changed.
    LaunchedEffect(deepLinkRoute) {
        if (deepLinkRoute != null && savedRadio != null) {
            navController.navigate(Routes.chat(deepLinkRoute)) { launchSingleTop = true }
            onDeepLinkHandled()
        }
    }

    AppScaffold {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = startDestination,
        ) {
            composable(Routes.CONNECT) {
                ConnectScreen(
                    onConnected = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.CONNECT) { inclusive = true }
                            // Connect is reachable from Home as well as at cold start;
                            // without this, returning here would stack a second Home.
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) },
                    onOpenNodes = { navController.navigate(Routes.NODES) },
                    onOpenChannels = { navController.navigate(Routes.CHANNELS) },
                    onOpenMap = { navController.navigate(Routes.MAP) },
                    onOpenWaypoints = { navController.navigate(Routes.WAYPOINTS) },
                    onOpenStatus = { navController.navigate(Routes.STATUS) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenConnect = { navController.navigate(Routes.CONNECT) },
                )
            }

            composable(Routes.CONVERSATIONS) {
                ConversationsScreen(
                    onOpenChat = { key -> navController.navigate(Routes.chat(key)) },
                )
            }

            composable(
                route = Routes.CHAT,
                arguments = listOf(navArgument(Routes.ARG_KEY) { type = NavType.StringType }),
            ) { entry ->
                val key = entry.arguments?.getString(Routes.ARG_KEY).orEmpty()
                ChatScreen(
                    conversationKey = key,
                    onOpenNode = { num -> navController.navigate(Routes.nodeDetail(num)) },
                )
            }

            composable(Routes.NODES) {
                NodesScreen(
                    onOpenNode = { num -> navController.navigate(Routes.nodeDetail(num)) },
                )
            }

            composable(
                route = Routes.NODE_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_NUM) { type = NavType.IntType }),
            ) { entry ->
                val num = entry.arguments?.getInt(Routes.ARG_NUM) ?: 0
                NodeDetailScreen(
                    nodeNum = num,
                    onOpenChat = { key -> navController.navigate(Routes.chat(key)) },
                    onOpenCompass = { navController.navigate(Routes.compass(num)) },
                    onRemoved = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.COMPASS,
                arguments = listOf(navArgument(Routes.ARG_NUM) { type = NavType.IntType }),
            ) { entry ->
                CompassScreen(nodeNum = entry.arguments?.getInt(Routes.ARG_NUM) ?: 0)
            }

            composable(Routes.CHANNELS) {
                ChannelsScreen(
                    onOpenChat = { key -> navController.navigate(Routes.chat(key)) },
                )
            }

            composable(Routes.STATUS) { StatusScreen() }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenRadioConfig = { navController.navigate(Routes.RADIO_CONFIG) },
                    onOpenQuickReplies = { navController.navigate(Routes.QUICK_REPLIES) },
                    onOpenUpdate = { navController.navigate(Routes.UPDATE) },
                    onForgetRadio = {
                        navController.navigate(Routes.CONNECT) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.RADIO_CONFIG) { RadioConfigScreen() }

            composable(Routes.MAP) {
                MapScreen(
                    onOpenNode = { num -> navController.navigate(Routes.nodeDetail(num)) },
                )
            }

            composable(Routes.WAYPOINTS) { WaypointsScreen() }

            composable(Routes.QUICK_REPLIES) { QuickRepliesScreen() }

            composable(Routes.UPDATE) { UpdateScreen() }
        }

        // Declared last so it paints above whichever screen is showing. One host for the
        // whole app, so asynchronous results surface wherever the wearer happens to be.
        NoticeHost()
    }
}
