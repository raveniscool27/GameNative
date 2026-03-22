package app.gamenative.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.CustomGameScanner
import timber.log.Timber

/**
 * Converts a document tree URI to a file path.
 * Returns null if conversion fails.
 */
fun getPathFromTreeUri(uri: Uri?): String? {
    if (uri == null) return null

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val docId = DocumentsContract.getTreeDocumentId(uri)

            if (docId.startsWith("primary:")) {
                val path = docId.substringAfter(":")
                val externalStorage = Environment.getExternalStorageDirectory()
                return if (path.isEmpty()) {
                    externalStorage.path
                } else {
                    "${externalStorage.path}/$path"
                }
            }

            if (docId.contains(":")) {
                val parts = docId.split(":", limit = 2)
                if (parts.size == 2) {
                    val volumeId = parts[0]
                    val path = parts[1]
                    val possiblePath = if (path.isEmpty()) {
                        "/storage/$volumeId"
                    } else {
                        "/storage/$volumeId/$path"
                    }
                    val file = java.io.File(possiblePath)
                    if (file.exists() || file.parentFile?.exists() == true) {
                        return possiblePath
                    }
                    return possiblePath
                }
            }

            if (!docId.contains(":")) {
                return docId
            }
        }

        uri.path?.let { path ->
            if (path.startsWith("/tree/")) {
                val docId = path.substringAfter("/tree/")
                if (docId.startsWith("primary:")) {
                    val filePath = docId.substringAfter(":")
                    val externalStorage = Environment.getExternalStorageDirectory()
                    return if (filePath.isEmpty()) {
                        externalStorage.path
                    } else {
                        "${externalStorage.path}/$filePath"
                    }
                }
            }
            path
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Ensures we have the correct permissions for the provided path.
 */
fun requestPermissionsForPath(
    context: Context,
    path: String,
    storagePermissionLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>?,
) {
    val isOutsideSandbox = !path.contains("/Android/data/${context.packageName}") &&
        !path.contains(context.dataDir.path)

    if (!isOutsideSandbox) {
        return
    }

    // Android 11+ (R) and above uses SAF for specific folders
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        storagePermissionLauncher?.launch(permissions)
    }
    // For Android 11+, we rely on SAF permission granted during folder picking
}

data class CustomGameFolderPicker(
    val launchPicker: () -> Unit,
)

/**
 * Helper for remembering a folder picker launcher that returns a resolved file path.
 */
@Composable
fun rememberCustomGameFolderPicker(
    onPathSelected: (String) -> Unit,
    onFailure: (String) -> Unit = {},
    onCancel: () -> Unit = {},
): CustomGameFolderPicker {
    val context = LocalContext.current
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) {
            onCancel()
            return@rememberLauncherForActivityResult
        }

        try {
            // 1. Ask for persistent permissions that remain after app restart
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

            val path = getPathFromTreeUri(uri)
            if (path != null) {
                // 2. Store the persistent URI for this path in PrefManager
                PrefManager.setString("saf_uri_$path", uri.toString())

                // 3. Add logs to confirm the process
                Timber.tag("CustomGameScanner").d("Persistent SAF access granted to: $path (URI: $uri)")

                // 4. Show Snackbar confirmation
                SnackbarManager.show("Custom Game Scanner: Access granted to $path")

                onPathSelected(path)
            } else {
                onFailure(context.getString(R.string.custom_game_folder_picker_error))
            }
        } catch (e: Exception) {
            Timber.tag("CustomGameScanner").e(e, "Failed to grant persistent SAF access")
            onFailure("Failed to grant persistent access")
        }
    }

    return remember {
        CustomGameFolderPicker(
            launchPicker = { pickerLauncher.launch(null) },
        )
    }
}
