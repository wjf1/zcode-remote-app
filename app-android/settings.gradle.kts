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
    }
}
rootProject.name = "ZCodeRemote"
include(":app")
// §11.8 长会话基准：Macrobenchmark 独立测试模块（com.android.test，针对 :app 的 benchmark 变体）
include(":benchmark")
