package com.kuguan.app.domain.voice

import java.io.File

/** 单测读仓库文件的公共入口（Gradle 单测工作目录是 `app/`，但兼容从仓库根跑）。 */
object TestFiles {

    fun read(vararg candidates: String): String {
        val roots = listOf(
            File(""),
            File("app"),
            File(System.getProperty("user.dir") ?: ""),
            File(System.getProperty("user.dir") ?: "").parentFile ?: File(""),
        )
        for (candidate in candidates) {
            for (root in roots) {
                val file = File(root, candidate)
                if (file.isFile) return file.readText()
            }
        }
        error("找不到文件：${candidates.joinToString(" / ")}")
    }

    const val CORPUS = "src/test/resources/intent-cases.json"
    const val PROMPT = "src/main/assets/prompt/intent-v1.md"
}
