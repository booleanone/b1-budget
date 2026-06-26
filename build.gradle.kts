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
		dependencies {
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
	}
}
