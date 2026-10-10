import 'package:flutter/material.dart';

import '../tema.dart';

class TituloDeSecao extends StatelessWidget {
  final String texto;
  const TituloDeSecao(this.texto, {super.key});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(4, 22, 4, 10),
        child: Text(
          texto,
          style: const TextStyle(
            color: Cores.ciano,
            fontSize: 13,
            fontWeight: FontWeight.w800,
            letterSpacing: 1.2,
          ),
        ),
      );
}

/// Cartão de aviso com cor por gravidade e até dois botões.
class CartaoAviso extends StatelessWidget {
  final IconData icone;
  final Color cor;
  final String titulo;
  final String texto;
  final String? botao;
  final VoidCallback? aoTocarBotao;
  final String? botaoSecundario;
  final VoidCallback? aoTocarSecundario;

  const CartaoAviso({
    super.key,
    required this.icone,
    required this.cor,
    required this.titulo,
    required this.texto,
    this.botao,
    this.aoTocarBotao,
    this.botaoSecundario,
    this.aoTocarSecundario,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: cor.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: cor.withValues(alpha: 0.45)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icone, color: cor, size: 22),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  titulo,
                  style: TextStyle(color: cor, fontWeight: FontWeight.w800, fontSize: 15),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(texto, style: const TextStyle(color: Cores.texto, height: 1.4, fontSize: 14)),
          if (botao != null || botaoSecundario != null) const SizedBox(height: 12),
          if (botao != null || botaoSecundario != null)
            Wrap(
              spacing: 10,
              runSpacing: 8,
              children: [
                if (botao != null)
                  FilledButton(
                    style: FilledButton.styleFrom(backgroundColor: cor, foregroundColor: Colors.black),
                    onPressed: aoTocarBotao,
                    child: Text(botao!, style: const TextStyle(fontWeight: FontWeight.w700)),
                  ),
                if (botaoSecundario != null)
                  TextButton(
                    onPressed: aoTocarSecundario,
                    child: Text(botaoSecundario!, style: const TextStyle(color: Cores.textoFraco)),
                  ),
              ],
            ),
        ],
      ),
    );
  }
}

/// Linha de configuração com chave liga/desliga.
class LinhaChave extends StatelessWidget {
  final IconData icone;
  final String titulo;
  final String subtitulo;
  final bool valor;
  final ValueChanged<bool>? aoMudar;
  final String? explicacao;

  const LinhaChave({
    super.key,
    required this.icone,
    required this.titulo,
    required this.subtitulo,
    required this.valor,
    this.aoMudar,
    this.explicacao,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icone, color: valor ? Cores.ciano : Cores.textoFraco),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(titulo, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
                      const SizedBox(height: 2),
                      Text(subtitulo, style: const TextStyle(color: Cores.textoFraco, fontSize: 13)),
                    ],
                  ),
                ),
                Switch(value: valor, onChanged: aoMudar),
              ],
            ),
            if (explicacao != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(38, 10, 8, 0),
                child: Text(explicacao!, style: const TextStyle(color: Cores.textoFraco, fontSize: 12.5, height: 1.4)),
              ),
          ],
        ),
      ),
    );
  }
}

/// Linha informativa (sem chave).
class LinhaInfo extends StatelessWidget {
  final IconData icone;
  final String titulo;
  final String subtitulo;
  final Widget? fim;
  final String? explicacao;
  final Color? corDoIcone;

  const LinhaInfo({
    super.key,
    required this.icone,
    required this.titulo,
    required this.subtitulo,
    this.fim,
    this.explicacao,
    this.corDoIcone,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icone, color: corDoIcone ?? Cores.ciano),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(titulo, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
                      const SizedBox(height: 2),
                      Text(subtitulo, style: const TextStyle(color: Cores.textoFraco, fontSize: 13)),
                    ],
                  ),
                ),
                if (fim != null) fim!,
              ],
            ),
            if (explicacao != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(38, 10, 0, 0),
                child: Text(explicacao!, style: const TextStyle(color: Cores.textoFraco, fontSize: 12.5, height: 1.4)),
              ),
          ],
        ),
      ),
    );
  }
}

class Selo extends StatelessWidget {
  final String texto;
  final Color cor;
  const Selo(this.texto, this.cor, {super.key});

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: cor.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(6),
          border: Border.all(color: cor.withValues(alpha: 0.6)),
        ),
        child: Text(
          texto,
          style: TextStyle(color: cor, fontSize: 10.5, fontWeight: FontWeight.w800, letterSpacing: 0.6),
        ),
      );
}

void mostrarAviso(BuildContext context, String texto, {String? acao, VoidCallback? aoTocar}) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(
      SnackBar(
        content: Text(texto),
        duration: const Duration(seconds: 5),
        action: acao == null ? null : SnackBarAction(label: acao, textColor: Cores.ciano, onPressed: aoTocar ?? () {}),
      ),
    );
}

Future<bool> confirmar(
  BuildContext context, {
  required String titulo,
  required String texto,
  String sim = 'Confirmar',
  String nao = 'Cancelar',
}) async {
  final r = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(
      backgroundColor: Cores.cartao,
      title: Text(titulo),
      content: Text(texto, style: const TextStyle(height: 1.4)),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: Text(nao)),
        FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(sim)),
      ],
    ),
  );
  return r ?? false;
}
