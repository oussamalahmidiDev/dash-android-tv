plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "tv.afterglow"
    compileSdk = 36
    defaultConfig {
        applicationId = "tv.afterglow"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "2.0.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = false } }
    signingConfigs {
        if (providers.environmentVariable("AFTERGLOW_KEYSTORE").isPresent) {
            create("personal") {
                storeFile = file(providers.environmentVariable("AFTERGLOW_KEYSTORE").get())
                storePassword = providers.environmentVariable("AFTERGLOW_STORE_PASSWORD").get()
                keyAlias = "afterglow"
                keyPassword = storePassword
            }
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.findByName("personal") }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

val torrentJni by configurations.creating
val unpackTorrentJni by tasks.registering(Sync::class) {
    from({ torrentJni.map { zipTree(it) } }) {
        include("lib/**/*.so")
        eachFile { path = path.removePrefix("lib/") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/torrent-jni"))
}
android.sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/torrent-jni"))
tasks.named("preBuild").configure { dependsOn(unpackTorrentJni) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    val media3 = "1.9.2"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("com.frostwire:jlibtorrent:2.0.12.9")
    listOf("arm", "arm64", "x86", "x86_64").forEach {
        torrentJni("com.frostwire:jlibtorrent-android-$it:2.0.12.9")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
