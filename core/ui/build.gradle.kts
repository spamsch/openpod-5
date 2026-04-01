plugins {
    id("openpod.android.library")
    id("openpod.android.compose")
}
android { namespace = "com.openpod.core.ui" }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.timber)
}
