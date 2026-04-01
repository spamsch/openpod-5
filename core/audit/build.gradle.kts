plugins {
    id("openpod.android.library")
    id("openpod.android.hilt")
}
android { namespace = "com.openpod.core.audit" }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.android)
}
