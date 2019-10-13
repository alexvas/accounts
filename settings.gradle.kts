rootProject.name = "accounts"

include(
        "common",
        "dal",
        "core",
        "web",
        "client",
        "app"
)

pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "kotlinx-serialization") {
                useModule("org.jetbrains.kotlin:kotlin-serialization:${requested.version}")
            }
        }
    }
}
