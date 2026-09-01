#!/usr/bin/env python3
"""omtx — ส่ง tx จาก command line ให้ node ที่ไม่ยอมถือ key ให้ (Besu)

geth ให้พิมพ์ eth.sendTransaction ใน console ได้เพราะ node ถือ key ให้
Besu ไม่ถือ key ให้ใครเลย ทางเดียวคือ **เซ็นข้างนอกแล้วส่ง eth_sendRawTransaction**
สคริปต์นี้ทำหน้าที่นั้น: อ่านคีย์จากไฟล์ → เซ็น → แสดงให้ดูก่อน → ส่งเมื่อยืนยัน

⚠️ คีย์รับทาง **ไฟล์** เท่านั้น ไม่รับทาง argument
   เพราะ argument โผล่ใน `ps aux` และ shell history ให้คนทั้งเครื่องเห็น

ตัวอย่าง
  # ดูยอด/nonce ก่อน
  omtx.py info --key /root/hotwallet.key

  # โอน (จะถามยืนยันก่อนส่ง)
  omtx.py send --key /root/hotwallet.key --to 0xabc... --amount 1.5

  # ยกเลิก tx ที่ค้าง: ยิงทับ nonce เดิมด้วยราคาสูงกว่า ส่งเข้าตัวเอง จำนวน 0
  omtx.py cancel --key /root/hotwallet.key --nonce 42

  # เรียก contract (data ดิบ)
  omtx.py send --key /root/k --to 0xcontract --data 0xa9059cbb... --gas 120000
"""
import argparse
import getpass
import json
import os
import sys
import time
import urllib.request

from eth_account import Account
from eth_utils import is_address, to_checksum_address

DEFAULT_RPC = os.environ.get("OMTX_RPC", "http://127.0.0.1:8545")
DEFAULT_CHAIN_ID = int(os.environ.get("OMTX_CHAIN_ID", "1246"))
# นโยบายเชน: ต่ำกว่านี้ node ไม่รับ
MIN_GAS_PRICE_GWEI = 500


def rpc(method, params, url, timeout=30):
    req = urllib.request.Request(
        url,
        json.dumps({"jsonrpc": "2.0", "method": method, "params": params, "id": 1}).encode(),
        {"Content-Type": "application/json"})
    body = json.load(urllib.request.urlopen(req, timeout=timeout))
    if "error" in body:
        raise SystemExit(f"  ❌ {method}: {body['error'].get('message', body['error'])}")
    return body["result"]


def load_key(path):
    """อ่านคีย์จากไฟล์ รองรับทั้ง hex ดิบ และ keystore JSON (จะถามรหัสผ่าน)"""
    if not os.path.exists(path):
        raise SystemExit(f"  ❌ ไม่พบไฟล์คีย์ {path}")
    mode = os.stat(path).st_mode & 0o077
    if mode:
        print(f"  ⚠️ {path} คนอื่นอ่านได้ (chmod 600 ก่อนจะดีกว่า)")

    raw = open(path).read().strip()
    if raw.lstrip().startswith("{"):
        password = getpass.getpass("  รหัสผ่าน keystore: ")
        return Account.from_key(Account.decrypt(json.loads(raw), password))
    return Account.from_key(raw if raw.startswith("0x") else "0x" + raw)


def eth(wei):
    return wei / 1e18


def address(value):
    """eth-account รับเฉพาะ address แบบ checksum — แปลงให้ และฟ้องแต่เนิ่น ๆ ถ้าผิดรูป"""
    if not is_address(value):
        raise SystemExit(f"  ❌ ไม่ใช่ address: {value}")
    return to_checksum_address(value)


def queued_nonces(sender, url):
    """nonce ทั้งหมดของ address นี้ที่ยังอยู่ในคิว ทั้งที่พร้อม mine และที่ติดช่องว่าง"""
    content = rpc("txpool_contentFrom", [sender], url)
    out = set()
    for group in ("pending", "queued"):
        out.update(int(n) for n in (content.get(group) or {}))
    return out


def show(acct, url):
    bal = int(rpc("eth_getBalance", [acct.address, "latest"], url), 16)
    nonce = int(rpc("eth_getTransactionCount", [acct.address, "latest"], url), 16)
    pending = int(rpc("eth_getTransactionCount", [acct.address, "pending"], url), 16)
    print(f"  address  {acct.address}")
    print(f"  ยอดเงิน   {eth(bal):.6f}")
    print(f"  nonce     ยืนยันแล้ว {nonce} · รวมที่ค้างในคิว {pending}"
          + (f"  ← ค้างอยู่ {pending - nonce} ใบ" if pending > nonce else ""))
    return bal, nonce, pending


def build_and_send(args, acct, url, to, value_wei, data, nonce, gas_price_gwei, gas):
    if gas_price_gwei < MIN_GAS_PRICE_GWEI:
        raise SystemExit(f"  ❌ เชนนี้ไม่รับ gas ต่ำกว่า {MIN_GAS_PRICE_GWEI} gwei")

    tx = {"nonce": nonce, "to": address(to), "value": value_wei, "gas": gas,
          "gasPrice": gas_price_gwei * 10**9, "chainId": args.chain_id}
    if data:
        tx["data"] = data

    cost = value_wei + gas * gas_price_gwei * 10**9
    print("\n  จะส่ง tx นี้")
    print(f"    จาก      {acct.address}")
    print(f"    ไปที่     {to}")
    print(f"    จำนวน     {eth(value_wei):.6f}")
    print(f"    nonce    {nonce}")
    print(f"    gas      {gas} × {gas_price_gwei} gwei = {eth(gas*gas_price_gwei*10**9):.6f}")
    print(f"    รวมสูงสุด {eth(cost):.6f}")
    if data:
        print(f"    data     {data[:66]}{'…' if len(data) > 66 else ''}")

    if not args.yes:
        if input("\n  พิมพ์ yes เพื่อส่ง: ").strip().lower() != "yes":
            raise SystemExit("  ยกเลิก ไม่ได้ส่งอะไรออกไป")

    signed = Account.sign_transaction(tx, acct.key)
    h = rpc("eth_sendRawTransaction",
            ["0x" + signed.raw_transaction.hex().removeprefix("0x")], url)
    print(f"\n  ส่งแล้ว {h}")

    if args.wait:
        print("  รอยืนยัน", end="", flush=True)
        for _ in range(args.wait):
            time.sleep(1)
            r = rpc("eth_getTransactionReceipt", [h], url)
            if r:
                ok = int(r["status"], 16) == 1
                blk = int(r["blockNumber"], 16)
                print(f"\n  {'✅ สำเร็จ' if ok else '❌ ล้มเหลว (status 0)'} ที่ block {blk:,}"
                      f" · ใช้ gas {int(r['gasUsed'],16):,}")
                return 0 if ok else 1
            print(".", end="", flush=True)
        print("\n  ⚠️ ยังไม่ยืนยันในเวลาที่รอ — tx ยังอยู่ในคิว")
    return 0


def main():
    p = argparse.ArgumentParser(description="ส่ง tx จาก CLI ให้ Besu (เซ็นข้างนอกแล้วส่ง raw)")
    p.add_argument("--rpc", default=DEFAULT_RPC, help=f"ปลายทาง RPC (default {DEFAULT_RPC})")
    p.add_argument("--chain-id", type=int, default=DEFAULT_CHAIN_ID)
    p.add_argument("--key", required=True, help="ไฟล์คีย์ (hex ดิบ หรือ keystore JSON)")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("info", help="ดูยอดเงินและ nonce")

    s = sub.add_parser("send", help="ส่ง tx")
    s.add_argument("--to", required=True)
    s.add_argument("--amount", default="0", help="จำนวนเป็นเหรียญ (ไม่ใช่ wei)")
    s.add_argument("--data", default=None, help="calldata ดิบ ขึ้นต้น 0x")
    s.add_argument("--gas", type=int, default=None, help="ไม่ใส่ = ให้ node ประเมินให้")
    s.add_argument("--gas-price", type=int, default=MIN_GAS_PRICE_GWEI, help="หน่วย gwei")
    s.add_argument("--nonce", type=int, default=None, help="ไม่ใส่ = ใช้ nonce ถัดไปของคิว")

    c = sub.add_parser("cancel", help="ยิงทับ nonce ที่ค้าง (ส่ง 0 เข้าตัวเอง)")
    c.add_argument("--nonce", type=int, required=True)
    c.add_argument("--gas-price", type=int, default=MIN_GAS_PRICE_GWEI * 2,
                   help="ต้องสูงกว่าใบเดิมพอสมควร ไม่งั้น node ไม่ยอมแทนที่")

    for x in (s, c):
        x.add_argument("--yes", action="store_true", help="ไม่ต้องถามยืนยัน")
        x.add_argument("--wait", type=int, default=60, help="รอยืนยันกี่วินาที (0 = ไม่รอ)")

    args = p.parse_args()
    acct = load_key(args.key)

    print(f"  RPC {args.rpc} · chainId {args.chain_id}")
    bal, nonce, pending = show(acct, args.rpc)

    if args.cmd == "info":
        return 0

    if args.cmd == "cancel":
        if args.nonce < nonce:
            raise SystemExit(f"  ❌ nonce {args.nonce} ถูก mine ไปแล้ว (ยืนยันถึง {nonce}) "
                             "ยกเลิกไม่ได้")
        # ห้ามใช้ nonce แบบ pending มาตัดสิน: ถ้าคิวมีช่องว่าง มันจะหยุดที่ก่อนช่องว่าง
        # ต้องดูรายการจริงในคิวของ address นี้
        queued = queued_nonces(acct.address, args.rpc)
        if args.nonce not in queued:
            raise SystemExit(f"  ❌ nonce {args.nonce} ไม่มีในคิวของ address นี้ "
                             f"(ในคิวตอนนี้: {sorted(queued) or 'ว่าง'})")
        print(f"\n  ยกเลิกใบที่ nonce {args.nonce} ด้วยการยิงทับ")
        return build_and_send(args, acct, args.rpc, acct.address, 0, None,
                              args.nonce, args.gas_price, 21000)

    value_wei = int(float(args.amount) * 10**18)
    gas = args.gas
    if gas is None:
        call = {"from": acct.address, "to": args.to, "value": hex(value_wei)}
        if args.data:
            call["data"] = args.data
        gas = int(int(rpc("eth_estimateGas", [call], args.rpc), 16) * 1.2)
        print(f"  ประเมิน gas ได้ {gas:,} (เผื่อ 20% แล้ว)")

    return build_and_send(args, acct, args.rpc, args.to, value_wei, args.data,
                          args.nonce if args.nonce is not None else pending,
                          args.gas_price, gas)


if __name__ == "__main__":
    sys.exit(main())
