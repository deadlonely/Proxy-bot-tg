# 🌋 NEXORA Bot — Kotlin/JVM Edition

> High-performance proxy management for Telegram, pure Java runtime

---

## ⚡ Quick Setup (2 Minutes)

### 1️⃣ Set Environment Variables

Windows (PowerShell):
```powershell
$env:BOT_TOKEN = "your_bot_token_here"
$env:CHANNEL_URL = "https://t.me/your_channel"
$env:PROVIDER_NAME = "NEXORA"
$env:ADMIN_ID = "123456789"
```

Or create `.env` file in `kotlin-version/`:
```bash
BOT_TOKEN=your_bot_token_here
CHANNEL_URL=https://t.me/your_channel
PROVIDER_NAME=NEXORA
ADMIN_ID=123456789
```

### 2️⃣ Add Proxies

Create `proxies.txt`:

```
|socks5|user:pass@proxy.host:1080
|socks5|192.168.1.1:9050
|socks4|10.0.0.1:1080
```

### 3️⃣ Compile & Run

Windows (double-click or run in PowerShell):
```batch
run_kotlinc.bat    # Compiles to out.jar
run_java.bat       # Starts the bot
```

**Output:**
```
Bot starting polling...
```

---

## 📦 What's Included

```
kotlin-version/
├── src/main/kotlin/
│   └── Main.kt (600+ lines, full implementation)
├── libs/
│   ├── jackson-core-2.15.2.jar
│   ├── jackson-databind-2.15.2.jar
│   ├── jackson-annotations-2.15.2.jar
│   ├── jackson-module-kotlin-2.15.2.jar
│   ├── kotlin-stdlib-1.9.0.jar
│   └── kotlin-reflect-1.9.0.jar
├── run_kotlinc.bat (build script)
├── run_java.bat (run script)
├── .env (configuration)
├── proxies.txt (proxy list)
├── out.jar (compiled artifact)
├── db/
│   ├── proxy_status.json (auto-created)
│   └── user_stats.json (auto-created)
└── README.md (this file)
```

---

## 🎮 Features (Kotlin/JVM)

### ✨ Core Features
- 🌍 Multi-language (Russian/English)
- 📱 QR code generation (via public API)
- ⚡ Latency testing via TCP socket
- 🗂️ Auto-group by geolocation (ip-api.com)
- 💾 Persistent proxy status cache
- 📊 User analytics tracking
- 🚀 Fast startup (~1 second)

### 🔧 Technologies
- **Kotlin** — Modern JVM language
- **Java HttpClient** — Direct Telegram API calls
- **Jackson** — JSON serialization (with kotlin-module)
- **java.net.Socket** — Proxy testing
- **java.nio.file.Paths** — File I/O

---

## 🎯 Commands & Actions

### Public Commands
```
/start         → Select language & open main menu
/mystats       → View your personal statistics
```

### Main Menu
```
🔗 Channel      → Visit official channel
🗂️ Proxies     → Browse proxies by region
📱 QR Help     → How to use QR connection
```

### Proxy Actions (Per Region)
```
📱 QR #1       → Generate connection QR (public API)
🔗 #1          → Direct Telegram link
🔄 Проверить   → Check latency & status
🔁 Обновить    → Refresh proxy list
⬅️ Back        → Return to region select
```

### Admin Commands
```
/stats         → Admin-only user analytics dashboard
                 (requires ADMIN_ID env var)
```

---

## 📊 Data Persistence

### `db/proxy_status.json`
Auto-saved every 60s. Example:
```json
{
  "user:pass@proxy.host:1080": {
    "alive": true,
    "ms": 145,
    "at": "2026-03-04T14:30:45.123Z"
  }
}
```

### `db/user_stats.json`
Tracks user actions. Example:
```json
{
  "123456789": {
    "actions": {
      "open_proxies": 5,
      "check_US_0": 3,
      "qr_DE_1": 2
    },
    "last": "2026-03-04T14:30:45.123Z"
  }
}
```

---

## 🔧 Environment Variables

| Variable | Required | Example | Notes |
|----------|----------|---------|-------|
| **BOT_TOKEN** | ✅ | `123...ABC` | Get from @BotFather |
| **CHANNEL_URL** | ❌ | `https://t.me/nexora` | Optional channel link |
| **PROVIDER_NAME** | ❌ | `NEXORA` | Default: NEXORA |
| **ADMIN_ID** | ❌ | `123,456,789` | Comma-separated user IDs |

The `.env` file is read from the current directory (kotlin-version/) first, then falls back to parent directory.

---

## 🛠️ Build & Run

### Windows Batch Files

**run_kotlinc.bat** - Compiles Kotlin to JAR
```batch
@echo off
REM Assembles classpath from libs and compiles Main.kt
REM Output: out.jar
```

**run_java.bat** - Runs compiled JAR
```batch
@echo off
REM Reads .env file, sets environment variables
REM Launches bot via Java
```

### Manual Build

```bash
# Compile
kotlinc -cp "libs\*" src/main/kotlin/Main.kt -include-runtime -d out.jar

# Run
java -cp "out.jar;libs\*" MainKt
```

### Alternative: Direct Kotlin

```bash
# Install Kotlin compiler (if not installed)
choco install kotlin  # Windows
brew install kotlin   # macOS
apt install kotlin    # Linux

# Then use batch scripts as normal
```

---

## 🚀 Performance Characteristics

| Metric | Typical |
|--------|---------|
| **Startup** | ~1 second |
| **Memory** | ~100 MB (via JVM) |
| **QR Generation** | < 100ms (API call) |
| **Proxy Check** | 2-5s per proxy |
| **JSON Parsing** | < 50ms (Jackson) |
| **Concurrent Users** | 50+200 (JVM threading) |

---

## 📱 Auto-Detection Features

### 🌐 Country Detection
- Uses `ip-api.com/json/{ip}?fields=countryCode`
- Caches results in HashMap
- Falls back to `AUTO` on error
- Automatic DNS hostname → IP resolution

### 🚀 Latency Testing
- TCP socket connection test
- 5-second timeout
- Returns: `Pair<alive, ms>`
- Results saved to persistent storage

### 🎨 Flag Emojis
- Converts ISO country codes to flag emoji
- Supports all valid ISO 3166-1 alpha-2 codes
- Graceful fallback to 🌐 for unknown codes

### 📡 QR Generation
- Uses public API: `api.qrserver.com`
- 300x300px PNG format
- No external image libraries needed
- Returns both link and QR image URL

---

## 🛠️ Troubleshooting

### "NoClassDefFoundError: kotlin/..."
**Solution:** Ensure all JARs in `libs/` are present
```bash
REM Check files exist:
dir libs\*.jar
```

Required JARs:
- `kotlin-stdlib-*.jar`
- `kotlin-reflect-*.jar`
- `jackson-*.jar`

### Bot Won't Start
```
Exception in thread "main" java.lang.RuntimeException: 
  No BOT_TOKEN provided
```
**Solution:** Set `BOT_TOKEN` environment variable
```powershell
$env:BOT_TOKEN = "your_token_here"
run_java.bat
```

### No Proxies Found
```
Warning: proxies.txt not found
```
**Solution:** Create `proxies.txt` in the same directory as `run_java.bat`

### QR Images Don't Load
```
Error: Cannot reach api.qrserver.com
```
**Solution:** Ensure internet connectivity; public API is required for QR generation

### File Not Found (proxies.txt)
Kotlin version checks both:
1. `proxies.txt` in current directory (kotlin-version/)
2. `../proxies.txt` in parent directory
3. Creates warning but continues execution

---

## 🔐 Security Notes

✅ **Best Practices:**
- Keep `BOT_TOKEN` secret (don't commit `.env`)
- Use environment variables in production
- Restrict `/stats` access via `ADMIN_ID`
- Monitor `db/` folder permissions

❌ **Don't:**
- Expose `BOT_TOKEN` in scripts
- Share admin credentials
- Use weak proxy passwords
- Run with system admin privileges

---

## 📈 Kotlin vs Node.js

| Feature | Kotlin | Node.js |
|---------|--------|---------|
| Startup | ~1s | ~2s |
| Memory | ~100 MB | ~60 MB |
| Dependencies | JARs (included) | npm packages |
| Build Tools | No setup | npm install |
| Compilation | Run .bat script | Direct npm start |
| Runtime | JVM (Java 11+) | Node.js 16+ |
| Performance | Native JVM speed | Event-driven async |
| Threading | Native threads | Single-threaded event loop |

**Choose Kotlin if:** You prefer JVM stability & have Java installed
**Choose Node.js if:** You prefer lightweight & npm ecosystem

---

## 🎓 Code Structure

### Main Components

```kotlin
// Data models
data class ProxyEntry(
    val region: String,
    val type: String,
    val proxy: String,
    val comment: String?
)

data class UserActions(
    val actions: MutableMap<String, Int> = mutableMapOf(),
    var last: String = Instant.now().toString()
)

// Core functions
parseProxiesGroupedByIp(file: File): Map<String, List<ProxyEntry>>
measureProxyLatency(proxy: String, timeoutMs: Int): Pair<Boolean, Long?>
generateSocksQR(proxy: String): Pair<String, String>?
detectCountryCodeForHost(host: String?): String
buildRegionPayload(region, entries, opts, lang): Pair<String, List<...>>
editOrReplyForUser(uid, chatId, msgId, text, kb): Boolean
```

### Polling Loop

Main event loop uses Telegram `getUpdates()` method:
- Polls every 10 seconds (configurable via API)
- Handles messages, callbacks, and commands
- Stores offset to track processed updates
- Graceful error handling with retry

---

## 📦 Dependencies (Included)

All JARs are pre-downloaded and included:

```
jackson-core-2.15.2.jar
jackson-databind-2.15.2.jar
jackson-annotations-2.15.2.jar
jackson-module-kotlin-2.15.2.jar
kotlin-stdlib-1.9.0.jar
kotlin-reflect-1.9.0.jar
```

**No additional dependencies needed!** Everything is in `libs/`.

---

## 🚀 Deployment

### Local Windows
```batch
run_kotlinc.bat
run_java.bat
```

### Docker
```dockerfile
FROM openjdk:11-jre-slim
WORKDIR /app
COPY . .
CMD java -cp "out.jar:libs/*" MainKt
```

Build & run:
```bash
docker build -t nexora-kotlin .
docker run -e BOT_TOKEN=xxx nexora-kotlin
```

### Linux/macOS
```bash
# Ensure Java 11+ installed
java --version

# Compile (requires kotlinc)
kotlinc -cp "libs/*" src/main/kotlin/Main.kt -include-runtime -d out.jar

# Run
java -cp "out.jar:libs/*" MainKt
```

---

## 📞 Support

**Having issues?**
1. Check `BOT_TOKEN` is set (environment variable or `.env`)
2. Verify `proxies.txt` format and location
3. Ensure all JAR files in `libs/` exist (6 files)
4. Check Java version: `java -version` (need 11+)
5. Review console output for specific errors

**Common Commands (PowerShell):**
```powershell
# Set token temporarily
$env:BOT_TOKEN = "your_token_here"

# Check Java installed
java -version

# Compile & run
.\run_kotlinc.bat
.\run_java.bat
```

---

**Built with ❤️ in Kotlin**

*JVM Edition — Fast, Powerful, Production-Ready*
