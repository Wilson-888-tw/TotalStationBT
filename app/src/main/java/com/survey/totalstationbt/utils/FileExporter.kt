package com.survey.totalstationbt.utils

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.survey.totalstationbt.model.SurveyPoint
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object FileExporter {

    private val timestampFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    // ── 匯出 CSV ────────────────────────────────
    fun exportCSV(context: Context, points: List<SurveyPoint>): File {
        val filename = "survey_${LocalDateTime.now().format(timestampFmt)}.csv"
        val dir = getExportDir(context)
        val file = File(dir, filename)

        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF") // UTF-8 BOM for Excel
            writer.write(SurveyPoint.CSV_HEADER)
            points.forEach { writer.write(it.toCSVRow() + "\n") }
        }
        return file
    }

    // ── 匯出 GSI 原始資料 ───────────────────────
    fun exportRaw(context: Context, lines: List<String>): File {
        val filename = "raw_${LocalDateTime.now().format(timestampFmt)}.txt"
        val dir = getExportDir(context)
        val file = File(dir, filename)
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        return file
    }

    // ── 匯出 DXF ────────────────────────────────
    fun exportDXF(context: Context, points: List<com.survey.totalstationbt.db.PointEntity>): File {
        val filename = "survey_${LocalDateTime.now().format(timestampFmt)}.dxf"
        val dir = getExportDir(context)
        val file = File(dir, filename)

        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            // DXF Header - 使用 R12 (AC1009) 格式，相容性最高，不需要 Handle
            writer.write("  0\nSECTION\n  2\nHEADER\n  9\n\$ACADVER\n  1\nAC1009\n  0\nENDSEC\n")
            
            // TABLES Section (定義圖層)
            writer.write("  0\nSECTION\n  2\nTABLES\n  0\nTABLE\n  2\nLAYER\n 70\n10\n")
            writer.write("  0\nLAYER\n  2\nPOINTS\n 70\n0\n 62\n4\n  6\nCONTINUOUS\n") // 點位圖層 (青色)
            writer.write("  0\nLAYER\n  2\nNAMES\n 70\n0\n 62\n7\n  6\nCONTINUOUS\n") // 點號圖層 (白色)
            writer.write("  0\nENDTAB\n  0\nENDSEC\n")

            // BLOCKS Section (R12 requires this section even if empty)
            writer.write("  0\nSECTION\n  2\nBLOCKS\n  0\nENDSEC\n")

            // ENTITIES Section
            writer.write("  0\nSECTION\n  2\nENTITIES\n")
            
            points.forEach { pt ->
                val x = String.format(Locale.US, "%.4f", pt.easting ?: 0.0)
                val y = String.format(Locale.US, "%.4f", pt.northing ?: 0.0)
                val z = String.format(Locale.US, "%.4f", pt.elevation ?: 0.0)
                
                // POINT Entity
                writer.write("  0\nPOINT\n  8\nPOINTS\n")
                writer.write(" 10\n$x\n 20\n$y\n 30\n$z\n")
                
                // TEXT Entity (點號)
                if (pt.pointName.isNotEmpty()) {
                    writer.write("  0\nTEXT\n  8\nNAMES\n")
                    writer.write(" 10\n$x\n 20\n$y\n 30\n$z\n")
                    writer.write(" 40\n0.2\n") // 文字高度 0.2m
                    writer.write("  1\n${pt.pointName}\n")
                    writer.write(" 50\n0.0\n") // 旋轉角度
                }
            }
            
            writer.write("  0\nENDSEC\n  0\nEOF\n")
        }
        return file
    }

    // ── 分享檔案 ────────────────────────────────
    fun shareFile(context: Context, file: File) {
        try {
            val authority = "${context.packageName}.provider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            
            // 讀取檔案前幾行做為預覽文字 (類似分享文字的功能)
            val previewText = try {
                file.bufferedReader().useLines { lines ->
                    lines.take(5).joinToString("\n")
                }
            } catch (e: Exception) { "" }

            val mimeType = when (file.extension.lowercase()) {
                "dxf" -> "image/vnd.dxf"
                "csv" -> "text/csv"
                else  -> "text/plain"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "測量點位匯出檔案：${file.name}\n\n內容預覽：\n$previewText")
                putExtra(Intent.EXTRA_SUBJECT, "測量資料：${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooser = Intent.createChooser(intent, "分享測量資料")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "分享失敗：${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ── 取得匯出目錄 ────────────────────────────
    private fun getExportDir(context: Context): File {
        // 儲存在公共的 Download/TotalStationBT 目錄下，方便用戶在檔案總管找到
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloadDir, "TotalStationBT")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listExportedFiles(context: Context): List<File> {
        return try {
            getExportDir(context).listFiles()
                ?.filter { it.isFile && it.extension in listOf("csv", "txt", "dxf") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }
}
