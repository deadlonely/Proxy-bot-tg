# 🌋 Nexora - Telegram Proxy Bot

Premium SOCKS5 proxy service bot with QR-code generation, advanced checking, and complete user analytics.

## ✨ Key Features

- 📱 **QR-Code Generation** - Scan to connect instantly (5-min lifetime)
- 🌍 **Regional Organization** - Proxies grouped by region with country flags
- ⏱️ **Auto-Expiring QR** - 1-minute warning before expiration
- 📊 **User Analytics** - Track proxy views, QR requests, connections globally and per-user
- 🔔 **Smart Monitoring** - Speed drop alerts & availability notifications  
- 🚀 **Quick Connect** - One-click links and buttons
- 🔐 **Admin Dashboard** - `/stats` for bot analytics, `/mystats` for users
- 🌐 **Bilingual** - Full English and Russian support

## 📋 Project Structure

```
.
├── index.js                 # Main bot (Telegraf)
├── package.json            # NPM dependencies
├── .env                    # Configuration (gitignored)
├── proxies.txt             # region|type|proxy format
├── checks.json             # Proxy check history
├── alerts.json             # Active monitoring alerts
├── utils/
│   └── stats.js           # User statistics module
├── db/
│   └── users.json         # User data persistence
└── README.md              # This file
```

## 🚀 Installation

### Prerequisites
- Node.js v16+ 
- npm/yarn
- Telegram Bot Token from [@BotFather](https://t.me/BotFather)

### Steps

```bash
# Install dependencies
npm install

# Copy and edit .env
cp .env .env.local
nano .env.local  # Add TELEGRAM_BOT_TOKEN and ADMIN_ID
```

### .env Configuration

```bash
# Required
TELEGRAM_BOT_TOKEN=your_bot_token_here

# Recommended
ADMIN_ID=your_telegram_id_for_admin_commands

# Optional (timings in milliseconds)
QR_LIFETIME_MS=300000                 # Default: 5 min
MONITOR_INTERVAL_MS=300000            # Default: 5 min  
SPEED_DROP_THRESHOLD_PERCENT=50       # Default: 50%

# Optional (monitoring)
MONITOR_CHAT_ID=your_chat_id
MONITOR_START_ON_LAUNCH=0
```

## ▶️ Running

**Direct startup:**
```bash
npm start
```

**With token in PowerShell:**
```powershell
$env:TELEGRAM_BOT_TOKEN="your_token"; npm start
```

**With .env file:**
```bash
# Edit .env first, then:
npm start
```

## 📱 QR-Code System

### User Flow
1. Select region → pick proxy
2. Click **📱 QR** button
3. QR-code generated (expires in 5 minutes)
4. Scan in Telegram: Settings → Data & Storage → Proxy → Scan QR
5. Bot notifies 1 minute before expiry

### Customization
Leave `QR_LIFETIME_MS` in .env to change expiration time.

## 📊 User Statistics

### Admin Commands (requires ADMIN_ID set)
```bash
/stats           # Global bot stats
```

### User Commands
```bash
/mystats         # Your personal stats
/start           # Initialize/restart
```

### Tracked Metrics
| Metric | Description |
|--------|-------------|
| Total Users | Account creation count |
| Proxies Viewed | Region proxy list clicks |
| QR Requests | QR button clicks per user |
| Connect Clicks | Connection button clicks |
| Active Today | Last activity in 24hrs |
| Language Split | EN vs RU preferences |

### Data Storage
Users automatically saved to `db/users.json`:
```json
{
  "123456789": {
    "id": 123456789,
    "joinedAt": "2026-03-03T14:30:00Z",
    "proxiesViewed": 5,
    "qrRequests": 3,
    "connectClicks": 2,
    "lastActivity": "2026-03-03T14:45:00Z",
    "lang": "ru"
  }
}
```

## 🔍 Proxy Monitoring

### What's Checked
- TCP connection (ping)
- Download speed (64KB file)
- Anonymity detection
- Availability tracking

### Alerts Sent
- ⚠️ Proxy down
- ✅ Proxy recovered
- 🐢 Speed drop > 50%
- ⚡ Speed recovered

### Monitor Commands
```bash
/monitor_start   # Begin checking (requires MONITOR_CHAT_ID)
/monitor_stop    # Stop checking
```

## 📄 Proxy File Format

Edit `proxies.txt` with one proxy per line:

```
region|type|proxy
nl|SOCKS5|user:password@188.227.220.178:1080
us|SOCKS5|user:password@proxy.example.com:1081
ru|SOCKS5|user:password@1.2.3.4:1082
```

**Fields:**
- `region` → 2-letter code (nl, us, ru, etc.) - shown with 🌍 flag
- `type` → SOCKS5, HTTP, etc.
- `proxy` → `[user:password@]host:port`

Comments start with `#`:
```
# Example proxies
nl|SOCKS5|user:pass@host:1080
# This line is ignored
```

## 🔐 Security

1. **Never commit .env**
   ```bash
   cat .gitignore  # Should include .env
   ```

2. **Rotate token if exposed**
   - Use BotFather to regenerate
   - Update .env immediately

3. **Admin-only access**
   - Set your Telegram ID in `ADMIN_ID`
   - Only you can run `/stats`

4. **HTTPS everywhere**
   - All external API calls use HTTPS
   - Proxy creds transmitted securely

## 📦 Dependencies

```json
{
  "telegraf": "^4.12.2",           // Telegram API
  "axios": "^1.4.0",               // HTTP client
  "qrcode": "^1.5.4",              // QR generation
  "socks-proxy-agent": "^7.0.0",   // SOCKS5 support
  "https-proxy-agent": "^7.0.0",   // HTTPS proxy
  "dotenv": "^16.3.1"              // Config loading
}
```

## 🌐 Usage Flow

```
/start
  ↓
[Language: EN/RU]
  ↓
[Main Menu: Proxies | QR Help]
  ↓
[Select Region] 🇳🇱 NL (3)
  ↓
[Proxy List]
├─ #1 [SOCKS5] ✅ Active
│  ├─ 📱 QR (expires 5 min)
│  └─ 🔗 Connect (port 1080)
└─ #2 [SOCKS5] ✅ Active
   ├─ 📱 QR
   └─ 🔗 Connect (port 1081)
```

## 📈 Monitoring Loop

```
Every 5 minutes:
  ↓
[Check each proxy]
  ├─ Ping
  ├─ Speed
  └─ IP/Anonymity
  ↓
[Compare with previous check]
  ↓
[Alert if changed]
  ├─ Speed drop > 50% → 🐢 Alert
  ├─ Proxy down → ⚠️ Alert  
  └─ Proxy recovered → ✅ Alert
  ↓
[Save to checks.json + alerts.json]
```

## 🐛 Troubleshooting

| Problem | Solution |
|---------|----------|
| Bot won't start | Check `TELEGRAM_BOT_TOKEN` in .env |
| Can't generate QR | Verify proxy format, test connectivity |
| Stats not saving | Check `db/` folder permissions, restart |
| `/stats` shows "Access denied" | Set `ADMIN_ID=your_id_number` in .env |
| No monitor alerts | Set `MONITOR_CHAT_ID` in .env |

## 🎯 Admin Checklist

- [ ] Add `ADMIN_ID` to .env (your Telegram ID, not username)
- [ ] Test `/stats` command
- [ ] Add proxies to `proxies.txt`
- [ ] Set `QR_LIFETIME_MS` if needed
- [ ] Configure `MONITOR_CHAT_ID` for alerts
- [ ] Backup `db/users.json` regularly

## 📊 Performance

| Metric | Typical |
|--------|---------|
| QR Generation | < 100ms |
| Single Proxy Check | 2-5s |
| Monitor Pass (10 proxies) | 5-10s |
| Memory Usage | 50-100MB |
| User Data Size | ~100KB per 100 users |

## 🔗 API Endpoints

- `httpbin.org/ip` - IP detection
- `httpbin.org/stream-bytes/65536` - Speed test
- `ip-api.com/json/{ip}` - Country lookup (cached)

---

**Questions?** Check logs and make sure `.env` is properly configured.

**Channel:** [@nexoraproxy](https://t.me/nexoraproxy)
