package com.example.udid.util

import android.app.Activity
import android.content.ContentValues
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.example.udid.usage.AppSession
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates a PDF of the user's session notes for the past 30 days
 * and shares it via the Android share sheet.
 *
 * Uses [PdfDocument] (built-in, no external libraries).
 */
object NotesPdfExporter {

    // ── PDF styling ──
    private const val PAGE_WIDTH = 595   // A4 width in points (72 dpi)
    private const val PAGE_HEIGHT = 842  // A4 height in points
    private const val MARGIN_LEFT = 50f
    private const val MARGIN_RIGHT = 50f
    private const val MARGIN_TOP = 60f
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT

    private val titlePaint = Paint().apply {
        textSize = 20f
        color = Color.parseColor("#1A1A2E")
        isFakeBoldText = true
        isAntiAlias = true
    }
    private val subtitlePaint = Paint().apply {
        textSize = 12f
        color = Color.parseColor("#666666")
        isAntiAlias = true
    }
    private val dateHeaderPaint = Paint().apply {
        textSize = 14f
        color = Color.parseColor("#0D7377")
        isFakeBoldText = true
        isAntiAlias = true
    }
    private val appNamePaint = Paint().apply {
        textSize = 12f
        color = Color.parseColor("#1A1A2E")
        isFakeBoldText = true
        isAntiAlias = true
    }
    private val timePaint = Paint().apply {
        textSize = 10f
        color = Color.parseColor("#888888")
        isAntiAlias = true
    }
    private val notePaint = Paint().apply {
        textSize = 11f
        color = Color.parseColor("#333333")
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 0.5f
    }

    /**
     * Generate a PDF and save it to the public Downloads folder.
     * Shows a Toast with the file path on success.
     *
     * @param activity  Current activity (needed for context and Toast)
     * @param sessions  All sessions to include (will be filtered to those with notes)
     * @param notes     Map of sessionKey → noteText
     */
    fun downloadToDownloads(
        activity: Activity,
        sessions: List<AppSession>,
        notes: Map<String, String>
    ) {
        val pdfBytes = generatePdfBytes(activity, sessions, notes)
        val fileName = "TimeSlayer_Notes_${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.pdf"

        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — use MediaStore
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                activity.contentResolver.openOutputStream(it)?.use { out ->
                    out.write(pdfBytes)
                }
                true
            } ?: false
        } else {
            // Android 9 and below — direct file write
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                out.write(pdfBytes)
            }
            true
        }

        val msg = if (saved) "PDF saved to Downloads/$fileName" else "Failed to save PDF"
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }

    private fun generatePdfBytes(
        context: Activity,
        sessions: List<AppSession>,
        notes: Map<String, String>
    ): ByteArray {
        val document = PdfDocument()
        var pageNumber = 1
        var currentPage = startPage(document, pageNumber)
        var y = MARGIN_TOP

        // Filter to only sessions with notes, sorted by time descending
        val sessionsWithNotes = sessions
            .filter { notes.containsKey(sessionKey(it)) }
            .sortedByDescending { it.startedAt }

        // Group by day
        val grouped = sessionsWithNotes.groupBy { it.startedAt / (24 * 60 * 60 * 1000) }
            .toSortedMap(compareByDescending { it })

        val sdfDate = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault())
        val sdfTime = SimpleDateFormat("h:mm a", Locale.getDefault())

        // ── Title ──
        y = drawText(currentPage.canvas, "TimeSlayer — Monthly Notes", MARGIN_LEFT, y, titlePaint)
        y += 4f

        // Date range
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - (30L * 24 * 60 * 60 * 1000)
        val rangeLabel = "${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(thirtyDaysAgo))} – " +
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(now))
        y = drawText(currentPage.canvas, rangeLabel, MARGIN_LEFT, y, subtitlePaint)
        y += 4f

        val countLabel = "${sessionsWithNotes.size} note${if (sessionsWithNotes.size != 1) "s" else ""}"
        y = drawText(currentPage.canvas, countLabel, MARGIN_LEFT, y, subtitlePaint)
        y += 16f

        // ── Content ──
        if (sessionsWithNotes.isEmpty()) {
            y = drawText(currentPage.canvas, "No notes recorded this month.", MARGIN_LEFT, y, notePaint)
        } else {
            for ((_, daySessions) in grouped) {
                val dateLabel = sdfDate.format(Date(daySessions.first().startedAt))

                // Check if we need a new page for the date header
                y = ensureSpace(document, currentPage, y, 60f, pageNumber)
                    if (y == MARGIN_TOP) {
                        pageNumber++
                        currentPage = startPage(document, pageNumber)
                    }

                    // Date header
                y = drawText(currentPage.canvas, dateLabel, MARGIN_LEFT, y, dateHeaderPaint)
                y += 2f

                // Divider line
                currentPage.canvas.drawLine(MARGIN_LEFT, y, PAGE_WIDTH - MARGIN_RIGHT, y, linePaint)
                y += 10f

                for (session in daySessions) {
                    val key = sessionKey(session)
                    val note = notes[key] ?: continue

                    // Check space for a full note block (approx 60pt)
                    y = ensureSpace(document, currentPage, y, 60f, pageNumber)
                    if (y == MARGIN_TOP) {
                        pageNumber++
                        currentPage = startPage(document, pageNumber)
                    }

                    // App name
                    y = drawText(currentPage.canvas, session.appName, MARGIN_LEFT + 8f, y, appNamePaint)

                    // Time range + duration
                    val timeRange = "${sdfTime.format(Date(session.startedAt))} → ${
                        if (session.isActive) "Now" else sdfTime.format(Date(session.endedAt))
                    } · ${formatDuration(session.durationSec)}"
                    y = drawText(currentPage.canvas, timeRange, MARGIN_LEFT + 8f, y, timePaint)
                    y += 2f

                    // Note text (with word wrap)
                    y = drawWrappedText(currentPage.canvas, "\"$note\"", MARGIN_LEFT + 8f, y, notePaint, CONTENT_WIDTH - 8f)
                    y += 12f
                }

                y += 6f
            }
        }

        document.finishPage(currentPage)

        // Write to byte array
        val outputStream = java.io.ByteArrayOutputStream()
        document.writeTo(outputStream)
        document.close()
        return outputStream.toByteArray()
    }

    private fun startPage(document: PdfDocument, pageNumber: Int): PdfDocument.Page {
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        return document.startPage(pageInfo)
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint): Float {
        canvas.drawText(text, x, y, paint)
        return y + paint.textSize + 4f
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        maxWidth: Float
    ): Float {
        var currentY = y
        val words = text.split(" ")
        var line = ""

        for (word in words) {
            val testLine = if (line.isEmpty()) word else "$line $word"
            val testWidth = paint.measureText(testLine)
            if (testWidth > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line, x, currentY, paint)
                currentY += paint.textSize + 2f
                line = word
            } else {
                line = testLine
            }
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line, x, currentY, paint)
            currentY += paint.textSize + 2f
        }
        return currentY
    }

    private fun ensureSpace(
        document: PdfDocument,
        currentPage: PdfDocument.Page,
        currentY: Float,
        neededSpace: Float,
        pageNumber: Int
    ): Float {
        return if (currentY + neededSpace > PAGE_HEIGHT - MARGIN_TOP) {
            // Need new page — return signal to caller
            MARGIN_TOP
        } else {
            currentY
        }
    }

    private fun sessionKey(session: AppSession): String {
        return "${session.packageName}_${session.startedAt}_${session.endedAt}"
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m")
        }.trim()
    }
}
