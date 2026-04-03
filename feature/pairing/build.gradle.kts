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
dependencies {
    implementation(project(":core:ble"))
    implementation(project(":core:crypto"))
    implementation(project(":core:protocol"))

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.truth)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
