#!/usr/bin/env python3
"""อ่านค่าลับจากไฟล์ .env แทนที่จะฝังไว้ในโค้ด

วางไฟล์ `.env` ไว้ที่รากของ repo (ถูก .gitignore ไว้แล้ว) หน้าตาแบบนี้

    OMCHAIN_ECW_KEY=...
    OMCHAIN_ECW_DB_PASSWORD=...

ดูชื่อตัวแปรทั้งหมดได้ที่ `omchain/verify/.env.example`

ทำไมไม่ให้ Makefile โหลดเอง: ค่าจริงมีตัว `#` อยู่ข้างใน ซึ่ง make จะตัดทิ้งเป็น comment
ตัวนี้ตัด comment เฉพาะบรรทัดที่ขึ้นต้นด้วย `#` เท่านั้น ค่าจึงไม่โดนตัด
"""
import os
from pathlib import Path


def load(path=None):
    """ยัดค่าใน .env เข้า os.environ (ไม่ทับของที่ตั้งไว้แล้วจาก shell)"""
    if path is None:
        here = Path(__file__).resolve()
        for candidate in (here.parent / ".env", here.parents[2] / ".env"):
            if candidate.exists():
                path = candidate
                break
        else:
            return
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def need(name, what):
    """อ่านค่าที่ขาดไม่ได้ แล้วบอกให้ชัดถ้าไม่มี แทนที่จะโยน KeyError เปล่า ๆ"""
    load()
    value = os.environ.get(name)
    if not value:
        raise SystemExit(
            f"  ❌ ไม่มีค่า {name} ({what})\n"
            f"     ตั้งใน .env ที่รากของ repo หรือ export ก่อนรัน\n"
            f"     ดูตัวอย่างที่ omchain/verify/.env.example")
    return value
