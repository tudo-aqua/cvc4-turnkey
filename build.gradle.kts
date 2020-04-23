/*
 * Copyright 2019-2020 Simon Dierl <simon.dierl@cs.tu-dortmund.de>
 * SPDX-License-Identifier: ISC
 *
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby
 * granted, provided that the above copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
 * INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN
 * AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
 * PERFORMANCE OF THIS SOFTWARE.
 */

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.JavaVersion.VERSION_1_8
import java.nio.file.Files.createDirectories
import java.nio.file.Files.writeString
import java.nio.file.Path


plugins {
    id("de.undercouch.download") version "4.0.4"
    `java-library`
    `maven-publish`
    signing
}


group = "io.github.tudo-aqua"

val cvc4Version = "1.7"
val turnkeyVersion = ""
version = "$cvc4Version$turnkeyVersion"


java {
    sourceCompatibility = VERSION_1_8
    targetCompatibility = VERSION_1_8
}


buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(group = "com.github.javaparser", name = "javaparser-core", version = "3.15.12")
    }
}

/**
 * CVC4 licensing model metadata.
 */
data class CVC4License(val buildName: String, val humanReadable: String, val includeGPL: Boolean = false)

val cvc4LicenseVariants = listOf(
    CVC4License("gpl", "GPL", includeGPL = true),
    CVC4License("permissive", "Permissive")
)
val permissive = cvc4LicenseVariants[1]


/**
 * Build, Operating System and CPU architecture metadata.
 * @param buildName the name of the build archives.
 * @param buildSystem the Azure VM used for building the artifact.
 * @param os the operating system name used in the final distribution.
 * @param architecture the CPU architecture name used in the final distribution.
 * @param extension the library file name extension used by the OS.
 * @param jniExtension the library file name extension used by the OS for JNI libraries.
 */
data class CVC4Distribution(
    val buildName: String, val buildSystem: String, val os: String, val architecture: String, val extension: String,
    val jniExtension: String = extension
)

/** The OS-CPU combinations CVC4 distributions are available for. */
val cvc4Architectures = listOf(
    CVC4Distribution("Linux", "ubuntu-latest", "linux", "amd64", "so"),
    CVC4Distribution("MacOS", "macOS-latest", "osx", "amd64", "dylib", "jnilib")
)
val linux = cvc4Architectures[0]


/** Convert a Java package name to the expected class file path.
 * @param packageName the package name.
 * @return the respective relative path.
 *
 */
fun packageToPath(packageName: String): Path = Path.of(
    packageName.substringBefore("."),
    *packageName.split(".").drop(1).toTypedArray()
)


/** The name of the CVC4 Java package. */
val cvc4Package = "edu.nyu.acsys.CVC4"

/** The relative path to the CVC4 package. */
val cvc4PackagePath = packageToPath(cvc4Package)


cvc4Architectures.forEach { (arch, _) ->
    tasks.register<Download>("downloadCVC4Binary$arch") {
        description = "Download the CVC4 binary distribution for $arch."
        src("https://github.com/tudo-aqua/cvc4-azure-build/releases/download/$cvc4Version/Build.Build.$arch.zip")
        dest(buildDir.toPath().resolve("binary-archives").resolve("Build.Build.$arch.zip").toFile())
        quiet(true)
        overwrite(false)
    }

    tasks.register<Copy>("extractCVC4Binary$arch") {
        description = "Extract the CVC4 binary distribution for $arch."
        dependsOn("downloadCVC4Binary$arch")
        from(zipTree(tasks.named<Download>("downloadCVC4Binary$arch").get().dest))
        into(buildDir.toPath().resolve("unpacked-binaries-$arch"))
    }
}


cvc4LicenseVariants.forEach { license ->
    cvc4Architectures.forEach { osData ->
        tasks.register("${license.buildName}CopyNativeLibraries${osData.buildName}") {
            description = "Copy the CVC4 native libraries with ${license.humanReadable} license model for" +
                    "${osData.buildName} to the correct directory layout."
            dependsOn("extractCVC4Binary${osData.buildName}")

            val input = tasks.named("extractCVC4Binary${osData.buildName}").get().outputs.files.singleFile.toPath()
            inputs.dir(input)

            val output = buildDir.toPath().resolve("native-libraries-${license.buildName}-${osData.buildName}")
            outputs.dir(output)
            doLast {
                val lib = input.resolve("Build.Build.${osData.buildName}")
                    .resolve("cvc4-$cvc4Version-${osData.buildSystem}-${license.buildName}").resolve("lib")
                listOf("cvc4", "cvc4parser").forEach { library ->
                    copy {
                        from(lib.resolve("lib$library.${osData.extension}"))
                        into(output.resolve("native").resolve("${osData.os}-${osData.architecture}"))
                    }
                }
                copy {
                    from(lib.resolve("libcvc4jni.${osData.jniExtension}"))
                    into(output.resolve("native").resolve("${osData.os}-${osData.architecture}"))
                }
            }
        }
    }

    tasks.register("${license.buildName}CopySources") {
        description =
            "Copy the CVC4 Java sources with ${license.humanReadable} license model to the correct directory " +
                    "structure."
        dependsOn("extractCVC4Binary${linux.buildName}")

        val sourceDir = tasks.named<Copy>("extractCVC4Binary${linux.buildName}").get().destinationDir.toPath()
            .resolve("Build.Build.${linux.buildName}")
            .resolve("cvc4-$cvc4Version-${linux.buildSystem}-${license.buildName}")
        val output = buildDir.toPath().resolve("swig-sources-${license.buildName}")

        inputs.dir(sourceDir)
        outputs.dir(output)

        doLast {
            copy {
                from(sourceDir.resolve("src").resolve("cvc4").resolve("java"))
                include("*.java")
                exclude("CVC4JNI.java")
                into(output.resolve(cvc4PackagePath))
            }
        }
    }

    tasks.register("${license.buildName}RewriteCVC4JNIJava") {
        description = "Rewrite the CVC4 native binding with ${license.humanReadable} license model to use the new " +
                "unpack-and-link code."
        dependsOn("extractCVC4Binary${linux.buildName}")

        val sourceDir = tasks.named<Copy>("extractCVC4Binary${linux.buildName}").get().destinationDir.toPath()
            .resolve("Build.Build.${linux.buildName}")
            .resolve("cvc4-$cvc4Version-${linux.buildSystem}-${license.buildName}")
        val output = buildDir.toPath().resolve("rewritten-cvc4jni-${license.buildName}")

        inputs.dir(sourceDir)
        outputs.dir(output)

        doLast {
            val cvc4jniJava = sourceDir.resolve("src").resolve("cvc4").resolve("java")
                .resolve("CVC4JNI.java")
            val parse = JavaParser().parse(cvc4jniJava)

            val compilationUnit = parse.result.orElseThrow()
            val nativeClass = compilationUnit.primaryType.orElseThrow()
            val staticInitializer = InitializerDeclaration(
                true,
                BlockStmt(NodeList(ExpressionStmt(MethodCallExpr("CVC4Loader.loadCVC4"))))
            )
            nativeClass.members.add(staticInitializer)

            val rewrittenNativeJava = output.resolve(cvc4PackagePath).resolve("CVC4JNI.java")
            createDirectories(rewrittenNativeJava.parent)
            writeString(rewrittenNativeJava, compilationUnit.toString())
        }
    }

    tasks.register<Jar>("${license.buildName}Jar") {
        description = "Create a JAR for ${license.humanReadable} license model."
        dependsOn("${license.buildName}Classes")

        archiveBaseName.set("${rootProject.name}-${license.buildName}")
        from(sourceSets[license.buildName].output.classesDirs, sourceSets[license.buildName].output.resourcesDir)
    }

    tasks.register<Javadoc>("${license.buildName}Javadoc") {
        description = "Create Javadoc for ${license.humanReadable} license model."
        dependsOn("${license.buildName}CopySources", "${license.buildName}RewriteCVC4JNIJava")

        source = sourceSets[license.buildName].allJava
        setDestinationDir(buildDir.toPath().resolve("docs").resolve("javadoc-${license.buildName}").toFile())
    }

    tasks.register<Jar>("${license.buildName}JavadocJar") {
        description = "Create a Javadoc JAR for ${license.humanReadable} license model."
        archiveBaseName.set("${rootProject.name}-${license.buildName}")
        archiveClassifier.set("javadoc")
        dependsOn("${license.buildName}Javadoc")
        from(tasks.named<Javadoc>("${license.buildName}Javadoc").get().destinationDir)
    }

    tasks.register<Jar>("${license.buildName}SourcesJar") {
        description = "Create a Sources JAR for ${license.humanReadable} license model."
        archiveBaseName.set("${rootProject.name}-${license.buildName}")
        archiveClassifier.set("sources")
        dependsOn("${license.buildName}CopySources", "${license.buildName}RewriteCVC4JNIJava")
        from(sourceSets[license.buildName].allSource)
    }
}


tasks.assemble {
    setDependsOn(cvc4LicenseVariants.flatMap { license ->
        listOf("", "Javadoc", "Sources").map { "${license.buildName}${it}Jar" }
    })
}


sourceSets {
    cvc4LicenseVariants.forEach { license ->
        create(license.buildName) {
            java {
                srcDirs(
                    sourceSets.main.get().allSource,
                    tasks.named("${license.buildName}CopySources").get().outputs.files,
                    tasks.named("${license.buildName}RewriteCVC4JNIJava").get().outputs.files
                )
            }
            resources {
                srcDirs(
                    *cvc4Architectures
                        .map {
                            tasks.named("${license.buildName}CopyNativeLibraries${it.buildName}").get().outputs.files
                        }.toTypedArray()
                )
            }
        }
    }
    create("integrationTest") {
        compileClasspath += tasks.named("${permissive.buildName}Jar").get().outputs.files
        runtimeClasspath += tasks.named("${permissive.buildName}Jar").get().outputs.files
    }
}


/** Integration test implementation configuration. */
val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

/** Integration test runtime-only configuration. */
val integrationTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.runtimeOnly.get())
}

repositories {
    mavenCentral()
}

val jUnitVersion = "5.6.0"

dependencies {
    integrationTestImplementation(group = "org.junit.jupiter", name = "junit-jupiter-api", version = jUnitVersion)
    integrationTestRuntimeOnly(group = "org.junit.jupiter", name = "junit-jupiter-engine", version = jUnitVersion)
}

cvc4LicenseVariants.forEach { license ->
    tasks.register<Test>("${license.buildName}IntegrationTest") {
        description = "Run the integration tests against the final JAR."
        dependsOn(tasks.jar)

        useJUnitPlatform()
        setForkEvery(1)

        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
    }
}

val integrationTestJar by tasks.registering(Jar::class) {
    description = "Package the integration tests into a JAR for standalone execution."
    dependsOn(tasks["integrationTestClasses"])

    archiveClassifier.set("integration-tests")
    from(sourceSets["integrationTest"].output.classesDirs)
}


publishing {
    publications {
        cvc4LicenseVariants.forEach { license ->
            create<MavenPublication>(license.buildName) {
                artifactId = "${project.name}-${license.buildName}"
                listOf("", "Javadoc", "Sources").forEach {
                    artifact(tasks.named("${license.buildName}${it}Jar").get())
                }
                pom {
                    name.set("CVC4-TurnKey (${license.humanReadable})")
                    description.set(
                        "A self-unpacking, standalone CVC4 distribution that ships all required native support code " +
                                "and automatically unpacks it at runtime. ${license.humanReadable}-licensed version."
                    )
                    url.set("https://github.com/tudo-aqua/cvc4-turnkey")
                    licenses {
                        license {
                            name.set("The 3-Clause BSD License")
                            url.set("https://opensource.org/licenses/BSD-3-Clause")
                        }
                        license {
                            name.set("GNU Lesser General Public License, Version 3")
                            url.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                        }
                        if (license.includeGPL) {
                            license {
                                name.set("GNU General Public License, Version 3")
                                url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                            }
                        }
                        license {
                            name.set("The PostgreSQL Licence")
                            url.set("https://opensource.org/licenses/PostgreSQL")
                        }
                        license {
                            name.set("The MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                        license {
                            name.set("ISC License")
                            url.set("https://opensource.org/licenses/ISC")
                        }
                    }
                    developers {
                        developer {
                            name.set("Simon Dierl")
                            email.set("simon.dierl@cs.tu-dortmund.de")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com:tudo-aqua/cvc4-turnkey.git")
                        developerConnection.set("scm:git:ssh://git@github.com:tudo-aqua/cvc4-turnkey.git")
                        url.set("https://github.com/tudo-aqua/cvc4-turnkey/tree/master")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "nexusOSS"
            val releasesUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsUrl = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
            credentials {
                username = properties["nexusUsername"] as? String
                password = properties["nexusPassword"] as? String
            }
        }
    }
}


signing {
    isRequired = !hasProperty("skip-signing")
    useGpgCmd()
    sign(*cvc4LicenseVariants.map { publishing.publications[it.buildName] }.toTypedArray())
}
