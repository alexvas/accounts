@file:Suppress("UnstableApiUsage")

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.creating
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.repositories

const val testImplementation = "testImplementation"
const val testRuntimeOnly = "testRuntimeOnly"

class JUnit5Plugin : Plugin<Project> {

    override fun apply(project: Project) = with(project) {
        repositories {
            maven("https://oss.sonatype.org/content/repositories/snapshots")
        }
        tasks.named<Test>("test") {
            @Suppress("UnstableApiUsage")
            useJUnitPlatform()
            reports {
                junitXml.isEnabled = true
                html.isEnabled = true
            }
        }

        dependencies {
            testImplementation(Libs.assertj_core)
            testImplementation(Libs.junit_jupiter_api)
            testImplementation(Libs.mockk)

            testRuntimeOnly(Libs.junit_jupiter_engine)
            testRuntimeOnly(Libs.log4j_core)
            testRuntimeOnly(Libs.log4j_jcl)
            testRuntimeOnly(Libs.log4j_jul)
            testRuntimeOnly(Libs.log4j_slf4j_impl)
            testRuntimeOnly(Libs.disruptor)
        }
    }
}

class JarTest : Plugin<Project> {

    override fun apply(project: Project): Unit = with(project) {
        @Suppress("UNUSED_VARIABLE")
        val testArchives by configurations.creating {
            extendsFrom(configurations["testCompile"])
        }

        val kotlinCompile = tasks.getByName("compileTestKotlin")

        val jarTest = tasks.register<Jar>("jarTest") {
            dependsOn(kotlinCompile)
            from(kotlinCompile.outputs)
            from("src/test/resources") {
                include("**/*.properties")
                include("**/*.xml")
                include("**/*.key")
                include("**/*.pub")
            }

            archiveClassifier.set("test")
        }

        artifacts {
            add(configurationName, jarTest)
        }
    }

    companion object {
        const val configurationName = "testArchives"
    }

}
