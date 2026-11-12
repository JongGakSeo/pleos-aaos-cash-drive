# VARCO 3D assets

Everything here was made with VARCO 3D from the user's clay-style character art (master
`assets-src/themes/`). Characters were generated, rigged and animated in the VARCO 3D web studio
(https://3d.varco.ai) and merged into one GLB each with master `scripts/varco_merge_glb.py`.
Procedure and checkpoints: master `docs/QUALITY_PLAN.md`; usage record: master `docs/API_USAGE.md`.

- `pony.glb` — 20,000 tris, 39-bone hoofed-quadruped rig. Clips: Idle, Walk, Trot, Canter (in place,
  loop cleanly) and RearUp (one shot, not used by the scene). Generated from a side-view redraw of the user's pony: the
  original three-quarter pose could not be rigged as a quadruped. Generated with the studio's
  "PBR 텍스처" option, which gives a flat colour texture; without it the texture is baked from twelve
  lit views and shows patches where the views meet. Merged with `--matte` (no metallic/roughness
  map).
- `miner.glb` (bearded miner), `kid.glb` (young miner) — generated with the T-pose option, 15,000
  tris, 23-bone humanoid rig with Mixamo-style bone names. Clips: Idle (looping, also the still
  pose and the parked idle), Hammer, Wave (not used by the scenes). Generated without the PBR option: with it the yellow
  helmets come out brown (textures repainted afterwards, see below). Library clips that do not fit these short, big-headed bodies and are not
  used: PickUp (hips sink into the floor), Cheer (fists inside the head), Push (fists pressed into
  the face, head dips through the arms).
- `props/` — static models: `meadow-base` (web studio, 20,000 tris, redrawn from the mine base in
  the same style, no PBR option: with it the sandy path comes out green; track ring flattened with
  master `scripts/varco_flatten_ring.py`) and the mine and coin dioramas' base, cart, lantern, wheelbarrow and pickaxes
  (NC Image to 3D v1, from the user's part sheets). The coin of `mining-base` is plain: the emblem the
  part sheet had on it was pressed flat and painted over with master `scripts/blender/plain_coin.py`.

Textures are 1024 px (base color and normal). The colour textures of `kid`, `miner` and
`meadow-base` were repainted flat with the studio's "색상·재질 수정" on the same high-poly meshes and
transferred onto these models' UVs (master `scripts/blender/transfer_texture.py`,
`fix_invented_hues.py`, `scripts/glb_set_image.py`); the normal maps are the original ones.
