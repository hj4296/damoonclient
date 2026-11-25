package com.damoon.sseuregi.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.damoon.sseuregi.brain.Command.Folder
import com.damoon.sseuregi.brain.TextUtil
import java.io.File

/** 휴대폰 저장공간의 파일 찾기 · 목록 · 열기 · 읽기 */
object FileTools {
    private val TEXT_EXT = setOf("txt", "md", "csv", "json", "log", "xml", "html", "htm", "kt", "java", "py", "js", "ini", "yml", "yaml")

    val root: File get() = Environment.getExternalStorageDirectory()

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    fun folder(f: Folder): File = when (f) {
        Folder.DOWNLOAD -> File(root, Environment.DIRECTORY_DOWNLOADS)
        Folder.CAMERA -> File(root, "${Environment.DIRECTORY_DCIM}/Camera")
        Folder.SCREENSHOTS -> listOf(
            File(root, "${Environment.DIRECTORY_DCIM}/Screenshots"),
            File(root, "${Environment.DIRECTORY_PICTURES}/Screenshots"),
        ).firstOrNull { it.isDirectory } ?: File(root, "${Environment.DIRECTORY_PICTURES}/Screenshots")
        Folder.DOCUMENTS -> File(root, Environment.DIRECTORY_DOCUMENTS)
        Folder.MUSIC -> File(root, Environment.DIRECTORY_MUSIC)
        Folder.MOVIES -> File(root, Environment.DIRECTORY_MOVIES)
    }

    fun folderName(f: Folder): String = when (f) {
        Folder.DOWNLOAD -> "다운로드"
        Folder.CAMERA -> "카메라 사진"
        Folder.SCREENSHOTS -> "스크린샷"
        Folder.DOCUMENTS -> "문서"
        Folder.MUSIC -> "음악"
        Folder.MOVIES -> "동영상"
    }

    fun list(f: Folder, limit: Int = 8): List<File> =
        (folder(f).listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedByDescending { it.lastModified() }
            .take(limit)

    /** 이름에 말한 글자가 들어간 파일 (최근 것부터) */
    fun find(spoken: String, limit: Int = 20): List<File> {
        val want = TextUtil.compact(spoken)
        if (want.isEmpty()) return emptyList()
        val found = ArrayList<File>()
        val deadline = System.currentTimeMillis() + 6000
        fun visit(dir: File, depth: Int) {
            if (depth > 7 || found.size >= limit * 3 || System.currentTimeMillis() > deadline) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (depth == 0 && f.name == "Android") continue
                    visit(f, depth + 1)
                } else if (TextUtil.compact(f.nameWithoutExtension).contains(want)) {
                    found += f
                }
            }
        }
        visit(root, 0)
        if (found.isEmpty() && want.length >= 3) {
            // 음성 인식이 조금 틀렸을 수도 있으니 비슷한 이름도 찾아본다
            fun fuzzy(dir: File, depth: Int) {
                if (depth > 5 || found.size >= limit || System.currentTimeMillis() > deadline + 3000) return
                for (f in dir.listFiles() ?: return) {
                    if (f.name.startsWith(".")) continue
                    if (f.isDirectory) {
                        if (!(depth == 0 && f.name == "Android")) fuzzy(f, depth + 1)
                    } else if (TextUtil.similarity(f.nameWithoutExtension, want) >= 0.7) {
                        found += f
                    }
                }
            }
            fuzzy(root, 0)
        }
        return found.sortedByDescending { it.lastModified() }.take(limit)
    }

    fun isText(f: File) = f.extension.lowercase() in TEXT_EXT

    fun readText(f: File, max: Int = 800): String =
        f.bufferedReader().use { r ->
            val buf = CharArray(max)
            val n = r.read(buf)
            if (n <= 0) "" else String(buf, 0, n)
        }

    fun open(context: Context, f: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(Intent.createChooser(intent, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
