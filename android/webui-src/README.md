Patched Razban.UI Servers page used to rebuild `android/app/src/main/assets/webui`.
Source tree: public teoplaydor/razban-vpn-client `src/Razban.UI`.
Rebuild: `npx vite build` then copy `dist/assets` and update hashed tags in the
Android `index.html` (keep the CSP / polyfill / AndroidBridgeNative shim).
