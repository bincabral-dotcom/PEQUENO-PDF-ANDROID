# Compactador PDF para Android

Aplicativo Android nativo em Kotlin que refaz a função do script recebido: compacta PDFs por rasterização, procura a melhor qualidade JPEG para um tamanho-alvo, permite tons de cinza, ajusta automaticamente o DPI e trata PDFs protegidos por senha.

## Gerar o APK

1. Abra esta pasta no Android Studio (versão recente, com Android SDK 35 instalado).
2. Aguarde a sincronização das dependências.
3. Use **Build > Build APK(s)**. O APK de teste será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

Para distribuir fora do Android Studio, crie uma chave de assinatura e use **Build > Generate Signed Bundle / APK**.

## Compilar pelo GitHub

O arquivo `.github/workflows/build-apk.yml` compila automaticamente um APK de teste quando você envia o projeto para as ramificações `main` ou `master`. Também é possível abrir a aba **Actions** no GitHub, selecionar **Gerar APK Android** e clicar em **Run workflow**. Ao terminar, baixe o item **Compactador-PDF-debug** na seção *Artifacts* da execução.

## Observações

- O app pede ao Android onde abrir e onde salvar o PDF; ele não precisa de permissão ampla para acessar arquivos.
- A compactação converte cada página para JPEG. Isso normalmente reduz muito PDFs escaneados, mas textos deixam de ser selecionáveis/pesquisáveis — igual ao comportamento do script Python.
- Em documentos grandes, o processo pode levar alguns minutos e consome memória; o mínimo é Android 8 (API 26).
