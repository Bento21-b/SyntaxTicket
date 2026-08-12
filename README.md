# Syntax — Discord Ticket Bot

บอท Ticket ภาษา Java (JDA) มี embed + dropdown หัวข้อ **Setting** สร้างห้องส่วนตัวให้ลูกค้าคุยกับทีม และรันบน Railway / Render ได้ตลอดเวลาโดยไม่ต้องเปิดคอม

## สิ่งที่ต้องมีก่อน

1. บัญชี [Discord Developer Portal](https://discord.com/developers/applications)
2. สร้าง Application → Bot → Copy **Token**
3. เปิด Privileged Gateway Intent: **Server Members Intent**
4. Invite บอทเข้าเซิร์ฟด้วยสิทธิ์:
   - Manage Channels
   - View Channels
   - Send Messages
   - Embed Links
   - Use Slash Commands
5. เปิด Developer Mode ใน Discord แล้ว Copy ID ของ Role ทีมงาน / Category ที่อยากใช้

Invite URL ตัวอย่าง (แทน `CLIENT_ID`):

```text
https://discord.com/api/oauth2/authorize?client_id=CLIENT_ID&permissions=268446720&scope=bot%20applications.commands
```

## ตัวแปรสภาพแวดล้อม

| ตัวแปร | จำเป็น | ความหมาย |
|--------|--------|----------|
| `DISCORD_TOKEN` | ใช่ | Token ของบอท |
| `SUPPORT_ROLE_ID` | แนะนำ | Role ที่เห็นทุก Ticket |
| `TICKET_CATEGORY_ID` | แนะนำ | Category สำหรับห้อง ticket |
| `PANEL_TITLE` | ไม่ | หัวข้อ embed |
| `PANEL_DESCRIPTION` | ไม่ | คำอธิบาย embed |
| `PANEL_FOOTER` | ไม่ | Footer |
| `PANEL_IMAGE_URL` | ไม่ | URL รูปใน embed |

## วิธีใช้งานใน Discord

1. หลังบอทออนไลน์ พิมพ์ `/ticket-setup` ในช่อง `#ticket`
2. ลูกค้าเลือก **Setting** จาก dropdown
3. ระบบสร้างห้อง `ticket-...` ให้คุยส่วนตัว
4. กดปุ่ม **ปิด Ticket** หรือใช้ `/ticket-close`

## Deploy บน Railway (แนะนำ)

1. Push โปรเจกต์นี้ขึ้น GitHub
2. เข้า [Railway](https://railway.app) → New Project → Deploy from GitHub
3. เลือก repo `Syntax`
4. Variables ใส่ `DISCORD_TOKEN` (+ `SUPPORT_ROLE_ID`, `TICKET_CATEGORY_ID` ถ้ามี)
5. Deploy — บอทจะออนไลน์ค้างไว้เอง

Railway อ่าน `Dockerfile` + `railway.toml` ให้อัตโนมัติ

## Deploy บน Render

1. Push ขึ้น GitHub
2. เข้า [Render](https://render.com) → New → Background Worker
3. เชื่อม repo แล้วเลือก Docker
4. ใส่ Environment Variables เหมือนด้านบน
5. Deploy

> บนแผนฟรีของ Render worker อาจหลับได้ — ถ้าต้องการเสถียรสุด ใช้ Railway หรือ VPS

## รันบนเครื่องตัวเอง (ถ้าติดตั้ง Java 17 แล้ว)

```bash
# คัดลอก .env.example เป็น .env แล้วใส่ค่า
# แล้ว export ตัวแปร หรือตั้งในระบบ

mvn -DskipTests package
java -jar target/bot.jar
```

หรือด้วย Docker:

```bash
docker build -t syntax-ticket-bot .
docker run --env-file .env syntax-ticket-bot
```

## โครงสร้างโปรเจกต์

```text
Syntax/
├── Dockerfile
├── railway.toml
├── render.yaml
├── pom.xml
└── src/main/java/com/syntax/ticket/
    ├── Bot.java
    ├── config/BotConfig.java
    └── listeners/
        ├── CommandListener.java
        └── TicketListener.java
```
