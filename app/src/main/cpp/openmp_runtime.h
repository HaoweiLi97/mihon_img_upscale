#pragma once

#include <cstdlib>

extern "C" void kmp_set_defaults(char const *defaults);

inline void configure_android_openmp() {
  // Disabling binding alone still lets libomp parse /proc/cpuinfo. Android
  // can take cores offline between libomp's CPU count and topology discovery,
  // tripping __kmp_create_affinity_none_places' CPU-count assertion. The flat
  // topology uses the runtime's existing count instead of that second snapshot.
  setenv("KMP_AFFINITY", "disabled", 1);
  setenv("KMP_TOPOLOGY_METHOD", "flat", 1);
  setenv("OMP_PROC_BIND", "false", 1);
  unsetenv("OMP_PLACES");
  kmp_set_defaults("KMP_AFFINITY=disabled|KMP_TOPOLOGY_METHOD=flat");
}
