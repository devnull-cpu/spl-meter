package uk.co.cinema.splmeter.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object Share {

    fun file(context: Context, file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share $subject").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "html" -> "text/html"
        "csv" -> "text/csv"
        "wav" -> "audio/wav"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
