package com.damoon.sseuregi.control

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 화면 사진 속 글자를 휴대폰 안에서 읽는다 (인터넷·키 필요 없음) */
object Ocr {
    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /** 위에서부터 글 덩어리 순서대로 */
    suspend fun read(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks
                    .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                    .map { it.text.replace('\n', ' ').trim() }
                    .filter { it.isNotEmpty() }
                if (cont.isActive) cont.resume(blocks)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    /** 한 줄씩, 화면 어디에 있는지까지 (눌러야 할 곳을 찾을 때) */
    suspend fun readLines(bitmap: Bitmap): List<Pair<String, Rect>> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { it.lines }
                    .mapNotNull { line -> line.boundingBox?.let { box -> line.text.trim() to box } }
                    .filter { it.first.isNotEmpty() }
                if (cont.isActive) cont.resume(lines)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(emptyList())
            }
    }
}
