# Head Scanner (Android, ARCore Depth)

Version **0.23**. The version is shown in the bottom-right corner of the app and set by `versionName` in `app/build.gradle.kts`.

Captures a person's head and neck with the phone's rear camera and exports a textured
low-poly 3D model:

| Output | Value |
|---|---|
| Geometry | Low: 1500 triangles, High: 3500 triangles (chosen on the start screen) |
| Texture | 1024 × 1024 PNG |
| Style | Realistic (smooth) or GoldenEye: Source style (Half-Life 2 mod character look: smooth head, crisp graded photo face with baked soft lighting and shadows in the creases) |
| Format | `<name>.obj` + `<name>.mtl` + `<name>_texture.png` |
| Location | `Downloads/HeadScans/<name>/` (Share button also offered) |

After processing, a 3D preview appears (drag to spin). A **Size** slider (50–150%) scales the whole model. In Selfie mode the **head is measured from your photos**: an on-device person cut-out (ML Kit Selfie Segmentation) finds your outline in each photo, and the generic skull's width, height, depth and position are fitted so it matches your outline from every angle, with your scanned face set into it. A few millimetres are allowed for hair. The preview shows the measured size (e.g. *Head 15.4 × 21.0 × 19.6 cm*); if your outline can't be found (busy background, poor light) it falls back to average proportions and says so. Turn your head well round each way while capturing, as the side-on views give the depth. You can change the **hairstyle** and switch **GoldenEye: Source style** on or off right on the preview. The **—** and **⋮** (settings) buttons sit just under the instructions. **—** minimises the instructions to one line and folds the size slider away (tap **+** to bring them back). The head rebuilds around your face by itself a moment after you let go of a slider, with no Apply button. Tap **Save** to name and save it, or **Discard**. The generic head has ears, and in Selfie mode the edge of the scanned face fades smoothly into the head so no flaps or spikes stick out at the cheeks and jaw.

In Selfie mode you can also pick a hairstyle for the generic head: **No hair**, **Side parting** (combed over from a side part, visible parting line), **Buzz cut** (speckled stubble), **Mohican** (a strip of tall spikes with stubble on the shaved sides), **Curtains** (centre parting, strands framing the face, jaw length, covers the ears) or **Spiky** (sharp spikes all over the top). Hair is painted with fine strands and goes through the same texture recipe as the face (sharpened, slightly desaturated, low-poly shading). Two sliders at the side of the preview change the hair **colour** (the photo colour, then natural shades from black through browns, auburn, ginger and blondes to grey and white, then the rainbow) and **tone** (dark to light), live. Hair is grown out of the scalp as part of the one mesh, so the triangle limit (1500 / 3500) always includes it. The head and neck skin tone is measured from your cheeks and forehead, and the hair colour from just above your forehead.

Everything runs on the phone. No server, no account.

## Requirements

* An Android phone that supports the **ARCore Depth API** (most mid/high-end phones since ~2020;
  see Google's list at https://developers.google.com/ar/devices, look for "Supports Depth API").
* Google Play Services for AR (the app prompts to install it).
* Android 7.0+.

## Texture and shape (0.19)

* **Layout:** the texture is a game-style unwrap of a few large islands (with the face in one piece and given
  about twice the resolution) instead of one cell per triangle, so it is continuous, sharper and has few seams.
* **GoldenEye: Source style:** high-pass sharpening (fine unsharp mask plus a wider high-pass that brings out
  pores and stubble), slight desaturation, and low-poly shading: big planes (forehead, cheeks, nose bridge, skull)
  are flat-shaded while the eyes, lips and ears stay smooth (OBJ smoothing groups).
* **Skin (Selfie mode):** uneven lighting on the photo face is evened out, a badly seen side of the face is
  filled in from the mirror-image side, and the painted skin on the sides, back and neck gets real pore/stubble
  detail cut from your cheek instead of a flat colour.
* **Head shape (Selfie mode):** the generic head now has a rounded back of the skull, slightly narrower temples,
  a jawline from the chin to below the ears, cheekbones, ears and a neck, all sized from your face.
* Nearest / linear filtering and DXT (BC1) compression are chosen in the game engine when the PNG is imported.

## Settings

Tap the **⋮** button (top right) for settings, which apply to the next scan:

* **Head style:** Realistic or GoldenEye: Source.

## Two scan modes

| Mode | Camera | Who holds the phone | Result |
|---|---|---|---|
| **Full head** | rear, ARCore Depth API | a friend walks around you | your real head, hair and neck shape |
| **Selfie** | front, ARCore Augmented Faces | you | your real face merged onto a generic head + neck |

Selfie mode can't see the back of your head, so the scalp and back are coloured from the hair seen above
your forehead, and the rest from your average skin tone. It also saves `alignment_check.jpg`, a photo with the
face mesh drawn on it in green, which shows whether the texture lined up.

## How to scan (full head)

The rear camera is used (ARCore depth does not work on the selfie camera), so **someone else holds the phone**
while the person being scanned sits still.

1. Sit the subject on a stool in good, even light. Hair tied back helps. Stay still for ~1 minute.
2. Hold the phone 40–60 cm from the face, put the crosshair on the nose and tap **Start**.
3. Walk slowly in a full circle around the head, keeping it centred. The ring fills in as angles are captured.
4. Then raise the phone and look down at the top of the head from a few angles.
5. Finish standing in front of the face and tap **Finish**, then hold still for a second while a
   full-resolution photo is taken. Processing takes roughly 10–40 seconds. The files are saved to `Downloads/HeadScans/...`.

## How to scan (selfie)

1. Choose **Selfie** on the start screen, hold the phone at arm's length and look at the screen.
2. Tap **Start**, keep the phone still and slowly turn your head one way, then the other, then tilt your chin
   up and down. The text tells you which angles are still missing.
3. Look straight at the screen and tap **Finish**, then hold still for a second while a full-resolution
   photo is taken.

## Sharp textures (0.10)

* Every tracked frame gets a blur score (variance of the Laplacian, measured at a fixed 640 px scale).
* When you tap Finish, ARCore is paused and the same camera takes one maximum-quality still with CameraX.
  It is lined up with the last tracked frame by image registration (scale, shift and mirroring, found by
  normalised cross-correlation, coarse to fine), colour-matched to it, and used as an extra keyframe.
  If it can't be matched (the phone or head moved), it is skipped and the scan still finishes.
* The texture is no longer an average of all photos. Each texel takes its colour from the best photo for it:
  most front-on, most image pixels on that spot, and sharpest. Photos only blend over a narrow band where two
  are about equally good, so seams stay soft without smearing detail.

Keep the three files in the same folder when you copy them, since the OBJ refers to the MTL and the MTL to the PNG.
Units are metres, +Y is up and the face looks toward +Z (Blender: import with "Y up / -Z forward" defaults works).

## Build and install

**Android Studio (easiest):** File → Open → this folder, let Gradle sync, plug in the phone with
USB debugging on, press Run.

**Command line:** with the Android SDK installed (`ANDROID_HOME` set):

```bash
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # runs the synthetic-head pipeline test
```

**GitHub:** push this folder to a GitHub repo and the included workflow
(`.github/workflows/build-apk.yml`) builds the APK; download it from the run's *Artifacts*.

## How it works

```
ARCore session (DepthMode.AUTOMATIC, largest CPU image up to 1920 px wide)
  └─ keyframe every ~9° around the head: DEPTH16 map + camera photo (JPEG) + camera pose + intrinsics
       │
       ▼   core/ (pure Kotlin, unit-tested on the JVM)
TsdfVolume      fuse all depth maps into a 4 mm truncated signed distance field around the head
SurfaceNets     extract the zero surface
MeshOps         crop to head + neck (cylinder below the chin, remove shoulder tops), keep largest piece,
                Taubin smoothing to remove depth noise
Decimator       quadric-error edge collapse down to 1500 triangles (boundary-preserving, no flips)
ChartAtlas      game-style UV layout: split the head into large islands by facing direction (face, sides,
                back, top, under the chin), flatten each, pack them with the face at ~2x texel density
ImageRegistration  line the full-resolution Finish photo up with the last tracked frame
TextureBaker    project every texel into each photo, depth-test for occlusion, take the best photo per texel
                (view angle × resolution × sharpness), fill unseen texels and pad seams
ObjWriter       OBJ (v / vt / vn / f) + MTL with map_Kd
```

Tunables (triangle count, texture size, voxel size, crop box) are in `ScanSettings`
(`app/src/main/java/com/headscan/core/HeadScanPipeline.kt`).

## Limitations

* ARCore depth is coarse (a few mm–cm of noise), so fine detail such as eyelids, ears and hair strands is
  smoothed over. The texture carries most of the likeness.
* The subject must not move during the scan; any motion blurs the fused surface.
* Areas never seen (e.g. under the chin if you never go low) are filled with the average skin colour.
