# Head Scanner (Android, ARCore Depth)

Version **0.1**. The version is shown in the bottom-right corner of the app and set by `versionName` in `app/build.gradle.kts`.

Captures a person's head and neck with the phone's rear camera and exports a textured
low-poly 3D model:

| Output | Value |
|---|---|
| Geometry | ~1500 triangles (head + neck, shoulders cut off) |
| Texture | 1024 × 1024 PNG |
| Format | `head.obj` + `head.mtl` + `head_texture.png` |
| Location | `Downloads/HeadScans/HeadScan_<date>_<time>/` (Share button also offered) |

Everything runs on the phone. No server, no account.

## Requirements

* An Android phone that supports the **ARCore Depth API** (most mid/high-end phones since ~2020;
  see Google's list at https://developers.google.com/ar/devices, look for "Supports Depth API").
* Google Play Services for AR (the app prompts to install it).
* Android 7.0+.

## How to scan

The rear camera is used (ARCore depth does not work on the selfie camera), so **someone else holds the phone**
while the person being scanned sits still.

1. Sit the subject on a stool in good, even light. Hair tied back helps. Stay still for ~1 minute.
2. Hold the phone 40–60 cm from the face, put the crosshair on the nose and tap **Start**.
3. Walk slowly in a full circle around the head, keeping it centred. The ring fills in as angles are captured.
4. Then raise the phone and look down at the top of the head from a few angles.
5. Tap **Finish**. Processing takes roughly 10–40 seconds. The files are saved to `Downloads/HeadScans/...`.

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
ARCore session (DepthMode.AUTOMATIC, largest CPU image)
  └─ keyframe every ~9° around the head: DEPTH16 map + camera photo (JPEG) + camera pose + intrinsics
       │
       ▼   core/ (pure Kotlin, unit-tested on the JVM)
TsdfVolume      fuse all depth maps into a 4 mm truncated signed distance field around the head
SurfaceNets     extract the zero surface
MeshOps         crop to head + neck (cylinder below the chin, remove shoulder tops), keep largest piece,
                Taubin smoothing to remove depth noise
Decimator       quadric-error edge collapse down to 1500 triangles (boundary-preserving, no flips)
UvAtlas         pack triangles in pairs into a grid on the 1024² texture
TextureBaker    project every texel into each photo, depth-test for occlusion, blend by view angle,
                fill unseen texels and pad seams
ObjWriter       OBJ (v / vt / vn / f) + MTL with map_Kd
```

Tunables (triangle count, texture size, voxel size, crop box) are in `ScanSettings`
(`app/src/main/java/com/headscan/core/HeadScanPipeline.kt`).

## Limitations

* ARCore depth is coarse (a few mm–cm of noise), so fine detail such as eyelids, ears and hair strands is
  smoothed over. The texture carries most of the likeness.
* The subject must not move during the scan; any motion blurs the fused surface.
* Areas never seen (e.g. under the chin if you never go low) are filled with the average skin colour.
