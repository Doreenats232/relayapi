FROM mcr.microsoft.com/playwright:v1.51.1-jammy
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
COPY . .
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
EXPOSE 10000
CMD ["node","server.js"]
