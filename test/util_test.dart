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
