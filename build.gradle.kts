plugins {
  application
  kotlin("jvm") version "2.3.0"
}

group = "tech.fouronesoft"
version = "1.0-SNAPSHOT"

application {
  mainClass.set("tech.fouronesoft.kvnoid.cli.Main")
}

repositories {
  mavenCentral()
}

dependencies {
  testImplementation(kotlin("test"))
}

kotlin {
  jvmToolchain(21)
}

tasks.test {
  useJUnitPlatform()
}
