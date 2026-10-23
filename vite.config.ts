import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import fs from 'fs';
import { defineConfig } from 'vite';

export default defineConfig(() => {
  return {
    plugins: [
      react(),
      tailwindcss(),
      {
        name: 'apk-download-headers-plugin',
        configureServer(server) {
          server.middlewares.use((req, res, next) => {
            const url = req.url?.split('?')[0];
            if (url === '/download/HMVideo.apk' || url === '/download/HMVideo-release.apk' || url === '/api/download/apk') {
              const filename = url.includes('HMVideo-release') ? 'HMVideo-release.apk' : 'HMVideo.apk';
              const apkPath = path.resolve(__dirname, `public/download/${filename}`);
              const fallbackPath = path.resolve(__dirname, 'public/download/HMVideo.apk');
              const finalPath = fs.existsSync(apkPath) ? apkPath : fallbackPath;
              if (fs.existsSync(finalPath)) {
                const stat = fs.statSync(finalPath);
                res.writeHead(200, {
                  'Content-Type': 'application/vnd.android.package-archive',
                  'Content-Disposition': `attachment; filename="${filename}"`,
                  'Content-Length': stat.size.toString(),
                  'Cache-Control': 'no-cache, no-store, must-revalidate',
                  'Access-Control-Allow-Origin': '*',
                });
                fs.createReadStream(finalPath).pipe(res);
                return;
              }
            }

            if (url === '/download/HMVideo-Android-Kotlin-Source.zip') {
              const zipPath = path.resolve(__dirname, 'public/download/HMVideo-Android-Kotlin-Source.zip');
              if (fs.existsSync(zipPath)) {
                const stat = fs.statSync(zipPath);
                res.writeHead(200, {
                  'Content-Type': 'application/zip',
                  'Content-Disposition': 'attachment; filename="HMVideo-Android-Kotlin-Source.zip"',
                  'Content-Length': stat.size.toString(),
                  'Cache-Control': 'no-cache',
                  'Access-Control-Allow-Origin': '*',
                });
                fs.createReadStream(zipPath).pipe(res);
                return;
              }
            }
            next();
          });
        },
      },
    ],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
      },
    },
    server: {
      hmr: process.env.DISABLE_HMR !== 'true',
      watch: process.env.DISABLE_HMR === 'true' ? null : {},
    },
  };
});
