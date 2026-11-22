// Pure Kotlin/JVM shared core: protocol, reliability, crypto, file chunking, clipboard resolution.
// No Android dependency, so it is unit-tested on the JVM and interop-tested against the C# hub.
plugins {
    alias(libs.plugins.kotlin.jvm)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    testImplementation(libs.junit)
}
tasks.test {
    systemProperty("ukpro.vectors", rootProject.file("../shared/test-vectors").absolutePath)
}

// Kotlin ↔ C# interop test client: ./gradlew :core:interop -PuriFile=/path/to/uri.txt (see interop/run.sh)
tasks.register<JavaExec>("interop") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ukpro.core.InteropClient")
    args(project.findProperty("uriFile")?.toString() ?: "uri.txt")
}
