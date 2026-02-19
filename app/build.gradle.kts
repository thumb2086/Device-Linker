plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") // 新增 KSP 用於 Room
}

android {
    namespace = "com.dlinker.app"
    compileSdk = 34

    signingConfigs {
        create("release") {
            val keystoreFile = project.findProperty("KEYSTORE_FILE") as? String
            val storePassword = project.findProperty("KEYSTORE_PASSWORD") as? String
            val keyAliasValue = project.findProperty("KEY_ALIAS") as? String
            val keyPassword = project.findProperty("KEY_PASSWORD") as? String

            if (!keystoreFile.isNullOrBlank() && !keyAliasValue.isNullOrBlank()) {
                storeFile = file(keystoreFile)
                this.storePassword = storePassword
                this.keyAlias = keyAliasValue
                this.keyPassword = keyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.dlinker.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            packaging {
                jniLibs {
                    useLegacyPackaging = true
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Room 數據庫
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // OkHttp 用於呼叫 Vercel API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    implementation("org.web3j:core:4.8.7-android")
    
    // WorkManager 用於背景通知
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    implementation(libs.guava)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
