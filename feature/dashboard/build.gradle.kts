plugins {
    id("openpod.android.feature")
}
android {
    namespace = "com.openpod.feature.dashboard"
    defaultConfig {
        buildConfigField(
            "boolean",
            "USE_EMULATOR",
            project.findProperty("useEmulator")?.toString() ?: "false",
        )
        buildConfigField(
            "boolean",
            "USE_BLE",
            project.findProperty("useBle")?.toString() ?: "false",
        )
    }
    buildFeatures {
        buildConfig = true
    }
}
