plugins {
    id("openpod.android.library")
    id("openpod.android.hilt")
}
android { namespace = "com.openpod.core.datastore" }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink)
    implementation(libs.timber)
}
