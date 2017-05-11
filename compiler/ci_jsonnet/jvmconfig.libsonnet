{
  # host vm configs
  Server:: {
    jvm: "server",
  },
  Graal:: self.Server {
    # make general
    jvm_config: "graal-core",
  },
  TraceRA:: self.Graal {
    jvm_config+: "-tracera",
  },
}
