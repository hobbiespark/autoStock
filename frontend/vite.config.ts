import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// autoStock FE 빌드 설정
// - 산출물은 Spring Boot(monitor 모듈)의 정적 리소스 디렉터리로 직접 출력한다.
// - 개발 서버는 /api 요청을 로컬 백엔드(8080)로 프록시한다.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../app/src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
