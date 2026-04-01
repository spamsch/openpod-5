plugins {
    id("openpod.android.library")
    id("openpod.android.hilt")
}
android { namespace = "com.openpod.core.crypto" }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.junit5.params)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
