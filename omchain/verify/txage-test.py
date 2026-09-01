#!/usr/bin/env python3
"""T-092 — ทดสอบจริงว่า tx ที่ mine ไม่ได้ (nonce ขาดช่วง) ถูกไล่ออกตามเวลา

ที่มา: prod ฝั่ง geth ตั้ง --txpool.lifetime 0h5m0s อยู่แล้ว
       Besu ไม่มีกลไกนี้ → patch เพิ่ม --tx-pool-max-future-age-seconds (default 300)
       สคริปต์นี้ตั้ง 60 วิเพื่อให้ทดสอบไม่ต้องรอนาน

ต้องรันบน trx40 ด้วย /home/ubuntu/venv-t092/bin/python (ต้องมี eth-account)

  txage-test.py evict     ยิง tx ที่ nonce โดด → ต้องหายเองใน ~60 วิ แล้ว address ใช้ต่อได้
  txage-test.py restart   ยิง tx ที่ nonce โดด → restart node → ดูว่าเกิดอะไรขึ้น
"""
# ค่าที่เป็นความลับอ่านจาก environment ไม่ฝังในไฟล์
#   export OMCHAIN_ECW_KEY=...        (AUTH_SECRET ของ ecw)
#   export OMCHAIN_ECW_DB_PASSWORD=...

import json
import os
import subprocess
import sys
import time
import urllib.request

import _env

from eth_account import Account

RPC = "http://127.0.0.1:49544"          # qtrial-qrpc
SIGNER_RPC = "http://127.0.0.1:49541"   # qtrial-qa
CHAIN_ID = 1246
GAS_PRICE = 500 * 10**9                 # นโยบายเชน: ต่ำกว่า 500 gwei ไม่รับ
ECW, ECW_KEY = 49080, _env.need("OMCHAIN_ECW_KEY", "AUTH_SECRET ของ ecw")


def rpc(method, params=None, url=RPC, timeout=20):
    req = urllib.request.Request(
        url,
        json.dumps({"jsonrpc": "2.0", "method": method, "params": params or [], "id": 1}).encode(),
        {"Content-Type": "application/json"},
    )
    body = json.load(urllib.request.urlopen(req, timeout=timeout))
    if "error" in body:
        raise RuntimeError(f"{method}: {body['error']}")
    return body["result"]


def ecw(method, params):
    req = urllib.request.Request(
        f"http://127.0.0.1:{ECW}/api/rpc",
        json.dumps({"jsonrpc": "2.0", "method": method, "params": params, "id": 1}).encode(),
        {"Content-Type": "application/json", "X-API-KEY": ECW_KEY},
    )
    return json.load(urllib.request.urlopen(req, timeout=30))


def funded_ecw_wallet():
    """หา wallet ใน ecw ที่มีเงินพอจ่าย gas + ยอดโอน"""
    out = subprocess.run(
        ["docker", "exec", "qtrial-ecw-mysql", "mysql", "-uecw_om", "-p" + _env.need("OMCHAIN_ECW_DB_PASSWORD", "รหัสผ่าน MySQL ของ ecw"),
         "ecw_om", "-N", "-e", "select address from wallet_account order by id limit 20"],
        capture_output=True, text=True).stdout.split()
    for a in out:
        w = "0x" + a
        if int(rpc("eth_getBalance", [w, "latest"]), 16) > 5 * 10**18:
            return w
    raise SystemExit("ไม่พบ wallet ที่มีเงินพอใน ecw")


def fund(address, amount_eth="1"):
    src = funded_ecw_wallet()
    res = ecw("wallet_transfer", [{"from": src, "to": address, "amount": amount_eth}])
    if "error" in res:
        raise SystemExit(f"เติมเงินไม่สำเร็จ: {res['error']}")
    print(f"  เติม {amount_eth} จาก {src[:10]}… → {address[:10]}…")
    for _ in range(60):
        time.sleep(1)
        if int(rpc("eth_getBalance", [address, "latest"]), 16) > 0:
            return
    raise SystemExit("เติมเงินแล้วแต่ยอดยังไม่เข้า")


def send_raw(acct, nonce, to=None):
    tx = {"nonce": nonce, "to": to or acct.address, "value": 10**15,
          "gas": 21000, "gasPrice": GAS_PRICE, "chainId": CHAIN_ID}
    signed = Account.sign_transaction(tx, acct.key)
    return rpc("eth_sendRawTransaction", ["0x" + signed.raw_transaction.hex().removeprefix("0x")])


def in_pool(txhash):
    return rpc("eth_getTransactionByHash", [txhash]) is not None


def mined(txhash):
    return rpc("eth_getTransactionReceipt", [txhash]) is not None


def pool_stats():
    s = rpc("txpool_besuStatistics")
    return f"pool={s.get('maxSize','?')} local={s.get('localCount')} remote={s.get('remoteCount')}"


def wait_gone(txhash, limit=180):
    t0 = time.time()
    while time.time() - t0 < limit:
        if not in_pool(txhash):
            return time.time() - t0
        time.sleep(2)
    return None


def new_account(tag):
    acct = Account.create(tag)
    print(f"  address ใหม่ {acct.address}")
    return acct


def cmd_evict():
    print("=== ทดสอบ 1: tx ที่ nonce โดด ต้องหายเองตามเวลา ===")
    acct = new_account("txage-evict")
    fund(acct.address)
    base = rpc("eth_getTransactionCount", [acct.address, "latest"])
    base = int(base, 16)
    print(f"  nonce ปัจจุบันของ address = {base}")

    print("\n--- ยิง tx nonce ที่โดดไป 5 (mine ไม่ได้แน่นอน) ---")
    stuck = send_raw(acct, base + 5)
    time.sleep(3)
    print(f"  hash {stuck}")
    print(f"  อยู่ในคิว: {in_pool(stuck)}  ({pool_stats()})")
    assert in_pool(stuck), "tx ควรอยู่ในคิวตอนนี้"

    print("\n--- รอให้ครบอายุ (ตั้งไว้ 60 วิ) ---")
    gone_after = wait_gone(stuck)
    if gone_after is None:
        print("  ❌ ยังอยู่ในคิวเกิน 180 วิ — patch ไม่ทำงาน")
        return 1
    print(f"  ✅ หายจากคิวใน {gone_after:.0f} วิ · ไม่ถูก mine: {not mined(stuck)}")

    print("\n--- address ต้องกลับมาใช้งานได้ทันที ---")
    good = send_raw(acct, base)
    for _ in range(30):
        time.sleep(1)
        if mined(good):
            break
    ok = mined(good)
    print(f"  tx nonce {base}: {'✅ ยืนยันแล้ว' if ok else '❌ ยังไม่ยืนยัน'}  {good}")
    return 0 if ok else 1


def cmd_no_collateral():
    print("=== ทดสอบ 2: tx ที่ mine ได้ ต้องไม่โดนลูกหลง ===")
    acct = new_account("txage-mixed")
    fund(acct.address)
    base = int(rpc("eth_getTransactionCount", [acct.address, "latest"]), 16)

    executable = send_raw(acct, base)          # ใบนี้ mine ได้เลย
    time.sleep(1)
    stuck = send_raw(acct, base + 9)           # ใบนี้โดด
    print(f"  mine ได้ {executable}\n  โดด     {stuck}")

    for _ in range(30):
        time.sleep(1)
        if mined(executable):
            break
    print(f"  ใบที่ mine ได้: {'✅ ยืนยันแล้ว' if mined(executable) else '❌ ค้าง'}")

    gone_after = wait_gone(stuck)
    print(f"  ใบที่โดด: {'✅ หายใน %.0f วิ' % gone_after if gone_after else '❌ ยังค้าง'}")
    return 0 if mined(executable) and gone_after else 1


def cmd_restart():
    print("=== ทดสอบ 3: RPC ดับแล้วกลับมา เกิดอะไรกับ tx ที่ค้าง ===")
    acct = new_account("txage-restart")
    fund(acct.address)
    base = int(rpc("eth_getTransactionCount", [acct.address, "latest"]), 16)

    stuck = send_raw(acct, base + 5)
    time.sleep(3)
    print(f"  ยิง tx ที่ nonce โดด: {stuck}")
    print(f"  ก่อน restart อยู่ในคิว: {in_pool(stuck)}")

    print("\n--- restart qtrial-qrpc (SIGTERM ปกติ) ---")
    t0 = time.time()
    subprocess.run(["docker", "restart", "-t", "120", "qtrial-qrpc"], capture_output=True)
    for _ in range(90):
        time.sleep(2)
        try:
            rpc("eth_blockNumber", timeout=4)
            break
        except Exception:
            pass
    print(f"  node กลับมาใน {time.time()-t0:.0f} วิ · หัวเชน {int(rpc('eth_blockNumber'),16):,}")

    time.sleep(5)
    restored = in_pool(stuck)
    print(f"  หลัง restart ยังอยู่ในคิว: {restored}")
    print("  (--tx-pool-enable-save-restore=true → Besu เซฟคิวลงดิสก์ตอนปิด แล้วโหลดคืน)")

    if restored:
        print("\n--- นาฬิกาอายุเริ่มนับใหม่หรือไม่ ---")
        gone_after = wait_gone(stuck)
        print(f"  หายหลัง restart {gone_after:.0f} วิ" if gone_after else "  ❌ ยังค้างเกิน 180 วิ")
    return 0


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "evict"
    sys.exit({"evict": cmd_evict, "mixed": cmd_no_collateral, "restart": cmd_restart}[cmd]())
