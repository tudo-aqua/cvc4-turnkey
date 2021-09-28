[![Azure DevOps builds](https://img.shields.io/azure-devops/build/tudo-aqua/cvc4-turnkey/3?logo=azure-pipelines)](https://dev.azure.com/tudo-aqua/cvc4-turnkey)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.tudo-aqua/cvc4-turnkey-gpl?label=maven-central%20(GPL)&logo=apache-maven)](https://search.maven.org/artifact/io.github.tudo-aqua/cvc4-turnkey-gpl)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.tudo-aqua/cvc4-turnkey-permissive?label=maven-central%20(Permissive)&logo=apache-maven)](https://search.maven.org/artifact/io.github.tudo-aqua/cvc4-turnkey-permissive)

### The CVC4-TurnKey distribution

[The CVC4 Theorem Prover](https://github.com/CVC4/CVC4) is a widely used SMT solver that is written in C and C++,
wrapping a large number of open source sub-solvers and libraries. The authors provide a Java API, however, it is not
trivial to set up in a Java project. This project aims to solve this issue.

#### Why?

Similar to [Z3-TurnKey](https://github.com/tudo-aqua/z3-turnkey), usage of CVC4 would be simplified by distributing a
Java artifact that
1. ships its own native libraries,
2. can use them without administrative privileges, and
3. can be obtained using [Maven](https://maven.apache.org/).

#### How?

This project consists of two parts:
1. a Java loader, `CVC4Loader`, that handles runtime unpacking and linking of the native support libraries, and
2. a build system that creates a JAR from
   [our unofficial CVC4 distributions](https://github.com/tudo-aqua/cvc4-azure-build/) that
    1. contains all native support libraries built by us (at the moment, Linux and Mac OS),
    2. introduced a call to `CVC4Loader` by rewriting the generated source code, and
    3. bundles all of the required files.
Also, JavaDoc and source JARs are generated for ease of use.

#### Building

The project is built using [Gradle](https://gradle.org/). In addition to Java 11 or higher, building a GPG signature
key.

The project can be built and tested on the current platform using:
> ./gradlew assemble integrationTest

##### Signing

Normally, Gradle will enforce a GPG signature on the artifacts. By setting the project parameter `skip-signing`,
enforcement is disabled:
> ./gradlew -Pskip-signing assemble

##### Releasing

This project uses the `maven-pubish` plugin in combination with the `Gradle Nexus Publish Plugin`.
To publis and autoclose a version, run `./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository`
as one command. Due to [WIP in the Gradle Nexus Publish Plugin](https://github.com/gradle-nexus/publish-plugin/issues/19) this has to be run in conjunction for now.


#### License

CVC4 combines multiple software projects under different licenses:

- CVC4, its ANTLR support code and `CmakeCoverage.cmage` are available under the
  [BSD 3-Clause License](https://opensource.org/licenses/BSD-3-Clause).
- MiniSat and BVMiniSat (parts of the CVC4 source tree) are available under the
  [MIT License](https://opensource.org/licenses/MIT).
- [ABC](https://github.com/berkeley-abc/abc) is available under the
  [PostgreSQL License](https://opensource.org/licenses/PostgreSQL) and is statically linked.
- [CaDiCal](https://github.com/arminbiere/cadical) is available under the
  [MIT License](https://opensource.org/licenses/MIT) and is statically linked.
- [CryptoMiniSat 5](https://www.msoos.org/cryptominisat5/) is available under the
  [MIT License](https://opensource.org/licenses/MIT) and is statically linked.
- [drat2er](https://github.com/alex-ozdemir/drat2er) is available under the
  [MIT License](https://opensource.org/licenses/MIT) and is statically linked.
- [GMP](https://gmplib.org/) is available under the
  [GNU Lesser General Public License, Version 3](https://www.gnu.org/licenses/lgpl-3.0.html) and is statically linked.
  **Make sure to comply with the license terms when using the resulting binary.**
- [LFSC](https://github.com/CVC4/LFSC) is available under the
  [BSD 3-Clause License](https://opensource.org/licenses/BSD-3-Clause).
- [SymFPU's CVC4 branch](https://github.com/martin-cs/symfpu/tree/CVC4) is available under the
  [BSD 3-Clause License](https://opensource.org/licenses/BSD-3-Clause).

The GPL build also incorporates the following libraries, which require the binary and its consumers to adopt the GPL
license on publication:

- [CLN](https://www.ginac.de/CLN/) is available under the
  [GNU General Public License](http://www.gnu.org/licenses/gpl-2.0.html) (without version constraints) and is statically
  linked.
- [glpk-cut-log](https://github.com/timothy-king/glpk-cut-log) is available under the
  [GNU General Public License, Version 3](http://www.gnu.org/licenses/gpl-3.0.html) and is statically linked.

The support files in this project are licensed under the [ISC License](https://opensource.org/licenses/ISC).
