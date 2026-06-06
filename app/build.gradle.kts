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
val appApplicationId = "com.st14n.f1"
val appVersionCode = 3
val appVersionName = "1.0.2"
// Token refresh interval: default 6 hours; override in .env for testing (e.g. 300000 = 5 min)
val tokenRefreshIntervalMs = envProps["TOKEN_REFRESH_INTERVAL_MS"]?.toLongOrNull()
    ?: (6L * 60 * 60 * 1000)
// Custom Radio stream URL (set CUSTOM_RADIO_URL in .env to override defaults)
val customRadioUrl = envProps["CUSTOM_RADIO_URL"]
    ?: ""
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
        applicationId = appApplicationId
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField(
            "String",
            "DEFAULT_USER_AGENT",
            "\"Mozilla/5.0 (Linux; Android 14; Google TV Streamer Build/UTT3.240625.001.K5; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.114 Mobile Safari/537.36\""
        )
        buildConfigField(
            "String",
            "F1_DEVICE_INFO",
            "\"brand=Google;product=kirkwood;os=android;osv=14;dev=Google TV Streamer\""
        )
        buildConfigField("String", "F1_USERNAME",
            "\"${f1BuildUsername.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "F1_PASSWORD",
            "\"${f1BuildPassword.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("long", "TOKEN_REFRESH_INTERVAL_MS", "${tokenRefreshIntervalMs}L")
        buildConfigField("String", "CUSTOM_RADIO_URL",
            "\"${customRadioUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
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

    packaging {
        resources {
            excludes += "META-INF/native-image/**"
            excludes += "lib/**/ffmpeg"
            excludes += "lib/**/ffprobe"
        }
        jniLibs {
            excludes += "**/ffmpeg"
            excludes += "**/ffprobe"
            excludes += "lib/**/ffmpeg"
            excludes += "lib/**/ffprobe"
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val abiSuffix = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
                ?.let { "-$it" }
                .orEmpty()
            val buildTypeSuffix = if (variant.buildType == "release") "" else "-${variant.buildType}"
            output.outputFileName.set("$appApplicationId-$appVersionName$buildTypeSuffix$abiSuffix.apk")
        }
    }
}


tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    if (name.startsWith("hiltJavaCompile")) {
        val proj = project // capture at configuration time — avoids Task.project at execution time
        val variantName = name.removePrefix("hiltJavaCompile").replaceFirstChar { it.lowercase() }
        val javacTaskName = "compile${name.removePrefix("hiltJavaCompile")}JavaWithJavac"
        doFirst {
            val filteredProcessorPath = options.annotationProcessorPath
                ?.files
                .orEmpty()
                .filterNot { it.name.startsWith("moshi-kotlin-codegen") }
            options.annotationProcessorPath = proj.files(filteredProcessorPath)
        }
        doLast {
            val javacOutput = layout.buildDirectory.dir(
                "intermediates/javac/$variantName/$javacTaskName/classes"
            ).get().asFile
            val hiltOutput = layout.buildDirectory.dir(
                "intermediates/classes/$variantName/$name"
            ).get().asFile

            if (javacOutput.exists() && hiltOutput.exists()) {
                copy {
                    from(javacOutput)
                    into(hiltOutput)
                    include("**/*_GeneratedInjector.class")
                    include("hilt_aggregated_deps/**/*.class")
                }
            }
        }
    }
}

dependencies {
    val kotlinCoroutinesVersion = "1.10.2"
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinCoroutinesVersion")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    val leanbackVersion = "1.2.0"
    implementation("androidx.leanback:leanback:$leanbackVersion")
    implementation("androidx.leanback:leanback-preference:$leanbackVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.10.0")

    val hiltVersion = "2.59.2"
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    ksp("com.google.dagger:hilt-android-compiler:$hiltVersion")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

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
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui-leanback:$media3Version")
    implementation("org.bytedeco:javacv:1.5.8")
    implementation("org.bytedeco:ffmpeg:5.1.2-1.5.8")
    implementation("org.bytedeco:ffmpeg:5.1.2-1.5.8:android-arm")
    implementation("org.bytedeco:ffmpeg:5.1.2-1.5.8:android-arm64")
    implementation("org.bytedeco:javacpp:1.5.8")
    implementation("org.bytedeco:javacpp:1.5.8:android-arm")
    implementation("org.bytedeco:javacpp:1.5.8:android-arm64")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    debugImplementation("org.bytedeco:ffmpeg:5.1.2-1.5.8:android-x86_64")
    debugImplementation("org.bytedeco:javacpp:1.5.8:android-x86_64")

    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.google.android.material:material:1.12.0")
    implementation("com.jakewharton.threetenabp:threetenabp:1.4.9")
    implementation("com.google.code.gson:gson:2.14.0")
}

