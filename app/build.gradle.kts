plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
 id("org.jetbrains.kotlin.kapt")
 jacoco
}
val practical = providers.gradleProperty("practical").orElse("6").get().toInt()
android {
 namespace = "ua.kpi.radmonitor"
 compileSdk = 35
 buildToolsVersion = "36.0.0"
 defaultConfig {
  applicationId = "ua.kpi.radmonitor.pw$practical"
  minSdk = 26
  targetSdk = 35
  versionCode = 6
  versionName = "1.0.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  buildConfigField("int", "PRACTICAL", practical.toString())
 }
 // Optional dimension preserves a simple default build while allowing an explicit demo flavor.
 if (providers.gradleProperty("withFlavors").orNull == "true") {
  flavorDimensions += "distribution"
  productFlavors {
   create("demo") { dimension = "distribution"; applicationIdSuffix = ".demo"; versionNameSuffix = "-demo" }
   create("full") { dimension = "distribution" }
  }
 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 signingConfigs {
  if (System.getenv("RAD_RELEASE_STORE") != null) create("courseRelease") {
   storeFile = file(System.getenv("RAD_RELEASE_STORE"))
   storePassword = System.getenv("RAD_RELEASE_PASSWORD")
   keyAlias = "course"
   keyPassword = System.getenv("RAD_RELEASE_PASSWORD")
  }
 }
 buildTypes {
  debug { enableUnitTestCoverage = providers.gradleProperty("coverage").orNull == "true" }
  release { testProguardFiles("test-proguard-rules.pro"); if (System.getenv("RAD_RELEASE_STORE") != null) signingConfig = signingConfigs.getByName("courseRelease"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
 }
 testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()
 testOptions { unitTests.isIncludeAndroidResources = true }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation("androidx.navigation:navigation-compose:2.8.5")
 implementation("androidx.room:room-runtime:2.6.1")
 implementation("androidx.room:room-ktx:2.6.1")
 kapt("androidx.room:room-compiler:2.6.1")
 implementation("com.squareup.retrofit2:retrofit:2.11.0")
 implementation("com.squareup.retrofit2:converter-gson:2.11.0")
 implementation("androidx.work:work-runtime-ktx:2.9.1")
 implementation("org.osmdroid:osmdroid-android:6.1.20")
 implementation("io.coil-kt:coil-compose:2.7.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("androidx.test:core:1.6.1")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
 testImplementation("org.robolectric:robolectric:4.14.1")
 androidTestImplementation("com.google.errorprone:error_prone_annotations:2.30.0")
 androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
 debugImplementation("androidx.compose.ui:ui-tooling")
}

jacoco { toolVersion = "0.8.12" }
tasks.register<JacocoReport>("coverageReport") {
 dependsOn("testDebugUnitTest")
 executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
 sourceDirectories.setFrom(files("src/main/java"))
 classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
  exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/*_Impl*", "**/*Test*")
 })
 reports { xml.required.set(true); html.required.set(true) }
}
