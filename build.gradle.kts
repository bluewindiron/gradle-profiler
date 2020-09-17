import java.net.URI

plugins {
    id("profiler.java-library")
    groovy
    application
    `maven-publish`
}

allprojects {
    group = "org.gradle.profiler"
    version = property("profiler.version") as String
}

val gradleRuntime by configurations.creating
val profilerPlugins by configurations.creating

dependencies {
    implementation(versions.toolingApi)
    implementation("com.google.code.findbugs:annotations:3.0.1")
    implementation("com.google.guava:guava:27.1-android") {
        because("Gradle uses the android variant as well and we are running the same code there.")
    }
    implementation("net.sf.jopt-simple:jopt-simple:5.0.4")
    implementation("com.typesafe:config:1.3.3")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("com.github.javaparser:javaparser-core:3.1.3")
    implementation("org.apache.ant:ant-compress:1.5")
    implementation("commons-io:commons-io:2.6")
    implementation("org.gradle.org.openjdk.jmc:flightrecorder:7.0.0-alpha01")
    implementation("com.android.tools.build:builder-model:3.0.0")
    implementation("com.google.code.gson:gson:2.8.6") {
        because("To write JSON output")
    }
    implementation(project(":client-protocol"))

    gradleRuntime(gradleApi())
    gradleRuntime(versions.toolingApi)
    profilerPlugins(project(":chrome-trace"))
    profilerPlugins(project(":build-operations"))
    profilerPlugins(project(":instrumentation-support"))
    profilerPlugins(project(":studio-agent"))

    runtimeOnly("org.slf4j:slf4j-simple:1.7.10")
    testImplementation(versions.groovy)
    testImplementation(versions.spock)
    testRuntimeOnly("cglib:cglib:3.2.6")
    testRuntimeOnly("org.objenesis:objenesis:2.6")
}

allprojects {
    pluginManager.withPlugin("java") {
        java {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }
}

subprojects {
    // Subprojects are packaged into the Gradle profiler Jar, so let's make them reproducible
    tasks.withType<Jar>().configureEach {
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
        dirMode = Integer.parseInt("0755", 8)
        fileMode = Integer.parseInt("0644", 8)
    }
}

java {
    withSourcesJar()
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to "Gradle Profiler",
            "Implementation-Version" to project.version
        )
    }
}

application.mainClassName = "org.gradle.profiler.Main"

tasks.processResources {
    into("META-INF/jars") {
        from(profilerPlugins.minus(gradleRuntime)) {
            // Removing the version from the JARs here, since they are referenced by name in production code.
            rename("""(.*)-\d\.\d.*\.jar""", "${'$'}1.jar")
        }
    }
}

val testReports = mapOf(
    "testHtmlReportWithoutOps" to "example",
    "testHtmlReportWithOps" to "example-with-build-operations",
    "testHtmlReportRegression" to "example-regression"
)
testReports.forEach { taskName, fileName ->
    tasks.register<ProcessResources>(taskName) {
        val dataFile = file("src/test/resources/org/gradle/profiler/report/${fileName}.json")
        inputs.file(dataFile)

        from("src/main/resources/org/gradle/profiler/report")
        into("$buildDir/test-html-report")
        rename("report-template.html", "test-report-${fileName}.html")
        filter { line -> if (line == "@@BENCHMARK_RESULT_JSON@@") dataFile.readText() else line }
    }
}

tasks.register("testHtmlReports") {
    dependsOn(testReports.keys)
}

val profilerDistribution = artifacts.add("archives", tasks.distZip.flatMap { it.archiveFile }) {
    type = "zip"
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(profilerDistribution)
            pom {
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "GradleBuildInternal"
            url = gradleInternalRepositoryUrl()
            credentials {
                username = project.findProperty("artifactoryUsername") as String?
                password = project.findProperty("artifactoryPassword") as String?
            }
        }
    }
}

fun Project.gradleInternalRepositoryUrl(): URI {
    val isSnapshot = version.toString().endsWith("-SNAPSHOT")
    val repositoryQualifier = if (isSnapshot) "snapshots" else "releases"
    return uri("https://repo.gradle.org/gradle/ext-$repositoryQualifier-local")
}

buildScan {
    isCaptureTaskInputFiles = true
}
