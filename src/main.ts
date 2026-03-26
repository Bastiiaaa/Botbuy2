import { Bot } from './bot';

const bot = new Bot();

bot.run().catch((err: unknown) => {
  console.error('[Main] Error fatal:', err);
  process.exit(1);
});
