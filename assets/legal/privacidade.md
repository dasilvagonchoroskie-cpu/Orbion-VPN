# Política de Privacidade do Orbion VPN

Versão 1 — em vigor desde 11 de outubro de 2026.

## Resumo em poucas palavras

- Não coletamos seus dados. O Orbion não tem cadastro, conta, anúncios, rastreadores nem ferramentas de estatística, e nós não temos servidores que recebam o seu tráfego de internet.
- O que o aplicativo guarda (suas configurações e os registros de funcionamento) fica só no seu aparelho.
- Nós só recebemos alguma informação se você mesmo tocar em "Enviar relatório" e escolher mandar o arquivo para nós.
- Você pode apagar tudo a qualquer momento, limpando os dados do app ou desinstalando.

## 1. Quem somos

O Orbion VPN é desenvolvido pela Fortaleza Digital Security, de Goiatuba (GO), Brasil.

- Site: https://fortalezadigitalsecurity.com.br
- E-mail para assuntos de privacidade e suporte: fortalezadigitalsecurity@gmail.com

Esta política explica como o aplicativo trata informações, em conformidade com a Lei Geral de Proteção de Dados (Lei nº 13.709/2018, LGPD) e com o Marco Civil da Internet (Lei nº 12.965/2014).

## 2. Como o Orbion funciona

O Orbion usa o recurso de VPN do próprio Android (VpnService) para receber o tráfego de internet dos aplicativos do seu aparelho e encaminhá-lo pela rede Tor, uma rede mundial de retransmissores mantida por voluntários. Assim, os sites e serviços que você acessa veem o endereço de um retransmissor do Tor, e não o seu endereço de internet (IP).

Para escolher o caminho de cada conexão e bloquear rastreadores, o Orbion lê, dentro do próprio aparelho, o endereço de destino de cada conexão (o nome do site e a porta). Essa leitura acontece só na memória do aparelho e não é enviada para nós nem para ninguém. O Orbion não lê o conteúdo das suas conversas, páginas ou arquivos.

### Rota inteligente

Alguns serviços recusam conexões vindas da rede Tor ou já sabem quem você é porque você entra neles com seu nome (bancos, governo, mensageiros, redes sociais, serviços do Google, streaming, compras e assistentes de inteligência artificial). Por isso, com a rota inteligente ligada:

- Os aplicativos dessas categorias ficam fora da VPN, por decisão do próprio Android: o tráfego deles vai direto, como se o Orbion não existisse, protegido pela criptografia do próprio aplicativo.
- Nos demais aplicativos e no navegador, os sites dessas categorias também saem direto, e somente por conexões criptografadas (endereços "http://" sem criptografia nunca saem por fora do Tor).
- Um site que falha repetidamente pela rede Tor, enquanto o Tor funciona normalmente para os outros, pode ser enviado direto até a VPN ser desligada (desvio automático).

Você pode desligar a rota inteligente, desligar categorias e escolher o caminho de qualquer aplicativo ou site nas telas do app. O que vai por fora do Tor é visto pelo serviço de destino com o seu IP real.

## 3. Informações que ficam só no seu aparelho

Estas informações são criadas e guardadas apenas no armazenamento privado do Orbion, no seu aparelho. Nós não temos acesso a elas.

- Configurações: suas escolhas no app, incluindo as listas de aplicativos e sites que você mandou por fora ou pela rede Tor e as pontes (bridges) que você digitar.
- Lista de aplicativos instalados: usada só para mostrar a aba Apps e aplicar a rota por aplicativo. O Orbion consulta os aplicativos que têm ícone na tela inicial e alguns serviços do sistema; guarda apenas o nome do pacote de cada um e a categoria em que ele se encaixa.
- Registro de decisões: os endereços mais recentes acessados pelos aplicativos que passam pelo Orbion e o caminho escolhido para cada um (no máximo 300). Fica só na memória e é apagado sempre que a VPN é desligada ou reiniciada.
- Diário da VPN: horários em que a VPN ligou, desligou, trocou de rede ou foi fechada pelo Android, e os nomes dos aplicativos cuja rota você mudou. Tamanho limitado (os registros mais antigos são apagados).
- Registros técnicos: as mensagens do programa do Tor (que o próprio Tor já grava sem endereços de sites), avisos do túnel e, se acontecer, o rastro de erros internos do aplicativo.
- Motivos de fechamento: o Android informa ao próprio aplicativo por que ele foi encerrado (por exemplo, falta de memória). O Orbion só exibe essa informação para você.

## 4. O que sai do aparelho e para quem

Para funcionar, o Orbion se comunica com terceiros que não são controlados por nós:

- Rede Tor: o tráfego dos aplicativos que passam pelo Tor viaja cifrado por três retransmissores voluntários. O último retransmissor (de saída) vê o endereço de destino e o conteúdo que não estiver cifrado pelo próprio site; por isso, prefira sempre sites com "https://". O primeiro retransmissor vê o seu IP, mas não o destino.
- Pontes e Snowflake: se a sua rede bloquear o Tor, o Orbion pode usar pontes públicas (obfs4) ou o Snowflake, que usa voluntários e servidores públicos de conexão (como os de STUN) para atravessar o bloqueio. Eles veem o seu IP, como qualquer entrada da rede Tor.
- DNS criptografado: para os sites que saem direto (rota inteligente), o nome do site é consultado por DNS criptografado na Cloudflare (1.1.1.1) ou no Google (8.8.8.8). Se a sua rede bloquear esses serviços, a consulta é feita pelo DNS da própria rede. Esses provedores recebem o nome do site e o IP do aparelho, conforme as políticas deles. Nada disso é feito para os sites que vão pela rede Tor: esses nomes são resolvidos dentro da própria rede Tor.
- Destinos que vão direto: os aplicativos e sites que vão por fora do Tor se comunicam com o seu serviço normalmente e veem o seu IP real.
- Google Play: o download, as atualizações e, se houver, os pagamentos são feitos pelo Google, conforme a política de privacidade do Google. Não recebemos os dados do seu cartão.

Nós não vendemos, não alugamos e não compartilhamos dados de usuários, porque não os recebemos.

## 5. Relatório para o suporte (opcional)

Se você tocar em "Enviar relatório", o Orbion monta um arquivo de texto e abre o menu Compartilhar do Android. Você escolhe o aplicativo e para quem enviar; nada é enviado sozinho.

O relatório contém: versão do Orbion e do Android, marca e modelo do aparelho, estado da VPN, as configurações (as listas aparecem só como quantidades), o diagnóstico do aparelho, os motivos de fechamento informados pelo Android, o diário da VPN (com os nomes dos aplicativos cuja rota você mudou), contagens do filtro, as últimas linhas do registro técnico do Tor, os avisos do túnel e o rastro de erros internos.

O relatório não contém: os sites que você visitou, as suas listas de sites nem as suas pontes.

Se você enviar o relatório para nós, ele será usado somente para entender e resolver o problema relatado, e será apagado em até 90 dias depois do atendimento, ou antes, se você pedir. O arquivo gerado no aparelho é apagado automaticamente em até 24 horas.

## 6. Permissões que o Orbion usa e por quê

- VPN (VpnService): criar a VPN no aparelho, sempre com a sua autorização na janela do Android.
- Internet e estado da rede: conectar à rede Tor e perceber quando você troca de Wi-Fi para dados móveis.
- Serviço em primeiro plano e notificações: manter a proteção ativa com uma notificação visível e mostrar o estado da VPN.
- Iniciar com o aparelho: religar a proteção ao ligar o celular, somente se você ativar essa opção.
- Ignorar a otimização de bateria: pedir ao Android que não encerre a VPN em segundo plano (o Android pergunta a você).
- Manter o aparelho ativo (wake lock): concluir a conexão quando a tela estiver apagada.
- Consulta aos aplicativos instalados: mostrar a aba Apps e aplicar a rota por aplicativo, como descrito no item 3.

O Orbion não pede acesso a localização, contatos, câmera, microfone, arquivos, fotos ou SMS.

## 7. Bases legais (LGPD)

A maior parte do funcionamento do Orbion acontece no seu próprio aparelho, sob o seu controle, para executar o serviço que você pediu ao ligar a VPN (art. 7º, V, da LGPD). Quando você escolhe nos enviar o relatório, tratamos esse conteúdo para prestar o suporte solicitado por você (art. 7º, V) e com base no seu consentimento, dado ao decidir enviá-lo (art. 7º, I).

Por sua natureza, a rede Tor e os provedores de DNS citados ficam em vários países. As comunicações com eles acontecem por sua iniciativa, para que o serviço que você pediu funcione.

## 8. Seus direitos

Você pode pedir, a qualquer momento, a confirmação de que tratamos dados seus, o acesso, a correção, a eliminação, a portabilidade, a informação sobre compartilhamento e a revogação do consentimento (art. 18 da LGPD), pelo e-mail fortalezadigitalsecurity@gmail.com. Como não guardamos dados dos usuários, na maior parte dos casos a resposta será que não temos dados seus; se você nos enviou um relatório, nós o apagaremos.

Para apagar o que está no aparelho: Configurações (Settings) do Android → Apps → Orbion VPN → Armazenamento (Storage) → Limpar dados (Clear data). Desinstalar o aplicativo também apaga tudo.

Você também pode apresentar reclamação à Autoridade Nacional de Proteção de Dados (ANPD).

## 9. Segurança

- O Orbion não tem servidores próprios no caminho do seu tráfego.
- A parte que cuida da VPN roda em um processo separado do aplicativo, e a comunicação entre as partes do app é restrita ao próprio Orbion.
- Os arquivos ficam na área privada do aplicativo, que outros aplicativos não acessam.
- O relatório só é entregue ao aplicativo que você escolher, com permissão temporária do Android.

Nenhum sistema é totalmente imune a falhas. Se descobrirmos um problema de segurança que afete você, avisaremos pelos canais do aplicativo.

## 10. Crianças e adolescentes

O Orbion não é destinado a crianças e não coleta dados de ninguém, incluindo crianças e adolescentes. Menores de 18 anos só devem usar o aplicativo com o acompanhamento dos pais ou responsáveis.

## 11. Por quanto tempo as informações ficam guardadas

- No aparelho: até você apagar os dados do aplicativo ou desinstalá-lo. O registro de decisões some quando a VPN é desligada; o diário e os registros técnicos têm tamanho limitado; os relatórios gerados são apagados em até 24 horas.
- Conosco: somente os relatórios que você decidir enviar, por até 90 dias depois do atendimento.

## 12. Mudanças nesta política

Se esta política mudar de forma importante, a nova versão será mostrada no aplicativo para você ler e aceitar antes de continuar. A versão e a data ficam sempre no início do texto.

## 13. Contato

Dúvidas, pedidos ou reclamações sobre privacidade: fortalezadigitalsecurity@gmail.com.
