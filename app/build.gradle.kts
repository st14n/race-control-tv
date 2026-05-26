// Parse .env for build-time credential injection (fallback creds when none saved on device)
val envProps = mutableMapOf<String, String>()
rootProject.file(".env").takeIf { it.exists() }?.forEachLine { line ->
    val trimmed = line.trim()
    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
        val eqIdx = trimmed.indexOf('=')
        if (eqIdx > 0) {
            envProps[trimmed.substring(0, eqIdx).trim()] =
                trimmed.substring(eqIdx + 1).trim().removeSurrounding("\"")
        }
    }
}
val f1BuildUsername = envProps["F1_username"] ?: ""
val f1BuildPassword = envProps["F1_password"] ?: ""
// Token refresh interval: default 6 hours; override in .env for testing (e.g. 300000 = 5 min)
val tokenRefreshIntervalMs = envProps["TOKEN_REFRESH_INTERVAL_MS"]?.toLongOrNull()
    ?: (6L * 60 * 60 * 1000)
// Custom Radio stream URLs (set CUSTOM_RADIO_URL_* in .env to override defaults)
val customRadioUrlMp3 = envProps["CUSTOM_RADIO_URL_MP3"]
    ?: "https://playerservices.streamtheworld.com/api/livestream-redirect/GRAND_PRIX_RADIO.mp3"
val customRadioUrlSc  = envProps["CUSTOM_RADIO_URL_SC"]
    ?: "https://playerservices.streamtheworld.com/api/livestream-redirect/GRAND_PRIX_RADIO_SC"
val customRadioUrlAac = envProps["CUSTOM_RADIO_URL_AAC"]
    ?: "https://playerservices.streamtheworld.com/api/livestream-redirect/GRAND_PRIX_RADIOAAC.aac"
val hasCustomReleaseSigning = listOf(
    "signing.key.store.path",
    "signing.key.password",
    "signing.key.alias"
).all { key ->
    (project.properties[key] as String?)?.isNotBlank() == true
}

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("dagger.hilt.android.plugin")
    id("kotlin-parcelize")
}

android {
    namespace = "fr.groggy.racecontrol.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.st14n.f1"
        minSdk = 28
        targetSdk = 36
        versionCode = 44
        versionName = "3.0.0"

        buildConfigField(
            "String",
            "DEFAULT_USER_AGENT",
            "\"Mozilla/5.0 (Linux; Android 14; Google TV Streamer Build/UTT3.240625.001.K5; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.114 Mobile Safari/537.36\""
        )
        buildConfigField(
            "String",
            "F1_DEVICE_INFO",
            "\"app=com.formulaone.production;os=android;osv=14;dev=Google TV Streamer\""
        )
        buildConfigField("String", "F1_USERNAME",
            "\"${f1BuildUsername.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "F1_PASSWORD",
            "\"${f1BuildPassword.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("long", "TOKEN_REFRESH_INTERVAL_MS", "${tokenRefreshIntervalMs}L")
        buildConfigField("String", "CUSTOM_RADIO_URL_MP3",
            "\"${customRadioUrlMp3.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "CUSTOM_RADIO_URL_SC",
            "\"${customRadioUrlSc.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "CUSTOM_RADIO_URL_AAC",
            "\"${customRadioUrlAac.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        create("release") {
            if (hasCustomReleaseSigning) {
                storeFile = project.properties["signing.key.store.path"]?.let { file(it) }
                storePassword = project.properties["signing.key.password"] as String?
                keyAlias = project.properties["signing.key.alias"] as String?
                keyPassword = project.properties["signing.key.password"] as String?
            }
        }
    }

    buildTypes {
        val appName = "F1 TV Player"
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            isDebuggable = true
            resValue("string", "app_name", "$appName (debug)")
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use a real release key when configured; otherwise fall back to the
            // debug keystore so local release APKs remain installable on devices.
            signingConfig = if (hasCustomReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            resValue("string", "app_name", appName)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        buildConfig = true   // required because you use buildConfigField
        resValues = true     // required because you use resValue() in buildTypes
    }
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    // Annotation processing for this module is handled by KSP.
    if ("-proc:none" !in options.compilerArgs) {
        options.compilerArgs.add("-proc:none")
    }
}

dependencies {
    val kotlinCoroutinesVersion = "1.10.1"
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinCoroutinesVersion")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    val leanbackVersion = "1.2.0"
    implementation("androidx.leanback:leanback:$leanbackVersion")
    implementation("androidx.leanback:leanback-preference:$leanbackVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.0")

    val hiltVersion = "2.59.2"
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    ksp("com.google.dagger:hilt-android-compiler:$hiltVersion")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    val okHttpVersion = "4.12.0"
    implementation("com.squareup.okhttp3:okhttp:$okHttpVersion")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:$okHttpVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okHttpVersion")

    val moshiVersion = "1.15.2"
    implementation("com.squareup.moshi:moshi-kotlin:$moshiVersion") {
        exclude(module = "kotlin-reflect")
    }
    ksp("com.squareup.moshi:moshi-kotlin-codegen:$moshiVersion")

    implementation("com.auth0.android:jwtdecode:2.0.2")

    val glideVersion = "4.16.0"
    implementation("com.github.bumptech.glide:glide:$glideVersion")
    implementation("com.github.bumptech.glide:okhttp3-integration:$glideVersion")

    // Media3 playback stack (replaces ExoPlayer 2.x)
    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui-leanback:$media3Version")
    implementation("org.videolan.android:libvlc-all:3.6.2")

    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.google.android.material:material:1.12.0")
    implementation("com.jakewharton.threetenabp:threetenabp:1.4.7")
    implementation("com.google.code.gson:gson:2.11.0")
}

