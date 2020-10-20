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
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.JavaVersion.VERSION_1_8
import org.paleozogt.gradle.zip.SymUnzip
import java.nio.file.Files.createDirectories
import java.nio.file.Files.writeString
import java.nio.file.Path

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
    signing
}


group = "io.github.tudo-aqua"

val cvc4Version = "1.8"
val turnkeyVersion = ""
version = "$cvc4Version$turnkeyVersion"


tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
    gradleReleaseChannel="current"
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


/** Convert a Java package name to the expected class file path.
 * @param packageName the package name.
 * @return the respective relative path.
 *
 */
fun packageToPath(packageName: String): Path = Path.of(
        packageName.substringBefore("."),
        *packageName.split(".").drop(1).toTypedArray()
)
val p = JavaParser()
fun mergeCVC4JNIs(linuxFile: Path, osxFile: Path, outputFile: Path) {
    val cuLinux = p.parse(linuxFile).result.orElseThrow()
    val cuOSX = p.parse(osxFile).result.orElseThrow()
    val linuxMethods: HashMap<String, MethodDeclaration> = HashMap()
    var jniClass: ClassOrInterfaceDeclaration? = null
    cuLinux.getChildNodes().filter{ n -> n is ClassOrInterfaceDeclaration}.forEach{ n ->
        jniClass = n as ClassOrInterfaceDeclaration
        n.getChildNodes().stream().filter { f -> f is MethodDeclaration }.forEach { f -> linuxMethods.put((f as MethodDeclaration).getDeclarationAsString(), f) }
    }
    cuOSX.getChildNodes().filter{ n -> n is ClassOrInterfaceDeclaration}.forEach{ n -> n.childNodes.stream().filter{ n -> n is MethodDeclaration && !linuxMethods.containsKey(n.getDeclarationAsString())}.forEach{ n -> val cloned: MethodDeclaration = n.clone() as MethodDeclaration
            cloned.setLineComment("From osX CVC4JNI")
            jniClass!!.members.add(cloned)
        }
    }
    val staticInitializer = InitializerDeclaration(
            true,
            BlockStmt(NodeList(ExpressionStmt(MethodCallExpr("CVC4Loader.loadCVC4"))))
    )
    staticInitializer.setLineComment("Generated by cvc4-turnkey build script")
    jniClass!!.members.add(staticInitializer)
    writeString(outputFile, cuLinux.toString())
    logger.info("Java File rewritten to " + outputFile)
}
fun convertExplicitConstructorInvocation(toBeConverted: ExplicitConstructorInvocationStmt): ExpressionStmt? {
    val newStmt = MethodCallExpr()
    newStmt.setName("init")
    newStmt.arguments = toBeConverted.getArguments()
    return ExpressionStmt(newStmt)
}
fun mergeDataTypeFile(linuxFile: Path, osxFile: Path, outputFile: Path){
    val template = "String os = java.lang.System.getProperty(\"os.name\").toLowerCase();"
    val conditionTemplate = "os.startsWith(\"darwin\") || os.startsWith(\"mac\")"
    val initalizeMethod = "private void init(long cPtr, boolean cMemoryOwn) {" +
        "swigCMemOwn = cMemoryOwn;" +
        "swigCPtr = cPtr;" +
        "}"
    val cuLinux = p.parse(linuxFile).result.orElseThrow()
    val cuOSX = p.parse(osxFile).result.orElseThrow()

    val methods: HashMap<String, Tuple> = HashMap()

    cuLinux.getType(0).members.filter { f -> f.isMethodDeclaration || f.isConstructorDeclaration }.forEach { member ->
        if (member.isMethodDeclaration()) {
            val md = member as MethodDeclaration
            methods.put(md.declarationAsString, Tuple(md, md.body.orElseThrow()))
        } else {
            val cd: ConstructorDeclaration = member as ConstructorDeclaration
            methods.put(cd.getDeclarationAsString(), Tuple(cd, cd.body))
        }
    }
    cuOSX.getType(0).members.filter { m -> m is MethodDeclaration }.forEach { member ->
        val md = member as MethodDeclaration
        if (methods.containsKey(md.declarationAsString)) {
            check(methods[md.declarationAsString]!!.body.equals(md.body.get())) { "This is a state we cannot handle right now." }
        } else {
            val cloned = md.clone()
            cloned.setLineComment("From MacOS File")
            cuLinux.getType(0).addMember(cloned)
        }
    }
    cuOSX.getType(0).getMembers().stream().filter { m -> m is ConstructorDeclaration }.forEach { member ->
        val md: ConstructorDeclaration = member as ConstructorDeclaration
        if (methods.containsKey(md.getDeclarationAsString())) {
            val linuxNode = methods[md.getDeclarationAsString()]!!
            if (!linuxNode.body.equals(md.getBody())) {
                val newBody = BlockStmt()
                val ifStep = IfStmt()
                ifStep.setCondition(p.parseExpression<Expression>(conditionTemplate).result.orElseThrow())
                ifStep.setThenStmt(convertExplicitConstructorInvocation(md.getBody().statements[0] as ExplicitConstructorInvocationStmt))
                ifStep.setElseStmt(convertExplicitConstructorInvocation(linuxNode.body.statements[0] as ExplicitConstructorInvocationStmt))
                newBody.addStatement(template)
                newBody.addStatement(ifStep)
                check(linuxNode.node.replace(linuxNode.body, newBody)) { "Cannot replace node" }
            }
        } else {
            val cloned = md.clone()
            cloned.setLineComment("From MacOS File")
            cuLinux.getType(0).addMember(cloned)
        }
    }
    cuLinux.getType(0).addMember(p.parseMethodDeclaration(initalizeMethod).result.orElseThrow())
    writeString(outputFile, cuLinux.toString())
}

/** The name of the CVC4 Java package. */
val cvc4Package = "edu.stanford.CVC4"

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

        val linuxSourceDir = tasks.named<SymUnzip>("extractCVC4Binary${linux.buildName}").get().destinationDir.toPath()
            .resolve("Build.Build.${linux.buildName}")
            .resolve("cvc4-$cvc4Version-${linux.buildSystem}-${license.buildName}")
        val macSourceDir = tasks.named<SymUnzip>("extractCVC4Binary${osx.buildName}").get().destinationDir.toPath()
            .resolve("Build.Build.${osx.buildName}")
            .resolve("cvc4-$cvc4Version-${osx.buildSystem}-${license.buildName}")
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
            .resolve("Build.Build.${linux.buildName}")
            .resolve("cvc4-$cvc4Version-${linux.buildSystem}-${license.buildName}")
        val osxSourceDir = tasks.named<SymUnzip>("extractCVC4Binary${osx.buildName}").get().destinationDir.toPath()
                .resolve("Build.Build.${osx.buildName}")
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

            mergeCVC4JNIs(prefixJavaLinux.resolve("CVC4JNI.java"), prefixJavaOSX.resolve("CVC4JNI.java"), rewrittenNativeJava.resolve("CVC4JNI.java"))
            mergeDataTypeFile(prefixJavaLinux.resolve("Rational.java"), prefixJavaOSX.resolve("Rational.java"), rewrittenNativeJava.resolve("Rational.java"))
            mergeDataTypeFile(prefixJavaLinux.resolve("Integer.java"), prefixJavaOSX.resolve("Integer.java"), rewrittenNativeJava.resolve("Integer.java"))
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
