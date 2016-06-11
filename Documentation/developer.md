---
layout: page
title: Developer Guide
---
# Building from Source
A complete build of flow involves two parts

1. Building Scala sources (the front-end), resulting in a platform independent artifact (i.e. a jar file).

2. Building C sources (the back-end), yielding a native library that may only be used on systems resembling the platform for which it was compiled.

Both steps are independent, their only interaction being a header file generated by the JDK utility `javah` (see `sbt javah` for details), and may therefore be built in any order.

## Building Scala Sources
Run `sbt core/package` in the base directory. This simply compiles Scala sources as with any standard sbt project and packages the resulting class files into a jar.

## Building Native Sources
The back-end is managed by CMake and all relevant files are contained in `flow-native/src`.

### Build Process
Several steps are involved in producing the native library:

1. Bootstrap the build (run this once, if `Makefile` does not exist).

	1. Required dependencies: CMake (2.8 or higher), JDK (1.8 or above)
    2. Run `cmake .`

2. Compile

    1. Run `make`.
       *Note: should you encounter an error about a missing "jni.h" file, try setting the JAVA_HOME environment variable to point to the base path of your JDK installation.*

3. Install

    The native library is now ready and can be:

	- copied to a local directory: `DESTDIR=$(pwd)/<directory> make install`

    - installed system-wide: `make install`

    - put into a "fat" jar, useful for dependency management with sbt (see next section)

### Creating a Fat Jar
The native library produced in the previous step may be bundled into a "fat" jar so that it can be included in SBT projects through its regular dependency mechanisms. In this process, sbt basically acts as a wrapper script around CMake, calling the native build process and packaging generated libraries. Running `sbt native/package` produces the fat jar in `flow-native/target`.

Note: an important feature of fat jars is to include native libraries for several platforms. To copy binaries compiled on other platforms to the fat jar, place them in a subfolder of `flow-native/lib_native`. The subfolder should have the name `$(arch)-$(kernel)`, where `arch` and `kernel` are, respectively, the lower-case values returned by `uname -m` and `uname -s`.

### Note About Versioning
The project and package versions follow a [semantic](http://semver.org/) pattern: `M.m.p`, where

- `M` is the major version, representing backwards incompatible changes to the public API

- `m` is the minor version, indicating backwards compatible changes such as new feature additions

- `p` is the patch number, representing internal modifications such as bug-fixes

Usually (following most Linux distribution's conventions), shared libraries produced by a project `name` of version `M.m.p` are named `libname.so.M.m.p`. However, since when accessing shared libraries through the JVM, only the `name` can be specified and no particular version, the convention adopted by flow is to append `M` to the library name and always keep the major version at zero. E.g. `libflow.so.3.1.2` becomes `libflow3.so.0.1.2`.

# Publishing and Releasing
The release process is managed with the `sbt-release` plugin. See 'project/Release.scala' for a description of the various steps involved.

Here are some important notes on creating a release:

- During a release, only readily available libraries in `lib_native` are packaged into the fat jar, no local native compilation is performed. The rationale behind this is that while native libraries rarely change, they are still tied to the version of libc of the compiling system. Since the releases are mostly done on a development machine (cutting-edge OS), compiling native libraries locally could break compatibility with older systems.

- Currently, the release script does not handle uploading the native libraries archive (don't confuse this with the fat jar, which is uploaded). If creating a release that changed the native libraries or added support for more platforms, creating and uploading a new native archive must be done manually.

- Don't forget to update the website after creating a new release:

    - Run `sbt makeSite` to generate documentation in `target/site/`
	- Checkout GitHub Pages branch `git checkout gh-pages`
	- Copy contents of `target/site/` to `documentation/M.m/`
	- Update `_config.yml` with latest version
	- Push to GitHub