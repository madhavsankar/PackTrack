import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.packtrack.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.packtracker.app"
        minSdk = 26
        targetSdk = 34
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.0.$runNumber"

        // Read optional GEMINI_API_KEY and FIREBASE_PROJECT_ID from git-ignored local.properties or env vars
        val geminiKey = localProps.getProperty("GEMINI_API_KEY")
            ?: project.findProperty("GEMINI_API_KEY")?.toString()
            ?: System.getenv("GEMINI_API_KEY")
            ?: ""
        val firebaseProjectId = localProps.getProperty("FIREBASE_PROJECT_ID")
            ?: project.findProperty("FIREBASE_PROJECT_ID")?.toString()
            ?: System.getenv("FIREBASE_PROJECT_ID")
            ?: ""
        val defaultUserId = localProps.getProperty("DEFAULT_USER_ID")
            ?: project.findProperty("DEFAULT_USER_ID")?.toString()
            ?: System.getenv("DEFAULT_USER_ID")
            ?: ""
        val allowedEmails = localProps.getProperty("ALLOWED_EMAILS")
            ?: project.findProperty("ALLOWED_EMAILS")?.toString()
            ?: System.getenv("ALLOWED_EMAILS")
            ?: ""
        val googleClientId = localProps.getProperty("GOOGLE_CLIENT_ID")
            ?: project.findProperty("GOOGLE_CLIENT_ID")?.toString()
            ?: System.getenv("GOOGLE_CLIENT_ID")
            ?: ""

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "DEFAULT_USER_ID", "\"$defaultUserId\"")
        buildConfigField("String", "ALLOWED_EMAILS", "\"$allowedEmails\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")
    }

    val keystorePath = localProps.getProperty("KEYSTORE_FILE")
        ?: System.getenv("KEYSTORE_FILE")
        ?: ""
    val keystorePassword = localProps.getProperty("KEYSTORE_PASSWORD")
        ?: System.getenv("KEYSTORE_PASSWORD")
        ?: ""
    val keyAliasName = localProps.getProperty("KEY_ALIAS")
        ?: System.getenv("KEY_ALIAS")
        ?: "upload"
    val keyPasswordValue = localProps.getProperty("KEY_PASSWORD")
        ?: System.getenv("KEY_PASSWORD")
        ?: keystorePassword
    val keystoreFile = if (keystorePath.isNotBlank()) file(keystorePath) else null
    val hasReleaseKeystore = keystoreFile != null && keystoreFile.exists() && keystorePassword.isNotBlank()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Room Local SQLite Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Firebase BoM & Analytics & Google Sign-In
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Unit Testing / Evals
    testImplementation("junit:junit:4.13.2")
}
