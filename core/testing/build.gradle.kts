plugins {
    id("openpod.android.library")
}
android { namespace = "com.openpod.core.testing" }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    api(libs.junit5.api)
    api(libs.junit5.params)
    api(libs.mockk)
    api(libs.turbine)
    api(libs.truth)
    api(libs.kotlinx.coroutines.test)
}
