package com.anggrayudi.storage.media

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.RecoverableSecurityException
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.callback.FileCallback
import com.anggrayudi.storage.callback.FileCopyCallback
import com.anggrayudi.storage.callback.FileMoveCallback
import com.anggrayudi.storage.extension.closeStream
import com.anggrayudi.storage.extension.startCoroutineTimer
import com.anggrayudi.storage.extension.toInt
import com.anggrayudi.storage.file.*
import kotlinx.coroutines.Job
import java.io.*

/**
 * Created on 06/09/20
 * @author Anggrayudi H
 */
class MediaFile(_context: Context, val uri: Uri) {

    private val context = _context.applicationContext

    interface AccessCallback {

        /**
         * When this method called, you can ask user's concent to modify other app's files.
         * @see RecoverableSecurityException
         * @see [android.app.Activity.startIntentSenderForResult]
         */
        fun onWriteAccessDenied(mediaFile: MediaFile, sender: IntentSender)
    }

    /**
     * Only useful for Android 10 and higher.
     * @see RecoverableSecurityException
     */
    var accessCallback: AccessCallback? = null

    @Suppress("DEPRECATION")
    val name: String?
        get() = toJavaFile()?.name ?: getColumnInfoString(MediaStore.MediaColumns.DISPLAY_NAME)

    val baseName: String
        get() = name.orEmpty().substringBeforeLast('.')

    val extension: String
        get() = name.orEmpty().substringAfterLast('.')

    val type: String?
        get() = getColumnInfoString(MediaStore.MediaColumns.MIME_TYPE) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

    @Suppress("DEPRECATION")
    val length: Long
        get() = toJavaFile()?.length() ?: getColumnInfoLong(MediaStore.MediaColumns.SIZE)

    @Suppress("DEPRECATION")
    val exists: Boolean
        get() = toJavaFile()?.exists()
            ?: context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                true
            } ?: false

    /**
     * `true` if this file was created with [File]. Only works on API 28 and lower.
     */
    val isJavaFile: Boolean
        get() = uri.scheme == ContentResolver.SCHEME_FILE

    /**
     * @return `null` in Android 10+ or if you try to read files from SD Card or you want to convert a file picked
     * from [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT].
     * @see toDocumentFile
     */
    @Deprecated("Accessing files with java.io.File only works on app private directory since Android 10.")
    fun toJavaFile() = if (isJavaFile) File(uri.path!!) else null

    fun toDocumentFile() = realPath.let { if (it.isEmpty()) null else DocumentFileCompat.fromFullPath(context, it) }

    @Suppress("DEPRECATION")
    val realPath: String
        @SuppressLint("InlinedApi")
        get() {
            val file = toJavaFile()
            return when {
                file != null -> file.path
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                    try {
                        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DATA))
                            } else ""
                        }.orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                }
                else -> {
                    val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val name = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME))
                            val relativePath = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH))
                            "${SimpleStorage.externalStoragePath}/$relativePath/$name"
                        } else ""
                    }.orEmpty()
                }
            }
        }

    /**
     * @see MediaStore.MediaColumns.RELATIVE_PATH
     */
    @Suppress("DEPRECATION")
    val relativePath: String
        @SuppressLint("InlinedApi")
        get() {
            val file = toJavaFile()
            return when {
                file != null -> {
                    file.path.substringBeforeLast('/').replaceFirst(SimpleStorage.externalStoragePath, "").trim { it == '/' } + "/"
                }
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                    try {
                        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val realFolderPath = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DATA)).substringBeforeLast('/')
                                realFolderPath.replaceFirst(SimpleStorage.externalStoragePath, "").trim { it == '/' } + "/"
                            } else ""
                        }.orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                }
                else -> {
                    val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH))
                        } else ""
                    }.orEmpty()
                }
            }
        }

    @Suppress("DEPRECATION")
    fun delete(): Boolean {
        val file = toJavaFile()
        return if (file != null) {
            context.contentResolver.delete(uri, null, null)
            file.delete()
        } else try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            handleSecurityException(e)
            false
        }
    }

    /**
     * Please note that this method does not move file if you input `newName` as `Download/filename.mp4`.
     * If you want to move media files, please use [moveTo] instead.
     */
    @Suppress("DEPRECATION")
    fun renameTo(newName: String): Boolean {
        val file = toJavaFile()
        val contentValues = ContentValues(1).apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
        return if (file != null) {
            file.renameTo(File(file.parent, newName)) && context.contentResolver.update(uri, contentValues, null, null) > 0
        } else try {
            context.contentResolver.update(uri, contentValues, null, null) > 0
        } catch (e: SecurityException) {
            handleSecurityException(e)
            false
        }
    }

    var isPending: Boolean
        @RequiresApi(Build.VERSION_CODES.Q)
        get() = getColumnInfoInt(MediaStore.MediaColumns.IS_PENDING) == 1
        @RequiresApi(Build.VERSION_CODES.Q)
        set(value) {
            val contentValues = ContentValues(1).apply { put(MediaStore.MediaColumns.IS_PENDING, value.toInt()) }
            try {
                context.contentResolver.update(uri, contentValues, null, null)
            } catch (e: SecurityException) {
                handleSecurityException(e)
            }
        }

    private fun handleSecurityException(e: SecurityException, callback: FileCallback? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
            accessCallback?.onWriteAccessDenied(this, e.userAction.actionIntent.intentSender)
        } else {
            callback?.onFailed(ErrorCode.STORAGE_PERMISSION_DENIED)
        }
    }

    @UiThread
    fun openFileIntent(authority: String) = Intent(Intent.ACTION_VIEW)
        .setData(if (isJavaFile) FileProvider.getUriForFile(context, authority, File(uri.path!!)) else uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * @param append if `false` and the file already exists, it will recreate the file.
     */
    @WorkerThread
    fun openOutputStream(append: Boolean = true): OutputStream? {
        return try {
            @Suppress("DEPRECATION")
            val file = toJavaFile()
            if (file != null) {
                FileOutputStream(file, append)
            } else {
                context.contentResolver.openOutputStream(uri, if (append) "wa" else "w")
            }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    @WorkerThread
    fun openInputStream(): InputStream? {
        return try {
            @Suppress("DEPRECATION")
            val file = toJavaFile()
            if (file != null) {
                FileInputStream(file)
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    fun moveTo(relativePath: String): Boolean {
        val contentValues = ContentValues(1).apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
        return try {
            context.contentResolver.update(uri, contentValues, null, null) > 0
        } catch (e: SecurityException) {
            handleSecurityException(e)
            false
        }
    }

    @WorkerThread
    fun moveTo(targetFolder: DocumentFile, callback: FileMoveCallback? = null) {
        val sourceFile = toDocumentFile()
        if (sourceFile != null) {
            sourceFile.moveTo(context, targetFolder, callback)
            return
        }

        try {
            if (callback?.onCheckFreeSpace(DocumentFileCompat.getFreeSpace(context, targetFolder.storageId), length) == false) {
                callback.onFailed(ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH)
                return
            }
        } catch (e: Throwable) {
            callback?.onFailed(ErrorCode.STORAGE_PERMISSION_DENIED)
            return
        }

        val reportInterval = callback?.onStartMoving(this) ?: 0
        val watchProgress = reportInterval > 0

        try {
            val targetFile = createTargetFile(targetFolder, callback) ?: return
            createFileStreams(targetFile, callback) { inputStream, outputStream ->
                copyFileStream(inputStream, outputStream, targetFile, watchProgress, reportInterval, callback)
            }
        } catch (e: SecurityException) {
            handleSecurityException(e, callback)
        } catch (e: InterruptedIOException) {
            callback?.onFailed(ErrorCode.CANCELLED)
        } catch (e: InterruptedException) {
            callback?.onFailed(ErrorCode.CANCELLED)
        } catch (e: IOException) {
            callback?.onFailed(ErrorCode.UNKNOWN_IO_ERROR)
        }
    }

    @WorkerThread
    fun copyTo(targetFolder: DocumentFile, callback: FileCopyCallback? = null) {
        val sourceFile = toDocumentFile()
        if (sourceFile != null) {
            sourceFile.copyTo(context, targetFolder, callback)
            return
        }

        try {
            if (callback?.onCheckFreeSpace(DocumentFileCompat.getFreeSpace(context, targetFolder.storageId), length) == false) {
                callback.onFailed(ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH)
                return
            }
        } catch (e: Throwable) {
            callback?.onFailed(ErrorCode.STORAGE_PERMISSION_DENIED)
            return
        }

        val reportInterval = callback?.onStartCopying(this) ?: 0
        val watchProgress = reportInterval > 0
        try {
            val targetFile = createTargetFile(targetFolder, callback) ?: return
            createFileStreams(targetFile, callback) { inputStream, outputStream ->
                copyFileStream(inputStream, outputStream, targetFile, watchProgress, reportInterval, callback)
            }
        } catch (e: SecurityException) {
            handleSecurityException(e, callback)
        } catch (e: InterruptedIOException) {
            callback?.onFailed(ErrorCode.CANCELLED)
        } catch (e: InterruptedException) {
            callback?.onFailed(ErrorCode.CANCELLED)
        } catch (e: IOException) {
            callback?.onFailed(ErrorCode.UNKNOWN_IO_ERROR)
        }
    }

    private fun createTargetFile(targetDirectory: DocumentFile, callback: FileCallback?): DocumentFile? {
        try {
            val targetFolder = DocumentFileCompat.mkdirs(context, targetDirectory.storageId, targetDirectory.filePath)
            if (targetFolder == null) {
                callback?.onFailed(ErrorCode.STORAGE_PERMISSION_DENIED)
                return null
            }

            var targetFile = targetFolder.findFile(name.orEmpty())
            if (targetFile?.isFile == true) {
                callback?.onFailed(ErrorCode.TARGET_FILE_EXISTS)
                return null
            }

            targetFile = targetFolder.createFile(type ?: DocumentFileCompat.MIME_TYPE_UNKNOWN, name.orEmpty())
            if (targetFile == null) {
                callback?.onFailed(ErrorCode.CANNOT_CREATE_FILE_IN_TARGET)
            } else {
                return targetFile
            }
        } catch (e: SecurityException) {
            handleSecurityException(e, callback)
        } catch (e: InterruptedIOException) {
            callback?.onFailed(ErrorCode.CANCELLED)
        } catch (e: InterruptedException) {
            callback?.onFailed(ErrorCode.CANCELLED)
        } catch (e: IOException) {
            callback?.onFailed(ErrorCode.UNKNOWN_IO_ERROR)
        }
        return null
    }

    private inline fun createFileStreams(
        targetFile: DocumentFile,
        callback: FileCallback?,
        onStreamsReady: (InputStream, OutputStream) -> Unit
    ) {
        val outputStream = targetFile.openOutputStream(context)
        if (outputStream == null) {
            callback?.onFailed(ErrorCode.TARGET_FILE_NOT_FOUND)
            return
        }

        val inputStream = openInputStream()
        if (inputStream == null) {
            callback?.onFailed(ErrorCode.SOURCE_FILE_NOT_FOUND)
            outputStream.closeStream()
            return
        }

        onStreamsReady(inputStream, outputStream)
    }

    private fun copyFileStream(
        inputStream: InputStream,
        outputStream: OutputStream,
        targetFile: DocumentFile,
        watchProgress: Boolean,
        reportInterval: Long,
        callback: FileCallback?
    ) {
        var timer: Job? = null
        try {
            var byteMoved = 0L
            var writeSpeed = 0
            val srcSize = length
            // using timer on small file is useless. We set minimum 10MB.
            if (watchProgress && callback != null && srcSize > 10 * FileSize.MB) {
                timer = startCoroutineTimer(repeatMillis = reportInterval) {
                    callback.onReport(byteMoved * 100f / srcSize, byteMoved, writeSpeed)
                    writeSpeed = 0
                }
            }
            val buffer = ByteArray(1024)
            var read = inputStream.read(buffer)
            while (read != -1) {
                outputStream.write(buffer, 0, read)
                byteMoved += read
                writeSpeed += read
                read = inputStream.read(buffer)
            }
            timer?.cancel()
            if (callback is FileCopyCallback && callback.onCompleted(targetFile)) {
                delete()
            } else if (callback is FileMoveCallback) {
                delete()
                callback.onCompleted(targetFile)
            }
        } finally {
            timer?.cancel()
            inputStream.closeStream()
            outputStream.closeStream()
        }
    }

    private fun getColumnInfoString(column: String): String? {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(column)
                if (columnIndex != -1) {
                    return cursor.getString(columnIndex)
                }
            }
        }
        return null
    }

    private fun getColumnInfoLong(column: String): Long {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(column)
                if (columnIndex != -1) {
                    return cursor.getLong(columnIndex)
                }
            }
        }
        return 0
    }

    private fun getColumnInfoInt(column: String): Int {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(column)
                if (columnIndex != -1) {
                    return cursor.getInt(columnIndex)
                }
            }
        }
        return 0
    }

    override fun equals(other: Any?) = other === this || other is MediaFile && other.uri == uri

    override fun hashCode() = uri.hashCode()

    override fun toString() = uri.toString()
}