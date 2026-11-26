"""Decode CMF watch traffic from an Android ``btsnoop_hci.log``.

Enable *Developer options -> Enable Bluetooth HCI snoop log*, use the Nothing X
app, then pull the log (``adb bugreport`` or ``/data/misc/bluetooth/logs``).
Frame headers are always cleartext, so the opcode sequence is visible without
keys; pass the auth key to also decrypt payloads (the session key is derived
from the nonce seen in the capture).
"""

from __future__ import annotations

import re
import struct
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

from ..protocol import crypto, frame
from ..protocol import opcodes as ops
from ..protocol.opcodes import PLAINTEXT_OPS, Op

BTSNOOP_MAGIC = b"btsnoop\x00"
EPOCH_DELTA_US = 0x00DCDDB30F2F8000  # btsnoop epoch (year 0) -> unix epoch
ATT_CID = 0x0004
ATT_WRITE_REQ, ATT_WRITE_CMD, ATT_NOTIFY, ATT_INDICATE = 0x12, 0x52, 0x1B, 0x1D


@dataclass
class AttValue:
    ts_us: int
    outgoing: bool  # phone -> watch
    handle: int
    value: bytes


@dataclass
class L2capPacket:
    ts_us: int
    outgoing: bool
    conn: int
    cid: int
    payload: bytes


def read_l2cap(path: str | Path) -> Iterator[L2capPacket]:
    """Reassembled L2CAP packets from ACL data, on every channel (BLE and Classic)."""
    data = Path(path).read_bytes()
    if data[:8] != BTSNOOP_MAGIC:
        raise ValueError("not a btsnoop file")
    _version, datalink = struct.unpack_from(">II", data, 8)
    pos = 16
    partial: dict[int, tuple[bytearray, int, int, bool]] = {}
    while pos + 24 <= len(data):
        _orig, incl, flags, _drops, ts = struct.unpack_from(">IIIIq", data, pos)
        pkt = data[pos + 24 : pos + 24 + incl]
        pos += 24 + incl
        if datalink == 1002:  # H4: leading packet-type byte
            if not pkt or pkt[0] != 0x02:
                continue
            pkt = pkt[1:]
        elif datalink != 1001 or flags & 0x02:  # 1001 = raw HCI; flag bit1 = command/event
            continue
        if len(pkt) < 4:
            continue
        handle_flags, acl_len = struct.unpack_from("<HH", pkt)
        conn, boundary = handle_flags & 0x0FFF, (handle_flags >> 12) & 0x3
        body = pkt[4 : 4 + acl_len]
        outgoing = not (flags & 0x01)
        if boundary in (0x00, 0x02):
            if len(body) < 4:
                continue
            l2_len, cid = struct.unpack_from("<HH", body)
            partial[conn] = (bytearray(body[4:]), l2_len, cid, outgoing)
        elif conn in partial:
            partial[conn][0].extend(body)
        else:
            continue
        buf, l2_len, cid, direction = partial[conn]
        if len(buf) < l2_len:
            continue
        del partial[conn]
        yield L2capPacket(ts - EPOCH_DELTA_US, direction, conn, cid, bytes(buf[:l2_len]))


def read_att(path: str | Path) -> Iterator[AttValue]:
    for p in read_l2cap(path):
        buf = p.payload
        if p.cid != ATT_CID or not buf:
            continue
        if buf[0] in (ATT_WRITE_REQ, ATT_WRITE_CMD, ATT_NOTIFY, ATT_INDICATE) and len(buf) >= 3:
            (att_handle,) = struct.unpack_from("<H", buf, 1)
            yield AttValue(p.ts_us, p.outgoing, att_handle, buf[3:])


PSM_NAMES = {
    0x0001: "SDP",
    0x0003: "RFCOMM (HFP/SPP)",
    0x0017: "AVCTP (media keys)",
    0x0019: "AVDTP (A2DP audio)",
    0x001B: "AVCTP browsing",
    0x001F: "ATT (EATT)",
    0x0080: "LE ATT",
}
L2CAP_CONN_REQ = 0x02


def channel_summary(path: str | Path) -> list[str]:
    """Which Bluetooth channels carried traffic, and how much. Shows whether audio went
    over Classic A2DP/HFP rather than the BLE command service."""
    psm_of: dict[tuple[int, int], int] = {}
    totals: dict[tuple[int, int], list[int]] = {}
    for p in read_l2cap(path):
        if p.cid in (0x0001, 0x0005) and len(p.payload) >= 8 and p.payload[0] == L2CAP_CONN_REQ:
            psm, scid = struct.unpack_from("<HH", p.payload, 4)
            psm_of[(p.conn, scid)] = psm
        t = totals.setdefault((p.conn, p.cid), [0, 0, 0])
        t[0 if p.outgoing else 1] += len(p.payload)
        t[2] += 1
    lines = []
    for (conn, cid), (out_b, in_b, n) in sorted(totals.items()):
        if cid == ATT_CID:
            label = "ATT (BLE)"
        elif cid in (0x0001, 0x0005):
            label = "L2CAP signalling"
        else:
            psm = psm_of.get((conn, cid))
            label = PSM_NAMES.get(psm, f"PSM 0x{psm:04x}") if psm is not None else "dynamic channel"
        lines.append(
            f"conn 0x{conn:03x} cid 0x{cid:04x} {label:<22} {n:6d} pkts"
            f"  phone->watch {out_b:9d} B  watch->phone {in_b:9d} B"
        )
    return lines


@dataclass
class RawFrame:
    op: Op
    index: int
    count: int
    body: bytes
    encrypted: bool


class _Stream:
    """Frame splitter tolerant of both plaintext CRC conventions."""

    def __init__(self) -> None:
        self.buf = bytearray()

    def feed(self, data: bytes) -> Iterator[RawFrame]:
        self.buf.extend(data)
        while True:
            start = self.buf.find(frame.MAGIC)
            if start < 0:
                self.buf.clear()
                return
            del self.buf[:start]
            if len(self.buf) < frame.HEADER_LEN:
                return
            _, n, cmd1, count, index, cmd2 = frame.HEADER.unpack_from(self.buf)
            op = Op(cmd1, cmd2)
            encrypted = op not in PLAINTEXT_OPS
            if encrypted:
                size = n
            else:
                avail = len(self.buf) - frame.HEADER_LEN
                short = n - 4
                nxt = frame.HEADER_LEN + short
                size = short if avail == short or (avail > short and self.buf[nxt] == frame.MAGIC) else n
            if len(self.buf) < frame.HEADER_LEN + size:
                return
            body = bytes(self.buf[frame.HEADER_LEN : frame.HEADER_LEN + size])
            del self.buf[: frame.HEADER_LEN + size]
            if not encrypted and size == n and len(body) >= 4 and crypto.crc32_le(body[:-4]) == body[-4:]:
                body = body[:-4]
            yield RawFrame(op, index, count, body, encrypted)


def decode_log(
    path: str | Path, authkey: bytes | None = None, show_payload: bool = True, secret: bytes | None = None
) -> Iterator[str]:
    """Yield one line per frame. Without an auth key, a capture that includes a fresh
    pairing is still decryptable: the device secret (passed in, or read from the
    ``AT GETSECRET`` reply in the log) plus the two plaintext pairing randoms give it."""
    streams: dict[tuple[bool, int], _Stream] = {}
    key: bytes | None = None
    rnd1: bytes | None = None
    t0: int | None = None
    for att in read_att(path):
        t0 = att.ts_us if t0 is None else t0
        stamp = f"{(att.ts_us - t0) / 1e6:9.3f}s {'->' if att.outgoing else '<-'} h{att.handle:04x}"
        if att.value[:1] != bytes([frame.MAGIC]) and all(32 <= b < 127 or b in (10, 13) for b in att.value):
            text = att.value.decode()
            found = re.search(r"GETSECRET:([0-9A-Fa-f]{32})", text)
            if found and secret is None:
                secret = bytes.fromhex(found.group(1))
            yield f"{stamp} AT    {text.strip()}"
            continue
        stream = streams.setdefault((att.outgoing, att.handle), _Stream())
        for rf in stream.feed(att.value):
            name = ops.name_of(rf.op)
            if rf.op == ops.AUTH_PAIR_REQUEST:
                rnd1 = rf.body[:16]
            elif rf.op == ops.AUTH_PAIR_REPLY and rnd1 and secret and len(rf.body) >= 16:
                authkey = crypto.derive_authkey(rnd1, rf.body[:16], secret)
                yield f"{stamp} (auth key derived from this pairing: {authkey.hex()})"
            text = ""
            if show_payload:
                payload = rf.body
                if rf.encrypted:
                    if rf.op == ops.AUTH_PHONE_NAME and authkey:
                        key = authkey
                    try:
                        payload = crypto.aes_decrypt(key, rf.body)[:-4] if key else None
                    except ValueError:
                        payload = None
                    if payload is not None and rf.op == ops.AUTH_NONCE_REPLY and authkey:
                        key = crypto.derive_session_key(payload, authkey)
                text = payload.hex() if payload is not None else f"<encrypted {len(rf.body)} B>"
            chunk = f" [{rf.index}/{rf.count}]" if rf.count > 1 else ""
            yield f"{stamp} {name:<26}{chunk} {text}"
