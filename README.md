# Complemento-Grafico
Um projeto criado sem derivação de outros projetos criado para ser totalmente gratuito e sem anúncios data de lançamento prevista para Sábado ou Domingo está seria uma versão de lançamento acesso antecipado coisas podem mudar 

# [Complemento Grafico]

> **Status do Projeto:** Acesso Antecipado (Early Access) — Aberto a testes, sugestões e relatos de bugs na aba [Issues](https://github.com[welinton-dev]/[SeuRepositorio]/issues).

Um utilitário Android leve, gratuito e de código aberto focado em dar ao usuário controle total sobre os ajustes de desempenho e resolução (`game_overlay`) via [Shizuku](https://github.comRikkaApps/Shizuku), sem depender de acesso root tradicional.

---

## Principais Recursos

* **Integração Dinâmica com Shizuku:** Detecta automaticamente se o Shizuku está instalado e rodando, alternando os botões da interface em tempo real para abrir o serviço ou solicitar a permissão de acesso.
* **Quick Settings Tiles Inteligentes:** Atalhos na barra de notificações para alternar rapidamente entre perfis (como *Equilibrado* e *Foco em Resposta*). Os tiles são mutuamente exclusivos (ativar um desativa o outro automaticamente) e, ao desligá-los, o app limpa o namespace `game_overlay` e restaura a sincronização padrão (`none`).
* **Controle Fino e Proteção de Sessão:** Controles deslizantes para ajuste personalizado de resolução, seleção manual de apps (caso a detecção automática de jogos não liste algum título) e opção de bloqueio temporário (`until_reboot`) para impedir que o sistema reverta as configurações durante uma partida.
* **Restauração Completa:** Botão dedicado para limpar todas as modificações aplicadas e devolver os parâmetros originais do sistema.

---

## Privacidade e Transparência (Zero Telemetria)

Este projeto foi construído sob o princípio de que o usuário deve saber exatamente o que o aplicativo faz:

* **Sem acesso à Internet:** O aplicativo **não solicita** a permissão `android.permission.INTERNET` no `AndroidManifest.xml`. Nenhum dado sai do seu aparelho em segundo plano.
* **Apenas 2 permissões necessárias:** Você pode confirmar nas configurações do sistema (*Informações do app > Permissões > Todas as permissões*) que o app utiliza exclusivamente a permissão da API do **Shizuku** e a permissão para **listar pacotes instalados** (`QUERY_ALL_PACKAGES`).
* **Diagnóstico Manual e Anônimo:** A tela de diagnóstico lê apenas informações técnicas essenciais para resolver bugs (`dumpsys game`, modo de sincronização atual, versão do Android e nível de API). O app **não coleta** o modelo do aparelho nem qualquer dado pessoal, e o envio é 100% manual — você copia o relatório se quiser e decide se deseja colar em uma *Issue*.

---

## Requisitos

* Android [Versão mínima, ex: 12 ou superior]
* [Shizuku](https://rikka.app) instalado e ativado (via Depuração sem fio ou ADB).

---

## Licença

Este projeto é distribuído sob a licença **GNU General Public License v3.0 (GPLv3)** — veja o arquivo [LICENSE](LICENSE) para mais detalhes. 

O código permanece aberto para que qualquer pessoa possa auditar sua segurança, estudar seu funcionamento e garantir que modificações futuras continuem transparentes para a comunidade.

---

## Créditos

* **[Shizuku](https://github.comRikkaApps/Shizuku)** — Pela API essencial que torna possível executar comandos privilegiados com segurança sem root.
* 
