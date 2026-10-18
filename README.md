# Group Sticker Fix

An LSPosed/Xposed module that makes stickers behave like stickers instead of plain images.

| App | Problem | What the module does |
|-----|---------|----------------------|
| **Messenger** (`com.facebook.orca`) | A sticker pasted from the keyboard is sent as a sticker in DMs but as an image in group chats. | Makes groups take the same sticker path as DMs. |
| **Zalo** (`com.zing.zalo`) | Keyboard stickers are sent inconsistently, and a sticker pasted from the Android clipboard is sent as a transparent image. | Sends tagged stickers as stickers (keyboard or clipboard); ordinary images stay images. |

Package: `dhp.tpl.groupstickerfix`. Requires Android 9+ (minSdk 28) and LSPosed.

## How it works

Names in these apps are obfuscated and change every release, so **no class or method name is hardcoded**. [DexKit](https://github.com/LuckyPray/DexKit) finds the target methods at runtime from stable fingerprints (log strings, constants, call structure). Results are cached per APK build, so the search only runs after an app update.

### Messenger
The keyboard-content handler only converts the pasted image to a sticker when `ThreadKey.A1H()` is true (DM thread kinds). In groups it returns false and the handler falls back to an image. The module makes the first `A1H()` check inside that handler return true.

### Zalo
Keyboard commits (source 2) and clipboard pastes (source 1) arrive in one method. Zalo sends a sticker for GIFs, or for PNGs when the source is the keyboard **and** a "sticker-like PNG" test passes (small, transparent edges). The module:
- runs that same test for clipboard-pasted and drag-and-dropped PNGs, so a sticker becomes a sticker while photos, screenshots and large or opaque images stay images;
- forces the sticker path for anything tagged "sticker" (clip label, extras or MIME) and for PNGs committed by the keyboard picker (keyboards do not tag their stickers);
- also runs a transparency check on every image (any route): a PNG/WebP with a real alpha channel, a large transparent area and an opaque object is treated as a sticker; photos and screenshots are not;
- leaves other keyboard images (JPEG, ...) exactly as Zalo handles them.

## Install
1. Download `GroupStickerFix.apk` from [Releases](../../releases) (or the Actions artifact) and install it.
2. In LSPosed, enable the module and tick **Messenger** and/or **Zalo** in its scope.
3. Force-stop the app, then try it.

## Troubleshooting
The module logs lines tagged `GroupStickerFix` to the LSPosed module log. In Zalo every received image logs its source, MIME, label, extras and the decision taken, which shows what tag your keyboard sets. If a sticker still arrives as an image, open an issue with those lines and the app version.

Tested against Messenger 583.0.0.29.95 and Zalo 26.10.01. Other versions rely on the DexKit fingerprints still matching.

## Build
CI (`.github/workflows/build.yml`) builds and signs a release APK with Gradle 8.9 and JDK 17. It needs these repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Pushing a `v*` tag attaches the APK to a GitHub release.

## How a sticker is recognised
1. **Tag** – the clip's label, MIME types or extras contain `sticker` or `object_capture` (Samsung gallery object cut-outs).
2. **Pixel check** – PNG/WebP only; a downsampled copy must have an alpha channel, at least 12% (almost) fully transparent pixels and at least 4% (almost) opaque pixels (an object on a transparent background). Photos and screenshots fail.
