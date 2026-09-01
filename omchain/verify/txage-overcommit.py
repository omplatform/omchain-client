#!/usr/bin/env python3
"""T-092 — พิสูจน์ว่าทำไม address ที่เงินไม่พอ ถึงยัด tx เข้าคิวได้เกินตัว

คำถาม: "ไม่มีเงินแล้วเอาเข้า pending ได้เหรอ"
คำตอบจากโค้ด (MainnetTransactionValidator.validateForSender):
    upfrontCost.compareTo(senderBalance) > 0   →  ปฏิเสธ
  เทียบ **ทีละใบ** กับยอดเงิน **ที่ยืนยันแล้ว** ของ address
  ไม่ได้บวกสะสมว่าใบที่ค้างอยู่ในคิวแล้วจะกินเงินไปเท่าไหร่
→ address ที่มีเงินพอ 3 ใบ ยัดเข้าคิวได้ 10 ใบสบาย ๆ พอ 3 ใบแรกถูก mine เงินก็หมด
  ที่เหลือกลายเป็นใบที่จ่ายไม่ไหว และ Besu ไม่ลบให้ (geth ลบทุก block)

รันบน trx40: sudo /home/ubuntu/venv-t092/bin/python txage-overcommit.py
"""
# ค่าที่เป็นความลับอ่านจาก environment ไม่ฝังในไฟล์
#   export OMCHAIN_ECW_KEY=...        (AUTH_SECRET ของ ecw)
#   export OMCHAIN_ECW_DB_PASSWORD=...

import json
import os
import subprocess
import time
import urllib.request

from eth_account import Account

RPC = "http://127.0.0.1:49544"
CHAIN_ID = 1246
GAS_PRICE = 500 * 10**9
GAS = 21000
COST_PER_TX = GAS * GAS_PRICE            # 0.0105 ETH ต่อใบ
N_TX = 10
AFFORDABLE = 3                            # เติมเงินให้พอจ่ายแค่ 3 ใบ


def rpc(m, p=None, to=30):
    r = urllib.request.Request(RPC, json.dumps(
        {"jsonrpc": "2.0", "method": m, "params": p or [], "id": 1}).encode(),
        {"Content-Type": "application/json"})
    body = json.load(urllib.request.urlopen(r, timeout=to))
    if "error" in body:
        raise RuntimeError(body["error"].get("message", body["error"]))
    return body["result"]


def ecw(m, p):
    r = urllib.request.Request("http://127.0.0.1:49080/api/rpc", json.dumps(
        {"jsonrpc": "2.0", "method": m, "params": p, "id": 1}).encode(),
        {"Content-Type": "application/json",
         "X-API-KEY": os.environ["OMCHAIN_ECW_KEY"]})
    return json.load(urllib.request.urlopen(r, timeout=60))


def wallets():
    out = subprocess.run(
        ["docker", "exec", "qtrial-ecw-mysql", "mysql", "-uecw_om", "-p" + os.environ["OMCHAIN_ECW_DB_PASSWORD"],
         "ecw_om", "-N", "-e", "select address from wallet_account order by id limit 25"],
        capture_output=True, text=True).stdout.split()
    return ["0x" + a for a in out]


def balance(addr):
    return int(rpc("eth_getBalance", [addr, "latest"]), 16)


def eth(wei):
    return wei / 1e18


rich = sorted(wallets(), key=lambda w: -balance(w))[0]
acct = Account.create("overcommit")
budget = COST_PER_TX * AFFORDABLE + COST_PER_TX // 2      # พอจ่าย 3 ใบ ไม่ถึง 4

print("=== เติมเงินให้พอจ่ายแค่ 3 ใบ แล้วยิง 10 ใบ ===")
print(f"  address {acct.address}")
print(f"  ค่า gas ต่อใบ {eth(COST_PER_TX):.4f} · เติมให้ {eth(budget):.4f} (พอ {AFFORDABLE} ใบ)")

res = ecw("wallet_transfer", [{"from": rich, "to": acct.address,
                               "amount": f"{eth(budget):.6f}"}])
if "error" in res:
    raise SystemExit(f"เติมเงินไม่สำเร็จ: {res['error']}")
for _ in range(60):
    time.sleep(1)
    if balance(acct.address) > 0:
        break
print(f"  ยอดจริงที่ได้ {eth(balance(acct.address)):.4f}\n")

print("  ยิง 10 ใบ nonce เรียงกัน 0..9 (ทุกใบ nonce ถูกต้อง ไม่มีช่องว่าง)")
accepted = []
for nonce in range(N_TX):
    tx = {"nonce": nonce, "to": acct.address, "value": 0,
          "gas": GAS, "gasPrice": GAS_PRICE, "chainId": CHAIN_ID}
    raw = Account.sign_transaction(tx, acct.key).raw_transaction
    try:
        h = rpc("eth_sendRawTransaction", ["0x" + raw.hex().removeprefix("0x")])
        accepted.append(h)
        print(f"    nonce {nonce}: ✅ รับเข้าคิว")
    except Exception as e:
        print(f"    nonce {nonce}: ❌ {e}")

print(f"\n  รับเข้าคิว {len(accepted)}/{N_TX} ใบ · "
      f"เงินพอจ่ายจริงแค่ {AFFORDABLE} ใบ → เกินตัว {len(accepted)-AFFORDABLE} ใบ")

print("\n  รอ 60 วิ ดูว่าใบไหน mine ได้บ้าง")
time.sleep(60)
mined = [h for h in accepted if rpc("eth_getTransactionReceipt", [h])]
print(f"    mine ได้ {len(mined)} ใบ · เหลือค้าง {len(accepted)-len(mined)} ใบ")
print(f"    ยอดคงเหลือ {eth(balance(acct.address)):.6f} · "
      f"ค่า gas ใบถัดไปต้องใช้ {eth(COST_PER_TX):.6f}")
print(f"    nonce จริงตอนนี้ = {int(rpc('eth_getTransactionCount',[acct.address,'latest']),16)}")

st = rpc("txpool_content")["pending"].get(acct.address.lower(), {})
print(f"    ยังอยู่ในคิว {len(st)} ใบ nonce {sorted(int(k) for k in st)}")

print("\n  รออีก 5 นาทีเต็ม (นานกว่า --tx-pool-max-future-age-seconds=300) ว่าหายเองไหม")
for i in range(7):
    time.sleep(50)
    st = rpc("txpool_content")["pending"].get(acct.address.lower(), {})
    print(f"    {(i+1)*50:4d}s  ค้าง {len(st)} ใบ")
    if not st:
        break
if st:
    print("    ❌ ไม่หายเอง — ยืนยันว่า Besu ไม่ลบใบที่เจ้าของจ่าย gas ไม่ไหว")

print("\n  เติมเงินให้ แล้วดูว่าเคลียร์ไหม (เก็บกวาดคิวด้วยในตัว)")
ecw("wallet_transfer", [{"from": rich, "to": acct.address, "amount": "1"}])
for i in range(12):
    time.sleep(10)
    st = rpc("txpool_content")["pending"].get(acct.address.lower(), {})
    if not st:
        print(f"    ✅ เคลียร์หมดใน {(i+1)*10} วิ หลังเจ้าของมีเงิน")
        break
else:
    print(f"    ⚠️ ยังเหลือ {len(st)} ใบ")
