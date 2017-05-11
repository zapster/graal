{
  # benchmarks
  Dacapo:: {
    targets: ["bench", "post-merge"],
    bench_suite: "dacapo:*",
    bench_suite_name: "dacapo",
  },
  DacapoTiming:: {
    targets: ["bench", "daily"],
    bench_suite: "dacapo-timing:*",
    bench_suite_name: "dacapo-timing",
  },
}
