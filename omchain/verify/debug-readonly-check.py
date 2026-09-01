#!/usr/bin/env python3
"""T-092 — ยืนยันว่า namespace DEBUG เหลือแต่คำสั่งอ่าน

ที่มา: 2026-07-31 มีคนยิง debug_setHead จากเน็ตเข้ามาที่ node-rpc ของ omchain
       → เชนถอยหลัง 11M block, node พังถาวร
patch เพิ่ม `--rpc-debug-read-only` (default true) → คำสั่งที่เขียนได้จะไม่ถูก register เลย

รันบน trx40: python3 debug-readonly-check.py [พอร์ต]
"""
import json
import sys
import urllib.request

PORT = sys.argv[1] if len(sys.argv) > 1 else "49544"
RPC = f"http://127.0.0.1:{PORT}"

# (method, params, ทำอะไร)
WRITING = [
    ("debug_setHead", ["0x1"], "ถอยหัวเชน ← ตัวที่ทำให้เกิดเหตุ"),
    ("debug_replayBlock", ["0x1"], "สั่ง import block ซ้ำ"),
    ("debug_resyncWorldState", [], "สั่ง sync state ใหม่ทั้งก้อน"),
    ("debug_batchSendRawTransaction", [[]], "ยัด tx เข้าคิว"),
    ("debug_standardTraceBlockToFile", ["0x1"], "เขียนไฟล์ลงดิสก์ node"),
    ("debug_standardTraceBadBlockToFile", ["0x1"], "เขียนไฟล์ลงดิสก์ node"),
]

READING = [
    ("debug_getRawHeader", ["0x1"], "explorer ใช้"),
    ("debug_getRawBlock", ["0x1"], "explorer ใช้"),
    ("debug_accountRange", None, "อ่านอย่างเดียว"),
    ("debug_metrics", [], "อ่านอย่างเดียว"),
]


def call(method, params):
    r = urllib.request.Request(RPC, json.dumps(
        {"jsonrpc": "2.0", "method": method, "params": params or [], "id": 1}).encode(),
        {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(r, timeout=60))


def head():
    return int(call("eth_blockNumber", [])["result"], 16)


before = head()
print(f"หัวเชนก่อนทดสอบ {before:,}\n")

print("คำสั่งที่เปลี่ยนสถานะ node — ต้องไม่มีให้เรียก")
ok = True
for method, params, what in WRITING:
    body = call(method, params)
    err = body.get("error", {})
    gone = err.get("code") in (-32601, -32604)   # ไม่รู้จัก / ไม่ได้เปิดใช้
    ok &= gone
    mark = "✅ ไม่มีให้เรียก" if gone else f"❌ ยังเรียกได้ → {body}"
    print(f"  {method:<38} {mark}   ({what})")

print("\nคำสั่งอ่าน — ต้องยังใช้ได้ (explorer พึ่งพวกนี้)")
for method, params, what in READING:
    body = call(method, params)
    err = body.get("error", {})
    gone = err.get("code") in (-32601, -32604)
    ok &= not gone
    mark = "❌ หายไปด้วย" if gone else "✅ ยังอยู่"
    print(f"  {method:<38} {mark}   ({what})")

after = head()
print(f"\nหัวเชนหลังทดสอบ {after:,} — {'✅ ไม่ถูกถอย' if after >= before else '❌ ถูกถอย!'}")
sys.exit(0 if ok and after >= before else 1)
