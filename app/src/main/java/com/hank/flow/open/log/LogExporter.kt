package com.hank.flow.open.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogExporter {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    suspend fun exportToZip(context: Context): File = withContext(Dispatchers.IO) {
        OpenFlowLog.flush()
        val logsDir = File(
            context.getExternalFilesDir("logan") ?: context.filesDir,
            "logs",
        )
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        clearOld(outDir)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(outDir, "openflow-logs-$stamp.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            putManifest(zos, context, stamp)
            putReadme(zos)
            if (logsDir.exists()) {
                logsDir.walkTopDown().filter { it.isFile }.forEach { src ->
                    val rel = "logan/" + src.relativeTo(logsDir).path.replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry(rel))
                    FileInputStream(src).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        zipFile
    }

    fun share(context: Context, zipFile: File) {
        val authority = context.packageName + AUTHORITY_SUFFIX
        val uri = FileProvider.getUriForFile(context, authority, zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "OpenFlow 日志 ${zipFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "导出 OpenFlow 日志").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun clearOld(dir: File) {
        dir.listFiles()?.filter { it.name.startsWith("openflow-logs-") }?.forEach { it.delete() }
    }

    private fun putManifest(zos: ZipOutputStream, context: Context, stamp: String) {
        val pkg = context.packageName
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val manifest = """
            |{
            |  "exportedAt": "$stamp",
            |  "package": "$pkg",
            |  "versionName": "${info?.versionName.orEmpty()}",
            |  "versionCode": ${info?.longVersionCode ?: -1},
            |  "model": "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            |  "sdk": ${android.os.Build.VERSION.SDK_INT},
            |  "release": "${android.os.Build.VERSION.RELEASE}"
            |}
        """.trimMargin()
        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write(manifest.toByteArray())
        zos.closeEntry()
    }

    private fun putReadme(zos: ZipOutputStream) {
        val readme = """
            |OpenFlow Logan logs
            |
            |Decrypt with tools/logan/decode-logan.sh, using:
            |  LOGAN_AES_KEY=0123456789abcdef
            |  LOGAN_AES_IV=abcdef0123456789
            |
            |Files under logan/ are raw encrypted Logan frames.
        """.trimMargin()
        zos.putNextEntry(ZipEntry("README.txt"))
        zos.write(readme.toByteArray())
        zos.closeEntry()
    }
}
