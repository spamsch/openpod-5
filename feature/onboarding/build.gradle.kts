plugins {
    id("openpod.android.feature")
}
android { namespace = "com.openpod.feature.onboarding" }
dependencies {
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
}
