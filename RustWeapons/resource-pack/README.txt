RustWeapons Resource Pack — Minecraft Java 1.21.4

This pack uses the 1.21.4 item-model definition format.

For each weapon, assets/rustweapons/items/<id>.json selects:
- <id>_inventory for GUI, dropped item and item frames
- <id>_hand for first-person and third-person hand display

The texture references are temporary vanilla placeholders so the pack works without missing-texture squares. Replace the texture path in the corresponding model JSON files with your own transparent PNG files when the Rust weapon artwork is ready.

Suggested custom texture paths:
assets/rustweapons/textures/item/<id>_inventory.png
assets/rustweapons/textures/item/<id>_hand.png

The hand sprite should be a 2D first-person view with the weapon at the lower-right and its barrel pointing forward. The hand and inventory models already have separate display transforms.
