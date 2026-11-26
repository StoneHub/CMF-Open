from cmfkit.protocol.frame import FrameDecoder
from cmfkit.protocol.opcodes import Op


def test_empty_encrypted_ack_decodes():
    dec = FrameDecoder(key=bytes(16))
    msgs = list(dec.feed(bytes.fromhex("f500000065000100010003")))
    assert msgs == [type(msgs[0])(Op(0x0065, 0x0003), b"")]
