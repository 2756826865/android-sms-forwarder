pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.devtools.ksp") {
                useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/central") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        mavenLocal()
    }
}
include(":app")
