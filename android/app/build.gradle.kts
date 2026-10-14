import java.io.File

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val releaseSigningNames = listOf("RELEASE_KEYSTORE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
val releaseSigning = releaseSigningNames.map { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigning.any { it != null }
if (hasReleaseSigning) {
    require(releaseSigning.all { !it.isNullOrBlank() }) { "Set all four RELEASE signing environment variables, or none." }
    require(File(releaseSigning[0]!!).let { it.isAbsolute && it.isFile }) { "RELEASE_KEYSTORE must name an existing absolute keystore file." }
}
android {
    namespace = "dev.howaboua.phonedigest"
    compileSdk = 35
    defaultConfig { applicationId = "dev.howaboua.phonedigest"; minSdk = 26; targetSdk = 35; versionCode = 8; versionName = "0.8" }
    buildFeatures { buildConfig = true }
    if (hasReleaseSigning) {
        signingConfigs {
            create("privateRelease") {
                storeFile = File(releaseSigning[0]!!)
                storePassword = releaseSigning[1]
                keyAlias = releaseSigning[2]
                keyPassword = releaseSigning[3]
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("privateRelease")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.firebase:firebase-messaging:25.0.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
