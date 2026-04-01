plugins {
    id("openpod.android.feature")
}
android { namespace = "com.openpod.feature.history" }
dependencies {
    implementation(project(":core:database"))
}
