plugins {
  java
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.dependency.management)
}

description = "Budgeting API for B1 Budget: zero-based budgeting, categories, transactions, reporting, and sync."

dependencies {
	implementation("org.springframework.boot:spring-boot-starter")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
  layered {
    enabled = true
  }
}
