plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.damoon.sseuregi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.damoon.sseuregi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // 대부분의 휴대폰(ARM)용만 넣어서 설치 파일 크기를 줄인다
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 항상 같은 키로 서명해야 새 버전을 지우지 않고 덮어 설치할 수 있다
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 화면 사진 속 한국어 글자 인식 (휴대폰 안에서 동작, 인터넷·키 필요 없음)
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
