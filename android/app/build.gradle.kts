plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.xjb.kaoyancheckin"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.xjb.kaoyancheckin"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

val recordScreenshots = providers.gradleProperty("recordScreenshots").map(String::toBoolean).orElse(false)
val screenshotDir = providers.gradleProperty("screenshotDir")
    .orElse(providers.systemProperty("screenshotDir"))
    .orElse(layout.buildDirectory.dir("screenshots").map { it.asFile.absolutePath })

tasks.withType<Test>().configureEach {
    if (recordScreenshots.get()) {
        systemProperty("roborazzi.test.record", "true")
        systemProperty("screenshotDir", screenshotDir.get())
        // Robolectric may need to download its instrumented SDK in the test JVM.
        listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach { name ->
            providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
        }
        outputs.upToDateWhen { false }
    } else {
        exclude("**/ScreenshotTest.class")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.38.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
