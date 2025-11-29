import http from 'http';

// Operit TypeScript 项目
console.log('🚀 欢迎来到 Operit TypeScript 项目！');
console.log('='.repeat(50));

interface ServerConfig {
    hostname: string;
    port: number;
}

const config: ServerConfig = {
    hostname: '127.0.0.1',
    port: 3000
};

const server = http.createServer((req, res) => {
    res.statusCode = 200;
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.end(`
    <!DOCTYPE html>
    <html>
    <head>
      <meta charset="UTF-8">
      <title>Operit TypeScript</title>
      <style>
        body {
          font-family: system-ui, sans-serif;
          max-width: 800px;
          margin: 50px auto;
          padding: 20px;
          text-align: center;
        }
        h1 { color: #3178c6; }
        .badge {
          display: inline-block;
          padding: 5px 10px;
          margin: 5px;
          border-radius: 4px;
          background: #3178c6;
          color: white;
          font-size: 14px;
        }
      </style>
    </head>
    <body>
      <h1>🔷 TypeScript 服务器运行中</h1>
      <p>恭喜！您的 Operit TypeScript 项目已成功启动。</p>
      <div>
        <span class="badge">TypeScript</span>
        <span class="badge">pnpm</span>
        <span class="badge">Node.js</span>
      </div>
      <p>服务器运行在 http://${config.hostname}:${config.port}</p>
    </body>
    </html>
  `);
});

server.listen(config.port, config.hostname, () => {
    console.log(`✅ 服务器运行在 http://${config.hostname}:${config.port}/`);
    console.log('💡 提示：修改 src/index.ts 后运行 pnpm build 重新编译');
});
