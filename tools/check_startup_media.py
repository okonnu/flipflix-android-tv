"""Verify vector-only artwork and the shipped soundtrack's synchronization/fade."""
import array
import math
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
asset = root / "app/src/main/res/raw/darkflix_startup.m4a"
ffmpeg = os.environ.get("FFMPEG", "ffmpeg")


def ff(*args):
    return subprocess.run([ffmpeg, "-v", "error", *map(str, args)],
                          capture_output=True, check=True).stdout


for name in ("stem", "frame", "lower", "upper", "wordmark"):
    drawable = ET.parse(root / f"app/src/main/res/drawable/startup_{name}.xml").getroot()
    assert drawable.tag == "vector" and list(drawable.iter("path")), f"{name} must be paths, not a bitmap"
assert not (root / "app/src/main/res/raw/darkflix_startup.mp4").exists(), "Do not bundle the low-resolution preview clip"

mono = array.array("f", ff("-i", asset, "-vn", "-ac", "1", "-ar", "48000", "-f", "f32le", "-"))
stereo = array.array("f", ff("-i", asset, "-vn", "-ar", "48000", "-f", "f32le", "-"))
assert 7.99 <= len(mono) / 48000 <= 8.03
windows = [(i / 48000, sum(v * v for v in mono[i:i + 960]) / 960)
           for i in range(0, len(mono) - 960, 960)]
impact, _ = max(windows, key=lambda value: value[1])
assert abs(impact - 4.2) <= .04, f"Impact shifted to {impact}s"
peak = max(abs(v) for v in stereo)
assert peak < .9
assert max(abs(v) for v in mono[-480:]) < .001, "Audio must end silently"
print("PASS: all five logo/lettering layers are vectors, eight-second soundtrack, "
      f"impact {impact:.3f}s, {20 * math.log10(peak):.2f}dBFS peak, silent audio tail")
