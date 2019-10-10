import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

apply<JUnit5Plugin>()
apply<JarTest>()

repositories {
    maven("https://kotlin.bintray.com/kotlinx")
}

dependencies {
    implementation(Libs.kotlin_stdlib_jdk8)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.atomicfu)

    implementation(Libs.log4j_api)

    implementation(Libs.jackson_annotations)
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-XXLanguage:+InlineClasses")
}
