# Noir Environment Sources

Noir keeps the runtime renderer self-contained. Environment art is selected from open-license source packs and is not executed as third-party code.

## Recommended source

**Kenney Nature Kit** — CC0
- 329+ nature models (trees, rocks, grass and foliage)
- Source catalog mirror: https://github.com/Hidencod/tge-assets/tree/main/packs/nature-kit
- Original pack: https://kenney.nl/assets/nature-kit

## Suggested quality mapping

- MEDIUM: terrain + low-density foliage + low-cost sky
- HIGH: terrain + foliage + shadows + clouds
- ULTRA: denser foliage + higher shadow quality + reflections + bloom
- EXTREME: maximum native material/shadow/sky quality allowed by the device

The quality tier is a renderer capability profile, not an asset-license tier.

## Current engine behavior

The native viewport uses NoirGFX directly. WorldEnvironment/Sky3D/FogVolume/PostProcess resources are render-state resources and are never converted into visible cubes in the 3D scene.

The current native importer is intentionally separate from the source asset catalog. This keeps the APK stable while the NoirGFX mesh importer is being expanded to consume GLB assets directly.
