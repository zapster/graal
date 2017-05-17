local MxBenchmarkBuild = (import "bench-common.libsonnet").MxBenchmarkBuild;
{
  # Dacapo
  local DacapoBase = MxBenchmarkBuild + {
    bench_suite: "%s:*" % self.bench_suite_name,
    bench_suite_name: "dacapo",
  },
  Dacapo:: DacapoBase + {
    bench_suite_name: "dacapo",
  },
  ScalaDacapo:: DacapoBase + {
    bench_suite_name: "scala-dacapo",
  },
  Renaissance:: DacapoBase + {
    bench_suite_name: "renaissance",
  },
  Timing:: {
    bench_suite_name+: "-timing",
    jvm_config+:: "-timing"
  },
  # SPECJvm2008-related targets
  local SPECjvm2008Base= MxBenchmarkBuild + {
    downloads: {
      SPECJVM2008: { name: "specjvm2008", version: "1.01" }
    },
    teardown+: [
      ["rm", "-r", "${SPECJVM2008}/results"]
    ],
    timelimit: "2:50:00",
  },
  SPECjvm2008Single: SPECjvm2008Base + {
    bench_suite: "specjvm2008:*",
    bench_suite_name: "specjvm2008-single",
    bench_cmd_template+: {
      benchmark_args+:["-ikv", "-it", "240s", "-wt", "120s"],
    },
  },
  SPECjvm2008OneVM: SPECjvm2008Base + {
    bench_suite: "specjvm2008",
    bench_suite_name: "specjvm2008-onevm",
    bench_cmd_template+: {
      benchmark_args+:[ "-it", "240s", "-wt", "120s"],
    },
  },
}
