# Webstore Integration Plugin

A secure, lightweight **Paper plugin** that lets your webstore (e.g., Tebex, CraftingStore, custom checkout) deliver commands to your Minecraft server in real-time via a simple REST API.

---

## 📦 Features

| Feature                      | Description                                                      |
| ---------------------------- | ---------------------------------------------------------------- |
| 🔐 **Secure**                | Token-based authentication (`Authorization: Bearer <secret>`)    |
| 🛡️ **Command Whitelist**     | Only pre-approved command prefixes are executed                  |
| ⚡ **Async Processing**      | Commands run on the main thread without blocking the web request |
| 🌐 **REST Endpoints**        | `/deliver` (POST) and `/health` (GET)                            |
| 🧩 **Zero-Dependency**       | Uses only Java & Bukkit APIs (Gson is shaded)                    |
| 📝 **Rich Logging**          | Color-coded logs for success, failure, and security events       |
| 🔄 **Hot-Reloadable Config** | `/webstore reload` (or restart) to apply changes                 |

---

## 🚀 Quick Start

### 1. Download & Install

1. Grab the latest `WebstoreIntegration-*.jar` from [Releases](https://github.com/your-org/WebstoreIntegration/releases).
2. Drop the jar into your server’s `plugins/` folder.
3. Start (or reload) the server.
   A default `plugins/WebstoreIntegration/config.yml` is created.

### 2. Configure Security

```yaml
# plugins/WebstoreIntegration/config.yml
port: 8123
secret: "YOUR_SUPER_SECRET_KEY_HERE" # <-- CHANGE THIS!
allowedCommands:
  - "lp user"
  - "give"
  - "tellraw"
```

Restart or run `/webstore reload`.

### 3. Test Locally

```bash
curl -X POST http://localhost:8123/deliver \
  -H "Authorization: Bearer YOUR_SUPER_SECRET_KEY_HERE" \
  -H "Content-Type: application/json" \
  -d '{
        "orderId": 12345,
        "minecraftUsername": "Notch",
        "commands": [
          "give {player} diamond 5",
          "lp user {player} permission set webstore.vip true"
        ]
      }'
```

Expected response:

```json
{
  "orderId": 12345,
  "minecraftUsername": "Notch",
  "success": true,
  "executedCommands": [
    "give Notch diamond 5",
    "lp user Notch permission set webstore.vip true"
  ],
  "failedCommands": []
}
```

---

## 📖 API Reference

### POST `/deliver`

Executes one or more commands on behalf of a player.

| Header          | Value              |
| --------------- | ------------------ |
| `Authorization` | `Bearer <secret>`  |
| `Content-Type`  | `application/json` |

**Body schema:**

```json
{
  "orderId": 123,
  "minecraftUsername": "PlayerName",
  "commands": ["give {player} emerald 10", "tp {player} 0 100 0"]
}
```

**Response schema:**

```json
{
  "orderId": 123,
  "minecraftUsername": "PlayerName",
  "success": true,
  "error": null,
  "executedCommands": [...],
  "failedCommands": [...]
}
```

### GET `/health`

Returns server status and plugin version.

---

## 🛠️ Setup Instructions for Webstores

### Tebex (formerly Buycraft)

1. In your Tebex dashboard → **Servers** → **Edit** → **Commands**.
2. Set **Type** to **Webhook**.
3. URL: `http://YOUR_SERVER_IP:8123/deliver`
4. HTTP Method: `POST`
5. Headers:
   ```
   Authorization: Bearer YOUR_SUPER_SECRET_KEY_HERE
   Content-Type: application/json
   ```
6. Body template:
   ```json
   {
     "orderId": "{id}",
     "minecraftUsername": "{username}",
     "commands": [
       "give {username} diamond 10",
       "lp user {username} parent add vip"
     ]
   }
   ```

### CraftingStore

1. **Stores** → **Edit** → **Connections** → **Add Connection**.
2. Choose **Webhook**.
3. Fill in the same details as above.

---

## 🔧 Commands & Permissions

| Command            | Permission       | Description                         |
| ------------------ | ---------------- | ----------------------------------- |
| `/webstore reload` | `webstore.admin` | Reload `config.yml` without restart |
| `/webstore status` | `webstore.admin` | Show server & plugin status         |

---

## 📄 Configuration Reference

| Key                              | Default                       | Description                                                                             |
| -------------------------------- | ----------------------------- | --------------------------------------------------------------------------------------- |
| `port`                           | `8123`                        | HTTP port (ensure it’s open in firewall).                                               |
| `secret`                         | `"changeme-super-secret-key"` | **Change this!** Used to sign requests.                                                 |
| `allowedCommands`                | `[...]`                       | Only commands starting with these strings are allowed. Empty list = allow all (unsafe). |
| `logging.*`                      | `true`                        | Toggle request/command/failure logging.                                                 |
| `advanced.maxCommandsPerRequest` | `50`                          | Deny of service protection.                                                             |
| `advanced.requestTimeout`        | `30`                          | Seconds before HTTP request times out.                                                  |

---

## 🐛 Troubleshooting

| Symptom                  | Fix                                                  |
| ------------------------ | ---------------------------------------------------- |
| `403 Unauthorized`       | Check `Authorization` header matches `secret`.       |
| `405 Method Not Allowed` | Ensure POST to `/deliver`.                           |
| Commands not executing   | Verify command prefix is in `allowedCommands`.       |
| Port already bound       | Change `port` in config or stop conflicting service. |
| Firewall issues          | Open TCP port in OS & hosting provider panel.        |

---

## 🧪 Development

```bash
git clone https://github.com/your-org/WebstoreIntegration.git
cd WebstoreIntegration
mvn clean package
```

The compiled jar is in `target/WebstoreIntegration-*.jar`.

---

## 📄 License

---

Enjoy seamless webstore integration!

---

### Regarding Tebex / CraftingStore / etc.

I have **not** tested the plugin with Tebex, CraftingStore, or any other commercial webstore platform.
The provided integration examples are based on their public documentation only.
If you decide to test with those services, please:

1. Use a staging server.
2. Keep the command whitelist small (`allowedCommands`) to avoid accidental damage.
3. Monitor logs (`logs/latest.log`) for any rejected requests or command failures.
