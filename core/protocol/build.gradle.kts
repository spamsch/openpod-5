plugins {
    id("openpod.android.library")
    id("openpod.android.hilt")
}
android { namespace = "com.openpod.core.protocol" }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ble"))
    implementation(project(":core:crypto"))
    implementation(libs.wire.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)
}
