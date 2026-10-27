#include "../../main/cpp/qnn_fp16_support.h"
#include <HTP/QnnHtpDevice.h>
#include <cassert>
#include <cstdio>
#include <dlfcn.h>

int main() {
  QnnInterface_t missing = {};
  assert(qnn_ondevice::fp16_support(missing, nullptr) == -1);
  void *library = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_GLOBAL);
  assert(library);
  using Get = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);
  auto get = reinterpret_cast<Get>(dlsym(library, "QnnInterface_getProviders"));
  const QnnInterface_t **providers = nullptr;
  uint32_t count = 0;
  assert(get && get(&providers, &count) == QNN_SUCCESS && count);
  const auto &provider = *providers[0];
  const auto &api = provider.QNN_INTERFACE_VER_NAME;
  const uint32_t socs[] = {42, 43, 57, 69, 85, 87};
  for (auto soc : socs) {
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    QnnHtpDevice_CustomConfig_t target = {};
    target.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
    target.socModel = soc;
    QnnDevice_Config_t config = QNN_DEVICE_CONFIG_INIT;
    config.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    config.customConfig = &target;
    const QnnDevice_Config_t *configs[] = {&config, nullptr};
    assert(api.backendCreate(nullptr, nullptr, &backend) == QNN_SUCCESS);
    assert(api.deviceCreate(nullptr, configs, &device) == QNN_SUCCESS);
    const int supported = qnn_ondevice::fp16_support(provider, backend);
    assert(supported == (soc == 85 ? 0 : 1));
    std::printf("PASS SoC %u: FP16 supported=%d\n", soc, supported);
    api.deviceFree(device);
    api.backendFree(backend);
  }
  dlclose(library);
}
