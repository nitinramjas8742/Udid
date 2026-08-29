package com.example.udid.util

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.example.udid.data.UsageReport
import com.example.udid.ui.ReportPeriod
import com.example.udid.ui.ShareReportPayload
import com.example.udid.ui.ShareableReportCard
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Renders the shareable report card off-screen and hands it to the Android
 * share sheet as a PNG file.
 *
 * # How the card becomes a bitmap (new concept)
 *
 * The card is a normal Compose composable ([ShareableReportCard]). To turn it
 * into an image without showing it on screen, we:
 *
 *  1. Temporarily add a [ComposeView] to the Activity's content view hierarchy.
 *     This is critical: Compose requires LifecycleOwner, ViewModelStoreOwner,
 *     and SavedStateRegistryOwner — all of which the Activity provides to its
 *     child views automatically. A detached ComposeView crashes because these
 *     are missing.
 *
 *  2. The ComposeView measures and lays itself out at the fixed card pixel size
 *     (1080x1350 px at density 2f).
 *
 *  3. We call [android.view.View.draw] on the ComposeView, which draws the
 *     Compose display list into an Android [Bitmap] via its Canvas. This is
 *     the same path Android uses for screen rendering, just redirected into
 *     an in-memory bitmap.
 *
 *  4. The bitmap is removed from the view hierarchy and compressed to PNG in
 *     the app's cache directory.
 *
 *  5. The PNG is shared with [Intent.ACTION_SEND] (image/png) through a
 *     FileProvider URI.
 */
object ReportImageExporter {

    private const val CARD_WIDTH_PX = 1080
    private const val CARD_HEIGHT_PX = 1350
    private const val CARD_DENSITY = 2f

    fun generate(activity: Activity, report: UsageReport, period: ReportPeriod): File {
        val bitmap = renderCardBitmap(activity, report, period)
        return writePngToCache(activity, bitmap)
    }

    fun share(activity: Activity, period: ReportPeriod, report: UsageReport) {
        val file = generate(activity, report, period)

        val uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".fileprovider",
            file
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(
                activity.contentResolver,
                "udid_report",
                uri
            )
        }

        val chooser = Intent.createChooser(sendIntent, "Share your screen time report")
        activity.startActivity(chooser)
    }

    private fun renderCardBitmap(
        activity: Activity,
        report: UsageReport,
        period: ReportPeriod
    ): Bitmap {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<Bitmap>(1)
        val payload = buildPayload(activity, period, report)

        Handler(Looper.getMainLooper()).post {
            val contentView = activity.findViewById<FrameLayout>(android.R.id.content)

            val composeView = ComposeView(activity).apply {
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(CARD_DENSITY)
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            ShareableReportCard(payload)
                        }
                    }
                }
            }

            contentView.addView(composeView)

            composeView.post {
                composeView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        CARD_WIDTH_PX, android.view.View.MeasureSpec.EXACTLY
                    ),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        CARD_HEIGHT_PX, android.view.View.MeasureSpec.EXACTLY
                    )
                )
                composeView.layout(0, 0, CARD_WIDTH_PX, CARD_HEIGHT_PX)

                val bitmap = Bitmap.createBitmap(
                    CARD_WIDTH_PX, CARD_HEIGHT_PX, Bitmap.Config.ARGB_8888
                )
                composeView.draw(Canvas(bitmap))
                contentView.removeView(composeView)
                result[0] = bitmap
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        return result[0] ?: Bitmap.createBitmap(
            CARD_WIDTH_PX, CARD_HEIGHT_PX, Bitmap.Config.ARGB_8888
        )
    }

    private fun buildPayload(
        context: android.content.Context,
        period: ReportPeriod,
        report: UsageReport
    ): ShareReportPayload {
        val appInfo = context.applicationInfo
        val appName = context.packageManager.getApplicationLabel(appInfo).toString()
        val icon: ImageBitmap? = try {
            appInfo.loadIcon(context.packageManager)
                .toBitmap(120, 120)
                .asImageBitmap()
        } catch (_: Exception) {
            null
        }

        val periodLabel = when (period) {
            ReportPeriod.DAILY -> labelDay(report.startTime)
            ReportPeriod.WEEKLY -> labelWeek(report.startTime)
            ReportPeriod.MONTHLY -> labelMonth(report.startTime)
        }
        val periodTypeLabel = when (period) {
            ReportPeriod.DAILY -> "Daily"
            ReportPeriod.WEEKLY -> "Weekly"
            ReportPeriod.MONTHLY -> "Monthly"
        }

        return ShareReportPayload(
            appName = appName,
            appIcon = icon,
            periodLabel = periodLabel,
            periodTypeLabel = periodTypeLabel,
            totalScreenTimeMs = report.totalScreenTimeMs,
            comparison = report.comparison,
            mostUsedAppName = report.mostUsedApp?.appName,
            averageMpi = report.averageMpi
        )
    }

    private fun labelDay(startMs: Long): String {
        val fmt = java.text.SimpleDateFormat("EEE, MMM d, yyyy", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(startMs))
    }

    private fun labelWeek(startMs: Long): String {
        val fmt = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
        val start = java.util.Date(startMs)
        val end = java.util.Date(startMs + 6L * 24 * 60 * 60 * 1000)
        return fmt.format(start) + " \u2013 " + fmt.format(end)
    }

    private fun labelMonth(startMs: Long): String {
        val fmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(startMs))
    }

    private fun writePngToCache(context: android.content.Context, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "report_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        return file
    }
}
