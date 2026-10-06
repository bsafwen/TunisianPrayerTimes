"""Load the review-only Google Maps key from a Windows DPAPI file."""
import ctypes
import os
import re
from pathlib import Path


def load_maps_config(key_file):
    if not key_file:
        return {"enabled": False}
    path = Path(key_file)
    if os.name != "nt" or not path.is_absolute():
        raise ValueError("The Maps key file must be an absolute path on Windows.")
    encrypted = path.read_bytes()
    if not encrypted or len(encrypted) > 16384:
        raise ValueError("The Maps key file is invalid.")

    class Blob(ctypes.Structure):
        _fields_ = [("size", ctypes.c_ulong), ("data", ctypes.POINTER(ctypes.c_ubyte))]

    buffer = ctypes.create_string_buffer(encrypted)
    source = Blob(len(encrypted), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    target = Blob()
    decrypt = ctypes.windll.crypt32.CryptUnprotectData
    decrypt.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                        ctypes.c_void_p, ctypes.c_void_p, ctypes.c_ulong,
                        ctypes.POINTER(Blob)]
    decrypt.restype = ctypes.c_int
    if not decrypt(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise ValueError("The Maps key could not be opened by this Windows account.")
    try:
        key = ctypes.string_at(target.data, target.size).decode("ascii").strip()
    finally:
        ctypes.memset(target.data, 0, target.size)
        free = ctypes.windll.kernel32.LocalFree
        free.argtypes = [ctypes.c_void_p]
        free.restype = ctypes.c_void_p
        free(target.data)
    if not re.fullmatch(r"AIza[0-9A-Za-z_-]{35}", key):
        raise ValueError("The Maps key file does not contain a valid API key format.")
    return {"enabled": True, "key": key}
