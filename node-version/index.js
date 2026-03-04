require('dotenv').config();
const { Telegraf, Markup } = require('telegraf');
const QRCode = require('qrcode');
const fs = require('fs');
const path = require('path');
const net = require('net');

console.log('Starting index.js — begin initialization');
process.on('unhandledRejection', (err) => { console.error('unhandledRejection', err && err.stack ? err.stack : err); });
process.on('uncaughtException', (err) => { console.error('uncaughtException', err && err.stack ? err.stack : err); process.exit(1); });

const bot = new Telegraf(process.env.BOT_TOKEN || process.env.TELEGRAM_BOT_TOKEN);
const PROXIES_FILE = path.join(__dirname, 'proxies.txt');
const http = require('http');
const dns = require('dns').promises;

const ipCountryCache = {};
// runtime cache of proxy statuses: { '<proxy>': { alive: bool, ms: number|null, at: ISOString } }
const proxyStatus = {};
const STATUS_FILE = path.join(__dirname, 'db', 'proxy_status.json');

function ensureStatusDir() {
  try { fs.mkdirSync(path.dirname(STATUS_FILE), { recursive: true }); } catch (e) { /* ignore */ }
}

function loadProxyStatus() {
  try {
    if (!fs.existsSync(STATUS_FILE)) return;
    const raw = fs.readFileSync(STATUS_FILE, 'utf8');
    const j = JSON.parse(raw || '{}');
    for (const k of Object.keys(j)) proxyStatus[k] = j[k];
  } catch (e) { console.error('Failed loading proxy status:', e.message); }
}

function saveProxyStatus() {
  try {
    ensureStatusDir();
    const tmp = STATUS_FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(proxyStatus, null, 2), 'utf8');
    fs.renameSync(tmp, STATUS_FILE);
  } catch (e) { console.error('Failed saving proxy status:', e.message); }
}

// load persisted statuses on startup
loadProxyStatus();

// autosave every 60s
setInterval(() => { saveProxyStatus(); }, 60 * 1000);

process.once('exit', () => saveProxyStatus());
process.once('SIGINT', () => { saveProxyStatus(); process.exit(); });
process.once('SIGTERM', () => { saveProxyStatus(); process.exit(); });

async function detectCountryCodeForHost(host) {
  if (!host) return 'AUTO';
  if (ipCountryCache[host]) return ipCountryCache[host];
  let ip = host;
  // if hostname, resolve
  if (!/^(?:\d{1,3}\.){3}\d{1,3}$/.test(host)) {
    try { const r = await dns.lookup(host); ip = r.address; } catch (e) { ip = host; }
  }
  return new Promise((resolve) => {
    const req = http.get(`http://ip-api.com/json/${ip}?fields=countryCode`, (res) => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => {
        try { const j = JSON.parse(b); const code = (j && j.countryCode) ? j.countryCode : 'AUTO'; ipCountryCache[host] = code; resolve(code); } catch (e) { resolve('AUTO'); }
      });
    }).on('error', () => resolve('AUTO'));
    req.setTimeout(3000, () => { req.abort(); resolve('AUTO'); });
  });
}

function countryCodeToFlag(cc) {
  if (!cc) return '🌐';
  if (cc === 'AUTO' || cc === 'unknown') return '🌐';
  cc = String(cc).toUpperCase();
  if (cc.length !== 2) return '🏳️';
  try {
    const points = [...cc].map(c => 0x1F1E6 + c.charCodeAt(0) - 65);
    return String.fromCodePoint(...points);
  } catch (e) { return '🏳️'; }
}

// in-memory user state: language, last message id for editing, etc.
const userState = {};

function tr(uid, ru, en) {
  const lang = (userState[uid] && userState[uid].lang) || 'ru';
  return lang === 'ru' ? ru : en;
}

const CHANNEL_URL = process.env.CHANNEL_URL || 'https://t.me/nexoraproxy';

async function parseProxiesGroupedByIp() {
  const regions = {};
  if (!fs.existsSync(PROXIES_FILE)) return regions;
  const lines = fs.readFileSync(PROXIES_FILE, 'utf8').split(/\r?\n/);
  for (let raw of lines) {
    let line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split('|');
    let type = 'unknown', proxy = '';
    if (parts.length === 3) {
      [, type, proxy] = parts.map(s => s.trim());
    } else if (parts.length === 2) {
      [, proxy] = parts.map(s => s.trim());
    } else {
      proxy = parts[0].trim();
    }
    // allow trailing comment after proxy (space-separated)
    let comment = '';
    if (proxy.includes(' ')) {
      const toks = proxy.split(/\s+/);
      proxy = toks[0];
      comment = toks.slice(1).join(' ');
    }
    // extract host
    let addr = proxy.includes('@') ? proxy.split('@').pop() : proxy;
    const host = addr.split(':')[0];
    const country = await detectCountryCodeForHost(host);
    regions[country] = regions[country] || [];
    regions[country].push({ type, proxy, comment });
  }
  return regions;
}

function checkProxy(proxy, timeout = 2000) {
  return new Promise((resolve) => {
    let addr = proxy;
    if (proxy.includes('@')) addr = proxy.split('@').pop();
    if (!addr.includes(':')) return resolve(false);
    const [host, port] = addr.split(':');
    const socket = new net.Socket();
    let done = false;
    socket.setTimeout(timeout);
    socket.on('connect', () => { done = true; socket.destroy(); resolve(true); });
    socket.on('error', () => { if (!done) { done = true; resolve(false); } });
    socket.on('timeout', () => { if (!done) { done = true; resolve(false); } });
    socket.connect(Number(port), host);
  });
}

function measureProxyLatency(proxy, timeout = 5000) {
  return new Promise((resolve) => {
    let addr = proxy;
    if (proxy.includes('@')) addr = proxy.split('@').pop();
    if (!addr.includes(':')) return resolve({ alive: false, ms: null });
    const [host, port] = addr.split(':');
    const socket = new net.Socket();
    const start = Date.now();
    let done = false;
    socket.setTimeout(timeout);
    socket.on('connect', () => {
      const ms = Date.now() - start;
      done = true; socket.destroy(); resolve({ alive: true, ms });
    });
    socket.on('error', () => { if (!done) { done = true; resolve({ alive: false, ms: null }); } });
    socket.on('timeout', () => { if (!done) { done = true; resolve({ alive: false, ms: null }); } });
    socket.connect(Number(port), host);
  });
}

function buildRegionPayload(region, entries, opts = {}, lang = 'ru') {
  const provider = process.env.PROVIDER_NAME || process.env.CHANNEL_NAME || 'NEXORA';
  const updatedAt = new Date().toLocaleTimeString(lang === 'ru' ? 'ru-RU' : 'en-US', { hour: '2-digit', minute: '2-digit' });
  let text = '';
  text += '━━━━━━━━━━\n';
  text += `🌋 ${provider} - ${region} PROXIES\n`;
  text += `${lang === 'ru' ? 'Всего' : 'Total'}: ${entries.length}\n`;
  text += `${lang === 'ru' ? 'Обновлено' : 'Updated'}: ${updatedAt}\n`;
  text += '━━━━━━━━━━\n\n';
  const kb = [];
  const checkedResults = opts.checkedResults || {};
  const checkedIndex = (typeof opts.checkedIndex === 'number') ? opts.checkedIndex : null;
  for (let i = 0; i < entries.length; i++) {
    const { type, proxy, comment } = entries[i];
    // merge in global proxyStatus if exists
    const st = proxyStatus[proxy];
    const notChecked = lang === 'ru' ? '⏳ Не проверено' : '⏳ Not checked';
    const active = lang === 'ru' ? '✅ Активный' : '✅ Active';
    const inactive = lang === 'ru' ? '❌ Не активен' : '❌ Inactive';
    const checkedSuffix = lang === 'ru' ? ' (Проверен)' : ' (Checked)';
    let status = notChecked;
    if (st) {
      status = st.alive ? `${active} ${st.ms ? '— ' + st.ms + ' ms' : ''}` : inactive;
      if (st.at) {
        const t = new Date(st.at).toLocaleTimeString(lang === 'ru' ? 'ru-RU' : 'en-US', { hour: '2-digit', minute: '2-digit' });
        status += ` (${t})`;
      }
    }
    if (i in checkedResults) status = checkedResults[i] ? active : inactive;
    if (checkedIndex === i) status += checkedSuffix;
    const line1 = `#${i+1} ${type.toUpperCase()} ${countryCodeToFlag(region)} ${status}`;
    text += line1 + '\n';
    text += proxy + '\n';
    if (comment) text += `${comment}\n`;
    text += '\n';
    kb.push([ Markup.button.callback(`📱 QR ${i+1}`, `qr_${region}_${i}`), Markup.button.callback(`🔗 ${i+1}`, `connect_${region}_${i}`), Markup.button.callback('🔄 Проверить', `check_${region}_${i}`) ]);
  }
  kb.push([ Markup.button.callback('🔁 Обновить список', `refresh_${region}`) ]);
  kb.push([ Markup.button.callback('⬅️ Назад к регионам', 'open_proxies'), Markup.button.callback('⬅️ Главное меню', 'main_menu') ]);
  return { text, kb };
}

async function editOrReplyForUser(uid, ctx, text, kb, extra = {}) {
  const last = (userState[uid] && userState[uid].lastList) || null;
  const replyMarkup = kb ? Markup.inlineKeyboard(kb).reply_markup : undefined;
  if (last && last.chat && last.message_id) {
    try {
      await bot.telegram.editMessageText(last.chat, last.message_id, null, text, { reply_markup: replyMarkup, ...extra });
      return { edited: true };
    } catch (e) {
      // fallback to editing current message if possible
      try { await ctx.editMessageText(text, { reply_markup: replyMarkup, ...extra }); return { edited: true }; } catch (e2) { /* continue */ }
    }
  } else {
    try { await ctx.editMessageText(text, { reply_markup: replyMarkup, ...extra }); return { edited: true }; } catch (e) { /* continue */ }
  }
  // last resort: send a reply but try to delete previous bot replies to avoid spam
  const sent = await ctx.reply(text, kb ? Markup.inlineKeyboard(kb) : undefined);
  // store this message as lastList for the user
  userState[uid] = userState[uid] || {};
  userState[uid].lastList = { chat: sent.chat.id, message_id: sent.message_id };
  return { edited: false };
}

async function generateSocksQR(proxy) {
  let user = '', pass = '', host = '', port = '';
  if (proxy.includes('@')) {
    const [creds, addr] = proxy.split('@');
    if (creds.includes(':')) [user, pass] = creds.split(':');
    const parts = addr.split(':'); host = parts[0]; port = parts[1];
  } else {
    const parts = proxy.split(':'); host = parts[0]; port = parts[1];
  }
  if (!host || !port) return null;
  const link = `tg://socks?server=${encodeURIComponent(host)}&port=${port}` + (user ? `&user=${encodeURIComponent(user)}&pass=${encodeURIComponent(pass)}` : '');
  try {
    const buf = await QRCode.toBuffer(link, { width: 300, margin: 1 });
    return { link, buffer: buf, host, port, user, pass };
  } catch (e) { return null; }
}

async function showMainMenu(ctx) {
  const uid = ctx.from.id;
  const lang = (userState[uid] && userState[uid].lang) || 'ru';
  const provider = process.env.PROVIDER_NAME || 'Nexora';
  const channelLabel = lang === 'ru' ? '🔗 Канал' : '🔗 Channel';
  const proxiesLabel = lang === 'ru' ? '🗂️ Прокси' : '🗂️ Proxies';
  const helpLabel = lang === 'ru' ? '📱 Помощь (QR)' : '📱 QR Help';

  const textRu = `🌋 ${provider} - Proxy\n\n📄 Описание\nПремиум-прокси с глобальным покрытием\n🚀 Быстрый · Надёжный · Анонимный\n\n☰ Главное меню`;
  const textEn = `🌋 ${provider} - Proxy\n\n📄 Description\nPremium proxy service with global coverage\n🚀 Fast · Reliable · Anonymous\n\n☰ Main Menu`;

  const text = (lang === 'ru') ? textRu : textEn;
  const kb = [
    [ Markup.button.url(channelLabel, CHANNEL_URL) ],
    [ Markup.button.callback(proxiesLabel, 'open_proxies') ],
    [ Markup.button.callback(helpLabel, 'qr_help') ]
  ];
  await editOrReplyForUser(uid, ctx, text, kb, { parse_mode: 'Markdown' });
}

bot.command('start', async (ctx) => {
  const uid = ctx.from.id;
  userState[uid] = userState[uid] || {};
  const kb = Markup.inlineKeyboard([
    [ Markup.button.callback('RU', 'lang_ru'), Markup.button.callback('EN', 'lang_en') ]
  ]);
  await ctx.reply('*Выберите язык / Choose language*', { parse_mode: 'Markdown', ...kb });
});

bot.action(/lang_(.+)/, async (ctx) => {
  await ctx.answerCbQuery();
  const uid = ctx.from.id;
  const lang = ctx.match[1];
  userState[uid] = userState[uid] || {};
  userState[uid].lang = (lang === 'ru' ? 'ru' : 'en');
  await ctx.answerCbQuery(userState[uid].lang === 'ru' ? 'Язык выбран: Русский' : 'Language set: English');
  return showMainMenu(ctx);
});

bot.action('open_proxies', async (ctx) => {
  await ctx.answerCbQuery();
  const regionsMap = await parseProxiesGroupedByIp();
  const regions = Object.keys(regionsMap);
  const total = Object.values(regionsMap).reduce((s, a) => s + a.length, 0);
  const uid = ctx.from.id;
  if (!regions.length) return ctx.reply(tr(uid, 'Нет доступных прокси. Поместите список в proxies.txt', 'No proxies available. Put list in proxies.txt'));
  const kb = regions.map(r => {
    const flag = countryCodeToFlag(r);
    return [ Markup.button.callback(`${flag} ${r} (${regionsMap[r].length})`, `region_${r}`) ];
  });
  kb.push([ Markup.button.callback('🔁 Обновить список', 'refresh_all') ]);
  kb.push([ Markup.button.callback('⬅️ Назад', 'main_menu') ]);
  const sent = await ctx.reply(tr(uid, `Выберите регион — Всего: ${total}`, `Choose region — Total: ${total}`), Markup.inlineKeyboard(kb));
  userState[uid] = userState[uid] || {};
  userState[uid].lastList = { chat: sent.chat.id, message_id: sent.message_id };
  return sent;
});

bot.action('refresh_all', async (ctx) => {
  const uid = ctx.from.id;
  await ctx.answerCbQuery(tr(uid, 'Обновлено', 'Updated'));
  const regionsMap = await parseProxiesGroupedByIp();
  const regions = Object.keys(regionsMap);
  const total = Object.values(regionsMap).reduce((s, a) => s + a.length, 0);
  if (!regions.length) return ctx.reply(tr(uid, 'Нет доступных прокси. Поместите список в proxies.txt', 'No proxies available. Put list in proxies.txt'));
  const kb = regions.map(r => [ Markup.button.callback(`${countryCodeToFlag(r)} ${r} (${regionsMap[r].length})`, `region_${r}`) ]);
  kb.push([ Markup.button.callback('🔁 Обновить список', 'refresh_all') ]);
  kb.push([ Markup.button.callback('⬅️ Назад', 'main_menu') ]);
  // prefer editing the user's last list message if present
  const last = (userState[uid] && userState[uid].lastList) || null;
  const text = tr(uid, `Обновлено — Всего прокси: ${total}`, `Updated — Total proxies: ${total}`);
  const replyMarkup = Markup.inlineKeyboard(kb).reply_markup;
  if (last && last.chat && last.message_id) {
    try { await bot.telegram.editMessageText(last.chat, last.message_id, null, text, { reply_markup: replyMarkup }); return; } catch (e) { /* fallback below */ }
  }
  try { return ctx.editMessageText(text, { reply_markup: replyMarkup }); } catch (e) { return ctx.reply(text, Markup.inlineKeyboard(kb)); }
});

bot.action('main_menu', async (ctx) => { await ctx.answerCbQuery(); return showMainMenu(ctx); });

bot.action(/region_(.+)/, async (ctx) => {
  const uid = ctx.from.id;
  await ctx.answerCbQuery(tr(uid, '⏳ Проверка...', '⏳ Checking...'));
  const region = ctx.match[1];
  const regionsMap = await parseProxiesGroupedByIp();
  const entries = regionsMap[region] || [];
  if (!entries.length) return ctx.reply(tr(uid, 'Нет прокси в этом регионе', 'No proxies in this region'));
  const descs = {
    'US': 'Low-latency US gateways — best for North America',
    'DE': 'European hub: stable and fast German proxies',
    'NL': 'Netherlands: great for EU routing and speed',
    'FI': 'Finland: Nordic low-latency servers',
    'SG': 'Asia Pacific: Singapore fast nodes',
    'AUTO': 'Auto-detected proxies by IP address'
  };
  const provider = process.env.PROVIDER_NAME || process.env.CHANNEL_NAME || 'NEXORA';
  const description = descs[region] || 'Fast anonymous proxies for this region';
  const lang = (userState[uid] && userState[uid].lang) || 'ru';
  const payload = buildRegionPayload(region, entries, {}, lang);
  const last = (userState[uid] && userState[uid].lastList) || null;
  const replyMarkup = Markup.inlineKeyboard(payload.kb).reply_markup;
  if (last && last.chat && last.message_id) {
    try { await bot.telegram.editMessageText(last.chat, last.message_id, null, payload.text, { reply_markup: replyMarkup }); return; } catch (e) { /* fallback */ }
  }
  try { return await ctx.editMessageText(payload.text, { reply_markup: replyMarkup }); } catch (e) { return ctx.reply(payload.text, Markup.inlineKeyboard(payload.kb)); }
});

bot.action(/check_(.+)_(\d+)/, async (ctx) => {
  const uid = ctx.from.id;
  await ctx.answerCbQuery(tr(uid, 'Проверяю...', 'Checking...'));
  const region = ctx.match[1];
  const idx = parseInt(ctx.match[2], 10);
  const regionsMap = await parseProxiesGroupedByIp();
  const entries = regionsMap[region] || [];
  if (idx >= entries.length) return ctx.answerCbQuery(tr(uid, 'Прокси не найдено', 'Proxy not found'), { show_alert: true });
  const { proxy } = entries[idx];
  const res = await measureProxyLatency(proxy, 5000);
  const alive = !!res.alive;
  const ms = res.ms;
  // update global status cache
  proxyStatus[proxy] = { alive, ms, at: new Date().toISOString() };
  // build checkedResults map to highlight this index as checked
  const checkedResults = {};
  checkedResults[idx] = alive;
  const lang = (userState[uid] && userState[uid].lang) || 'ru';
  const payload = buildRegionPayload(region, entries, { checkedIndex: idx, checkedResults }, lang);
  try {
    await ctx.editMessageText(payload.text, { reply_markup: Markup.inlineKeyboard(payload.kb).reply_markup });
  } catch (e) {
    await ctx.reply(payload.text, Markup.inlineKeyboard(payload.kb));
  }
  // answer to user about result
  const msg = alive ? tr(uid, `✅ Проверен — ${ms ? ms + ' ms' : ''}`, `✅ Checked — ${ms ? ms + ' ms' : ''}`) : tr(uid, '❌ Не активен', '❌ Inactive');
  return ctx.answerCbQuery(msg);
});

bot.action('qr_help', async (ctx) => {
  await ctx.answerCbQuery();
  const uid = ctx.from.id;
  const lang = (userState[uid] && userState[uid].lang) || 'ru';
  const textRu = `✨ *Как работает QR-подключение* ✨

🔹 *Что это делает*
QR содержит все параметры прокси (сервер, порт, логин) для быстрого импорта в Telegram.

🔹 *Как пользоваться*
1️⃣ Откройте Telegram (моб.) → Настройки → Данные и диск
2️⃣ Раздел «Прокси» → Добавить прокси
3️⃣ Сканируйте QR или нажмите *Быстрое подключение*

⏳ *Важно:* QR действителен 5 минут — откройте заново, если истёк.

💡 *Советы*
- При ручном вводе проверяйте логин/пароль
- Если прокси не подключается — используйте кнопку *Проверить* для диагностики
`;
  const textEn = `✨ *QR Connection — Quick Guide* ✨

🔹 *What it does*
QR encodes all proxy parameters (server, port, user) for quick import into Telegram.

🔹 *How to use*
1️⃣ Open Telegram (mobile) → Settings → Data and Storage
2️⃣ Proxy → Add proxy
3️⃣ Scan the QR or press *Quick connect*

⏳ *Note:* QR is valid for 5 minutes — reopen if expired.

💡 *Tips*
- Check credentials when adding manually
- If proxy fails — use *Check* to diagnose
`;
  const text = (lang === 'ru') ? textRu : textEn;
  const kb = [[ Markup.button.callback(lang === 'ru' ? '⬅️ Назад' : '⬅️ Back', 'main_menu') ]];
  await editOrReplyForUser(uid, ctx, text, kb, { parse_mode: 'Markdown' });
  return;
});

bot.action(/qr_(.+)_(\d+)/, async (ctx) => {
  await ctx.answerCbQuery();
  const region = ctx.match[1];
  const idx = parseInt(ctx.match[2], 10);
  const regionsMap = await parseProxiesGroupedByIp();
  const entries = regionsMap[region] || [];
  const uid = ctx.from.id;
  if (idx >= entries.length) return ctx.reply(tr(uid, 'Прокси не найдено', 'Proxy not found'));
  const { type, proxy } = entries[idx];
  const qr = await generateSocksQR(proxy);
  if (!qr) return ctx.reply(tr(uid, 'Ошибка генерации QR', 'QR generation error'));
  const lang = (userState[ctx.from.id] && userState[ctx.from.id].lang) || 'ru';
  const flag = countryCodeToFlag(region);
  const userLabel = qr.user || '';
  const captionRu = `📱 QR-Код для подключения\n\n🌐 Регион: ${region} ${flag}\n📊 Тип: ${type.toUpperCase()}\n🖥️ Сервер: ${qr.host}:${qr.port}\n👤 Пользователь: ${userLabel || '—'}\n\n⏳ Действует: 5 минут\n\n💡 Как подключиться:\n1️⃣ Телеграм → Настройки → Данные и диск\n2️⃣ Прокси → Добавить прокси\n3️⃣ Сканировать QR → Отсканируйте код слева\n\nИли нажмите кнопку:`;
  const captionEn = `📱 QR for connection\n\n🌐 Region: ${region} ${flag}\n📊 Type: ${type.toUpperCase()}\n🖥️ Server: ${qr.host}:${qr.port}\n👤 User: ${userLabel || '—'}\n\n⏳ Valid: 5 minutes\n\n💡 How to connect:\n1️⃣ Telegram → Settings → Data and Storage\n2️⃣ Proxy → Add proxy\n3️⃣ Scan the QR → Scan the code on the left\n\nOr press the button:`;
  const caption = lang === 'ru' ? captionRu : captionEn;
  const btnLabel = lang === 'ru' ? '🔗 Быстрое подключение' : '🔗 Quick connect';
  // send QR image as a separate photo message with quick-connect button
  await ctx.replyWithPhoto({ source: qr.buffer }, { caption, ...Markup.inlineKeyboard([[ Markup.button.url(btnLabel, qr.link) ]]) });
  return ctx.answerCbQuery('QR отправлен');
});

bot.action(/connect_(.+)_(\d+)/, async (ctx) => {
  await ctx.answerCbQuery();
  const region = ctx.match[1];
  const idx = parseInt(ctx.match[2], 10);
  const regionsMap = await parseProxiesGroupedByIp();
  const entries = regionsMap[region] || [];
    if (idx >= entries.length) return ctx.reply(tr(ctx.from.id, 'Прокси не найдено', 'Proxy not found'));
  const { proxy } = entries[idx];
  // parse optional credentials
  let user = '', pass = '', addr = proxy;
  if (proxy.includes('@')) {
    const [creds, rest] = proxy.split('@');
    addr = rest;
    if (creds.includes(':')) [user, pass] = creds.split(':');
    else user = creds;
  }
    if (!addr.includes(':')) return ctx.reply(tr(ctx.from.id, 'Неверный формат прокси', 'Invalid proxy format'));
  const [host, port] = addr.split(':');
  const link = `tg://socks?server=${encodeURIComponent(host)}&port=${port}` + (user ? `&user=${encodeURIComponent(user)}&pass=${encodeURIComponent(pass)}` : '');
  const uid = ctx.from.id;
  const lang = (userState[uid] && userState[uid].lang) || 'ru';
  const textRu = `📡 *Подключение к прокси*\n\n🌐 Регион: ${region} ${countryCodeToFlag(region)}\n🖥️ Сервер: <code>${host}</code>\n🔌 Порт: <code>${port}</code>\n👤 Пользователь: ${user || '—'}\n\nНажмите кнопку ниже, чтобы подключиться:`;
  const textEn = `📡 *Proxy connection*\n\n🌐 Region: ${region} ${countryCodeToFlag(region)}\n🖥️ Server: <code>${host}</code>\n🔌 Port: <code>${port}</code>\n👤 User: ${user || '—'}\n\nPress the button below to connect:`;
  const text = (lang === 'ru') ? textRu : textEn;
  const kb = [
    [ Markup.button.url(lang === 'ru' ? '🔗 Подключиться' : '🔗 Connect', link) ],
      [ Markup.button.callback(lang === 'ru' ? '⬅️ Назад' : '⬅️ Back', `region_${region}`) ]
  ];
  await editOrReplyForUser(uid, ctx, text, kb, { parse_mode: 'Markdown' });
});

console.log('About to launch bot...');
bot.launch().then(() => console.log('Proxy bot started')).catch((e) => { console.error('launch error', e && e.stack ? e.stack : e); process.exit(1); });
process.once('SIGINT', () => bot.stop('SIGINT'));
process.once('SIGTERM', () => bot.stop('SIGTERM'));
