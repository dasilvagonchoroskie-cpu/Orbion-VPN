import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:orbion_vpn/motor.dart';
import 'package:orbion_vpn/util.dart';

void main() {
  test('formatarBytes', () {
    expect(formatarBytes(0), '0 B');
    expect(formatarBytes(1023), '1023 B');
    expect(formatarBytes(1536), '1.5 KB');
    expect(formatarBytes(5 * 1024 * 1024), '5.0 MB');
    expect(formatarBytes(3 * 1024 * 1024 * 1024), '3.00 GB');
  });

  test('formatarDuracao', () {
    expect(formatarDuracao(const Duration(seconds: 42)), '42s');
    expect(formatarDuracao(const Duration(minutes: 3, seconds: 5)), '3min 05s');
    expect(formatarDuracao(const Duration(hours: 2, minutes: 7)), '2h 07min');
  });

  test('linhasDeBridge ignora comentários e tira o prefixo Bridge', () {
    const texto = '# minhas\n\nBridge obfs4 1.2.3.4:443 AAA cert=x iat-mode=0\nobfs4 5.6.7.8:80 BBB cert=y iat-mode=1\n';
    expect(linhasDeBridge(texto), [
      'obfs4 1.2.3.4:443 AAA cert=x iat-mode=0',
      'obfs4 5.6.7.8:80 BBB cert=y iat-mode=1',
    ]);
  });

  test('nomeDoCaminho', () {
    expect(nomeDoCaminho('DIRETO'), 'entrada normal');
    expect(nomeDoCaminho('SNOWFLAKE'), contains('Snowflake'));
    expect(nomeDoCaminho('OBFS4'), contains('obfs4'));
  });

  test('mensagemDaAcao explica o que fazer na tela que abriu (qualquer marca)', () {
    expect(mensagemDaAcao('MARCA'), contains('sem restrições'));
    expect(mensagemDaAcao('LISTA'), contains('Não otimizar'));
    expect(mensagemDaAcao('APP_INFO'), contains('Unrestricted'));
    expect(mensagemDaAcao('AUTOSTART'), contains('Autostart'));
    expect(mensagemDaAcao('FALHOU'), contains('Configurações (Settings)'));
    expect(mensagemDaAcao('OK'), isNull);
    expect(mensagemDaAcao('PERGUNTA'), isNull);
  });

  test('normalizarDominio limpa o que a pessoa digita', () {
    expect(normalizarDominio('https://www.Banco.com.br/login?x=1'), 'banco.com.br');
    expect(normalizarDominio('*.exemplo.com'), 'exemplo.com');
    expect(normalizarDominio('site.com:8443'), 'site.com');
    expect(normalizarDominio('  App.MeuSite.com.  '), 'app.meusite.com');
    expect(normalizarDominio('não é site'), isNull);
    expect(normalizarDominio('localhost'), isNull);
    expect(normalizarDominio(''), isNull);
  });

  test('textoDaRota diz por onde o app vai e por quê', () {
    const banco = AppRota('com.nu.production', 'Nubank', false, RotaApp.foraAutomatico, 'Bancos e pagamentos');
    expect(textoDaRota(banco), contains('Bancos e pagamentos'));
    expect(banco.pelaTor, isFalse);
    expect(textoDaRota(banco.comRota(RotaApp.torManual)), contains('você escolheu'));
    expect(banco.comRota(RotaApp.torManual).pelaTor, isTrue);
  });

  test('só pergunta antes de mandar pelo Tor o que quebra notificações ou chamadas', () {
    const gms = AppRota('com.google.android.gms', 'Google Play Services', true, RotaApp.foraAutomatico, 'Google',
        categoriaId: 'google');
    const whats = AppRota('com.whatsapp', 'WhatsApp', false, RotaApp.foraAutomatico, 'Mensageiros e chamadas',
        categoriaId: 'mensageiros');
    const banco = AppRota('com.nu.production', 'Nubank', false, RotaApp.foraAutomatico, 'Bancos e pagamentos',
        categoriaId: 'bancos');
    const jogo = AppRota('com.exemplo.jogo', 'Jogo', false, RotaApp.foraManual, null);
    expect(avisoAoMandarPeloTor(gms), contains('notificações'));
    expect(avisoAoMandarPeloTor(whats), contains('Chamadas'));
    expect(avisoAoMandarPeloTor(banco), isNull);
    expect(avisoAoMandarPeloTor(jogo), isNull);
    // A categoria segue junto quando a rota muda.
    expect(whats.comRota(RotaApp.torManual).categoriaId, 'mensageiros');
    expect(whats.comRota(RotaApp.torManual).manual, isTrue);
    expect(textoDaMudancaDeRota(banco.comRota(RotaApp.torManual)), contains('desligar a chave'));
    expect(textoDaMudancaDeRota(jogo.comRota(RotaApp.tor)), 'Jogo agora vai pela rede Tor.');
    expect(textoDaMudancaDeRota(jogo), 'Jogo agora vai por fora do Tor.');
  });

  test('AppRota lê o código da categoria', () {
    final a = AppRota.deMapa({
      'pacote': 'com.whatsapp',
      'nome': 'WhatsApp',
      'sistema': false,
      'rota': 'FORA_AUTO',
      'categoria': 'Mensageiros e chamadas',
      'categoriaId': 'mensageiros',
    });
    expect(a.categoriaId, 'mensageiros');
    expect(a.rota, RotaApp.foraAutomatico);
    expect(a.manual, isFalse);
  });

  test('lerDocumento entende títulos, seções, itens e parágrafos', () {
    final b = lerDocumento('# Política\n\nVersão 1.\n\n## 1. Quem somos\n\nLinha um\ncontinua aqui.\n\n- item A\n- item B\n\n### Sub\nFim.\n');
    expect(b.map((x) => x.tipo).toList(), [
      TipoDeBloco.titulo,
      TipoDeBloco.paragrafo,
      TipoDeBloco.secao,
      TipoDeBloco.paragrafo,
      TipoDeBloco.item,
      TipoDeBloco.item,
      TipoDeBloco.subsecao,
      TipoDeBloco.paragrafo,
    ]);
    expect(b[3].texto, 'Linha um continua aqui.');
    expect(b[5].texto, 'item B');
    expect(nomeDoComponente('assets/licencas/tor.txt'), 'Tor');
    expect(nomeDoComponente('assets/licencas/outro.txt'), 'outro');
  });

  test('Política e Termos estão completos e com contato', () {
    for (final caminho in ['assets/legal/privacidade.md', 'assets/legal/termos.md']) {
      final texto = File(caminho).readAsStringSync();
      final blocos = lerDocumento(texto);
      expect(blocos.first.tipo, TipoDeBloco.titulo, reason: caminho);
      expect(blocos.where((x) => x.tipo == TipoDeBloco.secao).length, greaterThanOrEqualTo(10), reason: caminho);
      expect(texto, contains('fortalezadigitalsecurity@gmail.com'), reason: caminho);
      expect(texto, contains('Versão $versaoDosTermos'), reason: caminho);
    }
    expect(File('assets/legal/privacidade.md').readAsStringSync(), contains('Não coletamos seus dados'));
  });

  test('Config lê os aceites (termos e aviso da VPN)', () {
    final nova = Config.deMapa(const {});
    expect(nova.termosAceitos, 0);
    expect(nova.avisoVpnAceito, 0);
    final aceita = Config.deMapa({'termosAceitos': versaoDosTermos, 'avisoVpnAceito': versaoDoAvisoVpn});
    expect(aceita.termosAceitos >= versaoDosTermos && aceita.avisoVpnAceito >= versaoDoAvisoVpn, isTrue);
  });

  test('StatusVpn lê "rede Tor instável"', () {
    expect(StatusVpn.deMapa({'torInstavel': true}).torInstavel, isTrue);
    expect(StatusVpn.deMapa(const {}).torInstavel, isFalse);
  });

  test('Fechamento sabe se é de antes da atualização', () {
    final velho = Fechamento.deMapa({'processo': 'tela', 'quando': 1, 'explicacao': 'x', 'anterior': true});
    final novo = Fechamento.deMapa({'processo': 'vpn', 'quando': 2, 'explicacao': 'y'});
    expect(velho.anterior, isTrue);
    expect(novo.anterior, isFalse);
    expect(novo.daVpn, isTrue);
  });

  test('StatusVpn lê "sem internet"', () {
    expect(StatusVpn.deMapa({'estado': 'CONECTADO', 'semInternet': true}).semInternet, isTrue);
    expect(StatusVpn.deMapa(const {}).semInternet, isFalse);
  });

  test('StatusVpn reconhece VPN derrubada pelo sistema', () {
    final caiu = StatusVpn.deMapa({'estado': 'DESCONECTADO', 'estavaLigada': true, 'servicoAtivo': false});
    expect(caiu.derrubadaPeloSistema, isTrue);
    final desligadaPorVoce = StatusVpn.deMapa({'estado': 'DESCONECTADO', 'estavaLigada': false, 'servicoAtivo': false});
    expect(desligadaPorVoce.derrubadaPeloSistema, isFalse);
    final ligada = StatusVpn.deMapa({'estado': 'CONECTADO', 'estavaLigada': true, 'servicoAtivo': true});
    expect(ligada.derrubadaPeloSistema, isFalse);
  });

  test('Config: rota inteligente nasce ligada e lê as listas', () {
    final vazio = Config.deMapa(const {});
    expect(vazio.rotaInteligente, isTrue);
    expect(vazio.desvioAutomatico, isTrue);
    final c = Config.deMapa({
      'rotaInteligente': false,
      'categoriasDesligadas': ['redes'],
      'dominiosDiretos': ['meubanco.com.br'],
      'appsSempreNoTor': ['com.whatsapp'],
    });
    expect(c.rotaInteligente, isFalse);
    expect(c.categoriasDesligadas, {'redes'});
    expect(c.dominiosDiretos, ['meubanco.com.br']);
    expect(c.appsSempreNoTor, {'com.whatsapp'});
  });

  test('Técnico: a gravidade vem da etiqueta, não de palavras soltas', () {
    const resumo = 'Nov 06 07:40:22.000 [notice] Circuit build expiry summary: prefix_hops_0=0 prefix_hops_1=0 '
        'prefix_hops_2=1 post_prefix=0 open_channel=1 other_channel=0 late_firsthop=0 completed=57 abandoned=1 '
        'excluded=0 adaptive=1 connection_failed=0 channels_retired=0 firsthop_preserved=0 (build stages are at '
        'measurement or circuit expiry; recovery probes may finish later)';
    final r = lerLinhaTecnica(resumo);
    expect(r.nivel, NivelDaLinha.normal, reason: '"connection_failed=0" não é erro');
    expect(r.explicacao, contains('57 circuitos montados'));
    expect(r.explicacao, isNot(contains('cortado')));

    final troca = lerLinhaTecnica('Nov 06 08:55:24.000 [notice] Circuit build expiry summary: completed=4 abandoned=0 '
        'excluded=0 adaptive=1 connection_failed=18 channels_retired=0');
    expect(troca.explicacao, contains('18 cortados'));

    final guarda = lerLinhaTecnica(
        'Nov 06 07:33:24.000 [notice] All current guards excluded by path restriction type 2; using an additional guard.');
    expect(guarda.nivel, NivelDaLinha.normal);
    expect(guarda.explicacao, startsWith('Normal'));

    final pronto = lerLinhaTecnica('Nov 06 07:33:22.000 [notice] Bootstrapped 100% (done): Done');
    expect(pronto.nivel, NivelDaLinha.progresso);
    expect(pronto.explicacao, 'Conectado à rede Tor.');
    expect(lerLinhaTecnica('Nov 06 07:33:17.000 [notice] Bootstrapped 5% (conn): Connecting to a relay').explicacao,
        'Conectando à rede Tor: 5%.');
    expect(
        lerLinhaTecnica('Nov 06 07:33:09.749 [notice] Tor 0.4.9.15 (git-8fe2745aee2c3511) running on Linux with '
                'Libevent 2.1.13-stable, OpenSSL 3.5.9')
            .explicacao,
        contains('0.4.9.15'));
    expect(
        lerLinhaTecnica('Nov 06 08:55:24.000 [notice] DisableNetwork is set. Tor will not make or accept non-control '
                'network connections. Shutting down all existing connections.')
            .explicacao,
        contains('trocar de rede'));

    expect(lerLinhaTecnica('Nov 06 08:00:00.000 [warn] Problem bootstrapping. Stuck at 5% (conn): Connecting to a relay.').nivel,
        NivelDaLinha.aviso);
    expect(lerLinhaTecnica('Nov 06 08:00:00.000 [err] Reading config failed--see warnings above.').nivel, NivelDaLinha.erro);
    expect(lerLinhaTecnica('[2026-11-06 08:00:00] [E] socks5 tunnel init failed').nivel, NivelDaLinha.erro);
    expect(lerLinhaTecnica('[2026-11-06 08:00:00] [W] session timeout').nivel, NivelDaLinha.aviso);
    expect(lerLinhaTecnica('[2026-11-06 08:00:00] [I] connected').nivel, NivelDaLinha.normal);
    expect(lerLinhaTecnica('—— Iniciando Tor por DIRETO ——').nivel, NivelDaLinha.normal);
    expect(lerLinhaTecnica('Não foi possível ler o log: sem espaço').nivel, NivelDaLinha.aviso);

    final p = contarProblemas([guarda, r, lerLinhaTecnica('Nov 06 08:00:00.000 [warn] algo')]);
    expect(p.erros, 0);
    expect(p.avisos, 1);
    expect(resumoDoRegistro(0, 0), startsWith('Nenhum erro nem aviso'));
    expect(resumoDoRegistro(0, 2), contains('2 avisos'));
    expect(resumoDoRegistro(1, 0), startsWith('1 erro '));
    expect(resumoDoRegistro(3, 1), startsWith('3 erros'));
  });

  test('Técnico: o registro de 06/11/2026 (POCO C71) não tem nada em vermelho nem em amarelo', () {
    final linhas = File('test/dados/tor-2026-11-06.txt')
        .readAsLinesSync()
        .where((l) => l.trim().isNotEmpty)
        .map(lerLinhaTecnica)
        .toList();
    expect(linhas.length, greaterThan(30));
    final p = contarProblemas(linhas);
    expect(p.erros, 0);
    expect(p.avisos, 0);
    // Toda mensagem do Tor desse registro ganhou explicação em português.
    final semExplicacao = linhas.where((l) => l.texto.contains('[notice]') && l.explicacao == null).map((l) => l.texto);
    expect(semExplicacao, isEmpty);
  });

  test('ResumoRotas lê categorias, desvios e pausas', () {
    final r = ResumoRotas.deMapa({
      'categorias': [
        {'id': 'bancos', 'titulo': 'Bancos e pagamentos', 'ligada': true, 'apps': ['Nubank'], 'dominios': 30},
      ],
      'desviadosAuto': [
        {'dominio': 'exemplo.com', 'quando': 1, 'categoria': null},
      ],
      'pausados': [
        {'destino': '1.2.3.4:443', 'falhas': 3, 'segundos': 30},
      ],
      'appsFora': 5,
    });
    expect(r.categorias.single.apps, ['Nubank']);
    expect(r.desviadosAuto.single.dominio, 'exemplo.com');
    expect(r.pausados.single.segundos, 30);
    expect(r.appsFora, 5);
  });
}
