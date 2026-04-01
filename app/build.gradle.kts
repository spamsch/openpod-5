plugins {
    id("openpod.android.application")
    id("openpod.android.compose")
    id("openpod.android.hilt")
}

android {
    namespace = "com.openpod"

    defaultConfig {
        applicationId = "com.openpod"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":core:ble"))
    implementation(project(":core:datastore"))

    implementation(project(":feature:dashboard"))
    implementation(project(":feature:bolus"))
    implementation(project(":feature:basal"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:history"))
    implementation(project(":feature:alerts"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:onboarding"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.timber)
}
