
plugins {
    kotlin("jvm") version Versions.org_jetbrains_kotlin_jvm_gradle_plugin apply false
    id("org.liquibase.gradle") version Versions.org_liquibase_gradle_gradle_plugin apply false
    id("nu.studer.jooq") version Versions.nu_studer_jooq_gradle_plugin apply false
    buildSrcVersions
}

allprojects {
    repositories {
        jcenter()
    }
}
