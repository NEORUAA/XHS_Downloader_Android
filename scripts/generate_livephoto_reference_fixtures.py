"""Regenerate small protocol fixtures from pinned, locally checked out reference projects.

Usage: python3 scripts/generate_livephoto_reference_fixtures.py /path/to/research
No network access or Android production writer is used. See the fixture README.
"""
import importlib.util
import json
import re
import struct
import subprocess
import sys
from pathlib import Path

research = Path(sys.argv[1])
out = Path(__file__).resolve().parents[1] / "app/src/test/resources/livephoto"
revisions = {
    "oppo-live-photo-maker": "3d020598789e8eebb0fa042b993ea03e4d21c2f9",
    "MotionPhoto2": "ff46699215f1071ada579140d73994a4be29a399",
    "live-photo-box": "66b619249c32a6d6d4080063d64df9471589f003",
}
for folder, revision in revisions.items():
    actual = subprocess.check_output(["git", "-C", str(research / folder), "rev-parse", "HEAD"], text=True).strip()
    dirty = subprocess.check_output(["git", "-C", str(research / folder), "status", "--porcelain", "--untracked-files=no"], text=True)
    if dirty:
        raise ValueError(f"Modified reference checkout: {folder}")
    if actual != revision:
        raise ValueError(f"Unexpected reference revision: {folder}: {actual}")


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def box(kind, payload):
    return struct.pack(">I", len(payload) + 8) + kind.encode() + payload


video = box("ftyp", b"isom0000") + box("moov", b"\x01") + box("mdat", bytes(range(128)))
sys.path.insert(0, str(research / "MotionPhoto2"))
samsung = module("SamsungTags", research / "MotionPhoto2/SamsungTags.py")
(out / "samsung-jpeg-trailer.bin").write_bytes(samsung.SamsungTags(video, "jpg").video_footer())
oppo = module("oppo_muxer", research / "oppo-live-photo-maker/src/oppo_live_photo/muxer.py")
(out / "oplus-mpf-4096.bin").write_bytes(oppo._build_mpf_segment(4096))

# Extract upstream C# string constants, preserving every byte and field order.
# The two small binary envelopes below follow BuildTail/AppendVideoUuidBoxAsync;
# they are reference transcriptions, not execution of the C# runtime.
protocols = research / "live-photo-box/LivePhotoBox.Core/Services/Protocols"
source = (protocols / "VivoDualFileMetadataWriter.cs").read_text()


def constant(source, name):
    expression = source.split(f"string {name} =", 1)[1].split(';\n', 1)[0]
    return ''.join(json.loads(m.group()) for m in re.finditer(r'"(?:[^"\\]|\\.)*"', expression))


pair_id = "0123456789abcdef0123456789ab"


def vivo_tail(template):
    payload = template.format(pair_id).encode()
    return (payload + struct.pack('>I', len(payload) - 4) + b'cameralbum!' +
            struct.pack('>I', 19 + len(pair_id)) + pair_id.encode() + b'\xff' * 4 +
            bytes.fromhex('1b2a39485766758493a2b3'))


(out / 'vivo-image-tail.bin').write_bytes(vivo_tail(constant(source, 'ImageJsonTemplate')))
(out / 'vivo-video-uuid.bin').write_bytes(box('uuid', b'vivoMediaExtInfo' + vivo_tail(constant(source, 'VideoJsonTemplate'))))
vivo = (protocols / 'VivoLivePhotoProtocol.cs').read_text()
# RdfTemplateNoGainMap includes a constant reference between the two closing strings.
rdf = constant(vivo, 'RdfTemplateNoGainMap').replace('</rdf:Seq>', constant(vivo, 'ContainerBody') + '</rdf:Seq>')
wrapper = '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
(out / 'vivo-sdr-reference.xmp').write_text(wrapper + rdf.format(len(video), 0, 'image/jpeg', 0, 'video/mp4') + '</rdf:RDF></x:xmpmeta>')
(out / 'vivo-user-comment.txt').write_text(constant(vivo, 'UserCommentTemplate').format('2000:01:01 00:00:00'))
# Huawei BuildTail(coverFrame=0, totalFrames=18, mp4Size=len(video)).
# Its default new-file overload writes frame counts, not elapsed milliseconds.
tail = bytearray(b' ' * 60)
for offset, field in ((0, b'v6_f0'), (20, b'0:18'), (40, f'LIVE_{len(video) + 20}'.encode())):
    tail[offset:offset + len(field)] = field
(out / 'huawei-tail.bin').write_bytes(tail)
print('Generated seven pinned protocol fixtures.')
