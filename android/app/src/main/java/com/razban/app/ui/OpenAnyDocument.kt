package com.razban.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * SAF picker that must not filter by MIME. Samsung My Files hides JSON files
 * when EXTRA_MIME_TYPES lists application/json or text/plain. The intent
 * uses the wildcard MIME type and no EXTRA_MIME_TYPES extra; content is
 * validated after the user picks a file.
 */
class OpenAnyDocument : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*".plus("/").plus("*")
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent?.takeIf { resultCode == Activity.RESULT_OK }?.data
    }
}
