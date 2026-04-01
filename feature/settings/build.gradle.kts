plugins {
    id("openpod.android.feature")
}
android { namespace = "com.openpod.feature.settings" }
dependencies {
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
}
