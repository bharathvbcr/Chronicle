plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.chronicle.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.chronicle.app"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    // Release requires chronicle-android/keystore.properties
    // (storeFile, storePassword, keyAlias, keyPassword). Not committed.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val requestingRelease = gradle.startParameter.taskNames.any {
      it.contains("Release", ignoreCase = true)
    }
    if (requestingRelease && !keystorePropsFile.exists()) {
      throw GradleException(
        "Release builds require ${keystorePropsFile.absolutePath} " +
          "(storeFile, storePassword, keyAlias, keyPassword). " +
          "See chronicle-android/README.md. Debug builds do not need a release keystore.",
      )
    }
    if (keystorePropsFile.exists()) {
      val keystoreProps = keystorePropsFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate {
          val idx = it.indexOf('=')
          it.substring(0, idx).trim() to it.substring(idx + 1).trim()
        }
      create("release") {
        storeFile = rootProject.file(keystoreProps.getValue("storeFile"))
        storePassword = keystoreProps.getValue("storePassword")
        keyAlias = keystoreProps.getValue("keyAlias")
        keyPassword = keystoreProps.getValue("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // No debugConfig fallback — release must use a real keystore.
      val releaseSigning = signingConfigs.findByName("release")
      if (releaseSigning != null) {
        signingConfig = releaseSigning
      }
      // Missing keystore already fails above when a *Release* task is requested.
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  sourceSets {
    getByName("test") {
      resources.srcDir("${rootDir}/contract")
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.glance)
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.glance.material3)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.haze)
  implementation(libs.haze.materials)
  implementation(libs.markdown.renderer)
  implementation(libs.markdown.renderer.m3)
  implementation(libs.okhttp)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.mlkit.barcode.scanning)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mlkit.genai.proofreading)
  implementation(libs.mlkit.genai.rewriting)
  implementation(libs.mlkit.genai.summarization)
  implementation(libs.mlkit.genai.image.description)
  implementation(libs.androidx.health.connect.client)
  implementation(libs.androidx.security.crypto)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  // Real org.json for JVM unit tests (Android stub is not mocked)
  testImplementation("org.json:json:20240303")
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
