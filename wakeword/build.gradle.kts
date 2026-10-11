plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The app provides the Android build of ONNX Runtime (same Java API); tests use the desktop build.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("models.dir", rootProject.file("app/src/main/assets/wakeword").absolutePath)
    systemProperty("audio.dir", layout.buildDirectory.dir("test-audio").get().asFile.absolutePath)
}
