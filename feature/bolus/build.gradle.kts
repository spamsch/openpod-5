plugins {
    id("openpod.android.feature")
}
android { namespace = "com.openpod.feature.bolus" }
dependencies {
    implementation(project(":core:datastore"))
    implementation(project(":core:audit"))

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
tasks.withType<Test> { useJUnitPlatform() }
