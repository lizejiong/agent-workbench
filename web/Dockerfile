ARG WEB_NODE_IMAGE=node:22-bookworm-slim
ARG WEB_NGINX_IMAGE=nginx:1.28-alpine
FROM ${WEB_NODE_IMAGE} AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --registry=https://registry.npmjs.org --no-fund
COPY . .
RUN npm run build
FROM ${WEB_NGINX_IMAGE}
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
