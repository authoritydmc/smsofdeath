# Telegram Bot Manager (TGBM) Production Runbook & Flow Reference

**Target Host:** `rajlab-prod1` (`server1` / `100.117.212.84`)  
**Deployment Directory:** `/home/ubuntu/telegram-bot-manager`  
**Public Management URL:** https://bots.rajlabs.in  
**Primary Bot:** `@rajlabs_mainbot` (`RajLabs Assistant ⚡`, ID `8504534287`)

---

## 1. Architecture Overview

TGBM provides an n8n-like visual automation platform built specifically for Telegram bot operations, webhook automation, interactive flows, and homelab / server controls.

### Service Stack (Docker Compose)
All services run in Docker under the container naming prefix `tgbm-*`:
- **`tgbm-frontend-*`**: Vite + React + Tailwind + React Flow canvas (port 80 internally, proxied via Coolify / reverse proxy).
- **`tgbm-backend-*`**: FastAPI application (Python 3.12/3.14) providing REST API, webhook endpoints, and flow execution engine.
- **`tgbm-poller-*`**: Long-polling daemon handling incoming updates from Telegram and dispatching to registered triggers.
- **`tgbm-worker-*`**: Celery/async worker for executing asynchronous actions and outbound tasks.
- **`tgbm-redis-*`**: Caching, pub/sub, rate-limiting, and idempotency tracking.
- **`tgbm-local-api-*`**: Local telegram bot API mirror / internal proxy (ports 8081-8082).
- **`dev-postgres`**: Postgres database storing users, registered bots, chats, flows, executions, and API keys (`tgbm` DB).

---

## 2. Key Directories & Configuration Files

| Path | Description |
|------|-------------|
| `/home/ubuntu/telegram-bot-manager/.env` | Environment variables (DB URL, Redis, JWT secrets, encryption keys, public URLs) |
| `/home/ubuntu/telegram-bot-manager/flows/` | JSON definitions of visual workflows exported/synced via `sync_flows.py` |
| `/home/ubuntu/telegram-bot-manager/backend/app/` | Backend FastAPI application logic |
| `/home/ubuntu/telegram-bot-manager/backend/scripts/` | Maintenance and management utility scripts |

---

## 3. Webhooks & Inbound API Authentication

Inbound webhooks allow external services, alert managers, or scripts to trigger actions and message chats via TGBM.

### Endpoint Format
```http
POST https://bots.rajlabs.in/inbound/{slug}
```

### Authentication Modes
1. **API Key (Bearer Token):**
   ```bash
   curl -X POST https://bots.rajlabs.in/inbound/<slug> \
     -H "Authorization: Bearer tgbm_<API_KEY>" \
     -H "Content-Type: application/json" \
     -d '{"chat_id": 343582571, "message": "hello"}'
   ```
2. **Per-Webhook Secret Header:**
   ```bash
   -H "X-Webhook-Secret: <WEBHOOK_SECRET>"
   ```
3. **Query Parameter Secret:**
   ```
   POST /inbound/{slug}?secret=<WEBHOOK_SECRET>
   ```

### Active Inbound Webhooks
| ID | Slug | Purpose | Target / Bot |
|---|---|---|---|
| 1 | `media-sender` | Generic media dispatch webhook | Default / Any |
| 3 | `movy-download` | Movy download completion notifications | `@rajlabs_mainbot` |
| 4 | `system-alert` | Server, infrastructure, and sentinel alerts | `@rajlabs_mainbot` |
| 5 | `tv-screenshots` | Screenshots capture notifications from TV setup | `@rajlabs_mainbot` |

---

## 4. Flow Automations Catalog

All active flows are evaluated whenever incoming Telegram updates (messages, commands, callbacks) or inbound webhooks are received.

### A. Download & Media Automation
- **Flow 1 (`downloader-complete → notify`):**
  - **Trigger:** Inbound webhook `downloader-complete`
  - **Action:** Sends text notification to target chat notifying completion.
- **Flow 2 (`downloader-complete → approve buttons`):**
  - **Trigger:** Inbound webhook `downloader-complete`
  - **Action:** Sends interactive inline keyboard with approval / rejection buttons.
- **Flow 4 (`Movy Download Notification`):**
  - **Trigger:** Inbound webhook `movy-download`
  - **Action:** Formats download progress/result and notifies user with direct playback links.
- **Flow 5 (`Movy Download Command`):**
  - **Trigger:** Telegram Command `/download <url>`
  - **Action:** Forwards requested video/media URL to FetchBox/Movy queue and returns status tracker.
- **Flow 7 (`Movy Portal Links`):**
  - **Trigger:** Telegram Command `/movy`
  - **Action:** Replies with direct portal links and player URLs.
- **Flow 11 (`Movy Downloads List`):**
  - **Trigger:** Telegram Command `/list`
  - **Action:** Fetches and returns list of completed & active library items.
- **Flow 12 (`Media Request Approval Alert`):**
  - **Trigger:** Inbound webhook
  - **Action:** Delivers Jellyfin / Seerr media request approvals to admins.

### B. Bot Commands & Interaction
- **Flow 3 (`keyword auto-reply (hello)`):**
  - **Trigger:** Message containing greetings ("hello", "hi")
  - **Action:** Responds with automated friendly greetings.
- **Flow 6 (`Bot Welcome & Help`):**
  - **Trigger:** Telegram Command `/start` or `/help`
  - **Action:** Displays main dashboard menu and command guide.
- **Flow 8 (`System Status Check`):**
  - **Trigger:** Telegram Command `/status`
  - **Action:** Gathers system health and container statuses, responding to chat.
- **Flow 10 (`Live Weather Report`):**
  - **Trigger:** Telegram Command `/weather <city>`
  - **Action:** Queries weather API and posts forecast card.

### C. SecGuard & Remote Infrastructure Operations
SecGuard flows protect critical infrastructure and provide remote emergency actions via Telegram messages/callbacks with confirmation steps:
- **Flow 9 (`System Alert Notification`):**
  - **Trigger:** Inbound webhook `system-alert`
  - **Action:** Formats infrastructure alert and pings admin chat.
- **Flow 13 (`SecGuard: Kick & Block`):**
  - **Trigger:** Message matching `sec-kick`
  - **Action:** Drops attacker SSH/VPN session and inserts host ban into IPSet/firewall.
- **Flow 14 (`SecGuard: Power confirm prod1`):**
  - **Trigger:** Message matching `sec-power-ask|prod1`
  - **Action:** Prompts interactive confirmation dialog before powering down `prod1`.
- **Flow 15 (`SecGuard: Power confirm prod2`):**
  - **Trigger:** Message matching `sec-power-ask|prod2`
  - **Action:** Prompts interactive confirmation dialog before powering down `prod2`.
- **Flow 16 (`SecGuard: Power off`):**
  - **Trigger:** Message matching `sec-power-do`
  - **Action:** Executes power-off routine for verified target node.
- **Flow 17 (`SecGuard: Cancel`):**
  - **Trigger:** Callback/Message cancel
  - **Action:** Aborts pending power/reboot actions and clears verification state.
- **Flow 18 (`SecGuard: Reboot confirm prod1`):**
  - **Trigger:** Message matching `sec-reboot-ask|prod1`
  - **Action:** Prompts interactive confirmation dialog before rebooting `prod1`.
- **Flow 19 (`SecGuard: Reboot confirm prod2`):**
  - **Trigger:** Message matching `sec-reboot-ask|prod2`
  - **Action:** Prompts interactive confirmation dialog before rebooting `prod2`.
- **Flow 20 (`SecGuard: Reboot`):**
  - **Trigger:** Message matching `sec-reboot-do`
  - **Action:** Triggers system reboot on target host after confirmation.
- **Flow 21 (`SecGuard: Reboot confirm rajlab`):**
  - **Trigger:** Message matching `sec-reboot-ask|rajlab`
  - **Action:** Prompts interactive confirmation dialog before rebooting main lab server.
- **Flow 22 (`SecGuard: Power confirm rajlab`):**
  - **Trigger:** Message matching `sec-power-ask|rajlab`
  - **Action:** Prompts interactive confirmation dialog before powering down main lab server.

---

## 5. Operations & Troubleshooting

### Check Container Logs
```bash
# Backend logs
docker logs -f --tail 100 tgbm-backend-ypjovo9y7dbfumlp9vszpe01

# Poller daemon logs (Telegram communication)
docker logs -f --tail 100 tgbm-poller-ypjovo9y7dbfumlp9vszpe01

# Worker daemon logs (Flow executions)
docker logs -f --tail 100 tgbm-worker-ypjovo9y7dbfumlp9vszpe01
```

### Inspect Database Directly
```bash
docker exec -it dev-postgres psql -U postgres -d tgbm
```

### Sync Flow Definitions
If you update or add flows in `/home/ubuntu/telegram-bot-manager/flows/`:
```bash
cd /home/ubuntu/telegram-bot-manager
python3 scripts/sync_flows.py
```

### Restart Services
```bash
docker restart tgbm-backend-ypjovo9y7dbfumlp9vszpe01 tgbm-poller-ypjovo9y7dbfumlp9vszpe01 tgbm-worker-ypjovo9y7dbfumlp9vszpe01
```
