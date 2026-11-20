package com.watchtastic.glance

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.watchtastic.MainActivity
import com.watchtastic.R
import com.watchtastic.di.AppGraph
import com.watchtastic.mesh.model.LinkState

/**
 * Unread mesh messages on the watch face itself.
 *
 * Short text is the count with the mesh glyph — the watch-face equivalent of an app
 * badge. Long text adds link and node status for faces with a wide slot. The glyph
 * alone is offered too, as a one-tap launcher that still says which app it opens.
 */
class MeshComplicationService : SuspendingComplicationDataSourceService() {

    private val graph by lazy { AppGraph.from(this) }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, graph.glance.current())

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(
            type,
            MeshGlance.of(
                link = LinkState.Connected("Radio"),
                hasSavedRadio = true,
                messages = emptyList(),
                lastRead = emptyMap(),
                nodes = emptyList(),
                myNodeNum = 0,
                nowMs = 0,
            ).copy(unread = 3, onlineNodes = 7, totalNodes = 12),
        )

    private fun build(type: ComplicationType, glance: MeshGlance): ComplicationData? {
        val image = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
        ).build()
        val tap = tapIntent(glance)
        val description = glance.spokenSummary.plain()

        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(glance.unreadShort.plain(), description)
                    .setMonochromaticImage(image)
                    // A face that shows a title gets the link state for free: while the
                    // mesh is down, "Offline" says more than the count does.
                    .setTitle(glance.statusShort.plain())
                    .setTapAction(tap)
                    .build()

            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(glance.summary.plain(), description)
                    .setTitle((glance.radioName ?: "Watchtastic").plain())
                    .setMonochromaticImage(image)
                    .setTapAction(tap)
                    .build()

            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(image, description)
                    .setTapAction(tap)
                    .build()

            else -> null
        }
    }

    private fun tapIntent(glance: MeshGlance): PendingIntent {
        val route = glance.tapTarget
        val intent = Intent(this, MainActivity::class.java)
            .setPackage(packageName)
            // SINGLE_TOP only, as in Notifier: MainActivity is singleTask already, and
            // NEW_TASK/CLEAR_TOP would make the app misrepresent itself in Wear recents.
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun String.plain() = PlainComplicationText.Builder(this).build()

    private companion object {
        /** Distinct from the notifier's codes so the two never overwrite each other's extras. */
        const val REQUEST_CODE = 0x7710
    }
}
