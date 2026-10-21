plugins {
    // AGP 9 内置 Kotlin 支持：不要 apply org.jetbrains.kotlin.android，
    // 否则报 "Cannot add extension with name 'kotlin'"。
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}