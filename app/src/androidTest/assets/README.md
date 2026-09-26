# Live photo media fixture

`live_photo_av.mp4` is a one-second, locally generated 120x160 AVC/AAC test clip.
It contains a synthetic test pattern and a 440 Hz tone, not downloaded media.

```sh
ffmpeg -f lavfi -i testsrc2=size=120x160:rate=12 \
  -f lavfi -i sine=frequency=440:sample_rate=44100 \
  -t 1 -c:v libx264 -pix_fmt yuv420p -c:a aac -b:a 32k \
  -movflags +faststart live_photo_av.mp4
```

The instrumented tests compare compressed audio/video sample hashes, decode an
embedded frame, check EXIF orientation normalization, and round-trip MediaStore
outputs. These checks do not prove recognition by vendor gallery applications.
