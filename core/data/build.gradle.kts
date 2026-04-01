plugins {
    id("openpod.android.library")
    id("openpod.android.hilt")
}
android { namespace = "com.openpod.core.data" }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:ble"))
    implementation(project(":core:protocol"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
}
