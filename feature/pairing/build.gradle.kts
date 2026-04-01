plugins {
    id("openpod.android.feature")
}
android {
    namespace = "com.openpod.feature.pairing"
    defaultConfig {
        buildConfigField(
            "boolean",
            "USE_EMULATOR",
            project.findProperty("useEmulator")?.toString() ?: "false",
        )
    }
    buildFeatures {
        buildConfig = true
    }
}
dependencies {
    implementation(project(":core:ble"))
    implementation(project(":core:crypto"))
    implementation(project(":core:protocol"))
}
