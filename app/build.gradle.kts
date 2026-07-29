import com.android.build.api.variant.impl.VariantOutputImpl
import com.google.protobuf.gradle.id
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

val appName = "Watchtastic"

// Bump both together: versionName drives the APK filename and the Settings footer,
// versionCode is what Android compares to decide something is an upgrade. Leaving the
// code at 1 would make a rebuilt APK look like the same build to the installer.
val appVersionName = "1.2.0"
val appVersionCode = 3

/**
 * Release signing is read from an untracked `keystore.properties` at the repo root, so a
 * publishable build never depends on credentials living in version control. Without it
 * the release variant still builds — it just comes out unsigned.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.watchtastic"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.watchtastic"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        // Watchtastic is a standalone Wear app: it talks to the radio itself over BLE
        // and never requires a paired phone companion.
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")

                // v1 (JAR signing) is only needed below API 24 and this app is minSdk 33,
                // so it is skipped. v3 carries the key-rotation lineage, which matters if
                // the signing key is ever replaced.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "**/*.proto",
            )
        }
    }

    lint {
        // Wear-only app; the manifest legitimately has no phone launcher activity.
        disable += setOf("MissingApplicationIcon")
        abortOnError = false
    }
}

/**
 * Ship artefacts named for what they are: `Watchtastic-1.0.0-release.apk` rather than
 * `app-release.apk`, so a file sitting in a downloads folder still identifies itself.
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set(
                "$appName-$appVersionName-${variant.name}.apk",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Wear Compose still gates a lot of its expressive surface behind these.
        optIn.addAll(
            "androidx.wear.compose.material3.ExperimentalWearMaterial3Api",
            "androidx.wear.compose.foundation.ExperimentalWearFoundationApi",
        )
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // javalite keeps the generated surface small enough for a watch.
                id("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)

    implementation(libs.wear)
    implementation(libs.wear.ongoing)
    implementation(libs.wear.input)
    implementation(libs.wear.tooling.preview)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    implementation(libs.protobuf.javalite)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.wear.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
