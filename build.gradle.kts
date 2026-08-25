import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.LibraryPlugin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.google.ksp) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlinx.atomicfu) apply false
    alias(libs.plugins.remap) apply false
    alias(libs.plugins.loc) apply false
    alias(libs.plugins.littlerobots.version)
}

object Cfg {
    val compileSdk get() = 37
    val buildToolsVersion get() = "37.0.0"
    // 2026-08-25 - 26 是从上游 GKD 原样继承的，从没在这个项目里重新评估过。液态玻璃（这个应用
    // 最大的视觉卖点）已经在 8 个文件里硬性要求 API 33，低于 33 的设备本来就只能看阉割版外观。
    // 用户明确表示目前几个使用者都是现代机型、不用迁就极端老设备，改到 33 让 minSdk 对齐应用
    // 实际已经在要求的门槛，顺带换来 android:pathSuffix 这类 API 31+ 才有的精确匹配能力（见
    // GlassPresetImportActivity 的 intent-filter），不用再靠"点号当通配符"那种老式写法凑合。
    val minSdk get() = 33
    val targetSdk get() = compileSdk
    val sourceVersion = JavaVersion.VERSION_11
    val targetVersion get() = sourceVersion
    val kotlinTargetVersion get() = JvmTarget.fromTarget(targetVersion.majorVersion)
    val kotlinCompilerArgs = listOf(
        "-opt-in=kotlin.RequiresOptIn",
        "-opt-in=kotlin.contracts.ExperimentalContracts",
        "-opt-in=kotlinx.coroutines.FlowPreview",
        "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
        "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        "-XXLanguage:+MultiDollarInterpolation",
    )
}

subprojects {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(Cfg.kotlinTargetVersion)
        }
    }
    plugins.withType<AppPlugin> {
        tasks.withType<KotlinCompilationTask<*>>().configureEach {
            compilerOptions {
                freeCompilerArgs.addAll(Cfg.kotlinCompilerArgs)
            }
        }
        extensions.getByType(ApplicationExtension::class.java).apply {
            compileSdk = Cfg.compileSdk
            buildToolsVersion = Cfg.buildToolsVersion
            defaultConfig {
                minSdk = Cfg.minSdk
                targetSdk = Cfg.targetSdk
            }
            compileOptions {
                sourceCompatibility = Cfg.sourceVersion
                targetCompatibility = Cfg.targetVersion
            }
        }
    }
    plugins.withType<LibraryPlugin> {
        extensions.getByType(LibraryExtension::class.java).apply {
            compileSdk = Cfg.compileSdk
            buildToolsVersion = Cfg.buildToolsVersion
            defaultConfig {
                minSdk = Cfg.minSdk
            }
            compileOptions {
                sourceCompatibility = Cfg.sourceVersion
                targetCompatibility = Cfg.targetVersion
            }
        }
    }
}
