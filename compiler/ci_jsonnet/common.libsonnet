# build mixins
local compiler_suite_root = (import "../ci.jsonnet").compiler_suite_root;
{
  Build:: {
    name: error "name undefined",
    targets: [],
    capabilities: [],
    packages: {},
    downloads: {},
    environment: {},
    timelimit: error "timelimit undefined",
    setup: [],
    run: [],
    teardown: [],
    on_success: [],
    on_failure: [],
    verbose: false,
    logs: [],
    catch_files: [],
  },
  GraalCommonBuild:: self.Build {
    timelimit: "30:00",
    catch_files+: [
      "Graal diagnostic output saved in (?P<filename>.+\\.zip)",
    ],
  },
  Target:: {
    Daily : { targets+:["daily"]},
    PostMerge : { targets+:["post-merge"]},
    Weekly: { targets+:["weekly"]},
    Bench : { targets+:["bench"]},
    Gate : { targets+:["Gate"]},
  },
  CompilerCommonBuild:: self.GraalCommonBuild {
    setup: [
      ["cd", compiler_suite_root],
    ],
  },
  # java downloads
  local JavaDownloads = {
    java7: { name: "oraclejdk", version: "7", platformspecific: true },
    java8: { name: "labsjdk", version: "8u121-jvmci-0.26", platformspecific: true },
    java9: { name: "labsjdk", version: "9-20170429-102349", platformspecific: true },
    java9EA: { name: "labsjdk", version: "9-ea+168", platformspecific: true },
    java8Debug: { name: "labsjdk", version: "8u121-jvmci-0.26-fastdebug", platformspecific: true },
  },
  # java
  Java8:: {
    downloads+: {
      EXTRA_JAVA_HOMES: {
        pathlist: [JavaDownloads.java7],
      },
      JAVA_HOME: JavaDownloads.java8,
    },
    name+: "-java8",
  },
  # operation system
  Linux:: {
    packages+: {
      git: ">=1.8.3",
      mercurial: ">=2.2",
      "pip:astroid": "==1.1.0",
      "pip:pylint": "==1.1.0",
    },
    capabilities+: ["linux"],
    name+: "-linux",
  },
  Solaris:: {
    packages+: {
      git: ">=1.8.3",
      mercurial: ">=2.2",
      "pip:astroid": "==1.1.0",
      "pip:pylint": "==1.1.0",
    },
    capabilities+: ["solaris"],
    name+: "-solaris",
  },
  Darwin : {
    packages+: {
      # Brew does not support versions
      mercurial : "",
      "pip:astroid": "==1.1.0",
      "pip:pylint": "==1.1.0",
    },
    environment+: {
      # we might need to do something like PATH+: and set it to PATH:"$PATH" in Build
      PATH : "/usr/local/bin:$PATH",
    },
    capabilities+: ["darwin"],
    name+: "-darwin",
  },
  # architecture mixins
  AMD64:: {
    capabilities+: ["amd64"],
    name+: "-amd64",
  },
  SPARCv9:: {
    capabilities+: ["sparcv9"],
    name+: "-sparcv9",
  },
}
