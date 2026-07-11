# syntax=docker/dockerfile:1
#
# Frontend image: builds the Vite/React SPA and serves it with nginx, which also reverse-proxies the
# API/auth paths to the backend so the app is same-origin (cookies, CSRF, and ORCID all work without
# CORS). See nginx.conf. The SPA calls the API at relative /api paths, so no build-time API URL.

# --- build ---
FROM node:20-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

# --- runtime ---
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
