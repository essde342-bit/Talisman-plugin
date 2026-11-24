#!/usr/bin/env python3
"""
Fetch temporary Rust weapon SFX from the public rust-sounds catalog,
convert the MP3 clips to OGG, and generate Minecraft 1.21.4 sounds.json.

The catalog maps Rust effect prefabs to the exact audio clips extracted
from the Rust client. This script only downloads the few weapon clips used
by RustWeapons; it does not mirror the full catalog.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "resource-pack"
SOUNDS_DIR = PACK / "assets" / "rustweapons" / "sounds" / "weapon"
SOUNDS_JSON = PACK / "assets" / "rustweapons" / "sounds.json"

BASE_URL = "https://raw.githubusercontent.com/blaineddev/rust-sounds/main/"
CATALOG_URL = BASE_URL + "data/sounds.json"

# First three clips are the normal attack variants from the weapon's attack
# prefab. Some Rust weapons don't expose a separate reload FX prefab, so the
# closest available Rust reload clip is reused temporarily.
WEAPONS = {
    "semi": {
        "attack": "assets/prefabs/weapons/semi auto rifle/effects/attack.prefab",
        "reload": "assets/prefabs/weapons/ak47u/effects/reload_boltaction.prefab",
    },
    "ak": {
        "attack": "assets/prefabs/weapons/ak47u/effects/attack.prefab",
        "reload": "assets/prefabs/weapons/ak47u/effects/reload_start.prefab",
    },
    "thompson": {
        "attack": "assets/prefabs/weapons/thompson/effects/attack.prefab",
        "reload": "assets/prefabs/weapons/thompson/effects/reload_begin.prefab",
    },
    "smg": {
        "attack": "assets/prefabs/weapons/smg/effects/attack.prefab",
        "reload": "assets/prefabs/weapons/smg/effects/reload_start.prefab",
    },
    "pump": {
        # The currently indexed Rust pump-shotgun fire effect is represented
        # by the M4 shotgun prefab in the public effect catalog.
        "attack": "assets/prefabs/weapons/m4 shotgun/effects/attack.prefab",
        "reload": "assets/prefabs/weapons/sawnoff_shotgun/effects/reload_start.prefab",
    },
    "double": {
        "attack": "assets/prefabs/weapons/doubleshotgun/effects/attack.prefab",
        "reload": "assets/prefabs/weapons/sawnoff_shotgun/effects/reload_start.prefab",
    },
    "bolt": {
        "attack": "assets/prefabs/weapons/bolt rifle/effects/attack.prefab",
        "reload": "assets/prefabs/weapons/ak47u/effects/reload_boltaction.prefab",
    },
    "m92": {
        "attack": "assets/prefabs/weapons/m92/effects/attack.prefab",
        "reload": "assets/prefabs/weapons/thompson/effects/reload_begin.prefab",
    },
}


def fetch_bytes(url: str) -> bytes:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "VOID-SMP-RustWeapons-SoundPack/1.0"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def get_clip_ids(prefabs: dict[str, dict], prefab_path: str, limit: int) -> list[str]:
    prefab = prefabs.get(prefab_path)
    if prefab is None:
        raise RuntimeError(f"Sound prefab not found in upstream catalog: {prefab_path}")
    clip_ids = prefab.get("clips") or []
    if not clip_ids:
        raise RuntimeError(f"Sound prefab has no audio clips: {prefab_path}")
    return clip_ids[:limit]


def main() -> None:
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg is required. The GitHub Actions workflow installs it.")

    print("Downloading the public Rust sound catalog...")
    catalog = json.loads(fetch_bytes(CATALOG_URL).decode("utf-8"))
    clips = catalog["clips"]
    prefabs = {entry["path"]: entry for entry in catalog["prefabs"]}

    SOUNDS_DIR.mkdir(parents=True, exist_ok=True)
    sound_events: dict[str, dict] = {}
    converted: set[str] = set()

    def ensure_ogg(clip_id: str) -> str:
        if clip_id not in clips:
            raise RuntimeError(f"Clip ID missing from catalog: {clip_id}")

        ogg_path = SOUNDS_DIR / f"clip_{clip_id}.ogg"
        if clip_id not in converted:
            mp3_path = SOUNDS_DIR / f"clip_{clip_id}.mp3"
            clip_url = BASE_URL + clips[clip_id]["path"]
            print(f"  Downloading {clip_id}...")
            mp3_path.write_bytes(fetch_bytes(clip_url))
            subprocess.run(
                [
                    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", str(mp3_path), "-vn",
                    "-ac", "1", "-c:a", "libvorbis", "-q:a", "5",
                    str(ogg_path),
                ],
                check=True,
            )
            mp3_path.unlink(missing_ok=True)
            converted.add(clip_id)

        return f"rustweapons:weapon/clip_{clip_id}"

    for weapon_id, paths in WEAPONS.items():
        attack_ids = get_clip_ids(prefabs, paths["attack"], 3)
        reload_ids = get_clip_ids(prefabs, paths["reload"], 1)

        sound_events[f"weapon.{weapon_id}.shot"] = {
            "sounds": [
                {"name": ensure_ogg(clip_id), "type": "file", "volume": 1.0}
                for clip_id in attack_ids
            ]
        }
        sound_events[f"weapon.{weapon_id}.reload"] = {
            "sounds": [
                {"name": ensure_ogg(clip_id), "type": "file", "volume": 0.85}
                for clip_id in reload_ids
            ]
        }

    SOUNDS_JSON.parent.mkdir(parents=True, exist_ok=True)
    SOUNDS_JSON.write_text(
        json.dumps(sound_events, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print(
        f"Created {len(sound_events)} sound events and "
        f"{len(converted)} unique OGG clips in {SOUNDS_DIR}"
    )


if __name__ == "__main__":
    main()
