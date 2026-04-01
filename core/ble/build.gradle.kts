plugins {
    id("openpod.android.library")
    id("openpod.android.hilt")
}
android { namespace = "com.openpod.core.ble" }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.kable.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
}
