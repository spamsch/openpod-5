plugins {
    id("openpod.android.library")
    id("openpod.android.hilt")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}
android { namespace = "com.openpod.core.database" }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.tink)
    implementation(libs.timber)
}
room { schemaDirectory("$projectDir/schemas") }
