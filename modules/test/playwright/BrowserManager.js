const puppeteer = require('puppeteer-core');

class BrowserManager {
  constructor(remoteDebuggingUrl) {
    this.remoteDebuggingUrl = remoteDebuggingUrl;
    this.browser = null;
  }

  async connect() {
    try {
      const response = await fetch(this.remoteDebuggingUrl+'/json/version');
      const json = await response.json();
      const browserURL = json.webSocketDebuggerUrl;
  
      this.browser = await puppeteer.connect({ browserWSEndpoint: browserURL });
      console.log('Connected to browser');
      // You can now use `browser` object to interact with the browser instance.
    } catch (error) {
      console.error('Error connecting to browser:', error);
    }
  }

  async listOpenPages() {
    if (!this.browser) {
      throw new Error('Browser is not connected');
    }
    const pages = await this.browser.pages();
    return pages.map(page => page.url());
  }

  async closePage(index) {
    if (!this.browser) {
      throw new Error('Browser is not connected');
    }
    const pages = await this.browser.pages();
    if (index < 0 || index >= pages.length) {
      throw new Error('Invalid page index');
    }
    await pages[index].close();
    console.log(`Closed page at index ${index}`);
  }

  async openNewPage(url) {
    if (!this.browser) {
      throw new Error('Browser is not connected');
    }
    const page = await this.browser.newPage();
    await page.goto(url);
    console.log(`Opened new page with URL: ${url}`);
    return page;
  }

  async disconnect() {
    if (this.browser) {
      await this.browser.disconnect();
      console.log('Disconnected from browser');
    }
  }

  // Method to perform common browser setup tasks
  async setupAndPerformTasks(tasks) {
    await this.connect();

    try {
      for (const task of tasks) {
        await task(this);
      }
    } catch (error) {
      console.error('Error during tasks:', error);
    } finally {
      await this.disconnect();
    }
  }
}

module.exports = BrowserManager;