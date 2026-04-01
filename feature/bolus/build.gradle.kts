plugins {
    id("openpod.android.feature")
}
android { namespace = "com.openpod.feature.bolus" }
dependencies {
    implementation(project(":core:datastore"))
}
