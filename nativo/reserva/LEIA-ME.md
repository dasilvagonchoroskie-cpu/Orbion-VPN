# Binários de reserva (arm64-v8a)

Tirados do Orbion v5.0 de 25/07/2026 (o APK que estava funcionando no celular):

- `libtor.so` — Tor 0.4.9.11
- `libobfs4proxy.so` — lyrebird (obfs4)

A esteira do GitHub SEMPRE tenta primeiro baixar a versão mais nova do pacote
oficial do Tor (tor-expert-bundle para Android), conferindo SHA-256 e a
assinatura do Tor Project. Estes arquivos só entram no APK se esse download
falhar — assim o app nunca deixa de ser compilado. Nesse caso o Snowflake não
vem (o APK antigo não tinha).
