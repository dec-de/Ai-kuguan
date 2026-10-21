import java.util.Properties

plugins {
    // AGP 9：只 apply com.android.application，Kotlin 支持内置。
    // Kotlin compose 编译器插件仍需显式 apply。
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

ksp {
    // Room schema 导出：exportSchema = true 时生成 JSON 基线，供后续 Migration 校验。
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Local release signing material stays outside version control. Without it, release APKs remain unsigned.
val releaseSigningFile = rootProject.file("config/release-signing.local.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.exists()) releaseSigningFile.inputStream().use { load(it) }
}

android {
    namespace = "com.kuguan.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kuguan.app"
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }

        // Runtime service credentials live only in the app's Keystore-backed settings.
    }

    sourceSets.getByName("androidTest").assets.srcDir("schemas")

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    signingConfigs {
        if (releaseSigningFile.exists()) create("production") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile") ?: error("缺少正式版签名文件路径"))
            storePassword = releaseSigning.getProperty("storePassword") ?: error("缺少正式版签名密码")
            keyAlias = releaseSigning.getProperty("keyAlias") ?: error("缺少正式版签名别名")
            keyPassword = releaseSigning.getProperty("keyPassword") ?: error("缺少正式版密钥密码")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "收纳助手（调试）")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseSigningFile.exists()) signingConfig = signingConfigs.getByName("production")
            resValue("string", "app_name", "收纳助手")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room：exportSchema=true，禁止 fallbackToDestructiveMigration
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // 网络层（M1 接云 ASR / LLM）
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
