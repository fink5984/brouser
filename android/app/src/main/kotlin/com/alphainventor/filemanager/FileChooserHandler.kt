package com.alphainventor.filemanager

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Wires WebView's file-chooser callback (triggered by <input type="file">)
 * to the system document picker, with an optional "take photo" shortcut
 * when camera permission is already granted.
 */
class FileChooserHandler(private val activity: ComponentActivity) {
    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null

    private val pickLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingCallback
            pendingCallback = null
            if (result.resultCode != Activity.RESULT_OK) {
                callback?.onReceiveValue(null)
                return@registerForActivityResult
            }
            val data = result.data
            val clipData = data?.clipData
            val uris: List<Uri> = when {
                clipData != null -> (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }
                data?.data != null -> listOf(data.data!!)
                pendingCameraUri != null -> listOf(pendingCameraUri!!)
                else -> emptyList()
            }
            callback?.onReceiveValue(uris.toTypedArray())
        }

    private val cameraPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* used opportunistically next time */ }

    fun showChooser(
        filePathCallback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        pendingCallback?.onReceiveValue(null)
        pendingCallback = filePathCallback

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            // Ask now so a future upload can offer the camera shortcut;
            // doesn't block this chooser invocation.
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        val pickIntent = params.createIntent().apply {
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        val chooserIntent = Intent.createChooser(pickIntent, "Choose file")
        if (hasCameraPermission) {
            createCameraIntent()?.let { cameraIntent ->
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }

        return try {
            pickLauncher.launch(chooserIntent)
            true
        } catch (_: Exception) {
            pendingCallback = null
            false
        }
    }

    private fun createCameraIntent(): Intent? {
        val capturesDir = File(activity.cacheDir, "captures").apply { mkdirs() }
        val photoFile = File(capturesDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", photoFile)
        pendingCameraUri = uri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}
