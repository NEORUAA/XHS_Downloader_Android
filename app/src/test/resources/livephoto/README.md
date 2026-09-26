# Xiaomi compatibility fixture

`xiaomi-before-multiformat.xmp` was extracted from `generateXmpMetadata` in
`app/src/main/java/com/neoruaa/xhsdn/LivePhotoCreator.kt` at repository commit
`15c5f5701b3479c2a970f3acf953625725f0d59c`.

Only the three decimal video lengths have been replaced with `@VIDEO_LENGTH@`.
Preserve whitespace and metadata values: this is a frozen regression baseline,
not a fixture to regenerate from the current writer.

## Vendor reference fixtures

Regenerate with `python3 scripts/generate_livephoto_reference_fixtures.py /path/to/research`.
The directory must contain clean checkouts at the exact revisions checked by the script.
Tests consume frozen fixtures and never download or invoke reference projects.

- `samsung-jpeg-trailer.bin`: direct `SamsungTags(video, "jpg").video_footer()` output from
  [PetrVys/MotionPhoto2](https://github.com/PetrVys/MotionPhoto2/tree/ff46699215f1071ada579140d73994a4be29a399), MIT, copyright Petr Vyskocil.
- `oplus-mpf-4096.bin`: direct `_build_mpf_segment(4096)` output from
  [Young-Spark/oppo-live-photo-maker](https://github.com/Young-Spark/oppo-live-photo-maker/tree/3d020598789e8eebb0fa042b993ea03e4d21c2f9), MIT.
  The test replaces only the image-size field with its actual JPEG length.
- `vivo-sdr-reference.xmp` and `vivo-user-comment.txt`: extracted constants from `VivoLivePhotoProtocol.cs`
  in [LengxiQwQ/live-photo-box](https://github.com/LengxiQwQ/live-photo-box/tree/66b619249c32a6d6d4080063d64df9471589f003), GPL-3.0.
- `vivo-image-tail.bin`, `vivo-video-uuid.bin`: the same repository's `VivoDualFileMetadataWriter.cs`
  JSON constants, with an independent Python transcription of its binary envelope. Fixed ID:
  `0123456789abcdef0123456789ab`. This does not execute the C# runtime.
- `huawei-tail.bin`: independent transcription of `HuaweiMovingPhotoProtocol.BuildTail`'s new-file overload
  for cover frame 0, total frames 18 and synthetic video length. It does not use the historical-milliseconds overload.

Video input to binary fixtures: `ftyp(isom0000)` + `moov(01)` + `mdat(bytes 00..7f)`.
It is a structural test payload, not a playable clip. Android tests separately use the playable AVC/AAC asset.
These files prove source-layout parity, not vendor-gallery acceptance.
