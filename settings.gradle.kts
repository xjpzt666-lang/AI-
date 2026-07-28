pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 告诉打包机去下面这个旧仓库找 Xposed 的核心库！
        maven { url = uri("https://jcenter.bintray.com") } 
    }
}

rootProject.name = "HT_AI_Translator"
include(":app")
