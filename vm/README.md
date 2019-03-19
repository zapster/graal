# VM suite

The VM suite allows you to build custom GraalVM distributions, as well as installable components.
It defines a base GraalVM distribution that contains the JVMCI-enabled JDK, the Graal SDK, Truffle, and the GraalVM component installer.
More components are added by dynamically importing additional suites.
This can be done either by:
1. running `mx --dynamicimports <suite...> build`
2. setting the `DEFAULT_DYNAMIC_IMPORTS` or `DYNAMIC_IMPORTS` environment variables before running `mx build` 
3. running `mx --env <env file> build`

After the compilation:
- the `latest_graalvm` symbolic link points to the latest built GraalVM
- `$ mx [build-time arguments] graalvm-home` prints the path to the GraalVM home directory

Note that the build requirements of each component are specified in the README file of the corresponding repository.
For example, building the Graal compiler currently requires the `JAVA_HOME` environment variable to point to a JVMCI-enabled JDK, which can be downloaded [here](https://github.com/graalvm/openjdk8-jvmci-builder/releases) or [built from sources](https://github.com/graalvm/openjdk8-jvmci-builder).


### Example: build the base GraalVM CE image
The base GraalVM CE image includes:
- SubstrateVM (with the `native-image` tool)
- Graal compiler & the Truffle-Graal accelerator (imported as a dependency of `substratevm`)
- The inspector, profiler, and VisualVM tools
- Sulong
- Graal.nodejs
- Graal.js (imported as a dependency of `graal-nodejs`)
- the `polyglot` launcher
- the `libpolyglot` shared library

To build it, you can either run:

1.
```bash
$ mx --dynamicimports /substratevm,/tools,sulong,/graal-nodejs build
```

2.
```bash
$ export DEFAULT_DYNAMIC_IMPORTS=/substratevm,/tools,sulong,/graal-nodejs
$ mx build
```
or:
```bash
$ export DYNAMIC_IMPORTS=/substratevm,/tools,sulong,/graal-nodejs
$ mx build
```
Note that the suites listed in:
- `DYNAMIC_IMPORTS` are always imported
- `DEFAULT_DYNAMIC_IMPORTS` are imported only if no other dynamic import is specified (via command line, env file, or environment variable)

3.
```bash
$ mx --env ce build
```
Which uses the settings in the env file in `mx.vm/ce`. Note that you can add custom env files to your `mx.vm` directory, and call `mx --env <env file name> build`.


## Installable components
Installable components for the Graal Updater (`gu`) are built alongside the GraalVM for languages other than JS.
For example:
```bash
$ env FASTR_RELEASE=true mx --dynamicimports fastr,truffleruby,graalpython,/substratevm build
```
creates:
- a GraalVM image which includes the base CE components plus FastR, TruffleRuby, and Graal.Python
- the installables for FastR, TruffleRuby, and Graal.Python


## Native images
When `substratevm` is imported, the build system creates native launchers for the supported languages and for `polyglot`, plus the shared polyglot library (`libpolyglot`).
Otherwise, it creates bash launchers for the languages and for `polyglot`, and does not create the shared polyglot library.

To override the default behavior, the `vm` suite defines the following `mx` arguments:
```
  --disable-libpolyglot         Disable the 'polyglot' library project
  --disable-polyglot            Disable the 'polyglot' launcher project
  --force-bash-launchers        Force the use of bash launchers instead of native images.
                                This can be a comma-separated list of disabled launchers or `true` to disable all native launchers.
```
And the following environment variables:
```
  DISABLE_LIBPOLYGLOT           Same as '--disable-libpolyglot'
  DISABLE_POLYGLOT              Same as '--disable-polyglot'
  FORCE_BASH_LAUNCHERS          Same as '--force-bash-launchers'
```

Note that when the shared polyglot library is not built, Graal.nodejs can only work in JVM-mode (`node --jvm [args]`). 


### Example: avoid building the polyglot image and the polyglot shared library

```bash
$ mx --disable-polyglot --disable-libpolyglot --dynamicimports /substratevm,/tools,sulong,/graal-js build
```
builds the native SubstrateVM launchers for native-image, Graal.js, and Sulong, but no polyglot launcher and polyglot library.


### Example: force bash launchers
```bash
$ mx --force-bash-launchers=true --dynamicimports /substratevm,/tools,sulong,/graal-nodejs
```
builds the native SubstrateVM launcher for native-image, and creates bash launchers for Sulong, Graal.js, and `polyglot`


### Example: create an env file that builds only the SubstrateVM, Graal.Python, and their dependencies (no `polyglot`; no `libpolyglot`)
```bash
$ echo "DYNAMIC_IMPORTS=/substratevm,graalpython" > mx.vm/python
$ echo "DISABLE_LIBPOLYGLOT=true" >> mx.vm/python
$ echo "DISABLE_POLYGLOT=true" >> mx.vm/python
$ mx --env python build
```
Now, if you want to add Graal.js:
```bash
$ mx --env python --dynamicimports /graal-js build
```


## Versioned dynamic imports
Dynamic imports typically require the user to locate and clone the dynamically imported suites.
There is also no indication of which version of those suites would work.
To avoid this issue, the `vm` suite uses "versioned dynamic imports".

The `mx.vm/suite.py` file contains references to all the suites that might be imported to compose a GraalVM.
Unlike usual suite imports, they are marked as `dynamic`, which means they are only considered if they are part of the dynamically imported suites.
However, when they are included, they have URLs and versions which allow mx to automatically clone the correct version.

More details can be found in `docs/dynamic-imports.md` in the `mx` repository.


### Example: checkout the correct imports of Graal.js and Sulong, then build a GraalVM CE image
```bash
$ mx --env ce sforceimports
$ mx --env ce build
```

## Registering custom components
Suites can register new, custom components calling`mx_sdk.register_graalvm_component()`.