# LaundryHub TV

APK kiosk para exibir o display LaundryHub em Smart TVs Android.

## O que faz

- Abre a URL do display em tela cheia
- Inicia automaticamente quando liga a TV
- Mantém tela sempre ligada
- Bloqueia botão voltar/home (kiosk)
- Reconecta automaticamente se cair internet
- Audio/video autoplay habilitado
- Cache de assets para reduzir tráfego

## Como usar

### 1. Configurar URL

Edite `app/build.gradle.kts`, linha `DISPLAY_URL`:

```kotlin
buildConfigField("String", "DISPLAY_URL", "\"https://SUA-URL-AQUI/display/centro\"")
```

### 2. Build via GitHub Actions

1. Push para o repositório
2. Vá em **Actions** > **Build APK**
3. Baixe o APK em **Artifacts**

### 3. Instalar na TV

1. Copie o APK para um pendrive
2. Conecte na TV
3. Instale usando um gerenciador de arquivos
4. Permita "fontes desconhecidas" se pedir

## Mudar URL sem rebuild

Para mudar a URL, edite `DISPLAY_URL` em `app/build.gradle.kts` e faça push.
O GitHub Actions gera o novo APK automaticamente.
