plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.kapt") }
android {
    namespace = "ar.com.brilla.trivia"
    compileSdk = 34
    defaultConfig {
        applicationId = "ar.com.brilla.trivia"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.1.3"
        testInstrumentationRunner = "ar.com.brilla.trivia.DeviceTests"
        kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
    }
    signingConfigs { getByName("debug") { if (project.hasProperty("brillaSigningStore")) storeFile = file(project.property("brillaSigningStore")!!) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
}
dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
}
