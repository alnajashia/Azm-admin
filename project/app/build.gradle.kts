import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.alnajashia.azmadmin"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.alnajashia.azmadmin"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("azmAdminRelease") {
            // Azm Admin's dedicated release key lives OUTSIDE the project
            // (~/workspace/azm-admin/keys/, never in the repo), following
            // the same pattern as Azm's permanent key. Without it the
            // release build stays unsigned rather than falling back to a
            // debug key.
            val propsFile = file(
                System.getenv("AZMADMIN_KEYSTORE_PROPERTIES")
                    ?: "${rootDir}/../keys/keystore.properties",
            )
            if (propsFile.exists()) {
                val props = Properties().apply {
                    propsFile.inputStream().use { load(it) }
                }
                storeFile = propsFile.parentFile.resolve(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("azmAdminRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_21.toString()
    }
}

dependencies {
    implementation(project(":client"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Wireless-debugging start (pairing + ADB client), TLS 1.3 provider
    // and the certificate generator for the manager's ADB identity.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    testImplementation("junit:junit:4.13.2")
}
