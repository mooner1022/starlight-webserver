package dev.mooner.starlightwebserver.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import dev.mooner.starlightwebserver.WebServerRuntime

/** Copies the server's first address, if it is running. */
fun copyServerUrl(context: Context) {
    val url = WebServerRuntime.serverInfo().urls.firstOrNull() ?: return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
    Toast.makeText(context, tr("Copied $url", "$url 복사됨"), Toast.LENGTH_SHORT).show()
}
