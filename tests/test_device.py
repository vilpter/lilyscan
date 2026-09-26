from __future__ import annotations

import pytest

from lilyscan.runtime.device import (
    CudaProbe,
    Device,
    DeviceInfo,
    is_cuda_failure,
    onnx_providers,
    resolve_device,
    run_with_cpu_fallback,
)

NO_GPU = (lambda: CudaProbe(False, "none"),)
GPU = (lambda: CudaProbe(True, "torch", "Test GPU"),)
CUDA_INFO = DeviceInfo(Device.CUDA, "torch", "Test GPU", "test")
CPU_INFO = DeviceInfo(Device.CPU, "none", None, "test")


def test_default_is_auto_and_falls_back_to_cpu() -> None:
    info = resolve_device({}, NO_GPU)
    assert info.device is Device.CPU


def test_auto_uses_cuda_when_present() -> None:
    info = resolve_device({"LILYSCAN_DEVICE": "auto"}, GPU)
    assert info.device is Device.CUDA
    assert info.gpu_name == "Test GPU"


def test_forced_cpu_skips_probes() -> None:
    def boom() -> CudaProbe:
        raise AssertionError("probe must not run")

    assert resolve_device({"LILYSCAN_DEVICE": "cpu"}, (boom,)).device is Device.CPU


def test_forced_cuda_without_gpu_degrades_to_cpu(caplog: pytest.LogCaptureFixture) -> None:
    info = resolve_device({"LILYSCAN_DEVICE": "CUDA"}, NO_GPU)
    assert info.device is Device.CPU
    assert "using CPU" in caplog.text


def test_invalid_value_rejected() -> None:
    with pytest.raises(ValueError):
        resolve_device({"LILYSCAN_DEVICE": "tpu"}, NO_GPU)


def test_second_probe_is_consulted() -> None:
    probes = (lambda: CudaProbe(False, "torch"), lambda: CudaProbe(True, "onnxruntime"))
    info = resolve_device({}, probes)
    assert (info.device, info.backend) == (Device.CUDA, "onnxruntime")


def test_onnx_providers() -> None:
    assert onnx_providers(CPU_INFO) == ["CPUExecutionProvider"]
    assert onnx_providers(CUDA_INFO)[0] == "CUDAExecutionProvider"


def test_fallback_retries_on_cpu_after_cuda_oom() -> None:
    calls: list[Device] = []

    def stage(d: Device) -> str:
        calls.append(d)
        if d is Device.CUDA:
            raise RuntimeError("CUDA out of memory. Tried to allocate 2.00 GiB")
        return "ok"

    assert run_with_cpu_fallback("s", stage, CUDA_INFO) == "ok"
    assert calls == [Device.CUDA, Device.CPU]


def test_fallback_does_not_mask_other_errors() -> None:
    def stage(d: Device) -> str:
        raise ValueError("bad input")

    with pytest.raises(ValueError):
        run_with_cpu_fallback("s", stage, CUDA_INFO)


def test_is_cuda_failure_by_type_name() -> None:
    class OutOfMemoryError(RuntimeError):
        pass

    assert is_cuda_failure(OutOfMemoryError("x"))
    assert not is_cuda_failure(KeyError("x"))
