package com.watchtastic.glance

import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.CardDefaults.filledTonalCardColors
import androidx.wear.protolayout.material3.CardDefaults.filledCardColors
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textDataCard
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import com.watchtastic.MainActivity
import com.watchtastic.di.AppGraph
import com.watchtastic.ui.nav.Routes
import kotlin.time.Duration.Companion.minutes

/**
 * The Watchtastic tile: link status, unread messages and nodes in range, one swipe from
 * the watch face.
 *
 * Opening the app to answer "is anything happening on the mesh?" costs a launch, a
 * splash and a scroll. The tile answers it at a glance, and each number is also the way
 * in to the screen that explains it.
 */
class MeshTileService : Material3TileService(
    // Brand colours rather than the wallpaper's: green means "mesh is up" everywhere
    // else in the app, and a tile tinted to the wallpaper would lose that.
    allowDynamicTheme = false,
    defaultColorScheme = ColorScheme(
        primary = 0xFF67EA94.argb,
        onPrimary = 0xFF05150C.argb,
        primaryContainer = 0xFF16452B.argb,
        onPrimaryContainer = 0xFFC9FBDC.argb,
        secondaryContainer = 0xFF2C2D3C.argb,
        onSecondaryContainer = 0xFFD6DCEC.argb,
        tertiary = 0xFFF0B559.argb,
        onSurface = 0xFFE6E8F0.argb,
        onSurfaceVariant = 0xFFA8B0C8.argb,
    ),
) {

    private val graph by lazy { AppGraph.from(this) }

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val glance = graph.glance.current()
        return tile(
            timeline = Timeline.fromLayoutElement(layout(glance)),
            // Node liveness ages with no packet to trigger a refresh, so let the system
            // re-ask now and then; everything else is pushed by GlanceUpdater.
            freshness = 15.minutes,
        )
    }

    private fun MaterialScope.layout(glance: MeshGlance): LayoutElement = primaryLayout(
        titleSlot = {
            text(
                (glance.radioName?.takeIf { glance.isConnected } ?: glance.status).layoutString,
                color = if (glance.isConnected) colorScheme.primary else colorScheme.onSurfaceVariant,
            )
        },
        mainSlot = {
            buttonGroup {
                buttonGroupItem {
                    textDataCard(
                        // Unlike the edge button this never falls back to home: with
                        // nothing unread, the list is still where you'd go to write.
                        onClick = open(glance.unreadConversation ?: Routes.CONVERSATIONS, "unread"),
                        modifier = LayoutModifier.contentDescription(
                            "${glance.unread} unread message${if (glance.unread == 1) "" else "s"}",
                        ),
                        // Highlighted only when there's something to read, so the card
                        // that wants attention is the one that looks like it.
                        colors = if (glance.unread > 0) filledCardColors() else filledTonalCardColors(),
                        title = { text(glance.unreadShort.layoutString) },
                        content = { text("Unread".layoutString) },
                    )
                }
                buttonGroupItem {
                    textDataCard(
                        onClick = open(Routes.NODES, "nodes"),
                        modifier = LayoutModifier.contentDescription(
                            "${glance.onlineNodes} of ${glance.totalNodes} nodes online",
                        ),
                        colors = filledTonalCardColors(),
                        title = { text(glance.onlineNodes.toString().layoutString) },
                        content = { text("Online".layoutString) },
                    )
                }
            }
        },
        bottomSlot = {
            textEdgeButton(
                onClick = open(Routes.HOME, "open"),
                colors = filledTonalButtonColors(),
                labelContent = {
                    text(
                        when {
                            glance.isConnected -> "Open"
                            glance.isTransient -> glance.status
                            else -> "Connect"
                        }.layoutString,
                        typography = Typography.LABEL_MEDIUM,
                    )
                },
            )
        },
    )

    /**
     * Launches the app at [route] via the same deep-link extra notifications use, so a
     * tap lands where the wearer expects instead of on whatever screen was last open.
     */
    private fun open(route: String, id: String): Clickable = clickable(
        action = ActionBuilders.launchAction(
            ComponentName(this, MainActivity::class.java),
            mapOf(MainActivity.EXTRA_ROUTE to ActionBuilders.stringExtra(route)),
        ),
        id = id,
    )
}
