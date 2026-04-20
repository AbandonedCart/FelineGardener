import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
val signingKeyAlias = System.getenv("KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")

val gitShortHash = System.getenv("GITHUB_SHA")?.take(7)
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short=7", "HEAD")
        }.standardOutput.asText.get().trim()
    }.getOrDefault("local")
val requiresReleaseSigning = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("release", ignoreCase = true) ||
        taskName.contains("publish", ignoreCase = true) ||
        taskName.contains("bundle", ignoreCase = true) ||
        taskName.contains("sign", ignoreCase = true)
}

if (requiresReleaseSigning) {
    val missingVariables = buildList {
        if (keystorePassword.isNullOrBlank()) add("KEYSTORE_PASSWORD (or 'keyPassword' in keystore.properties)")
        if (signingKeyAlias.isNullOrBlank()) add("KEY_ALIAS (or 'keyAlias' in keystore.properties)")
    }
    if (missingVariables.isNotEmpty()) {
        throw GradleException(
            "Missing required signing configuration: ${missingVariables.joinToString(", ")}."
        )
    }
}

android {
    namespace = "com.felinegardener.toxicplants"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.felinegardener.toxicplants"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GIT_SHORT_HASH", "\"$gitShortHash\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("app/signing/release.keystore")
            storePassword = keystorePassword
            keyAlias = signingKeyAlias
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    flavorDimensions += "source"
    productFlavors {
        create("github") {
            dimension = "source"
            manifestPlaceholders["installPermission"] = "android.permission.REQUEST_INSTALL_PACKAGES"
            manifestPlaceholders["updatesPermission"] = "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"
            buildConfigField("Boolean", "GOOGLE_PLAY", "false")
        }
        create("google") {
            dimension = "source"
            isDefault = true
            manifestPlaceholders["installPermission"] = "com.felinegardener.toxicplants.INSTALL"
            manifestPlaceholders["updatesPermission"] = "com.felinegardener.toxicplants.UPDATES"
            buildConfigField("Boolean", "GOOGLE_PLAY", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)
    implementation(libs.androidx.browser)

    testImplementation(libs.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
