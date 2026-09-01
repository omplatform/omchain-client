#!/usr/bin/env python3
"""เทียบ error ที่ geth กับ Besu ตอบ ในเคสที่ ecw/wallet ต้องจับจริง

ทำไมต้องเทียบ: ecw เขียนขึ้นสมัยหลังบ้านเป็น geth ถ้ามันจับด้วย code หรือข้อความของ geth
พอย้ายมา Besu แล้ว **ทั้ง code และข้อความเปลี่ยน** การจับจะพลาดเงียบ ๆ
(เห็นหลักฐานแล้ว: ecw log ขึ้น err_class = "unknown")

วิธี: เปิด geth --dev ชั่วคราวในเครื่อง เติมเงินให้ address ทดสอบด้วย account ของ dev
      แล้วยิงเคสเดียวกันใส่ทั้ง geth และ Besu เทียบผลตรง ๆ

รันบน trx40: sudo /home/ubuntu/venv-t092/bin/python geth-vs-besu-errors.py
"""
# ค่าที่เป็นความลับอ่านจาก environment ไม่ฝังในไฟล์
#   export OMCHAIN_ECW_KEY=...        (AUTH_SECRET ของ ecw)
#   export OMCHAIN_ECW_DB_PASSWORD=...

import json
import os
import subprocess
import time
import urllib.request

import _env

from eth_account import Account

GETH = "http://127.0.0.1:48999"
BESU = "http://127.0.0.1:49544"
BESU_CHAIN_ID = 1246
BESU_MIN_GWEI = 500
ECW, ECW_KEY = 49080, _env.need("OMCHAIN_ECW_KEY", "AUTH_SECRET ของ ecw")


def rpc(url, method, params, timeout=30):
    r = urllib.request.Request(url, json.dumps(
        {"jsonrpc": "2.0", "method": method, "params": params, "id": 1}).encode(),
        {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(r, timeout=timeout))


def err_of(body):
    if "error" not in body:
        return "รับเข้าคิว (ไม่ error)"
    e = body["error"]
    return f"{e.get('code')} · {str(e.get('message'))[:60]}"


def signed(acct, chain_id, nonce, gwei, value_eth=0.0, gas=21000):
    tx = {"nonce": nonce, "to": acct.address, "value": int(value_eth * 10**18),
          "gas": gas, "gasPrice": int(gwei * 10**9), "chainId": chain_id}
    return "0x" + Account.sign_transaction(tx, acct.key).raw_transaction.hex().removeprefix("0x")


def start_geth():
    subprocess.run(["docker", "rm", "-f", "err-geth"], capture_output=True)
    subprocess.run([
        "docker", "run", "-d", "--name", "err-geth", "-p", "127.0.0.1:48999:8545",
        "ethereum/client-go:v1.13.13", "--dev", "--http", "--http.addr", "0.0.0.0",
        "--http.api", "eth,net,web3,txpool,personal", "--http.vhosts", "*",
        "--nodiscover", "--dev.period", "1"], capture_output=True)
    for _ in range(90):
        time.sleep(1)
        try:
            rpc(GETH, "eth_blockNumber", [])
            return int(rpc(GETH, "eth_chainId", [])["result"], 16)
        except Exception:
            pass
    raise SystemExit("geth ไม่ขึ้น")


def fund_on_geth(address, amount_eth=5):
    dev = rpc(GETH, "eth_accounts", [])["result"][0]
    rpc(GETH, "eth_sendTransaction",
        [{"from": dev, "to": address, "value": hex(int(amount_eth * 10**18))}])
    for _ in range(60):
        time.sleep(1)
        if int(rpc(GETH, "eth_getBalance", [address, "latest"])["result"], 16) > 0:
            return
    raise SystemExit("เติมเงินบน geth ไม่สำเร็จ")


def fund_on_besu(address, amount_eth=5):
    out = subprocess.run(
        ["docker", "exec", "qtrial-ecw-mysql", "mysql", "-uecw_om", "-p" + _env.need("OMCHAIN_ECW_DB_PASSWORD", "รหัสผ่าน MySQL ของ ecw"),
         "ecw_om", "-N", "-e", "select address from wallet_account order by id limit 25"],
        capture_output=True, text=True).stdout.split()
    rich = max(("0x" + a for a in out),
               key=lambda w: int(rpc(BESU, "eth_getBalance", [w, "latest"])["result"], 16))
    req = urllib.request.Request(
        f"http://127.0.0.1:{ECW}/api/rpc",
        json.dumps({"jsonrpc": "2.0", "method": "wallet_transfer",
                    "params": [{"from": rich, "to": address, "amount": str(amount_eth)}],
                    "id": 1}).encode(),
        {"Content-Type": "application/json", "X-API-KEY": ECW_KEY})
    json.load(urllib.request.urlopen(req, timeout=60))
    for _ in range(60):
        time.sleep(1)
        if int(rpc(BESU, "eth_getBalance", [address, "latest"])["result"], 16) > 0:
            return
    raise SystemExit("เติมเงินบน Besu ไม่สำเร็จ")


def probe(url, chain_id, min_gwei, fund):
    """คืน dict เคส → ข้อความ error ที่ client ตัวนั้นตอบ"""
    out = {}
    poor = Account.create("cmp-poor")
    out["เงินไม่พอ"] = err_of(rpc(url, "eth_sendRawTransaction",
                                  [signed(poor, chain_id, 0, min_gwei, value_eth=1000)]))

    rich = Account.create("cmp-rich")
    fund(rich.address)
    n = int(rpc(url, "eth_getTransactionCount", [rich.address, "pending"])["result"], 16)

    first = signed(rich, chain_id, n, min_gwei)
    rpc(url, "eth_sendRawTransaction", [first])
    out["ส่งใบเดิมซ้ำ"] = err_of(rpc(url, "eth_sendRawTransaction", [first]))
    out["แทนที่ด้วยราคาเท่าเดิม"] = err_of(
        rpc(url, "eth_sendRawTransaction", [signed(rich, chain_id, n, min_gwei, gas=21001)]))

    time.sleep(4)
    done = int(rpc(url, "eth_getTransactionCount", [rich.address, "latest"])["result"], 16)
    out["nonce ต่ำเกินไป"] = err_of(rpc(url, "eth_sendRawTransaction",
                                        [signed(rich, chain_id, max(0, done - 1), min_gwei)]))
    out["gas price ต่ำกว่าที่รับ"] = err_of(rpc(url, "eth_sendRawTransaction",
                                                [signed(rich, chain_id, done, 0.001)]))
    out["gas น้อยกว่าขั้นต่ำ"] = err_of(rpc(url, "eth_sendRawTransaction",
                                            [signed(rich, chain_id, done, min_gwei, gas=1000)]))
    return out


print("=== เปิด geth --dev ชั่วคราว (รุ่นเดียวกับ prod: v1.13.13) ===")
cid = start_geth()
print(f"  geth chainId {cid}")

g = probe(GETH, cid, 1, fund_on_geth)
b = probe(BESU, BESU_CHAIN_ID, BESU_MIN_GWEI, fund_on_besu)

print(f"\n  {'เคส':<26} {'geth v1.13.13 (prod วันนี้)':<66} Besu 26.8.0")
print("  " + "-" * 132)
for k in g:
    print(f"  {k:<26} {g[k]:<66} {b.get(k, '-')}")

subprocess.run(["docker", "rm", "-f", "err-geth"], capture_output=True)
print("\n  ลบ geth ชั่วคราวแล้ว")
