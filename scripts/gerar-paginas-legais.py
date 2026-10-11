#!/usr/bin/env python3
"""Gera as páginas públicas da Política de Privacidade e dos Termos de Uso.

A Play Store exige um endereço público da política de privacidade. O texto
vem dos mesmos arquivos que o app mostra (assets/legal/*.md), no mesmo
formato simples: "# título", "## seção", "### subseção", "- item" e
parágrafos separados por linha em branco. Assim o app e o site dizem sempre
a mesma coisa.

Uso: python3 scripts/gerar-paginas-legais.py <pasta-de-saída>
(a saída vai para a pasta orbion/ do site fortalezadigitalsecurity.com.br)
"""
import html
import pathlib
import re
import sys

RAIZ = pathlib.Path(__file__).resolve().parent.parent
LEGAL = RAIZ / "assets" / "legal"

ESTILO = """
:root { --fundo:#f7f9fc; --texto:#16202c; --fraco:#55657a; --destaque:#007f99; --cartao:#ffffff; --borda:#dde4ee; }
@media (prefers-color-scheme: dark) {
  :root { --fundo:#0b0f14; --texto:#e8eef5; --fraco:#8a99ad; --destaque:#00e5ff; --cartao:#121821; --borda:#243041; }
}
* { box-sizing: border-box; }
body { margin:0; background:var(--fundo); color:var(--texto);
  font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
header, main, footer { max-width: 760px; margin: 0 auto; padding: 0 16px; }
header { padding-top: 22px; display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
header .marca { font-weight: 900; letter-spacing: 2px; font-size: 18px; }
header .marca span { color: var(--destaque); font-weight: 400; }
nav { margin-left:auto; display:flex; gap:14px; font-size: 15px; }
a { color: var(--destaque); }
main { background: var(--cartao); border: 1px solid var(--borda); border-radius: 14px; padding: 8px 22px 22px; margin-top: 18px; }
h1 { font-size: 26px; line-height: 1.25; margin: 18px 0 6px; }
h2 { font-size: 19px; margin: 26px 0 6px; color: var(--destaque); }
h3 { font-size: 16.5px; margin: 18px 0 4px; }
p, li { overflow-wrap: anywhere; }
ul { padding-left: 22px; }
li { margin: 4px 0; }
.versao { color: var(--fraco); margin-top: 0; }
footer { color: var(--fraco); font-size: 14px; padding: 22px 16px 40px; }
.chamada { font-size: 18px; }
.pontos li { margin: 8px 0; }
"""


def ligar_enderecos(texto: str) -> str:
    """Escapa o texto e transforma URLs e e-mails em links."""
    t = html.escape(texto)
    # Só endereços completos: "https://" sozinho no meio do texto não vira link.
    t = re.sub(r"(https?://[A-Za-z0-9][A-Za-z0-9./_%#?=+-]*[A-Za-z0-9/])", r'<a href="\1">\1</a>', t)
    t = re.sub(r"([\w.+-]+@[\w-]+\.[\w.-]+[\w])", r'<a href="mailto:\1">\1</a>', t)
    return t


def converter(md: str) -> tuple[str, str]:
    """Devolve (título, html do corpo)."""
    partes, paragrafo, lista = [], [], []
    titulo = ""

    def fechar_paragrafo():
        if paragrafo:
            texto = " ".join(paragrafo)
            classe = ' class="versao"' if texto.startswith("Versão ") and len(partes) == 1 else ""
            partes.append(f"<p{classe}>{ligar_enderecos(texto)}</p>")
            paragrafo.clear()

    def fechar_lista():
        if lista:
            partes.append("<ul>" + "".join(f"<li>{ligar_enderecos(i)}</li>" for i in lista) + "</ul>")
            lista.clear()

    for bruta in md.splitlines():
        linha = bruta.rstrip()
        if not linha.strip():
            fechar_paragrafo()
            fechar_lista()
        elif linha.startswith("### "):
            fechar_paragrafo(); fechar_lista()
            partes.append(f"<h3>{html.escape(linha[4:].strip())}</h3>")
        elif linha.startswith("## "):
            fechar_paragrafo(); fechar_lista()
            partes.append(f"<h2>{html.escape(linha[3:].strip())}</h2>")
        elif linha.startswith("# "):
            fechar_paragrafo(); fechar_lista()
            titulo = linha[2:].strip()
            partes.append(f"<h1>{html.escape(titulo)}</h1>")
        elif linha.startswith("- "):
            fechar_paragrafo()
            lista.append(linha[2:].strip())
        else:
            fechar_lista()
            paragrafo.append(linha.strip())
    fechar_paragrafo()
    fechar_lista()
    return titulo, "\n".join(partes)


def pagina(titulo: str, corpo: str, descricao: str) -> str:
    return f"""<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(titulo)}</title>
<meta name="description" content="{html.escape(descricao)}">
<style>{ESTILO}</style>
</head>
<body>
<header>
  <div class="marca">ORBION <span>VPN</span></div>
  <nav>
    <a href="./">Início</a>
    <a href="privacidade.html">Privacidade</a>
    <a href="termos.html">Termos</a>
  </nav>
</header>
<main>
{corpo}
</main>
<footer>
  Orbion VPN — Fortaleza Digital Security, Goiatuba (GO), Brasil ·
  <a href="mailto:fortalezadigitalsecurity@gmail.com">fortalezadigitalsecurity@gmail.com</a> ·
  <a href="https://fortalezadigitalsecurity.com.br/">fortalezadigitalsecurity.com.br</a>
</footer>
</body>
</html>
"""


INICIO = """<h1>Orbion VPN</h1>
<p class="chamada">Privacidade pela rede Tor, com rota inteligente para o que recusa o Tor.</p>
<ul class="pontos">
<li><strong>Não coletamos seus dados.</strong> Sem cadastro, sem anúncios e sem rastreadores. Não temos servidores que recebam o seu tráfego.</li>
<li><strong>Tudo fica no seu aparelho.</strong> Configurações e registros ficam só no celular, e você apaga quando quiser.</li>
<li><strong>Rota inteligente.</strong> Bancos, governo, mensageiros e outros serviços que recusam o Tor vão direto, às claras; o resto passa pela rede Tor.</li>
<li><strong>Kill Switch.</strong> Se a rede Tor cair, o tráfego que depende dela fica bloqueado em vez de sair sem proteção.</li>
</ul>
<p>O Orbion usa o recurso de VPN do Android (VpnService) para encaminhar o tráfego dos aplicativos pela rede Tor.
Saiba exatamente o que acontece com as suas informações:</p>
<ul>
<li><a href="privacidade.html">Política de Privacidade</a></li>
<li><a href="termos.html">Termos de Uso</a></li>
</ul>
<p>Dúvidas ou suporte: <a href="mailto:fortalezadigitalsecurity@gmail.com">fortalezadigitalsecurity@gmail.com</a></p>
"""


def main() -> None:
    saida = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "orbion")
    saida.mkdir(parents=True, exist_ok=True)
    for nome, arquivo, descricao in [
        ("privacidade", "privacidade.md", "Política de Privacidade do Orbion VPN: não coletamos seus dados."),
        ("termos", "termos.md", "Termos de Uso do Orbion VPN."),
    ]:
        titulo, corpo = converter((LEGAL / arquivo).read_text(encoding="utf-8"))
        (saida / f"{nome}.html").write_text(pagina(f"{titulo} — Orbion VPN" if "Orbion" not in titulo else titulo, corpo, descricao), encoding="utf-8")
    (saida / "index.html").write_text(
        pagina("Orbion VPN — Fortaleza Digital Security", INICIO, "Orbion VPN: privacidade pela rede Tor, sem coleta de dados."),
        encoding="utf-8",
    )
    print(f"Páginas geradas em {saida.resolve()}")


if __name__ == "__main__":
    main()
