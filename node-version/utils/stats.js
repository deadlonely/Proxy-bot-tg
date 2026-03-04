const fs = require('fs').promises;
const path = require('path');

const DB_PATH = path.join(__dirname, '../db/users.json');

class Stats {
  async initDB() {
    try {
      await fs.mkdir(path.dirname(DB_PATH), { recursive: true });
      await fs.access(DB_PATH);
    } catch {
      await fs.writeFile(DB_PATH, JSON.stringify({}, null, 2));
    }
  }

  async getUser(userId) {
    await this.initDB();
    const data = JSON.parse(await fs.readFile(DB_PATH, 'utf8'));
    if (!data[userId]) {
      data[userId] = this.createUser(userId);
      await fs.writeFile(DB_PATH, JSON.stringify(data, null, 2));
    }
    return data[userId];
  }

  createUser(userId) {
    return {
      id: userId,
      joinedAt: new Date().toISOString(),
      proxiesViewed: 0,
      qrRequests: 0,
      connectClicks: 0,
      lastActivity: new Date().toISOString(),
      lang: 'en'
    };
  }

  async updateUser(userId, updates) {
    await this.initDB();
    const data = JSON.parse(await fs.readFile(DB_PATH, 'utf8'));
    if (!data[userId]) {
      data[userId] = this.createUser(userId);
    }
    data[userId] = { ...data[userId], ...updates, lastActivity: new Date().toISOString() };
    await fs.writeFile(DB_PATH, JSON.stringify(data, null, 2));
    return data[userId];
  }

  async logProxyView(userId) {
    const user = await this.getUser(userId);
    return this.updateUser(userId, { proxiesViewed: (user.proxiesViewed || 0) + 1 });
  }

  async logQRRequest(userId) {
    const user = await this.getUser(userId);
    return this.updateUser(userId, { qrRequests: (user.qrRequests || 0) + 1 });
  }

  async logConnect(userId) {
    const user = await this.getUser(userId);
    return this.updateUser(userId, { connectClicks: (user.connectClicks || 0) + 1 });
  }

  async getStats() {
    await this.initDB();
    const data = JSON.parse(await fs.readFile(DB_PATH, 'utf8'));
    const users = Object.values(data);

    if (users.length === 0) {
      return {
        totalUsers: 0,
        totalProxiesViewed: 0,
        totalQRRequests: 0,
        totalConnects: 0,
        activeToday: 0,
        ru: 0,
        en: 0
      };
    }

    const today = new Date().toDateString();
    return {
      totalUsers: users.length,
      totalProxiesViewed: users.reduce((sum, u) => sum + (u.proxiesViewed || 0), 0),
      totalQRRequests: users.reduce((sum, u) => sum + (u.qrRequests || 0), 0),
      totalConnects: users.reduce((sum, u) => sum + (u.connectClicks || 0), 0),
      activeToday: users.filter(u => new Date(u.lastActivity).toDateString() === today).length,
      ru: users.filter(u => u.lang === 'ru').length,
      en: users.filter(u => u.lang === 'en').length
    };
  }

  async getUserStats(userId) {
    const user = await this.getUser(userId);
    const joinedDaysAgo = Math.floor((Date.now() - new Date(user.joinedAt)) / (1000 * 60 * 60 * 24));
    return {
      id: user.id,
      joined: user.joinedAt,
      daysAgo: joinedDaysAgo,
      proxiesViewed: user.proxiesViewed || 0,
      qrRequests: user.qrRequests || 0,
      connectClicks: user.connectClicks || 0
    };
  }
}

module.exports = new Stats();
