plugins {
	java
	alias(libs.plugins.spring.boot) apply false
	alias(libs.plugins.dependency.management) apply false
}

allprojects {
	group = "com.booleanone.budget"
	version = "0.0.1-SNAPSHOT"
}

description = "B1 Budget – budgeting platform"

subprojects {
	plugins.withType<JavaPlugin> {
		apply(plugin = "checkstyle")

		configurations {
			compileOnly {
				extendsFrom(configurations.annotationProcessor.get())
			}
		}

		dependencies {
			compileOnly(rootProject.libs.lombok)
			annotationProcessor(rootProject.libs.lombok)
			testCompileOnly(rootProject.libs.lombok)
			testAnnotationProcessor(rootProject.libs.lombok)
			testRuntimeOnly(rootProject.libs.junit.platform.launcher)
		}

		extensions.configure<JavaPluginExtension> {
			toolchain {
				languageVersion.set(JavaLanguageVersion.of(25))
			}
		}

		tasks.withType<Test> {
			useJUnitPlatform()
		}

		extensions.configure<CheckstyleExtension> {
			toolVersion = rootProject.libs.versions.checkstyle.get()
			configFile = rootProject.file("${rootDir}/.checkstyle/checkstyle.xml")
			configProperties = mapOf(
				"suppressionFile" to "${rootDir}/.checkstyle/checkstyle-suppressions.xml"
			)
		}
	}
}
