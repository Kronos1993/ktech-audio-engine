import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.kronos1993"
version = project.findProperty("libraryVersion") as String? ?: "0.0.0-SNAPSHOT"

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "KtechAudioEngine"
            isStatic = true
        }
    }

    jvm()

    android {
        namespace = "com.kronos.ktech.audioengine"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.session)
        }
        commonMain.dependencies {
            api(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
            implementation(libs.compose.runtime)
            implementation(libs.compose.components.resources)
        }
        jvmMain.dependencies {
            implementation(libs.javacv)
            implementation(libs.ffmpeg.platform)
        }
    }
}

compose.resources {
    packageOfResClass = "com.kronos.ktech.audioengine.generated.resources"
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(group.toString(), "ktech-audio-engine", version.toString())

    pom {
        name.set("ktech-audio-engine")
        description.set(
            "A Kotlin Multiplatform audio playback engine for Android, iOS, and Desktop " +
                "(Media3/ExoPlayer, AVAudioEngine, JavaCV/ffmpeg).",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/Kronos1993/ktech-audio-engine")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("Kronos1993")
                name.set("Kronos1993")
                url.set("https://github.com/Kronos1993")
            }
        }

        scm {
            url.set("https://github.com/Kronos1993/ktech-audio-engine")
            connection.set("scm:git:git://github.com/Kronos1993/ktech-audio-engine.git")
            developerConnection.set("scm:git:ssh://git@github.com/Kronos1993/ktech-audio-engine.git")
        }
    }
}
