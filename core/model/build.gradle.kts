plugins {
    id("openpod.kotlin.library")
}
dependencies {
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.junit5.params)
    testImplementation(libs.truth)
}
tasks.withType<Test> { useJUnitPlatform() }
