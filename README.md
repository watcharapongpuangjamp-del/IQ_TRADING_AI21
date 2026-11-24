# IQ Trading AI (Android Client)

แอปพลิเคชันมือถือสำหรับวิเคราะห์ตลาดและช่วยจัดการคำสั่งซื้อขายแบบอัตโนมัติ (Hybrid Architecture) พัฒนาด้วย **Kotlin + Jetpack Compose + Material 3** รองรับทั้งโหมด **Simulation ในตัว** และการเชื่อมต่อ **Python FastAPI Backend** ภายนอก

---

## ความสามารถหลัก (Core Features)

- **Real-Time Trading Terminal**: กราฟแท่งเทียนแบบ Interactive Canvas (ซูมและเลื่อนดูย้อนหลังได้) พร้อมเส้น **EMA Fast/Slow**, **Bollinger Bands** และแผงตัวชี้วัดล่าง **RSI / MACD Histogram**
- **AI Signal Intelligence**: ประมวลผลสัญญาณ **CALL / PUT / NO_TRADE** จากหลายหลักฐานทางเทคนิค (Price Action, Candlestick Patterns, RSI, MACD, EMA, Bollinger Bands, ATR, Support/Resistance) พร้อมแยกแสดง **Model Confidence** ออกจาก **Calibrated Win Probability**
- **Independent Risk Management Engine**: ด่านตรวจสอบความเสี่ยงอิสระก่อนส่งคำสั่งทุกครั้ง (Max Trade Amount, Max Daily Loss, Max Consecutive Losses, Max Daily Trades, Max Portfolio Exposure, Loss Cooldown และปุ่ม **KILL STOP** ฉุกเฉิน)
- **Daily P&L Dashboard & Trade Journal**: สรุปกำไร/ขาดทุนรายวัน, Win Rate, Max Drawdown, กราฟแท่ง 7 วัน และประวัติออเดอร์พร้อมคำอธิบายสัญญาณ
- **Telegram Bot Alerts**: แจ้งเตือนสัญญาณ ผลคำสั่งซื้อขาย และการตัดขาดทุนเข้า Telegram แบบเรียลไทม์
- **Cloud Backup & Auth**: ยืนยันตัวตนด้วย **Google Sign-In (Jetpack Credential Manager)** และสำรองกลยุทธ์ นโยบายความเสี่ยง ประวัติเทรด และสรุปผลรายวันขึ้น **Cloud Firestore** แยกสิทธิ์เฉพาะเจ้าของบัญชี
- **Hardware-Backed Security**: เข้ารหัส Telegram Bot Token และ FastAPI Bearer Token ด้วย **Android Keystore (AES-256-GCM)** โดยไม่เก็บรหัสผ่านโบรกเกอร์บนอุปกรณ์ และล็อกโหมด **LIVE** จนกว่า Backend Connector จะผ่านการตรวจสอบ

---

## การ Build APK ผ่าน GitHub Actions

โปรเจกต์นี้มี Workflow ที่ `.github/workflows/build-apk.yml` ซึ่งจะทำงานอัตโนมัติเมื่อ Push, Pull Request หรือกดสั่งรันจากแท็บ **Actions** (`workflow_dispatch`):

1. **Debug APK**: รัน Unit Tests (`TradingEnginesUnitTest`) และสร้างไฟล์ `app-debug.apk` อัปโหลดเป็น Artifact ชื่อ `iq-trading-ai-debug-apk` โดยอัตโนมัติ
2. **Signed Release APK (ไม่บังคับ)**: หากต้องการสร้าง Release APK ให้เพิ่ม GitHub Repository Secrets ต่อไปนี้:
   - `RELEASE_KEYSTORE_BASE64`: ไฟล์ Keystore ที่เข้ารหัส Base64
   - `STORE_PASSWORD`: รหัสผ่าน Keystore
   - `KEY_PASSWORD`: รหัสผ่าน Key Alias (`upload`)

---

## Hybrid FastAPI Backend Contract

เมื่อเชื่อมต่อ Python FastAPI Backend ภายนอกผ่านหน้า **Connect** แอปจะสื่อสารตาม Endpoint ดังนี้:

- `GET /api/v1/health` — ตรวจสอบสถานะ Connector, รายการ Capabilities และสิทธิ์ `live_authorized`
- `GET /api/v1/candles?instrument=EUR/USD&timeframe=1m&limit=80` — ดึงข้อมูลแท่งเทียน OHLCV
- `POST /api/v1/orders` — ส่งคำสั่งซื้อขายพร้อม `idempotency_key` ป้องกันคำสั่งซ้ำ

> **หมายเหตุด้านความปลอดภัย**: ระบบเริ่มต้นที่โหมด `SIMULATION` เสมอ ห้ามเปิดใช้งานโหมด `LIVE` จนกว่าจะทดสอบ Broker Connector ฝั่งเซิร์ฟเวอร์และยืนยันขีดจำกัดความเสี่ยงครบถ้วนแล้ว
