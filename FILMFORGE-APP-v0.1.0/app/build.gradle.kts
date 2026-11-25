import java.net.URI
import java.security.MessageDigest

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.krackmusik.filmforge"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.krackmusik.filmforge"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera.media3:media3-effect:1.0.0-alpha04")
    implementation("androidx.media3:media3-effect:1.7.1")
}

val modelAsset = layout.projectDirectory.file("src/main/assets/super-resolution.onnx")
val prepareNeuralAssets = tasks.register("prepareNeuralAssets") {
    outputs.file(modelAsset)
    outputs.file(layout.projectDirectory.file("src/main/assets/ONNX-MODEL-LICENSE.txt"))
    outputs.file(layout.projectDirectory.file("src/main/assets/ONNX-RUNTIME-LICENSE.txt"))
    doLast {
        val target = modelAsset.asFile
        target.parentFile.mkdirs()
        val expected = "85f36ff88cc504a24af5e0602148bc56a8aa09a58eca8c0da2756f3e8186035e"
        if (!target.exists() || MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) } != expected) {
            URI("https://media.githubusercontent.com/media/onnx/models/main/validated/vision/super_resolution/sub_pixel_cnn_2016/model/super-resolution-10.onnx")
                .toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == expected) { "Neural model checksum mismatch" }
        URI("https://raw.githubusercontent.com/onnx/models/main/LICENSE").toURL().openStream().use { input ->
            layout.projectDirectory.file("src/main/assets/ONNX-MODEL-LICENSE.txt").asFile.writeText(
                "FILMFORGE uses the ONNX Model Zoo sub-pixel CNN\nSource: https://github.com/onnx/models/tree/main/validated/vision/super_resolution/sub_pixel_cnn_2016\nSHA-256: $expected\n\n" + input.bufferedReader().readText())
        }
        layout.projectDirectory.file("src/main/assets/ONNX-RUNTIME-LICENSE.txt").asFile.writeText("""
            MIT License

            Copyright (c) Microsoft Corporation

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all
            copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.
        """.trimIndent())
    }
}
tasks.named("preBuild").configure { dependsOn(prepareNeuralAssets) }
