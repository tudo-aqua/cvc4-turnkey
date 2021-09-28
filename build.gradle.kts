/*
 * Copyright 2019-2020 Simon Dierl <simon.dierl@cs.tu-dortmund.de>, Malte Mues <mail.mues@gmail.com>
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

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.BinaryExpr.Operator.EQUALS
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.JavaVersion.VERSION_1_8
import org.gradle.process.internal.ExecException
import org.paleozogt.gradle.zip.SymUnzip
import java.io.ByteArrayOutputStream
import java.nio.file.Files.createDirectories
import java.nio.file.Files.writeString
import java.nio.file.Path
import com.github.javaparser.StaticJavaParser.parse as parseJava

buildscript {
    repositories {
        mavenCentral()
        jcenter()
    }
    dependencies {
        classpath(group = "com.github.javaparser", name = "javaparser-core", version = "3.15.12")
        classpath(group = "org.paleozogt", name = "symzip-plugin", version = "0.10.1")
    }
}

plugins {
    id("com.github.ben-manes.versions") version "0.33.0"
    id("de.undercouch.download") version "4.1.1"
    `java-library`
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    signing
}


group = "io.github.tudo-aqua"

val azureBuildVersion = "1.8.1"
val cvc4Version = "1.8"
val turnkeyVersion = ".3-SNAPSHOT"
version = "$cvc4Version$turnkeyVersion"


tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
    gradleReleaseChannel = "current"
}


java {
    sourceCompatibility = VERSION_1_8
    targetCompatibility = VERSION_1_8
}


/**
 * CVC4 licensing model metadata.
 */
data class CVC4License(val buildName: String, val humanReadable: String, val includeGPL: Boolean = false)
data class Tuple(val node: Node, val body: BlockStmt)

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
val osx = cvc4Architectures[1]


/**
 * Convert a Java package name to the expected class file path.
 * @param packageName the package name.
 * @return the respective relative path.
 */
fun packageToPath(packageName: String): Path = Path.of(
        packageName.substringBefore("."),
        *packageName.split(".").drop(1).toTypedArray()
)

/**
 * Merge a OSX-specific java source file into this one.
 * @param osxFile the file which is merged into this one.
 * @param outputFile the file to write the merge result into.
 * @param additional additional processing steps to perform post merge.
 */
fun Path.mergeOSXJavaFrom(osxFile: Path, outputFile: Path, additional: CompilationUnit.() -> Unit = {}) {
    parseJava(this).apply {
        mergeFrom(parseJava(osxFile))
        additional()
        writeString(outputFile, toString())
    }
    logger.info("Merged $osxFile into $this to $outputFile")
}

/**
 * Merge a complation unit into this one.
 * @param otherUnit the unit which is merged into this one.
 */
fun CompilationUnit.mergeFrom(otherUnit: CompilationUnit) {
    val thisTypes = types.filterIsInstance<ClassOrInterfaceDeclaration>()

    otherUnit.types.filterIsInstance<ClassOrInterfaceDeclaration>().forEach { otherType ->
        val target = thisTypes.singleOrNull { it.name == otherType.name }
        if (target != null) {
            target.mergeFrom(otherType)
        } else {
            childNodes.add(otherType.clone().setLineComment("Copied OS X type"))
        }
    }
}

/**
 * Merge a class or interface into this one.
 * @param otherType the class which is merged into this one.
 */
fun ClassOrInterfaceDeclaration.mergeFrom(otherType: ClassOrInterfaceDeclaration) {
    val thisCallables = members.filterIsInstance<CallableDeclaration<*>>()

    otherType.members.filterIsInstance<CallableDeclaration<*>>().forEach { otherCallable ->
        val target = thisCallables.singleOrNull { it.signature == otherCallable.signature }
        if (target == null) {
            members.add(otherCallable.clone().apply { setLineComment("Copied OS X callable") })
        } else if (otherCallable != target) {
            if (target.body().statements.first() is ExplicitConstructorInvocationStmt) {
                target.insertOSXConstructorParameterSwitch(otherCallable)
            } else {
                target.insertOSXBigSwitch(otherCallable)
            }
        }
    }
}

/**
 * Merge a constructor using delegation into this one using per-parameter ternary expressions.
 * @param otherCallable the constructor which is merged into this one.
 */
fun CallableDeclaration<*>.insertOSXConstructorParameterSwitch(otherCallable: CallableDeclaration<*>) =
        ((body().statements.single() as ExplicitConstructorInvocationStmt).arguments zip
                (otherCallable.body().statements.single() as ExplicitConstructorInvocationStmt).arguments)
                .forEach { (thisArgument, otherArgument) ->
                    if (thisArgument != otherArgument) {
                        check(thisArgument.replace(
                                ConditionalExpr(
                                        isOSXExpr(),
                                        otherArgument.clone(),
                                        thisArgument.clone()
                                ).apply { setLineComment("OS switch added by merge") }
                        ))
                    }
                }

/**
 * Merge a method into this one using a big if statement.
 * @param otherCallable the method which is merged into this one.
 */
fun CallableDeclaration<*>.insertOSXBigSwitch(otherCallable: CallableDeclaration<*>) =
        check(body().replace(
                BlockStmt(
                        NodeList(
                                IfStmt(
                                        isOSXExpr(),
                                        otherCallable.body().clone(),
                                        body().clone()
                                ).apply { setLineComment("OS switch added by merge") }
                        )
                )
        )) { "rewrite error" }

/**
 * Create a test if the current OS is OS X.
 * @return a new expression checking for OS X.
 */
fun isOSXExpr() = BinaryExpr(
        MethodCallExpr("CVC4Loader.OperatingSystem.identify"),
        FieldAccessExpr(NameExpr("CVC4Loader.OperatingSystem"), "OS_X"),
        EQUALS
)

/**
 * Get the body of an arbitrary callable. Fails if no body is present.
 * @return the body.
 */
fun CallableDeclaration<*>.body() = when (this) {
    is ConstructorDeclaration -> this.body
    is MethodDeclaration -> this.body.get()
    else -> error("unsupported callable")
}


/** The name of the CVC4 Java package. */
val cvc4Package = "edu.stanford.CVC4"

/** The relative path to the CVC4 package. */
val cvc4PackagePath = packageToPath(cvc4Package)

class ExecResult(
        val executed: Boolean, exitValue: Int?,
        val standardOutput: ByteArray, val standardError: ByteArray
) {
    val successful = executed && exitValue == 0
}


fun execCommand(vararg commands: String): ExecResult {
    val stdOut = ByteArrayOutputStream()
    val stdErr = ByteArrayOutputStream()
    val (executed, exitValue) = try {
        true to exec {
            commandLine = listOf(*commands)
            isIgnoreExitValue = true
            standardOutput = stdOut
            errorOutput = stdErr
        }.exitValue
    } catch (_: ExecException) {
        false to null
    }
    return ExecResult(executed, exitValue, stdOut.toByteArray(), stdErr.toByteArray())
}

val installNameTool: String by lazy {
    (properties["install_name_tool"] as? String)?.let { return@lazy it }

    listOf("install_name_tool", "x86_64-apple-darwin-install_name_tool").forEach {
        if (execCommand(it).executed) return@lazy it
    }

    throw GradleException("No install_name_tool defined or found on the search path")
}

tasks.register<Download>("downloadCVC4StaticBinaryLinux") {
    description = "Download the CVC4 binary distribution for Linux."
    src("https://github.com/tudo-aqua/cvc4-azure-build/releases/download/$azureBuildVersion/Build.staticbinaries.Linux.zip")
    dest(buildDir.toPath().resolve("binary-archives").resolve("Build.staticbinaries.Linux.zip").toFile())
    quiet(true)
    overwrite(false)
}

tasks.register<SymUnzip>("extractCVC4StaticBinaryLinux") {
    description = "Extract the static CVC4 binary distribution for Linux."
    dependsOn("downloadCVC4StaticBinaryLinux")
    from(tasks.named<Download>("downloadCVC4StaticBinaryLinux").get().dest)
    into(buildDir.toPath().resolve("unpacked-static-binaries-Linux"))
}

cvc4Architectures.forEach { (arch, _) ->
    tasks.register<Download>("downloadCVC4Binary$arch") {
        description = "Download the CVC4 binary distribution for $arch."
        src("https://github.com/tudo-aqua/cvc4-azure-build/releases/download/$azureBuildVersion/Build.languagebindings.$arch.zip")
        dest(buildDir.toPath().resolve("binary-archives").resolve("Build.languagebindings.$arch.zip").toFile())
        quiet(true)
        overwrite(false)
    }

    logger.debug("arch $arch")
    tasks.register<SymUnzip>("extractCVC4Binary$arch") {
        description = "Extract the CVC4 binary distribution for $arch."
        dependsOn("downloadCVC4Binary$arch")
        from(tasks.named<Download>("downloadCVC4Binary$arch").get().dest)
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
                val lib = input.resolve("Build.languagebindings.${osData.buildName}")
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

    tasks.register("${license.buildName}CopyNativeBinariesLinux") {
        description = "Copy the CVC4 native build binaries with ${license.humanReadable} license model for" +
                "Linux to the correct directory layout."
        dependsOn("extractCVC4StaticBinaryLinux")
        val input = tasks.named("extractCVC4StaticBinaryLinux").get().outputs.files.singleFile.toPath()
        inputs.dir(input)

        val output = buildDir.toPath().resolve("native-binaries-${license.buildName}")
        outputs.dir(output)
        doLast {
            val binDir = input.resolve("Build.staticbinaries.Linux")
                    .resolve("cvc4-$cvc4Version-ubuntu-latest-${license.buildName}").resolve("bin")
            copy {
                from(binDir.resolve("cvc4"))
                rename("cvc4", "cvc4-linux-amd64")
                into(output)
            }
        }
    }

    tasks.register("${license.buildName}CopyNativeBinariesMacOS") {
        description = "Copy the CVC4 native build binaries with ${license.humanReadable} license model for" +
                "MacOS to the correct directory layout."
        dependsOn("extractCVC4BinaryMacOS")
        val input = tasks.named("extractCVC4BinaryMacOS").get().outputs.files.singleFile.toPath()
        inputs.dir(input)

        val output = buildDir.toPath().resolve("native-binaries-${license.buildName}")
        outputs.dir(output)
        doLast {
            val binDir = input.resolve("Build.languagebindings.MacOS")
                    .resolve("cvc4-$cvc4Version-macOS-latest-${license.buildName}").resolve("bin")
            val libDir = input.resolve("Build.languagebindings.MacOS")
                    .resolve("cvc4-$cvc4Version-macOS-latest-${license.buildName}").resolve("lib")
            copy {
                from(binDir.resolve("cvc4"))
                rename("cvc4", "cvc4-osx-amd64")
                into(output)
            }
            copy {
                from(libDir)
                include("*.dylib")
                into(output)
            }
        }
    }

    tasks.register("${license.buildName}FixLinkingMacOS") {
        description = "It is requried to change the way, the cvc4 binary looksup the dylibs on MacOS."
        dependsOn("${license.buildName}CopyNativeBinariesMacOS")
        val binary = buildDir.toPath().resolve("native-binaries-${license.buildName}").resolve("cvc4-osx-amd64").toAbsolutePath().toString()
        doLast{
            exec {
                commandLine = listOf(
                        installNameTool,
                        "-change", "@rpath/libcvc4.7.dylib", "@executable_path/libcvc4.7.dylib",
                        binary
                )
            }
            exec {
                commandLine = listOf(
                        installNameTool,
                        "-change", "@rpath/libcvc4parser.7.dylib", "@executable_path/libcvc4parser.7.dylib",
                        binary
                )
            }
        }
    }


    tasks.register("${license.buildName}CopySources") {
        description =
                "Copy the CVC4 Java sources with ${license.humanReadable} license model to the correct directory " +
                        "structure."
        dependsOn("extractCVC4Binary${linux.buildName}")

        val linuxSourceDir = tasks.named<SymUnzip>("extractCVC4Binary${linux.buildName}").get().destinationDir.toPath()
                .resolve("Build.languagebindings.${linux.buildName}")
                .resolve("cvc4-$cvc4Version-${linux.buildSystem}-${license.buildName}")
        val output = buildDir.toPath().resolve("swig-sources-${license.buildName}")

        inputs.dir(linuxSourceDir)
        outputs.dir(output)

        doLast {
            copy {
                from(linuxSourceDir.resolve("src").resolve("cvc4").resolve("java"))
                include("*.java")
                exclude("CVC4JNI.java", "Rational.java", "Integer.java")
                into(output.resolve(cvc4PackagePath))
            }
        }
    }

    tasks.register("${license.buildName}RewriteCVC4JNIJava") {
        description = "Rewrite the CVC4 native binding with ${license.humanReadable} license model to use the new " +
                "unpack-and-link code."
        dependsOn("extractCVC4Binary${linux.buildName}", "extractCVC4Binary${osx.buildName}")

        val linuxSourceDir = tasks.named<SymUnzip>("extractCVC4Binary${linux.buildName}").get().destinationDir.toPath()
                .resolve("Build.languagebindings.${linux.buildName}")
                .resolve("cvc4-$cvc4Version-${linux.buildSystem}-${license.buildName}")
        val osxSourceDir = tasks.named<SymUnzip>("extractCVC4Binary${osx.buildName}").get().destinationDir.toPath()
                .resolve("Build.languagebindings.${osx.buildName}")
                .resolve("cvc4-$cvc4Version-${osx.buildSystem}-${license.buildName}")
        val output = buildDir.toPath().resolve("rewritten-cvc4jni-${license.buildName}")

        inputs.dir(linuxSourceDir)
        inputs.dir(osxSourceDir)
        outputs.dir(output)

        doLast {
            val prefixJavaLinux = linuxSourceDir.resolve("src").resolve("cvc4").resolve("java")
            val prefixJavaOSX = osxSourceDir.resolve("src").resolve("cvc4").resolve("java")

            val rewrittenNativeJava = output.resolve(cvc4PackagePath)

            createDirectories(rewrittenNativeJava)

            prefixJavaLinux.resolve("CVC4JNI.java").mergeOSXJavaFrom(
                    prefixJavaOSX.resolve("CVC4JNI.java"),
                    rewrittenNativeJava.resolve("CVC4JNI.java")) {
                types.first().members.add(InitializerDeclaration(
                        true,
                        BlockStmt(NodeList(ExpressionStmt(MethodCallExpr("CVC4Loader.loadCVC4"))))
                ).apply { setLineComment("Generated by cvc4-turnkey build script") })
            }
            listOf("Rational", "Integer").forEach {
                prefixJavaLinux.resolve("$it.java").mergeOSXJavaFrom(
                        prefixJavaOSX.resolve("$it.java"),
                        rewrittenNativeJava.resolve("$it.java"))
            }
        }
    }

    tasks.register<Jar>("${license.buildName}Jar") {
        description = "Create a JAR for ${license.humanReadable} license model."
        dependsOn("${license.buildName}Classes")

        archiveBaseName.set("${rootProject.name}-${license.buildName}")
        from(sourceSets[license.buildName].output.classesDirs, sourceSets[license.buildName].output.resourcesDir)
    }

    tasks.register<Jar>("${license.buildName}BinariesJar") {
        description = "Create a JAR for the cvc4 binaries of the ${license.humanReadable} license model."
        dependsOn("${license.buildName}CopyNativeBinariesLinux", "${license.buildName}CopyNativeBinariesMacOS", "${license.buildName}FixLinkingMacOS")
        archiveBaseName.set("${rootProject.name}-${license.buildName}")
        archiveClassifier.set("binaries")
        from(tasks.named("${license.buildName}CopyNativeBinaries${linux.buildName}").get().outputs, tasks.named("${license.buildName}CopyNativeBinaries${osx.buildName}").get().outputs)
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
        listOf("", "Javadoc", "Sources", "Binaries").map { "${license.buildName}${it}Jar" }
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

val jUnitVersion = "5.7.0"

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
                listOf("", "Javadoc", "Sources", "Binaries").forEach {
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
}

nexusPublishing {
    /*
     * To run this sucessfull, you need to configure gradle properties in ~/.gradle/gradle.properties
     * with the username and password.
     * We do recommend to use Maven Central API Tokens as explained here:
     * https://blog.solidsoft.pl/2015/09/08/deploy-to-maven-central-using-api-key-aka-auth-token/
     */
    repositories {
        sonatype {
            username.set(properties["nexusUsername"] as? String)
            password.set(properties["nexusPassword"] as? String)
        }
    }
}

signing {
    isRequired = !hasProperty("skip-signing")
    useGpgCmd()
    sign(*cvc4LicenseVariants.map { publishing.publications[it.buildName] }.toTypedArray())
}
