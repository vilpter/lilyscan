"""Compute device selection.

This is the only module allowed to probe CUDA. Everything else asks
``get_device()`` and passes the result along.

Resolution order:
    1. ``LILYSCAN_DEVICE`` environment variable: ``auto`` (default), ``cpu``, or ``cuda``.
    2. Auto-detect: PyTorch CUDA if torch is installed, else the ONNX Runtime CUDA provider.
    3. CPU.

A GPU is never required. Requesting ``cuda`` on a machine without one logs a
warning and falls back to CPU, and ``run_with_cpu_fallback`` retries a stage on
CPU after a CUDA out-of-memory or initialization error.
"""

from __future__ import annotations

import logging
import os
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from enum import StrEnum
from functools import cache

log = logging.getLogger(__name__)


class Device(StrEnum):
    CPU = "cpu"
    CUDA = "cuda"


@dataclass(frozen=True)
class DeviceInfo:
    device: Device
    backend: str  # "torch", "onnxruntime", or "none"
    gpu_name: str | None
    reason: str

    def describe(self) -> str:
        if self.device is Device.CUDA:
            return f"cuda ({self.gpu_name or 'unknown GPU'}, via {self.backend})"
        return f"cpu ({self.reason})"


@dataclass(frozen=True)
class CudaProbe:
    available: bool
    backend: str
    gpu_name: str | None = None


def probe_torch() -> CudaProbe:
    try:
        import torch
    except ImportError:
        return CudaProbe(False, "none")
    try:
        if torch.cuda.is_available():
            return CudaProbe(True, "torch", str(torch.cuda.get_device_name(0)))
    except Exception as exc:  # a broken driver must not take the worker down
        log.warning("torch CUDA probe failed: %s", exc)
    return CudaProbe(False, "torch")


def probe_onnxruntime() -> CudaProbe:
    try:
        import onnxruntime
    except ImportError:
        return CudaProbe(False, "none")
    providers = onnxruntime.get_available_providers()
    return CudaProbe("CUDAExecutionProvider" in providers, "onnxruntime")


def resolve_device(
    env: Mapping[str, str] | None = None,
    probes: tuple[Callable[[], CudaProbe], ...] = (probe_torch, probe_onnxruntime),
) -> DeviceInfo:
    e = os.environ if env is None else env
    requested = e.get("LILYSCAN_DEVICE", "auto").strip().lower() or "auto"
    if requested not in {"auto", "cpu", "cuda"}:
        raise ValueError(f"LILYSCAN_DEVICE must be auto, cpu, or cuda; got {requested!r}")

    if requested == "cpu":
        return DeviceInfo(Device.CPU, "none", None, "LILYSCAN_DEVICE=cpu")

    for probe in probes:
        result = probe()
        if result.available:
            return DeviceInfo(Device.CUDA, result.backend, result.gpu_name, "CUDA detected")

    if requested == "cuda":
        log.warning("LILYSCAN_DEVICE=cuda but no usable CUDA device was found; using CPU")
        return DeviceInfo(Device.CPU, "none", None, "LILYSCAN_DEVICE=cuda but CUDA unavailable")
    return DeviceInfo(Device.CPU, "none", None, "no CUDA device detected")


@cache
def get_device() -> DeviceInfo:
    """Resolve once per process."""
    return resolve_device()


def onnx_providers(info: DeviceInfo) -> list[str]:
    """Execution providers to pass to an ONNX Runtime session, best first."""
    if info.device is Device.CUDA:
        return ["CUDAExecutionProvider", "CPUExecutionProvider"]
    return ["CPUExecutionProvider"]


def is_cuda_failure(exc: BaseException) -> bool:
    """True for CUDA out-of-memory and CUDA init/driver errors."""
    if type(exc).__name__ == "OutOfMemoryError":
        return True
    text = str(exc).lower()
    return any(k in text for k in ("cuda", "cudnn", "cublas", "out of memory"))


def run_with_cpu_fallback[T](stage: str, fn: Callable[[Device], T], info: DeviceInfo) -> T:
    """Run ``fn`` on the selected device; retry once on CPU after a CUDA failure."""
    if info.device is Device.CPU:
        return fn(Device.CPU)
    try:
        return fn(Device.CUDA)
    except Exception as exc:
        if not is_cuda_failure(exc):
            raise
        log.warning("stage %s failed on CUDA (%s); retrying on CPU", stage, exc)
        return fn(Device.CPU)
