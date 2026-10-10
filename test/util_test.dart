import 'package:flutter_test/flutter_test.dart';
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
}
